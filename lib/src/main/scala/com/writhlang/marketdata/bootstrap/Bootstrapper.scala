package com.writhlang.marketdata.bootstrap

import com.writhlang.marketdata.{BootstrappedCurve, DiscountCurveInterpolator}
import com.writhlang.time.{Schedule, TimeConventions}

import java.time.LocalDate

/**
  * Sequential bootstrapping: turn a set of quoted instruments into a discount
  * curve. "Sequential" means the instruments are solved in ascending maturity
  * order — each step has exactly one unknown discount factor, because the
  * shorter-maturity factors it depends on were already solved.
  *
  * The curve is anchored on the **valuation date**: each quote's tenor resolves
  * to a calendar date, and every discount factor is quoted at the year fraction
  * between the valuation date and that date (never at the nominal tenor count).
  * That is what keeps a leap year, a quarter-end or a monthly tenor honest.
  *
  * Steps:
  *   - a deposit pins `df(T) = e^(-rate · yf(asOf, T))` directly;
  *   - a future/FRA pins `df(end)` from the (interpolated) `df(start)`;
  *   - a swap pins `df(T)` from its par equation, accruing each period between
  *     its two actual coupon dates.
  *
  * Returns `Left` with a message if a step yields a non-positive or
  * non-decreasing discount factor (an arbitrage / bad-quote condition).
  */
object Bootstrapper {

  def bootstrap(
    quotes: QuoteSet,
    asOf: LocalDate,
    conventions: TimeConventions = TimeConventions.default
  ): Either[String, BootstrappedCurve] = {
    val sorted = quotes.sorted
    if (sorted.isEmpty) Left("cannot bootstrap an empty quote set")
    else {
      sorted.foldLeft[Either[String, Vector[(Double, Double)]]](Right(Vector.empty)) {
        case (acc, instrument) =>
          acc.flatMap(pillars => pillarFor(instrument, pillars, asOf, conventions).map(pillars :+ _))
      }.map(BootstrappedCurve(_))
    }
  }

  /** Throw on failure — used in the scenario hot path where quotes are known-valid. */
  def bootstrapOrThrow(
    quotes: QuoteSet,
    asOf: LocalDate,
    conventions: TimeConventions = TimeConventions.default
  ): BootstrappedCurve =
    bootstrap(quotes, asOf, conventions)
      .fold(err => throw new IllegalStateException(s"bootstrap failed: $err"), identity)

  /** Solve one pillar: the year fraction from `asOf` to the pillar and its discount factor. */
  private def pillarFor(
    instrument: ParInstrument,
    pillars: Vector[(Double, Double)],
    asOf: LocalDate,
    conventions: TimeConventions
  ): Either[String, (Double, Double)] = {
    val pillarDate = instrument.pillarDate(asOf)
    val pillarTime = conventions.yearFraction(asOf, pillarDate)
    val df = instrument match {
      case d: Deposit =>
        // Continuous compounding: df(T) = e^(-rate · yf(asOf, T)).
        math.exp(-d.rate * pillarTime)
      case f: Future =>
        // Forward rate r over [start, end]: df(end) = df(start)·e^(-r·yf(start, end)).
        val startDate = f.start.resolve(asOf)
        val endDate = f.end.resolve(asOf)
        val dfStart = DiscountCurveInterpolator.df(pillars, conventions.yearFraction(asOf, startDate))
        dfStart * math.exp(-f.rate * conventions.yearFraction(startDate, endDate))
      case s: Swap =>
        swapDiscountFactor(s, pillars, asOf, conventions)
    }

    if (df.isNaN || df.isInfinity || df <= 0.0)
      Left(s"non-positive discount factor $df at ${instrument.tenorYears}Y")
    else pillars.lastOption match {
      case Some((prevTime, prevDf)) if df >= prevDf =>
        Left(
          s"discount factor not decreasing: df($pillarDate)=$df >= df at $prevTime=$prevDf"
        )
      case _ => Right(pillarTime -> df)
    }
  }

  /**
    * Solve for the discount factor at a par swap's maturity.
    *
    * A par swap (notional 1, fixed rate `K`, `freq` payments/year, `N` periods
    * to maturity `T`) satisfies: fixed-leg PV = floating-leg PV. The floating
    * leg is `1 - df(T)`, giving
    *   `K·Σ accrualᵢ·df(tᵢ) = 1 - df(T)`.
    * The last term in the sum is `df(t_N) = df(T)` itself, so isolating it:
    *   `df(T) = (1 - K·Σ_{i<N} accrualᵢ·df(tᵢ)) / (1 + K·accrual_N)`.
    *
    * Each `accrualᵢ` is the day-count fraction between the swap's actual coupon
    * dates, and intermediate `df(tᵢ)` are interpolated from solved pillars.
    */
  private def swapDiscountFactor(
    spec: Swap,
    pillars: Vector[(Double, Double)],
    asOf: LocalDate,
    conventions: TimeConventions
  ): Double = {
    val monthsPerPeriod = Schedule.monthsPerPeriod(spec.freq).getOrElse(12)
    val periods = math.max(1, math.round(spec.tenor.years * spec.freq).toInt)
    val dates = Schedule.dates(asOf, periods, monthsPerPeriod, conventions)

    var previous = asOf
    var knownSum = 0.0
    var index = 0
    while (index < periods - 1) {
      val couponDate = dates(index)
      val accrual = conventions.yearFraction(previous, couponDate)
      val discountFactor =
        DiscountCurveInterpolator.df(pillars, conventions.yearFraction(asOf, couponDate))
      knownSum += accrual * discountFactor
      previous = couponDate
      index += 1
    }

    val lastAccrual = conventions.yearFraction(previous, dates.last)
    (1.0 - spec.rate * knownSum) / (1.0 + spec.rate * lastAccrual)
  }
}
