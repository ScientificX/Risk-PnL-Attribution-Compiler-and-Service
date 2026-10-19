package com.writhlang.marketdata

import com.writhlang.marketdata.bootstrap.QuoteSet
import com.writhlang.risk.RiskFactorKey
import com.writhlang.time.TimeConventions

import java.time.LocalDate

/**
  * A market-data snapshot: the set of market objects a pricing function needs,
  * each addressed by a [[RiskFactorKey]]. The key's `KeyType` determines which
  * collection holds the object (curve vs surface vs vector vs spot vs matrix).
  *
  * For curves there are two related maps:
  *   - `curveQuotes` — the *input*: the par instruments whose quotes the curve
  *     is bootstrapped from. This is what a shock transforms (par-conversion).
  *   - `curves` — the *derived* result: the bootstrapped (or flat) curve.
  *
  * A scenario is a transformation of this snapshot; the snapshot is immutable
  * and updated via the `with*` copy methods.
  */
final case class MarketData(
  asOf: LocalDate = LocalDate.of(2024, 1, 1),
  conventions: TimeConventions = TimeConventions.default,
  curves: Map[RiskFactorKey, Curve] = Map.empty,
  curveQuotes: Map[RiskFactorKey, QuoteSet] = Map.empty,
  volSurfaces: Map[RiskFactorKey, VolSurface] = Map.empty,
  prepayVectors: Map[RiskFactorKey, PrepayVector] = Map.empty,
  fxSpots: Map[RiskFactorKey, SpotQuote] = Map.empty,
  correlations: Map[RiskFactorKey, CorrelationMatrix] = Map.empty
) {
  /** The same snapshot valued one valuation date later (or earlier). */
  def withAsOf(date: LocalDate): MarketData = copy(asOf = date)

  /** Years from `from` to `to` under this snapshot's day-count convention. */
  def yearFraction(from: LocalDate, to: LocalDate): Double = conventions.yearFraction(from, to)

  def curve(key: RiskFactorKey): Curve = curves(key)
  def quotes(key: RiskFactorKey): QuoteSet = curveQuotes(key)
  def volSurface(key: RiskFactorKey): VolSurface = volSurfaces(key)
  def prepayVector(key: RiskFactorKey): PrepayVector = prepayVectors(key)
  def fxSpot(key: RiskFactorKey): SpotQuote = fxSpots(key)
  def correlation(key: RiskFactorKey): CorrelationMatrix = correlations(key)

  /**
    * The effective discount curve for credit-risky instruments: the risk-free
    * discount curve plus the credit spread curve, added pointwise.
    */
  def combinedCurve(discountKey: RiskFactorKey, creditKey: RiskFactorKey): Curve =
    curve(discountKey).plus(curve(creditKey))

  def withCurve(key: RiskFactorKey, value: Curve): MarketData =
    copy(curves = curves.updated(key, value))
  def withVolSurface(key: RiskFactorKey, value: VolSurface): MarketData =
    copy(volSurfaces = volSurfaces.updated(key, value))
  def withPrepayVector(key: RiskFactorKey, value: PrepayVector): MarketData =
    copy(prepayVectors = prepayVectors.updated(key, value))
  def withFxSpot(key: RiskFactorKey, value: SpotQuote): MarketData =
    copy(fxSpots = fxSpots.updated(key, value))
  def withCorrelation(key: RiskFactorKey, value: CorrelationMatrix): MarketData =
    copy(correlations = correlations.updated(key, value))

  /** Store both a bootstrapped curve and the quotes it was built from. */
  def withCurveAndQuotes(key: RiskFactorKey, value: Curve, quotes: QuoteSet): MarketData =
    copy(curves = curves.updated(key, value), curveQuotes = curveQuotes.updated(key, quotes))
}
