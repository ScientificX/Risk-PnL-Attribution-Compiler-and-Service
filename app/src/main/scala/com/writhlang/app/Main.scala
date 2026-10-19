package com.writhlang.app

import com.writhlang.dsl.{Currency, Parser}
import com.writhlang.engine.{Dag, DslCompiler, Executor}
import com.writhlang.marketdata.MarketDataJson
import com.writhlang.portfolio.PopulationDiff
import com.writhlang.render.DotRenderer
import com.writhlang.scenario.SensitivityConfig
import com.writhlang.time.ThetaPeriod
import zio._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.sys.process.{ProcessLogger, _}

object Main extends ZIOAppDefault {

  val defaultMarketJson: String =
    """{
      |  "curves": {
      |    "EUR": { "type": "DiscountCurve", "instruments": [
      |      { "kind": "deposit", "tenor": "1Y", "rate": 0.035 },
      |      { "kind": "swap", "tenor": "2Y", "rate": 0.040, "freq": 1 },
      |      { "kind": "swap", "tenor": "3Y", "rate": 0.045, "freq": 1 }
      |    ]},
      |    "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0015 }
      |  },
      |  "fxSpots": { "EURUSD": 1.25 },
      |  "volSurfaces": { "EUR.SWAPTION": { "flat": 0.20 } },
      |  "prepayVectors": { "EUR.MBS": { "termMonths": 360, "flat": 0.02 } }
      |}""".stripMargin

  val defaultDsl: String =
    """shocks {
      |  shock up2y { discountcurve EUR 2Y +0.0001; }
      |  shock fxup { fxspot EURUSD relative +0.01; }
      |}
      |
      |portfolio RatesBook {
      |  instrument bond BondA { notional 1000000; coupon 0.05; maturity 3; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
      |  instrument swap SwapA { notional 1000000; fixedRate 0.04; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
      |}
      |""".stripMargin

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    for {
      args <- ZIOAppArgs.getArgs.map(_.toList)
      config = CliConfig.fromArgs(args)
      dsl <- load(config.inputPath, defaultDsl)
      marketJson <- load(config.marketPath, defaultMarketJson)
      _ <- (config.prevInputPath, config.prevMarketPath) match {
        case (Some(prevDslPath), Some(prevMarketPath)) =>
          for {
            prevDsl <- load(Some(prevDslPath), "")
            prevMarketJson <- load(Some(prevMarketPath), "")
            _ <- runPeriod(prevDsl, prevMarketJson, dsl, marketJson, config)
          } yield ()
        case (None, None) =>
          runSingleDate(dsl, marketJson, config)
        case _ =>
          ZIO.succeed(println("Error: --prev-input and --prev-market must be provided together"))
      }
    } yield ()

  /** One market snapshot: base prices, Greeks and scenario prices. */
  private def runSingleDate(dsl: String, marketJson: String, config: CliConfig): ZIO[Any, Nothing, Unit] =
    (for {
      market <- MarketDataJson.parse(marketJson)
      program <- Parser.parseProgram(dsl)
    } yield (market, program)) match {
      case Left(err) => ZIO.succeed(println(s"Error: $err"))
      case Right((market, program)) =>
        DslCompiler.build(program, market, sensitivityConfig(config)) match {
          case Left(err) => ZIO.succeed(println(s"Error: $err"))
          case Right(dag) =>
            writeGraph(dag, config) *> Executor.run(dag).foldZIO(
              err => ZIO.succeed(println(s"Execution error: ${err.message}")),
              results => ZIO.succeed(printResults(program, results))
            )
        }
    }

  /** Two dated snapshots: the population events and the exact period P&L decomposition. */
  private def runPeriod(
    prevDsl: String,
    prevMarketJson: String,
    currDsl: String,
    currMarketJson: String,
    config: CliConfig
  ): ZIO[Any, Nothing, Unit] =
    (for {
      prevMarket <- MarketDataJson.parse(prevMarketJson)
      prevProgram <- Parser.parseProgram(prevDsl)
      currMarket <- MarketDataJson.parse(currMarketJson)
      currProgram <- Parser.parseProgram(currDsl)
    } yield (prevProgram, prevMarket, currProgram, currMarket)) match {
      case Left(err) => ZIO.succeed(println(s"Error: $err"))
      case Right((prevProgram, prevMarket, currProgram, currMarket)) =>
        val previous = (prevProgram, prevMarket)
        val current = (currProgram, currMarket)
        config.reportingCurrencyCode match {
          case None => buildAndPrintPeriod(previous, current, config, None)
          case Some(code) =>
            parseCurrency(code) match {
              case Left(err) => ZIO.succeed(println(s"Error: $err"))
              case Right(currency) => buildAndPrintPeriod(previous, current, config, Some(currency))
            }
        }
    }

