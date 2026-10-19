package com.writhlang.portfolio

import com.writhlang.dsl.Currency
import com.writhlang.marketdata.MarketData
import com.writhlang.risk.{KeyType, RiskFactorKey}

/**
  * Translation of a trade's amounts into a reporting currency.
  *
  * The engine prices in the trade's own currency and never converts inside a
  * pricing function. Translation happens once, at the portfolio layer, using the
  * FX spots in the market snapshot — which keeps the FX assumption explicit,
  * measurable and out of the instrument models.
  *
  * **Pair convention.** A spot `FxSpot:EURUSD = 1.25` means *1 EUR buys 1.25
  * USD* — the quote currency per unit of the base currency. Translating an amount
  * in the base currency multiplies by the rate; translating an amount in the quote
  * currency divides by it.
  *
  * A direct pair (`<from><to>`) is used when present, otherwise the inverse
  * (`<to><from>`) is inverted. If neither exists the translation fails and names
  * both pairs, rather than silently assuming a rate of 1.
  *
  * **What this is not:** it is a *level* translation, not an FX P&L model. The
  * period compiler translates every amount at the closing rate, so no FX gain or
  * loss enters the decomposition; FX P&L is a Stage 6 bucket.
  */
object FxConversion {

  /**
    * The multiplier that converts an amount in `tradeCurrency` into
    * `reportingCurrency` at the snapshot's spot.
    *
    * `None` on either side means "no conversion": an absent trade currency is
    * already in the reporting currency, and an absent reporting currency means the
    * book is reported as-is.
    */
  def factor(
    tradeCurrency: Option[Currency],
    reportingCurrency: Option[Currency],
    market: MarketData
  ): Either[String, Double] = (tradeCurrency, reportingCurrency) match {
    case (_, None)                              => Right(1.0)
    case (None, Some(_))                        => Right(1.0)
    case (Some(from), Some(to)) if from == to   => Right(1.0)
    case (Some(from), Some(to))                 => rate(from, to, market)
  }

  /** The spot rate translating one unit of `from` into `to`. */
  def rate(from: Currency, to: Currency, market: MarketData): Either[String, Double] = {
    val directKey = RiskFactorKey(KeyType.FxSpot, from.code + to.code)
    val inverseKey = RiskFactorKey(KeyType.FxSpot, to.code + from.code)

    market.fxSpots.get(directKey) match {
      case Some(quote) => Right(quote.value)
      case None =>
        market.fxSpots.get(inverseKey) match {
          case Some(quote) if quote.value == 0.0 =>
            Left(s"FX quote ${inverseKey.name} is zero; cannot invert it to translate ${from.code} to ${to.code}")
          case Some(quote) => Right(1.0 / quote.value)
          case None =>
            Left(
              s"no FX rate to translate ${from.code} into ${to.code}: " +
                s"expected ${directKey.name} or ${inverseKey.name} in the market-data fxSpots"
            )
        }
    }
  }
}
