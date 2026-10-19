package com.writhlang.engine

import zio._

object Executor {
  sealed trait ExecutionError extends Throwable { def message: String }
  case class MissingDependency(node: String, dep: String) extends ExecutionError {
    val message = s"missing dependency $dep for node $node"
  }
  case class GraphError(reason: String) extends ExecutionError { val message = reason }

  def run(dag: Dag): IO[ExecutionError, Map[String, Double]] =
    for {
      levels <- ZIO.fromEither(dag.levels.left.map(GraphError))
      ref <- Ref.make(Map.empty[String, Double])
      _ <- ZIO.foreach(levels) { level =>
        for {
          current <- ref.get
          computed <- ZIO.foreachPar(level) { instr =>
            ZIO.fromEither(evalInstruction(instr, current)).map(v => instr.id -> v)
          }
          _ <- ref.update(_ ++ computed.toMap)
        } yield ()
      }
      result <- ref.get
    } yield result

  private def evalInstruction(instr: Instruction, values: Map[String, Double]): Either[ExecutionError, Double] = {
    def req(id: String): Either[ExecutionError, Double] =
      values.get(id).toRight(MissingDependency(instr.id, id))

    instr.op match {
      case Const(v) => Right(v)
      case Sum =>
        instr.deps.foldLeft[Either[ExecutionError, Double]](Right(0.0)) { (running, dependency) =>
          for {
            total <- running
            value <- req(dependency)
          } yield total + value
        }
      case Price(instrument, market, tradeDate) =>
        Right(Pricing.price(instrument, market, tradeDate))
      case Theta(baseId, thetaEvalId) =>
        for {
          base <- req(baseId)
          thetaEval <- req(thetaEvalId)
        } yield thetaEval - base
      case Carry(instrument, from, to, tradeDate) =>
        Right(Pricing.carry(instrument, from, to, tradeDate))
      case Accrued(instrument, market, tradeDate) =>
        Right(Pricing.accrued(instrument, market, tradeDate))
      case CashFlow(instrument, market, from, to, tradeDate) =>
        Right(Pricing.cashIn(instrument, market, from, to, tradeDate))
      case Combine(terms) =>
        terms.foldLeft[Either[ExecutionError, Double]](Right(0.0)) { case (running, (dependency, coefficient)) =>
          for {
            total <- running
            value <- req(dependency)
          } yield total + coefficient * value
        }
      case DeltaCentral(baseId, upId, downId, bump) =>
        for {
          base <- req(baseId)
          up <- req(upId)
          down <- req(downId)
        } yield (up - down) / (2.0 * bump)
      case DeltaForward(baseId, upId, bump) =>
        for {
          base <- req(baseId)
          up <- req(upId)
        } yield (up - base) / bump
      case DeltaBackward(baseId, downId, bump) =>
        for {
          base <- req(baseId)
          down <- req(downId)
        } yield (base - down) / bump
      case Gamma(baseId, upId, downId, bump) =>
        for {
          base <- req(baseId)
          up <- req(upId)
          down <- req(downId)
        } yield (up - 2.0 * base + down) / (bump * bump)
      case CrossGamma(baseId, upIId, upJId, upIJId, bumpI, bumpJ) =>
        for {
          base <- req(baseId)
          upI <- req(upIId)
          upJ <- req(upJId)
          upIJ <- req(upIJId)
        } yield (upIJ - upI - upJ + base) / (bumpI * bumpJ)
      case LinearScenario(baseId, terms) =>
        for {
          base <- req(baseId)
          termVals <- collectLinear(instr.id, terms, values)
        } yield base + termVals.sum
      case QuadraticScenario(baseId, linear, gamma, cross) =>
        for {
          base <- req(baseId)
          lin <- collectLinear(instr.id, linear, values)
          gam <- collectSquared(instr.id, gamma, values).map(_.map(_ * 0.5))
          crs <- collectCross(instr.id, cross, values)
        } yield base + lin.sum + gam.sum + crs.sum
    }
  }

  // terms: (greekId, shockId) -> greek * shock
  private def collectLinear(
    nodeId: String,
    terms: List[(String, String)],
    values: Map[String, Double]
  ): Either[ExecutionError, List[Double]] =
    terms.foldLeft(Right(List.empty[Double]): Either[ExecutionError, List[Double]]) { (acc, term) =>
      for {
        list <- acc
        g <- values.get(term._1).toRight(MissingDependency(nodeId, term._1))
        s <- values.get(term._2).toRight(MissingDependency(nodeId, term._2))
      } yield list :+ (g * s)
    }

  // terms: (greekId, shockId) -> greek * shock^2 (for gamma contributions)
  private def collectSquared(
    nodeId: String,
    terms: List[(String, String)],
    values: Map[String, Double]
  ): Either[ExecutionError, List[Double]] =
    terms.foldLeft(Right(List.empty[Double]): Either[ExecutionError, List[Double]]) { (acc, term) =>
      for {
        list <- acc
        g <- values.get(term._1).toRight(MissingDependency(nodeId, term._1))
        s <- values.get(term._2).toRight(MissingDependency(nodeId, term._2))
      } yield list :+ (g * s * s)
    }

  // cross: (crossId, shockIId, shockJId) -> cross * s_i * s_j
  private def collectCross(
    nodeId: String,
    cross: List[(String, String, String)],
    values: Map[String, Double]
  ): Either[ExecutionError, List[Double]] =
    cross.foldLeft(Right(List.empty[Double]): Either[ExecutionError, List[Double]]) { (acc, term) =>
      for {
        list <- acc
        g <- values.get(term._1).toRight(MissingDependency(nodeId, term._1))
        si <- values.get(term._2).toRight(MissingDependency(nodeId, term._2))
        sj <- values.get(term._3).toRight(MissingDependency(nodeId, term._3))
      } yield list :+ (g * si * sj)
    }
}
