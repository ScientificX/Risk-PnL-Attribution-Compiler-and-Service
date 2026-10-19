package com.writhlang.portfolio

import com.writhlang.dsl._
import com.writhlang.engine.{DslCompiler, Executor}
import com.writhlang.marketdata._
import com.writhlang.time._
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import zio.{Runtime, Unsafe}

import java.time.LocalDate

/**
  * The trade layer: population snapshots, their lifecycle states, and the
  * classification of what happened between two valuation dates.
  */
@RunWith(classOf[JUnitRunner])
class PopulationDiffSuite extends AnyFunSuite {

  private def run(dag: com.writhlang.engine.Dag): Map[String, Double] =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(Executor.run(dag)).getOrThrow()
    }

  private def marketAsOf(date: LocalDate): MarketData =
    MarketDataJson.parse(
      s"""{
         |  "asOf": "$date",
         |  "curves": {
         |    "EUR": { "type": "DiscountCurve", "instruments": [
         |      { "kind": "deposit", "tenor": "1Y", "rate": 0.035 },
         |      { "kind": "swap", "tenor": "2Y", "rate": 0.040, "freq": 1 },
         |      { "kind": "swap", "tenor": "3Y", "rate": 0.045, "freq": 1 }
         |    ]},
         |    "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0 }
         |  }
         |}""".stripMargin
    ).toOption.get

  private def parseProgram(dsl: String): Program = Parser.parseProgram(dsl).toOption.get

  private def population(dsl: String, asOf: LocalDate): TradePopulation =
    TradePopulation.fromProgram(parseProgram(dsl), marketAsOf(asOf)).toOption.get

  private def diff(prevDsl: String, currDsl: String, prevAsOf: LocalDate, currAsOf: LocalDate): Either[String, List[PopulationEvent]] =
    PopulationDiff.between(population(prevDsl, prevAsOf), population(currDsl, currAsOf))

  private def bond(id: String, coupon: Double, maturityYears: Int, tradeDate: String): String =
    s"""instrument bond $id { notional 1000; coupon $coupon; maturity $maturityYears; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; tradeDate $tradeDate; }"""

  private def book(instruments: String*): String =
    s"portfolio Book {\n  ${instruments.mkString("\n  ")}\n}"

  private val t0 = LocalDate.of(2024, 1, 1)
  private val t1 = LocalDate.of(2024, 2, 1)

  // -------------------------------------------------------------------------
  // Classification
  // -------------------------------------------------------------------------

  test("a trade present at both dates with identical terms is unchanged") {
    val dsl = book(bond("A", 0.04, 2, "2024-01-01"))
    val events = diff(dsl, dsl, t0, t1).toOption.get

    events match {
      case List(Unchanged(trade)) =>
        assert(trade.id.value == "A")
        assert(trade.path == List("Book"))
        assert(trade.tradeDate == LocalDate.of(2024, 1, 1))
      case other => fail(s"expected a single unchanged trade, got $other")
    }
  }

  test("a trade only on the later book is new") {
    val prev = book(bond("A", 0.04, 2, "2024-01-01"))
    val curr = book(bond("A", 0.04, 2, "2024-01-01"), bond("D", 0.05, 3, "2024-02-01"))
    val events = diff(prev, curr, t0, t1).toOption.get

    assert(events.collect { case NewTrade(trade) => trade.id.value } == List("D"))
  }

  test("a trade removed before maturity is cancelled, not expired") {
    val prev = book(bond("A", 0.04, 2, "2024-01-01"), bond("C", 0.03, 2, "2024-01-01"))
    val curr = book(bond("A", 0.04, 2, "2024-01-01"))
    val events = diff(prev, curr, t0, t1).toOption.get

    assert(events.collect { case Cancelled(trade) => trade.id.value } == List("C"))
  }

  test("a trade whose terms changed between snapshots is amended") {
    val prev = book(bond("B", 0.05, 2, "2024-01-01"))
    val curr = book(bond("B", 0.06, 2, "2024-01-01"))
    val events = diff(prev, curr, t0, t1).toOption.get

    events match {
      case List(Amended(before, after)) =>
        assert(before.spec.asInstanceOf[BondSpec].coupon == 0.05)
        assert(after.spec.asInstanceOf[BondSpec].coupon == 0.06)
      case other => fail(s"expected a single amendment, got $other")
    }
  }

  test("a trade that matures inside the period is expired, not cancelled") {
    // Enters 2023-06-01 with a 1Y term: settles 2024-06-01, so it is alive at
    // 2024-01-01 and expired by 2024-07-01.
    val prev = book(bond("E", 0.04, 1, "2023-06-01"), bond("A", 0.04, 3, "2024-01-01"))
    val curr = book(bond("A", 0.04, 3, "2024-01-01"))
    val events = diff(prev, curr, t0, LocalDate.of(2024, 7, 1)).toOption.get

    assert(events.collect { case Expired(trade, _) => trade.id.value } == List("E"))
    assert(!events.exists(_.isInstanceOf[Cancelled]))
  }

  test("a matured trade still listed at the end date is expired with both versions") {
    val prev = book(bond("E", 0.04, 1, "2023-06-01"))
    val curr = book(bond("E", 0.04, 1, "2023-06-01"))
    val events = diff(prev, curr, t0, LocalDate.of(2024, 7, 1)).toOption.get

    events match {
      case List(Expired(_, Some(after))) => assert(after.id.value == "E")
      case other                         => fail(s"expected an expiry carrying the current version, got $other")
    }
  }

  test("a trade is active before its settlement date and expired on it") {
    val population = this.population(book(bond("E", 0.04, 1, "2023-06-01")), t0)
    val trade = population.trades(TradeId("E"))

    assert(trade.stateAt(LocalDate.of(2024, 5, 31), population.conventions) == TradeState.Active)
    assert(trade.stateAt(LocalDate.of(2024, 6, 1), population.conventions) == TradeState.Expired)
  }

  // -------------------------------------------------------------------------
  // Rejected inputs
  // -------------------------------------------------------------------------

  test("a trade date that changes between snapshots is rejected, not amended") {
    val prev = book(bond("B", 0.05, 2, "2024-01-01"))
    val curr = book(bond("B", 0.05, 2, "2024-02-01"))
    val result = diff(prev, curr, t0, t1)

    assert(result.isLeft)
    assert(result.left.get.contains("changed its tradeDate"))
  }

  test("a trade's currency changing between snapshots is rejected, not amended") {
    val previous = population(book(bond("B", 0.05, 2, "2024-01-01")), t0)
    val current = population(
      book(
        """instrument bond B { notional 1000; coupon 0.05; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; tradeDate 2024-01-01; currency USD; }"""
      ),
      t1
    )
    val result = PopulationDiff.between(previous, current)

    assert(result.isLeft)
    assert(result.left.get.contains("changed its currency"))
  }

  test("a trade already settled at the previous date is rejected") {
    val prev = book(bond("E", 0.04, 1, "2022-01-01")) // settles 2023-01-01, before t0
    val result = diff(prev, prev, t0, t1)

    assert(result.isLeft)
    assert(result.left.get.contains("already settled"))
  }

  test("a backwards valuation period is rejected") {
    val dsl = book(bond("A", 0.04, 2, "2024-01-01"))
    val result = diff(dsl, dsl, t1, t0)

    assert(result.isLeft)
    assert(result.left.get.contains("precedes"))
  }

  test("duplicate trade ids are rejected when building a population") {
    val dsl = book(bond("A", 0.04, 2, "2024-01-01"), bond("A", 0.05, 3, "2024-01-01"))
    val result = TradePopulation.fromProgram(parseProgram(dsl), marketAsOf(t0))

    assert(result.isLeft)
    assert(result.left.get.contains("duplicate trade id"))
  }

  // -------------------------------------------------------------------------
  // Trade dates
  // -------------------------------------------------------------------------

  test("a trade without a tradeDate defaults to the valuation date") {
    val dsl = book(
      """instrument bond A { notional 1000; coupon 0.04; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }"""
    )
    val trade = population(dsl, t0).trades(TradeId("A"))
    assert(trade.tradeDate == t0)
  }

  test("a trade's tradeDate is parsed and anchors its maturity date") {
    val trade = population(book(bond("A", 0.04, 2, "2022-03-15")), t0).trades(TradeId("A"))
    assert(trade.tradeDate == LocalDate.of(2022, 3, 15))
    assert(trade.settlementDate(marketAsOf(t0).conventions) == LocalDate.of(2024, 3, 15))
  }

  test("the DAG values a seasoned trade from its trade date, not the valuation date") {
    val dsl = book(bond("B", 0.05, 3, "2022-01-01"))
    val market = marketAsOf(t0)
    val results = run(DslCompiler.build(parseProgram(dsl), market).toOption.get)

    // Entered 2022-01-01 with a 3Y term, so it matures 2025-01-01: the 2023 and
    // 2024 coupons are already paid (ex-coupon at t0) and must not be revalued.
    // The only remaining cashflow is the 2025-01-01 coupon plus principal.
    val accrual2024to2025 = DayCount.Actual365Fixed.yearFraction(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1))
    val dfOneYear = math.exp(-0.035 * 366.0 / 365.0)
    val expected = (1000.0 + 1000.0 * 0.05 * accrual2024to2025) * dfOneYear
    val base = results(DslCompiler.basePriceId("B"))

    assert(math.abs(base - expected) < 1e-6, s"expected $expected, got $base")
  }
}
