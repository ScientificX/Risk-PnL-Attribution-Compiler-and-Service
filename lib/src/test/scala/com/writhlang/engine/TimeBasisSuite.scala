package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.risk._
import com.writhlang.time._
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

import java.time.LocalDate

/**
  * The date basis: schedules, day-count accrual, settlement dates and the fact
  * that a valuation is a function of the valuation date and the trade's date.
  */
@RunWith(classOf[JUnitRunner])
class TimeBasisSuite extends AnyFunSuite {

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
      |  "prepayVectors": { "EUR.MBS": { "termMonths": 360, "flat": 0.02 } }
      |}""".stripMargin

  private def market: MarketData = MarketDataJson.parse(marketJson).toOption.get

  private val eurKey = RiskFactorKey(KeyType.DiscountCurve, "EUR")
  private val creditKey = RiskFactorKey(KeyType.CreditCurve, "EUR.CREDIT")

  private def bond(maturityYears: Int, coupon: Double = 0.04, freq: Int = 1): BondSpec =
    BondSpec("B", 1000.0, coupon, maturityYears, eurKey, creditKey, freq)

  test("coupon dates are calendar anniversaries and accrue on the day-count basis") {
    val conventions = market.conventions
    val start = LocalDate.of(2024, 1, 1)
    val dates = Schedule.dates(start, 2, 12, conventions)

    assert(dates == Vector(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)))
    // 2024 is a leap year: the first coupon period is 366 days, the second 365.
    assert(math.abs(conventions.yearFraction(start, dates.head) - 366.0 / 365.0) < 1e-12)
    assert(math.abs(conventions.yearFraction(dates.head, dates(1)) - 365.0 / 365.0) < 1e-12)
  }

  test("a par bond prices at par on the day-count basis") {
    // The 2Y 4% annual bond has exactly the schedule of the 2Y 4% par swap quote,
    // so its fixed leg and redemption must reproduce the par value.
    assert(math.abs(Pricing.price(bond(2), market) - 1000.0) < 1e-9)
  }

  test("a trade valued after its settlement date is worth zero") {
    val spec = bond(2)
    val tradeDate = Some(LocalDate.of(2024, 1, 1))
    val afterMaturity = market.withAsOf(LocalDate.of(2026, 1, 1))

    assert(Pricing.hasMatured(spec, afterMaturity, tradeDate))
    assert(Pricing.price(spec, afterMaturity, tradeDate) == 0.0)
    assert(Pricing.accrued(spec, afterMaturity, tradeDate) == 0.0)
  }

  test("accrued interest is the coupon rate times the day count since the last coupon") {
    val spec = bond(3, coupon = 0.05)
    val tradeDate = Some(LocalDate.of(2024, 1, 1))
    val asOf = market.withAsOf(LocalDate.of(2024, 7, 1))

    val expected = 1000.0 * 0.05 * DayCount.Actual365Fixed.yearFraction(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 7, 1))
    assert(math.abs(Pricing.accrued(spec, asOf, tradeDate) - expected) < 1e-12)
    assert(expected > 0.0)
  }

  test("a trade's history is fixed by its trade date: paid coupons are not revalued") {
    val spec = bond(3, coupon = 0.05)
    val tradeDate = Some(LocalDate.of(2022, 1, 1))
    val asOf = market.withAsOf(LocalDate.of(2024, 1, 1))

    // Only the 2025-01-01 cashflow remains: coupon × accrual (2024 is a leap year)
    // plus principal, discounted with the 1Y pillar.
    val accrual = DayCount.Actual365Fixed.yearFraction(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1))
    val discountFactor = math.exp(-0.035 * 366.0 / 365.0)
    val expected = (1000.0 + 1000.0 * 0.05 * accrual) * discountFactor

    assert(math.abs(Pricing.price(spec, asOf, tradeDate) - expected) < 1e-9)
  }

  test("month-end trade dates roll over calendar months") {
    val dates = Schedule.dates(LocalDate.of(2024, 1, 31), 3, 1, market.conventions)
    assert(dates == Vector(
      LocalDate.of(2024, 2, 29), // leap year
      LocalDate.of(2024, 3, 31),
      LocalDate.of(2024, 4, 30)
    ))
  }

  test("a payment frequency that cannot form a schedule is rejected at parse time") {
    val rejected = Parser.parseProgram(
      """portfolio Book {
        |  instrument bond X { notional 1000; coupon 0.04; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; freq 5; }
        |}""".stripMargin)
    assert(rejected.isLeft)
    assert(rejected.left.get.contains("divide 12"))

    val accepted = Parser.parseProgram(
      """portfolio Book {
        |  instrument bond X { notional 1000; coupon 0.04; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; freq 4; }
        |}""".stripMargin)
    assert(accepted.isRight)
  }

  test("cash in a window is the contractual coupon on its payment date") {
    val spec = bond(3, coupon = 0.05)
    val tradeDate = Some(LocalDate.of(2024, 1, 1))

    val firstCoupon = Pricing.cashIn(spec, market, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), tradeDate)
    val noCash = Pricing.cashIn(spec, market, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), tradeDate)
    val expectedFirstCoupon = 1000.0 * 0.05 * DayCount.Actual365Fixed.yearFraction(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1))

    assert(math.abs(firstCoupon - expectedFirstCoupon) < 1e-12)
    assert(noCash == 0.0)
  }
}