  private def buildAndPrintPeriod(
    previous: (com.writhlang.dsl.Program, com.writhlang.marketdata.MarketData),
    current: (com.writhlang.dsl.Program, com.writhlang.marketdata.MarketData),
    config: CliConfig,
    reportingCurrency: Option[Currency]
  ): ZIO[Any, Nothing, Unit] =
    DslCompiler.buildPeriodPnl(previous, current, sensitivityConfig(config), reportingCurrency) match {
      case Left(err) => ZIO.succeed(println(s"Error: $err"))
      case Right(period) =>
        writeGraph(period.dag, config) *> Executor.run(period.dag).foldZIO(
          err => ZIO.succeed(println(s"Execution error: ${err.message}")),
          results => ZIO.succeed(printPeriodPnl(period, results))
        )
    }

  private def parseCurrency(code: String): Either[String, Currency] =
    if (code.length == 3 && code.forall(character => character >= 'A' && character <= 'Z')) Right(Currency(code))
    else Left(s"invalid currency code '$code' (expected three uppercase letters, e.g. EUR)")

  private def sensitivityConfig(config: CliConfig): SensitivityConfig =
    config.thetaDays match {
      case Some(days) => SensitivityConfig(thetaPeriod = Some(ThetaPeriod(days)))
      case None       => SensitivityConfig()
    }

  private def writeGraph(dag: Dag, config: CliConfig): ZIO[Any, Nothing, Unit] = {
    val dot = DotRenderer.toDot(dag)
    val dotPath = config.dotPath.getOrElse(Paths.get("build", "writhlang_dag.dot"))
    val pngPath = config.pngPath.getOrElse(Paths.get("build", "writhlang_dag.png"))

    val writeDot = ZIO.attempt {
      val parent = dotPath.getParent
      if (parent != null) Files.createDirectories(parent)
      Files.write(dotPath, dot.getBytes(StandardCharsets.UTF_8))
    }.orElse(ZIO.succeed(println(s"Warning: could not write $dotPath")))

    val render = ZIO.attempt {
      if (graphvizAvailable()) {
        Seq("dot", "-Tpng", dotPath.toString, "-o", pngPath.toString)
          .!(ProcessLogger(_ => (), _ => ()))
      }
    }.orElse(ZIO.succeed(()))

    writeDot.unit *> render.unit
  }

  private def graphvizAvailable(): Boolean = {
    try Seq("dot", "-V").!(ProcessLogger(_ => (), _ => ())) == 0
    catch { case _: Throwable => false }
  }

  private def printResults(program: com.writhlang.dsl.Program, results: Map[String, Double]): Unit = {
    println("--- Scenario Prices ---")
    DslCompiler.flattenInstruments(program.nodes).foreach { case (inst, _) =>
      val basePrice = results.getOrElse(DslCompiler.basePriceId(inst.id), Double.NaN)
      val theta = results.getOrElse(DslCompiler.thetaId(inst.id), Double.NaN)
      val carry = results.getOrElse(DslCompiler.carryId(inst.id), Double.NaN)
      println(s"${inst.id} base=${format(basePrice)} theta=${format(theta)} carry=${format(carry)}")
      program.shocks.foreach { shock =>
        val full = results.getOrElse(DslCompiler.fullScenarioId(inst.id, shock.name), Double.NaN)
        val lin = results.getOrElse(DslCompiler.linearScenarioId(inst.id, shock.name), Double.NaN)
        val quad = results.getOrElse(DslCompiler.quadraticScenarioId(inst.id, shock.name), Double.NaN)
        println(s"  ${shock.name}: full=${format(full)} linear=${format(lin)} quadratic=${format(quad)}")
      }
    }
  }

