package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.portfolio._
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import zio.{Runtime, Unsafe}

import java.time.LocalDate

/**
  * The two-date P&L decomposition: the exact cash identity, and one bucket per
  * population event. These are book-value numbers: discretionary cash (unwind
  * prices, entry prices, fees) is not in the data and is deliberately not claimed.
  */
@RunWith(classOf[JUnitRunner])
class TradePnlSuite extends AnyFunSuite {

  private def run(dag: Dag): Map[String, Double] =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(Executor.run(dag)).getOrThrow()
    }

  private def marketAt(asOf: LocalDate, swap2Y: Double = 0.040): MarketData =
    MarketDataJson.parse(
      s"""{
         |  "asOf": "$asOf",
         |  "curves": {
         |    "EUR": { "type": "DiscountCurve", "instruments": [
         |      { "kind": "deposit", "tenor": "1Y", "rate": 0.035 },
         |      { "kind": "swap", "tenor": "2Y", "rate": $swap2Y, "freq": 1 },
         |      { "kind": "swap", "tenor": "3Y", "rate": 0.045, "freq": 1 }
         |    ]},
         |    "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0 }
         |  }
         |}""".stripMargin
    ).toOption.get

  private def parse(dsl: String): Program = Parser.parseProgram(dsl).toOption.get

  private def book(instruments: String*): String =
    s"portfolio Book {\n  ${instruments.mkString("\n  ")}\n}"

  private def bond(id: String, coupon: Double, maturityYears: Int, tradeDate: String, notional: Double = 1000.0): String =
    s"""instrument bond $id { notional $notional; coupon $coupon; maturity $maturityYears; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; tradeDate $tradeDate; }"""

  private def period(prevDsl: String, currDsl: String, m0: MarketData, m1: MarketData): DslCompiler.PeriodPnl =
    DslCompiler.buildPeriodPnl((parse(prevDsl), m0), (parse(currDsl), m1)).toOption.get

  private val t0 = LocalDate.of(2024, 1, 1)
  private val t1 = LocalDate.of(2024, 1, 2)
  private val tradeDate = LocalDate.of(2024, 1, 1)

  private def sumBuckets(values: Map[String, Double], tradeId: String, buckets: List[String]): Double =
    buckets.flatMap(bucket => values.get(DslCompiler.pnlId(bucket, tradeId))).sum

  // -------------------------------------------------------------------------
  // Acceptance: an amended trade is isolated from the market move
  // -------------------------------------------------------------------------

  test("an overnight amendment is isolated and the identity holds exactly") {
    val prevDsl = book(bond("A", 0.04, 2, "2024-01-01"), bond("B", 0.05, 3, "2024-01-01"))
    val currDsl = book(bond("A", 0.04, 2, "2024-01-01"), bond("B", 0.06, 3, "2024-01-01"))
    val m0 = marketAt(t0)
    val m1 = marketAt(t1, swap2Y = 0.0401) // 2Y par quote +1bp
    val compiled = period(prevDsl, currDsl, m0, m1)
    val values = run(compiled.dag)

    val oldB = Program.trades(parse(prevDsl).nodes).last._1
    val newB = Program.trades(parse(currDsl).nodes).last._1

    val expectedInception =
      Pricing.price(newB, m0, Some(tradeDate)) - Pricing.price(oldB, m0, Some(tradeDate))
    val expectedMarket =
      Pricing.price(newB, m1.withAsOf(t1), Some(tradeDate)) - Pricing.price(newB, m0.withAsOf(t1), Some(tradeDate))

    assert(math.abs(values(DslCompiler.pnlId("inception", "B")) - expectedInception) < 1e-9)
    assert(expectedInception > 0.0, "increasing the coupon raises the value")
    assert(math.abs(values(DslCompiler.pnlId("market", "B")) - expectedMarket) < 1e-9)

    // The unchanged trade has no inception bucket at all.
    assert(values.get(DslCompiler.pnlId("inception", "A")).isEmpty)

    // Buckets of the amended trade reconstruct its total.
    val amendedBuckets = List("market", "theta", "carry", "inception")
    assert(math.abs(sumBuckets(values, "B", amendedBuckets) - values(DslCompiler.pnlTotalId("B"))) < 1e-9)

    // Book-level identity: Σ buckets = Σ end value + cash − Σ start value.
    val prevTrades = Program.trades(parse(prevDsl).nodes)
    val currTrades = Program.trades(parse(currDsl).nodes)
    val expectedTotal =
      currTrades.map { case (spec, date, _, _) =>
        Pricing.price(spec, m1, date) + Pricing.cashIn(spec, m0, t0, t1, date)
      }.sum - prevTrades.map { case (spec, date, _, _) => Pricing.price(spec, m0, date) }.sum

    assert(math.abs(values(DslCompiler.totalAggregateId("pnl:total")) - expectedTotal) < 1e-9)
    val tradeTotals = currTrades.map { case (spec, _, _, _) => values(DslCompiler.pnlTotalId(spec.id)) }.sum
    assert(math.abs(tradeTotals - values(DslCompiler.totalAggregateId("pnl:total"))) < 1e-9)
  }

  // -------------------------------------------------------------------------
  // Population events
  // -------------------------------------------------------------------------

  test("a cancelled trade is the negative of its start value and has no market or theta") {
    val prevDsl = book(bond("A", 0.04, 3, "2024-01-01"), bond("C", 0.03, 3, "2024-01-01"))
    val currDsl = book(bond("A", 0.04, 3, "2024-01-01"))
    val m0 = marketAt(t0)
    val m1 = marketAt(t1, swap2Y = 0.0401)
    val values = run(period(prevDsl, currDsl, m0, m1).dag)

    val cancelledSpec = Program.trades(parse(prevDsl).nodes).last._1
    val expected = -Pricing.price(cancelledSpec, m0, Some(tradeDate))

    assert(math.abs(values(DslCompiler.pnlId("cancelled", "C")) - expected) < 1e-9)
    assert(values.get(DslCompiler.pnlId("market", "C")).isEmpty)
    assert(values.get(DslCompiler.pnlId("theta", "C")).isEmpty)
    assert(math.abs(values(DslCompiler.pnlTotalId("C")) - expected) < 1e-9)
  }

  test("a new trade is its end value plus cash, with no decomposition") {
    val prevDsl = book(bond("A", 0.04, 3, "2024-01-01"))
    val currDsl = book(bond("A", 0.04, 3, "2024-01-01"), bond("N", 0.05, 2, "2024-01-02"))
    val m0 = marketAt(t0)
    val m1 = marketAt(t1, swap2Y = 0.0401)
    val values = run(period(prevDsl, currDsl, m0, m1).dag)

    val newSpec = Program.trades(parse(currDsl).nodes).last._1
    val enteredAt = LocalDate.of(2024, 1, 2)
    val expected = Pricing.price(newSpec, m1, Some(enteredAt)) + Pricing.cashIn(newSpec, m0, t0, t1, Some(enteredAt))

    assert(math.abs(values(DslCompiler.pnlId("new", "N")) - expected) < 1e-9)
    assert(values.get(DslCompiler.pnlId("market", "N")).isEmpty)
  }

  test("a trade maturing inside the period settles for cash and is not cancelled") {
    // Entered 2023-06-01 with a 1Y term: settles 2024-06-01, inside (t0, t1].
    val prevDsl = book(bond("A", 0.04, 3, "2024-01-01"), bond("E", 0.04, 1, "2023-06-01"))
    val currDsl = book(bond("A", 0.04, 3, "2024-01-01"))
    val m0 = marketAt(t0)
    val m1 = marketAt(LocalDate.of(2024, 7, 1), swap2Y = 0.0401)
    val compiled = period(prevDsl, currDsl, m0, m1)
    val values = run(compiled.dag)

    val expiredSpec = Program.trades(parse(prevDsl).nodes).last._1
    val enteredAt = LocalDate.of(2023, 6, 1)
    val cash = Pricing.cashIn(expiredSpec, m0, t0, LocalDate.of(2024, 7, 1), Some(enteredAt))
    val expected = cash - Pricing.price(expiredSpec, m0, Some(enteredAt))

    assert(compiled.events.exists { case Expired(trade, _) => trade.id.value == "E"; case _ => false })
    assert(!compiled.events.exists(_.isInstanceOf[Cancelled]))
    assert(math.abs(values(DslCompiler.pnlId("expired", "E")) - expected) < 1e-9)
    assert(values.get(DslCompiler.pnlId("cancelled", "E")).isEmpty)
  }

  test("a trade amended and then matured splits inception from settlement") {
    val prevDsl = book(bond("E", 0.04, 1, "2023-06-01"))
    val currDsl = book(bond("E", 0.05, 1, "2023-06-01"))
    val m0 = marketAt(t0)
    val m1 = marketAt(LocalDate.of(2024, 7, 1), swap2Y = 0.0401)
    val compiled = period(prevDsl, currDsl, m0, m1)
    val values = run(compiled.dag)

    val prevSpec = Program.trades(parse(prevDsl).nodes).head._1
    val currSpec = Program.trades(parse(currDsl).nodes).head._1
    val enteredAt = LocalDate.of(2023, 6, 1)
    val inception = Pricing.price(currSpec, m0, Some(enteredAt)) - Pricing.price(prevSpec, m0, Some(enteredAt))
    val cash = Pricing.cashIn(currSpec, m0, t0, LocalDate.of(2024, 7, 1), Some(enteredAt))
    val expired = cash - Pricing.price(currSpec, m0, Some(enteredAt))

    assert(compiled.events.exists { case Expired(_, Some(_)) => true; case _ => false })
    assert(math.abs(values(DslCompiler.pnlId("inception", "E")) - inception) < 1e-9)
    assert(math.abs(values(DslCompiler.pnlId("expired", "E")) - expired) < 1e-9)
    assert(math.abs(values(DslCompiler.pnlId("inception", "E")) + values(DslCompiler.pnlId("expired", "E")) -
      (cash - Pricing.price(prevSpec, m0, Some(enteredAt)))) < 1e-9)
  }

  // -------------------------------------------------------------------------
  // Guards
  // -------------------------------------------------------------------------

  test("a period run without an explicit tradeDate is rejected") {
    val noDate = book(
      """instrument bond A { notional 1000; coupon 0.04; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }"""
    )
    val result = DslCompiler.buildPeriodPnl(
      (parse(noDate), marketAt(t0)),
      (parse(noDate), marketAt(t1))
    )

    assert(result.isLeft)
    assert(result.left.get.contains("no tradeDate"))
  }

  test("book roll-ups are emitted for every bucket that occurred") {
    val prevDsl = book(bond("A", 0.04, 2, "2024-01-01"), bond("C", 0.03, 3, "2024-01-01"))
    val currDsl = book(bond("A", 0.04, 2, "2024-01-01"), bond("N", 0.05, 2, "2024-01-02"))
    val values = run(period(prevDsl, currDsl, marketAt(t0), marketAt(t1, 0.0401)).dag)

    assert(values.contains(DslCompiler.totalAggregateId("pnl:market")))
    assert(values.contains(DslCompiler.totalAggregateId("pnl:carry")))
    assert(values.contains(DslCompiler.totalAggregateId("pnl:cancelled")))
    assert(values.contains(DslCompiler.totalAggregateId("pnl:new")))
    assert(values.contains(DslCompiler.totalAggregateId("pnl:total")))
  }
}
