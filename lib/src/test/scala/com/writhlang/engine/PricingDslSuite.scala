package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.marketdata.bootstrap._
import com.writhlang.render.DotRenderer
import com.writhlang.risk._
import com.writhlang.scenario._
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import zio.{Runtime, Unsafe}

@RunWith(classOf[JUnitRunner])
class PricingDslSuite extends AnyFunSuite {

  private def run(dag: Dag): Map[String, Double] =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(Executor.run(dag)).getOrThrow()
    }

  private val eurKey = RiskFactorKey(KeyType.DiscountCurve, "EUR")
  private val eur2yKey = RiskFactorKey(KeyType.DiscountCurve, "EUR", tenor = Some(Tenor(2.0)))
  private val creditKey = RiskFactorKey(KeyType.CreditCurve, "EUR.CREDIT")
  private val fxKey = RiskFactorKey(KeyType.FxSpot, "EURUSD")

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

  private def buildDag(
    program: Program,
    market: MarketData,
    config: SensitivityConfig = SensitivityConfig()
  ): Dag = DslCompiler.build(program, market, config).toOption.get

  test("bootstrapper builds discount factors for the worked example") {
    val asOf = market.asOf
    val conventions = market.conventions
    val quotes = QuoteSet(List(
      Deposit(Tenor(1.0), 0.035),
      Swap(Tenor(2.0), 0.040, 1),
      Swap(Tenor(3.0), 0.045, 1)
    ))
    val curve = Bootstrapper.bootstrap(quotes, asOf).toOption.get

    // Pillars are quoted at the day-count fraction from asOf to the *resolved date*:
    // a "1Y" tenor starting in 2024 lands 366 days out, not 365.
    val date1 = Tenor(1.0).resolve(asOf)
    val date2 = Tenor(2.0).resolve(asOf)
    val date3 = Tenor(3.0).resolve(asOf)
    val yf1 = conventions.yearFraction(asOf, date1)
    val yf2 = conventions.yearFraction(asOf, date2)
    val yf3 = conventions.yearFraction(asOf, date3)
    val accrual1 = conventions.yearFraction(asOf, date1)
    val accrual2 = conventions.yearFraction(date1, date2)
    val accrual3 = conventions.yearFraction(date2, date3)

    val df1 = math.exp(-0.035 * yf1)
    val df2 = (1.0 - 0.040 * accrual1 * df1) / (1.0 + 0.040 * accrual2)
    val df3 = (1.0 - 0.045 * (accrual1 * df1 + accrual2 * df2)) / (1.0 + 0.045 * accrual3)

    assert(math.abs(curve.df(yf1) - df1) < 1e-12)
    assert(math.abs(curve.df(yf2) - df2) < 1e-12)
    assert(math.abs(curve.df(yf3) - df3) < 1e-12)
  }

  test("par-conversion: a +1bp 2Y swap bump reprices a 3Y bond via re-bootstrap") {
    val program = parse(
      """shocks {
        |  shock up2y { discountcurve EUR 2Y absolute +0.0001; }
        |}
        |portfolio Book {
        |  instrument bond B1 { notional 1000; coupon 0.045; maturity 3; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
        |}
        |""".stripMargin)

    val results = run(buildDag(program, market))
    val base = results(DslCompiler.basePriceId("B1"))
    val full = results(DslCompiler.fullScenarioId("B1", "up2y"))
    val linear = results(DslCompiler.linearScenarioId("B1", "up2y"))
    val quad = results(DslCompiler.quadraticScenarioId("B1", "up2y"))
    val delta = results(DslCompiler.deltaId("B1", eur2yKey))

    assert(base > 0.0)
    assert(math.abs(delta) > 0.0, "the 2Y swap point moves the bond price")
    // Par-conversion produces a sawtooth: bumping one swap point moves adjacent
    // discount factors in opposite directions, so the net bond move has no fixed
    // sign. What must hold is reconciliation — the quadratic (2nd-order)
    // approximation is at least as close to the full revaluation as linear.
    assert(math.abs(quad - full) <= math.abs(linear - full))
    assert(math.abs(linear - full) < 1e-4 * base, "first-order reconciliation within tolerance")
  }

  test("central/forward/backward delta schemes agree within tolerance") {
    val program = parse(
      """portfolio Book {
        |  instrument bond B1 { notional 1000; coupon 0.045; maturity 3; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
        |}
        |""".stripMargin)

    def deltaFor(scheme: ShiftScheme): Double = {
      val cfg = SensitivityConfig(shiftScheme = scheme)
      val results = run(buildDag(program, market, cfg))
      results(DslCompiler.deltaId("B1", eur2yKey))
    }

    val central = deltaFor(ShiftScheme.Central)
    val forward = deltaFor(ShiftScheme.Forward)
    val backward = deltaFor(ShiftScheme.Backward)

    assert(math.abs(central - forward) < 1e-4)
    assert(math.abs(central - backward) < 1e-4)
  }

  test("factorsFor yields per-pillar keys for a quote-backed curve") {
    val bond = BondSpec("B1", 1000, 0.045, 3, eurKey, creditKey, 1)
    val factors = DslCompiler.factorsFor(bond, market)

    assert(factors.contains(eur2yKey))
    assert(factors.contains(RiskFactorKey(KeyType.DiscountCurve, "EUR", tenor = Some(Tenor(1.0)))))
    assert(factors.contains(RiskFactorKey(KeyType.DiscountCurve, "EUR", tenor = Some(Tenor(3.0)))))
    assert(factors.contains(creditKey), "flat credit curve stays a single undimensioned key")
  }

  test("invalid quotes return Left") {
    val nonMonotonic = QuoteSet(List(
      Swap(Tenor(2.0), 0.040, 1),
      Swap(Tenor(3.0), 0.020, 1)
    ))
    assert(Bootstrapper.bootstrap(nonMonotonic, market.asOf).isLeft)
  }

  test("JSON loader round-trips the market-data schema") {
    val m = market
    assert(m.curve(eurKey).isInstanceOf[BootstrappedCurve])
    assert(m.quotes(eurKey).instruments.size == 3)
    assert(math.abs(m.fxSpot(fxKey).value - 1.25) < 1e-12)
    assert(m.curve(creditKey).rateAt(2.0) == 0.0)
  }

  test("recursive portfolios flatten with paths") {
    val program = parse(
      """portfolio Desk {
        |  portfolio BookA {
        |    instrument bond B1 { notional 1000; coupon 0.045; maturity 3; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
        |  }
        |  portfolio BookB {
        |    instrument swap S1 { notional 1000000; fixedRate 0.04; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
        |  }
        |}
        |""".stripMargin)
    val flattened = DslCompiler.flattenInstruments(program.nodes)
    assert(flattened.size == 2)
    assert(flattened.exists { case (spec, _) => spec.id == "B1" })
    assert(flattened.exists { case (_, path) => path == List("Desk", "BookA") })
    assert(flattened.exists { case (_, path) => path == List("Desk", "BookB") })
  }

  test("fx forward reprices under a relative FX spot shock") {
    val program = parse(
      """shocks { shock fxup { fxspot EURUSD relative +0.01; } }
        |portfolio FxBook {
        |  instrument fxforward F1 { notional 1000000; fxRate 1.25; maturity 1; domesticCurve EUR; foreignCurve EUR; fxSpot EURUSD; }
        |}
        |""".stripMargin)
    val results = run(buildDag(program, market))
    val base = results(DslCompiler.basePriceId("F1"))
    val full = results(DslCompiler.fullScenarioId("F1", "fxup"))
    assert(math.abs(base) < 1.0, "forward is near zero when domestic equals foreign")
    assert(full > base, "a stronger foreign currency raises the forward value")
  }

  test("DOT rendering clusters instrument nodes and keeps edges valid") {
    val program = parse(
      """shocks { shock up2y { discountcurve EUR 2Y absolute +0.0001; } }
        |portfolio Book {
        |  instrument bond B1 { notional 1000; coupon 0.045; maturity 3; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
        |}
        |""".stripMargin)
    val dag = buildDag(program, market)
    val dot = DotRenderer.toDot(dag)
    assert(dot.contains("subgraph cluster_B1"))
    dag.nodes.keys.foreach(id => assert(dot.contains(s"\"$id\" [label="), s"missing node $id"))
    dag.nodes.values.foreach { instr =>
      instr.deps.foreach(dep => assert(dag.nodes.contains(dep), s"${instr.id} refs missing dep $dep"))
    }
  }

  test("shift ordering: cross-factor and within-factor commute; mixed abs/rel does not") {
    val m = market

    def curvePillars(md: MarketData): Vector[(Double, Double)] =
      md.curve(eurKey).asInstanceOf[BootstrappedCurve].pillars

    // 1. Cross-factor: a curve pillar bump and an FX spot bump update disjoint
    //    MarketData fields, so the order does not matter.
    val curveShift = Scenario("c", Map(eurKey -> List(ParQuoteShift(2.0, 0.0001))))
    val fxShift = Scenario("f", Map(fxKey -> List(RelativeSpotShift(0.01))))
    val cThenF = fxShift.applyTo(curveShift.applyTo(m))
    val fThenC = curveShift.applyTo(fxShift.applyTo(m))
    assert(curvePillars(cThenF) == curvePillars(fThenC))
    assert(cThenF.fxSpot(fxKey).value == fThenC.fxSpot(fxKey).value)

    // 2. Within-factor additive: two pillar bumps on one curve also commute —
    //    they add to disjoint quotes and the re-bootstrap is deterministic on
    //    the final quote set.
    val twoThenThree = Scenario("23", Map(eurKey -> List(ParQuoteShift(2.0, 0.0001), ParQuoteShift(3.0, 0.0002))))
    val threeThenTwo = Scenario("32", Map(eurKey -> List(ParQuoteShift(3.0, 0.0002), ParQuoteShift(2.0, 0.0001))))
    assert(curvePillars(twoThenThree.applyTo(m)) == curvePillars(threeThenTwo.applyTo(m)))

    // 3. Mixed absolute + relative on the SAME spot does NOT commute:
    //    (spot + a)·(1+b) != spot·(1+b) + a. This is the case where order matters.
    val absThenRel = Scenario("ar", Map(fxKey -> List(AbsoluteSpotShift(0.01), RelativeSpotShift(0.01))))
    val relThenAbs = Scenario("ra", Map(fxKey -> List(RelativeSpotShift(0.01), AbsoluteSpotShift(0.01))))
    assert(absThenRel.applyTo(m).fxSpot(fxKey).value != relThenAbs.applyTo(m).fxSpot(fxKey).value)
  }

  test("a 2Y bond has zero sensitivity to a curve point beyond its maturity") {
    val md = MarketDataJson.parse(
      """{"curves": {
        |  "EUR": { "type": "DiscountCurve", "instruments": [
        |    {"kind":"deposit","tenor":"1Y","rate":0.035},
        |    {"kind":"swap","tenor":"2Y","rate":0.040,"freq":1},
        |    {"kind":"swap","tenor":"5Y","rate":0.050,"freq":1}
        |  ]},
        |  "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0 }
        |}}""".stripMargin).toOption.get

    val bond = BondSpec("B2", 1000, 0.04, 2, eurKey, creditKey, 1)
    val bump = 0.0001

    def deltaAt(tenor: Double): Double = {
      val up = Scenario("up", Map(eurKey -> List(ParQuoteShift(tenor, bump)))).applyTo(md)
      val down = Scenario("dn", Map(eurKey -> List(ParQuoteShift(tenor, -bump)))).applyTo(md)
      (Pricing.price(bond, up) - Pricing.price(bond, down)) / (2.0 * bump)
    }

    val base = Pricing.price(bond, md)
    val delta2y = deltaAt(2.0)
    val delta5y = deltaAt(5.0)

    assert(math.abs(base - 1000.0) < 1e-6, s"2Y 4% bond should price at par, got $base")
    assert(delta5y == 0.0, s"expected zero 5Y delta for a 2Y bond, got $delta5y")
    assert(delta2y != 0.0, s"expected non-zero 2Y delta, got $delta2y")
  }
}
