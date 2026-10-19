package com.writhlang.portfolio

import com.writhlang.dsl._
import com.writhlang.engine.{Dag, DslCompiler, Executor}
import com.writhlang.marketdata._
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import zio.{Runtime, Unsafe}

import java.time.LocalDate

/**
  * Currency levels: translation to a reporting currency, pair resolution, and the
  * rule that a missing rate is an error rather than an assumed 1.
  *
  * This is a *level* translation at the closing spot, not an FX P&L model: the
  * period identity survives translation intact, with no FX bucket.
  */
@RunWith(classOf[JUnitRunner])
class FxConversionSuite extends AnyFunSuite {

  private def run(dag: Dag): Map[String, Double] =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(Executor.run(dag)).getOrThrow()
    }

  private val eur = Currency("EUR")
  private val usd = Currency("USD")

  private def marketAt(asOf: LocalDate, fxSpots: String = "\"EURUSD\": 1.25"): MarketData =
    MarketDataJson.parse(
      s"""{
         |  "asOf": "$asOf",
         |  "curves": {
         |    "EUR": { "type": "DiscountCurve", "instruments": [
         |      { "kind": "deposit", "tenor": "1Y", "rate": 0.035 },
         |      { "kind": "swap", "tenor": "2Y", "rate": 0.040, "freq": 1 },
         |      { "kind": "swap", "tenor": "3Y", "rate": 0.045, "freq": 1 }
         |    ]},
         |    "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0 }
         |  },
         |  "fxSpots": { $fxSpots }
         |}""".stripMargin
    ).toOption.get

  private def parse(dsl: String): Program = Parser.parseProgram(dsl).toOption.get

  private def book(instruments: String*): String =
    s"portfolio Book {\n  ${instruments.mkString("\n  ")}\n}"

  private def bond(
    id: String,
    coupon: Double = 0.04,
    maturityYears: Int = 2,
    tradeDate: String = "2024-01-01",
    currency: Option[String] = None
  ): String = {
    val currencyProperty = currency.map(code => s" currency $code;").getOrElse("")
    s"""instrument bond $id { notional 1000; coupon $coupon; maturity $maturityYears; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; tradeDate $tradeDate;$currencyProperty }"""
  }

  private val t0 = LocalDate.of(2024, 1, 1)
  private val t1 = LocalDate.of(2024, 1, 2)

  // -------------------------------------------------------------------------
  // Pair resolution
  // -------------------------------------------------------------------------

  test("a direct pair translates the quote currency per unit of the base") {
    val factor = FxConversion.factor(Some(usd), Some(eur), marketAt(t0))
    // EURUSD = 1.25 means 1 EUR buys 1.25 USD, so USD amounts are divided by it.
    assert(factor == Right(1.0 / 1.25))
  }

  test("an inverse-only pair is inverted") {
    val factor = FxConversion.factor(Some(eur), Some(usd), marketAt(t0))
    assert(factor == Right(1.25))
  }

  test("same currency, absent trade currency and absent reporting currency all mean no conversion") {
    val market = marketAt(t0)
    assert(FxConversion.factor(Some(eur), Some(eur), market) == Right(1.0))
    assert(FxConversion.factor(None, Some(eur), market) == Right(1.0))
    assert(FxConversion.factor(Some(usd), None, market) == Right(1.0))
  }

  test("a missing rate is an error naming both candidate pairs") {
    val market = marketAt(t0, fxSpots = "")
    val result = FxConversion.factor(Some(Currency("JPY")), Some(eur), market)

    assert(result.isLeft)
    assert(result.left.get.contains("JPY EUR".replace(" ", "")) || result.left.get.contains("JPYEUR"))
    assert(result.left.get.contains("EURJPY"))
  }

  test("a zero inverse quote is rejected rather than dividing by zero") {
    val market = marketAt(t0, fxSpots = "\"EURUSD\": 0.0")
    val result = FxConversion.factor(Some(usd), Some(eur), market)

    assert(result.isLeft)
    assert(result.left.get.contains("zero"))
  }

  // -------------------------------------------------------------------------
  // Period translation
  // -------------------------------------------------------------------------

  test("a period book in one foreign currency translates at the closing spot and keeps the identity") {
    val prevDsl = book(bond("A", currency = Some("USD")), bond("B", coupon = 0.05, maturityYears = 3, currency = Some("USD")))
    val currDsl = book(bond("A", currency = Some("USD")), bond("B", coupon = 0.06, maturityYears = 3, currency = Some("USD")))
    val m0 = marketAt(t0)
    val m1 = marketAt(t1)

    val inUsd = run(DslCompiler.buildPeriodPnl((parse(prevDsl), m0), (parse(currDsl), m1)).toOption.get.dag)
    val compiled = DslCompiler.buildPeriodPnl((parse(prevDsl), m0), (parse(currDsl), m1), reportingCurrency = Some(eur)).toOption.get
    val inEur = run(compiled.dag)

    assert(compiled.reportingCurrency.contains(eur))
    val factor = 1.0 / 1.25
    val ids = List(
      DslCompiler.pnlId("market", "A"),
      DslCompiler.pnlId("carry", "A"),
      DslCompiler.pnlId("inception", "B"),
      DslCompiler.pnlTotalId("B"),
      DslCompiler.totalAggregateId("pnl:total")
    )
    ids.foreach { id =>
      assert(math.abs(inEur(id) - inUsd(id) * factor) < 1e-9, s"$id did not translate")
    }

    // The identity survives translation: the translated buckets still sum to the total.
    val buckets = List("market", "theta", "carry", "inception").map(name => DslCompiler.totalAggregateId(DslCompiler.pnlMetric(name)))
    val sum = buckets.map(id => inEur.getOrElse(id, 0.0)).sum
    assert(math.abs(sum - inEur(DslCompiler.totalAggregateId("pnl:total"))) < 1e-9)
  }

  test("a mixed-currency book without a reporting currency is rejected") {
    val dsl = book(bond("A", currency = Some("USD")), bond("B", currency = Some("EUR")))
    val result = DslCompiler.buildPeriodPnl((parse(dsl), marketAt(t0)), (parse(dsl), marketAt(t1)))

    assert(result.isLeft)
    assert(result.left.get.contains("mixes"))
    assert(result.left.get.contains("EUR") && result.left.get.contains("USD"))
  }

  test("a mixed-currency single-snapshot build is rejected rather than summed blindly") {
    val dsl = book(bond("A", currency = Some("USD")), bond("B", currency = Some("EUR")))
    val result = DslCompiler.build(parse(dsl), marketAt(t0))

    assert(result.isLeft)
    assert(result.left.get.contains("mixes"))
  }

  test("a trade's currency is parsed from the DSL") {
    val population = TradePopulation.fromProgram(parse(book(bond("A", currency = Some("USD")))), marketAt(t0)).toOption.get
    assert(population.trades(TradeId("A")).currency == Some(usd))
  }

  test("a currency code must be three uppercase letters") {
    assert(Parser.parseProgram(book(bond("A", currency = Some("usd")))).isLeft)
    assert(Parser.parseProgram(book(bond("A", currency = Some("US")))).isLeft)
  }
}
