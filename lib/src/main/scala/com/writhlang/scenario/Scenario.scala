package com.writhlang.scenario

import com.writhlang.marketdata.MarketData
import com.writhlang.marketdata.bootstrap.Bootstrapper
import com.writhlang.risk.{KeyType, RiskFactorKey}

/**
  * A shift applied to a single risk factor's market object. The object's shape
  * is implied by the factor's `KeyType` (curve vs vector vs surface vs spot).
  */
sealed trait ScenarioShift

/** Add `shape.at(x)` to the factor's object (curves, prepay vectors, vol). */
final case class AdditiveShift(shape: ShiftShape) extends ScenarioShift

/** Bump one par instrument's quote on a curve, then re-bootstrap. */
final case class ParQuoteShift(tenorYears: Double, amount: Double) extends ScenarioShift

/** Multiply a spot by (1 + amount) — the FX convention. */
final case class RelativeSpotShift(amount: Double) extends ScenarioShift

/** Add `amount` to a spot (absolute move). */
final case class AbsoluteSpotShift(amount: Double) extends ScenarioShift

/**
  * A named transformation of a [[MarketData]] snapshot: a map from risk-factor
  * key to the shift to apply to that factor's object.
  *
  * For a curve that is backed by a quote set, a shift is applied to the
  * *quotes* and the curve is re-bootstrapped — the par-conversion principle:
  * shock the market input, then re-derive everything downstream.
  */
final case class Scenario(name: String, shifts: Map[RiskFactorKey, List[ScenarioShift]]) {
  def applyTo(market: MarketData): MarketData =
    shifts.foldLeft(market) { case (currentMarket, (key, keyShifts)) =>
      // Apply each factor's shifts in list order. Cross-factor shifts, and the
      // current additive/par-quote shifts within one factor, commute — each
      // shift mutates the quote set and re-bootstraps deterministically. Only
      // mixed absolute+relative moves on the same spot are order-dependent,
      // which is why per-factor order is preserved.
      keyShifts.foldLeft(currentMarket) { case (m, shift) => applyShift(m, key, shift) }
    }

  private def applyShift(market: MarketData, key: RiskFactorKey, shift: ScenarioShift): MarketData =
    shift match {
      case AdditiveShift(shape) if isCurveKey(key.keyType) =>
        if (market.curveQuotes.contains(key)) {
          // Shock the quotes, then re-bootstrap the derived curve.
          val shiftedQuotes = market.curveQuotes(key).shifted(shape.at)
          market.withCurveAndQuotes(
            key,
            Bootstrapper.bootstrapOrThrow(shiftedQuotes, market.asOf, market.conventions),
            shiftedQuotes
          )
        } else {
          market.withCurve(key, market.curve(key).shifted(shape.at))
        }
      case ParQuoteShift(tenor, amount) if isCurveKey(key.keyType) =>
        val shiftedQuotes = market.curveQuotes(key).shiftOne(tenor, amount)
        market.withCurveAndQuotes(
          key,
          Bootstrapper.bootstrapOrThrow(shiftedQuotes, market.asOf, market.conventions),
          shiftedQuotes
        )
      case AdditiveShift(shape) if key.keyType == KeyType.Prepay =>
        market.withPrepayVector(key, market.prepayVector(key).shifted(shape.at))
      case AdditiveShift(shape) if isVolKey(key.keyType) =>
        market.withVolSurface(key, market.volSurface(key).shifted(shape.at))
      case RelativeSpotShift(amount) if isSpotKey(key.keyType) =>
        market.withFxSpot(key, market.fxSpot(key).shiftedRelative(amount))
      case AbsoluteSpotShift(amount) if isSpotKey(key.keyType) =>
        market.withFxSpot(key, market.fxSpot(key).shiftedAbsolute(amount))
      case _ =>
        // Unsupported combination (e.g. a correlation or security factor): leave
        // the object untouched.
        market
    }

  private def isCurveKey(keyType: KeyType): Boolean = keyType match {
    case KeyType.DiscountCurve | KeyType.IndexCurve | KeyType.YieldCurve | KeyType.CreditCurve |
         KeyType.CommodityCurve | KeyType.InflationZero | KeyType.InflationYoY | KeyType.DividendYield => true
    case _ => false
  }

  private def isVolKey(keyType: KeyType): Boolean = keyType match {
    case KeyType.SwaptionVolatility | KeyType.CapFloorVolatility | KeyType.FxVolatility |
         KeyType.EquityVolatility | KeyType.CommodityVolatility => true
    case _ => false
  }

  private def isSpotKey(keyType: KeyType): Boolean = keyType match {
    case KeyType.FxSpot | KeyType.EquitySpot => true
    case _ => false
  }
}
