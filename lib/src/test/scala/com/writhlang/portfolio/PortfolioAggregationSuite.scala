package com.writhlang.portfolio

import com.writhlang.dsl._
import com.writhlang.engine.{Dag, DslCompiler, Executor, Sum}
import com.writhlang.marketdata._
import com.writhlang.render.DotRenderer
import com.writhlang.risk.RiskFactorKey
import com.writhlang.scenario.SensitivityConfig
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import zio.{Runtime, Unsafe}

/**
  * Roll-up: book/desk/total nodes are plain sums, so they are exact, and a sum of
  * sensitivities is netted (opposing positions cancel).
  */
@RunWith(classOf[JUnitRunner])
class PortfolioAggregationSuite extends AnyFunSuite {

  private def run(dag: Dag): Map[String, Double] =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(Executor.run(dag)).getOrThrow()
    }

  private val marketJson =
    """{
      |  "curves": {
      |    "EUR": { "type": "DiscountCurve", "instruments": [
      |      { "kind": "deposit", "tenor": "1Y", "rate": 0.035 },
      |      { "kind": "swap", "tenor": "2Y", "rate": 0.040, "freq": 1 },
      |      { "kind": "swap", "tenor": "3Y", "rate": 0.045, "freq": 1 }
      |    ]},
      |    "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0 }
      |  }
      |}""".stripMargin

  private def market: MarketData = MarketDataJson.parse(marketJson).toOption.get
  private def parse(dsl: String): Program = Parser.parseProgram(dsl).toOption.get

  private def dag(program: Program, config: SensitivityConfig = SensitivityConfig()): Dag =
    DslCompiler.build(program, market, config).toOption.get

  /** The whole-book roll-up id for a metric, e.g. `price:base` or `greek:delta:<key>`. */
  private def total(metric: String): String = DslCompiler.totalAggregateId(metric)

  /** The roll-up id for a metric at a book path, e.g. `Desk/BookA`. */
  private def at(metric: String, path: String): String =
    DslCompiler.aggregateId(metric, path.split("/").toList.filter(_.nonEmpty))

  private def bond(id: String, notional: Double, coupon: Double, maturity: Int): String =
    s"""instrument bond $id { notional $notional; coupon $coupon; maturity $maturity; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }"""

  private val nestedBook =
    s"""shocks { shock up2y { discountcurve EUR 2Y absolute +0.0001; } }
       |portfolio Desk {
       |  portfolio BookA {
       |    ${bond("B1", 1000, 0.04, 2)}
       |  }
       |  portfolio BookB {
       |    ${bond("B2", 2000, 0.05, 3)}
       |  }
       |}""".stripMargin

  test("the book total is the exact sum of trade prices at every level") {
    val values = run(dag(parse(nestedBook)))
    val b1 = values(DslCompiler.basePriceId("B1"))
    val b2 = values(DslCompiler.basePriceId("B2"))

    assert(math.abs(values(total("price:base")) - (b1 + b2)) < 1e-9)
    assert(math.abs(values(at("price:base", "Desk")) - (b1 + b2)) < 1e-9)
    assert(math.abs(values(at("price:base", "Desk/BookA")) - b1) < 1e-9)
    assert(math.abs(values(at("price:base", "Desk/BookB")) - b2) < 1e-9)
  }

  test("Greeks roll up per factor and net across trades") {
    val values = run(dag(parse(nestedBook)))
    val key = "DiscountCurve:EUR:2Y"
    val riskFactorKey = RiskFactorKey.parse(key).toOption.get
    val tradeDelta = values(DslCompiler.deltaId("B1", riskFactorKey))
    val metric = s"greek:delta:$key"

    assert(math.abs(tradeDelta) > 0.0)
    assert(math.abs(values(at(metric, "Desk/BookA")) - tradeDelta) < 1e-12)
    // BookA's trade plus BookB's trade must reconstruct the desk roll-up exactly.
    val bookB = values(DslCompiler.deltaId("B2", riskFactorKey))
    assert(math.abs(values(at(metric, "Desk")) - (tradeDelta + bookB)) < 1e-12)
    assert(math.abs(values(total(metric)) - values(at(metric, "Desk"))) < 1e-12)
  }

  test("an offsetting long and short net to zero in price and sensitivity") {
    val program = parse(
      s"""portfolio Hedged {
         |  ${bond("LONG", 1000, 0.04, 2)}
         |  ${bond("SHORT", -1000, 0.04, 2)}
         |}""".stripMargin)
    val values = run(dag(program))

    assert(math.abs(values(total("price:base"))) < 1e-9, "netted price must be zero")
    assert(math.abs(values(total("greek:delta:DiscountCurve:EUR:2Y"))) < 1e-9, "netted delta must be zero")
    assert(math.abs(values(total("price:theta"))) < 1e-9, "netted theta must be zero")
    assert(math.abs(values(total("price:carry"))) < 1e-9, "netted carry must be zero")
  }

  test("scenario prices roll up as well as base prices") {
    val values = run(dag(parse(nestedBook)))
    val linearMetric = "price:linear:up2y"
    val fullMetric = "price:full:up2y"
    val sumOfLinear =
      values(DslCompiler.linearScenarioId("B1", "up2y")) + values(DslCompiler.linearScenarioId("B2", "up2y"))
    val sumOfFull =
      values(DslCompiler.fullScenarioId("B1", "up2y")) + values(DslCompiler.fullScenarioId("B2", "up2y"))

    assert(math.abs(values(total(linearMetric)) - sumOfLinear) < 1e-9)
    assert(math.abs(values(total(fullMetric)) - sumOfFull) < 1e-9)
  }

  test("theta and carry roll up to the whole book") {
    val values = run(dag(parse(nestedBook), SensitivityConfig()))
    val thetaSum = values(DslCompiler.thetaId("B1")) + values(DslCompiler.thetaId("B2"))
    val carrySum = values(DslCompiler.carryId("B1")) + values(DslCompiler.carryId("B2"))

    assert(math.abs(values(total("price:theta")) - thetaSum) < 1e-9)
    assert(math.abs(values(total("price:carry")) - carrySum) < 1e-9)
  }

  test("every roll-up node's dependencies resolve") {
    val built = dag(parse(nestedBook))
    val aggregateNodes = built.nodes.values.filter(_.op == Sum).toList

    assert(aggregateNodes.nonEmpty)
    aggregateNodes.foreach { node =>
      assert(node.deps.nonEmpty, s"roll-up ${node.id} has no dependencies")
      node.deps.foreach(dep => assert(built.nodes.contains(dep), s"${node.id} references missing $dep"))
    }
  }

  test("the DOT graph renders the roll-up nodes") {
    val dot = DotRenderer.toDot(dag(parse(nestedBook)))

    assert(dot.contains(total("price:base")))
    assert(dot.contains(at("price:base", "Desk/BookA")))
    assert(dot.contains("Σ"))
  }

  test("duplicate trade ids are rejected rather than silently collapsed") {
    val program = parse(
      s"""portfolio Desk {
         |  portfolio BookA { ${bond("DUP", 1000, 0.04, 2)} }
         |  portfolio BookB { ${bond("DUP", 2000, 0.05, 3)} }
         |}""".stripMargin)

    val result = DslCompiler.build(program, market)
    assert(result.isLeft)
    assert(result.left.get.contains("duplicate trade id"))
  }
}