  /**
    * The period report: one row per trade with its buckets, the book totals, and the
    * reconciliation line. The numbers are book-value changes plus contractual cash;
    * discretionary unwind and entry prices are not in the data and are not claimed.
    */
  private def printPeriodPnl(period: DslCompiler.PeriodPnl, results: Map[String, Double]): Unit = {
    val bucketNames = List("market", "theta", "carry", "inception", "new", "cancelled", "expired")

    def tradeIdOf(event: com.writhlang.portfolio.PopulationEvent): String = event match {
      case com.writhlang.portfolio.Unchanged(trade)    => trade.id.value
      case com.writhlang.portfolio.Amended(_, curr)    => curr.id.value
      case com.writhlang.portfolio.NewTrade(curr)      => curr.id.value
      case com.writhlang.portfolio.Cancelled(prev)     => prev.id.value
      case com.writhlang.portfolio.Expired(prev, _)    => prev.id.value
    }

    def cell(value: Option[Double]): String = value.map(format).getOrElse("")

    println(
      s"--- Period P&L (${period.from} -> ${period.to}" +
        period.reportingCurrency.map(currency => s" in ${currency.code}").getOrElse("") + ") ---"
    )
    println(f"${"trade"}%-16s${"event"}%-12s" + bucketNames.map(name => f"$name%14s").mkString + f"${"total"}%14s")

    period.events.foreach { event =>
      val tradeId = tradeIdOf(event)
      val cells = bucketNames.map(name => cell(results.get(DslCompiler.pnlId(name, tradeId)))).map(c => f"$c%14s").mkString
      val total = results.get(DslCompiler.pnlTotalId(tradeId))
      println(f"$tradeId%-16s${PopulationDiff.bucketOf(event)}%-12s" + cells + f"${cell(total)}%14s")
    }

    val totals = bucketNames.map(name =>
      results.getOrElse(DslCompiler.totalAggregateId(DslCompiler.pnlMetric(name)), 0.0)
    )
    val grandTotal = results.getOrElse(DslCompiler.totalAggregateId("pnl:total"), Double.NaN)
    println()
    println(f"${"BOOK"}%-16s${""}%-12s" + totals.map(total => f"$total%14.4f").mkString + f"$grandTotal%14.4f")
    println(f"reconciliation: sum(buckets) - total = ${totals.sum - grandTotal}%.10f")
    println("note: book-value change plus contractual cash; discretionary unwind/entry prices are excluded")
  }

  private def format(value: Double): String = f"$value%.4f"

  private def load(path: Option[java.nio.file.Path], default: String): ZIO[Any, Throwable, String] =
    path match {
      case Some(p) => ZIO.attempt(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
      case None    => ZIO.succeed(default)
    }

  private case class CliConfig(
    inputPath: Option[java.nio.file.Path],
    marketPath: Option[java.nio.file.Path],
    prevInputPath: Option[java.nio.file.Path],
    prevMarketPath: Option[java.nio.file.Path],
    reportingCurrencyCode: Option[String],
    dotPath: Option[java.nio.file.Path],
    pngPath: Option[java.nio.file.Path],
    thetaDays: Option[Int]
  )

  private object CliConfig {
    def fromArgs(args: List[String]): CliConfig = {
      val iter = args.iterator
      var input: Option[java.nio.file.Path] = None
      var market: Option[java.nio.file.Path] = None
      var prevInput: Option[java.nio.file.Path] = None
      var prevMarket: Option[java.nio.file.Path] = None
      var reportingCurrency: Option[String] = None
      var dot: Option[java.nio.file.Path] = None
      var png: Option[java.nio.file.Path] = None
      var thetaDays: Option[Int] = None

      while (iter.hasNext) {
        iter.next() match {
          case "--input" if iter.hasNext      => input = Some(Paths.get(iter.next()))
          case "--market" if iter.hasNext     => market = Some(Paths.get(iter.next()))
          case "--prev-input" if iter.hasNext  => prevInput = Some(Paths.get(iter.next()))
          case "--prev-market" if iter.hasNext => prevMarket = Some(Paths.get(iter.next()))
          case "--reporting-currency" if iter.hasNext => reportingCurrency = Some(iter.next())
          case "--dot" if iter.hasNext        => dot = Some(Paths.get(iter.next()))
          case "--png" if iter.hasNext        => png = Some(Paths.get(iter.next()))
          case "--theta-days" if iter.hasNext => thetaDays = Some(iter.next().toInt)
          case path if input.isEmpty          => input = Some(Paths.get(path))
          case _                              => ()
        }
      }
      CliConfig(input, market, prevInput, prevMarket, reportingCurrency, dot, png, thetaDays)
    }
  }
}
