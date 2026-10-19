package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.render.DotRenderer
import com.writhlang.scenario._
import com.writhlang.time._
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import zio.{Runtime, Unsafe}

import java.time.LocalDate

@RunWith(classOf[JUnitRunner])
class TimeDimensionSuite extends AnyFunSuite {

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
      |  },
      |  "fxSpots": { "EURUSD": 1.25 },
      |  "volSurfaces": { "EUR.SWAPTION": { "flat": 0.20 } },
      |  "prepayVectors": { "EUR.MBS": { "termMonths": 360, "flat": 0.02 } }
      |}""".stripMargin

  private def market: MarketData = MarketDataJson.parse(marketJson).toOption.get

  private def parse(dsl: String): Program = Parser.parseProgram(dsl).toOption.get

  private def buildDag(program: Program, market: MarketData, config: SensitivityConfig): Dag =
    DslCompiler.build(program, market, config).toOption.get

  test("day-count conventions compute year fractions") {
    val from = LocalDate.of(2024, 1, 1)
    val to = LocalDate.of(2025, 1, 1) // 366 days (2024 is a leap year)
    assert(math.abs(DayCount.Actual365Fixed.yearFraction(from, to) - 366.0 / 365.0) < 1e-12)
    assert(math.abs(DayCount.Actual360.yearFraction(from, to) - 366.0 / 360.0) < 1e-12)
    assert(math.abs(DayCount.Thirty360.yearFraction(from, to) - 1.0) < 1e-12)
  }

  test("weekend calendar rolls Friday to Monday") {
    val friday = LocalDate.of(2024, 1, 5) // Friday
    val monday = LocalDate.of(2024, 1, 8) // Monday
    assert(Calendar.weekend.addBusinessDays(friday, 1) == monday)
    assert(!Calendar.weekend.isBusinessDay(LocalDate.of(2024, 1, 6))) // Saturday
  }

  test("a swaption's one-day theta is a full revaluation at the advanced date") {
    val program = parse(
      """portfolio Book {
        |  instrument swaption SW { notional 1000000; strike 0.06; expiry 1; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; volSurface EUR.SWAPTION; freq 1; call; }
        |}""".stripMargin)
    val cfg = SensitivityConfig(thetaPeriod = Some(ThetaPeriod(1)))
    val results = run(buildDag(program, market, cfg))

    val base = results(DslCompiler.basePriceId("SW"))
    val theta = results(DslCompiler.thetaId("SW"))
    assert(theta != 0.0, "theta must be non-zero for an option")

    // Theta is now a full revaluation at the advanced valuation date on the frozen
    // market (not a tenor shift), so the check is the difference of two revaluations.
    val swaptionSpec = DslCompiler.flattenInstruments(program.nodes).head._1.asInstanceOf[SwaptionSpec]
    val advancedDate = cfg.thetaValuationDate(market.asOf).get
    // The trade date is pinned to the base snapshot, exactly as the DAG pins it.
    val tradeDate = Some(market.asOf)
    val manualTheta =
      Pricing.price(swaptionSpec, market.withAsOf(advancedDate), tradeDate) -
        Pricing.price(swaptionSpec, market, tradeDate)
    assert(math.abs(theta - manualTheta) < 1e-9 * math.max(1.0, math.abs(base)))
    assert(theta < 0.0, "a long out-of-the-money option loses value as time passes")
  }

  test("a bond's one-day carry equals its accrued coupon") {
    val program = parse(
      """portfolio Book {
        |  instrument bond B { notional 1000000; coupon 0.05; maturity 3; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
        |}""".stripMargin)
    val cfg = SensitivityConfig(thetaPeriod = Some(ThetaPeriod(1)))
    val results = run(buildDag(program, market, cfg))
    val carry = results(DslCompiler.carryId("B"))
    // Same convention as the DAG's carry node, so the expected accrual follows any
    // change to the theta horizon, calendar, or day-count.
    val elapsedYears = ThetaPeriod(1).elapsedYears(market.asOf, cfg.calendar, cfg.dayCount)
    val expected = 1000000.0 * 0.05 * elapsedYears
    assert(math.abs(carry - expected) < 1e-9)
  }

  test("market-data JSON parses and defaults the asOf date") {
    val withDate = MarketDataJson.parse(
      """{"asOf":"2025-06-30","curves":{"EUR":{"type":"DiscountCurve","instruments":[
        |  {"kind":"deposit","tenor":"1Y","rate":0.035},
        |  {"kind":"swap","tenor":"2Y","rate":0.040,"freq":1}
        |]}}}""".stripMargin)
    assert(withDate.toOption.get.asOf == LocalDate.of(2025, 6, 30))
    assert(market.asOf == LocalDate.of(2024, 1, 1))
  }

  test("DOT rendering includes theta and carry nodes") {
    val program = parse(
      """portfolio Book {
        |  instrument bond B { notional 1000; coupon 0.05; maturity 3; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
        |}""".stripMargin)
    val cfg = SensitivityConfig(thetaPeriod = Some(ThetaPeriod(1)))
    val dag = buildDag(program, market, cfg)
    val dot = DotRenderer.toDot(dag)
    assert(dot.contains(DslCompiler.thetaId("B")))
    assert(dot.contains(DslCompiler.carryId("B")))
  }
}
