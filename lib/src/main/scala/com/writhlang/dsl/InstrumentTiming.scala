package com.writhlang.dsl

import java.time.LocalDate

/**
 * Calendar facts derived from an instrument's own terms.
 *
 * The DSL expresses terms as counts ("3 years", "360 months", "1.5 years"). The
 * engine needs calendar dates: a maturity date decides when a trade expires, a
 * coupon schedule decides when cashflows land and how much each period accrues.
 * This object is the single place that converts terms into dates, so pricing and
 * the trade/lifecycle layer can never disagree about when an instrument ends.
 *
 * All dates are measured from the trade's own start date (its `tradeDate`), not
 * from the valuation date. A trade entered on 2022-01-01 with `maturity 3`
 * matures on 2025-01-01 however often it is revalued.
 */
object InstrumentTiming {

  /** Months between two payment dates for the instrument's frequency. */
  def monthsPerPeriod(instrument: InstrumentSpec): Int = {
    val freq = instrument match {
      case spec: BondSpec     => spec.couponFreq
      case spec: SwapSpec     => spec.freq
      case spec: CapSpec      => spec.freq
      case spec: SwaptionSpec => spec.freq
      case _                  => 12
    }
    require(freq > 0 && 12 % freq == 0, s"payment frequency must divide 12 (1,2,3,4,6,12), got $freq")
    instrument match {
      case _: MortgageSpec | _: MbsPoolSpec => 1
      case _: FxForwardSpec                 => 0
      case _                                => 12 / freq
    }
  }

  /** Number of periods from the start date to the instrument's contractual maturity. */
  def periods(instrument: InstrumentSpec): Int = instrument match {
    case spec: BondSpec     => spec.maturityYears * spec.couponFreq
    case spec: SwapSpec     => spec.maturityYears * spec.freq
    case spec: CapSpec      => spec.maturityYears * spec.freq
    case spec: MortgageSpec => spec.termMonths
    case spec: MbsPoolSpec  => spec.wamMonths
    case _: SwaptionSpec    => 1 // the option settles once, at expiry
    case _: FxForwardSpec   => 1
  }

  /**
   * The calendar date on which the instrument's life ends.
   *
   * - `bond`, `swap`, `cap`: `start` plus the maturity in years;
   * - `mortgage` / `mbs`: `start` plus the term in months;
   * - `swaption`: the **option expiry** (after it, the option has settled);
   * - `fxforward`: its single delivery date.
   */
  def maturityDate(instrument: InstrumentSpec, start: LocalDate): LocalDate = instrument match {
    case spec: BondSpec     => start.plusYears(spec.maturityYears.toLong)
    case spec: SwapSpec     => start.plusYears(spec.maturityYears.toLong)
    case spec: CapSpec      => start.plusYears(spec.maturityYears.toLong)
    case spec: MortgageSpec => start.plusMonths(spec.termMonths.toLong)
    case spec: MbsPoolSpec  => start.plusMonths(spec.wamMonths.toLong)
    case spec: SwaptionSpec => start.plusMonths(math.round(spec.expiryYears * 12.0))
    case spec: FxForwardSpec => start.plusMonths(math.round(spec.maturityYears * 12.0))
  }

  /** The swaption's swap maturity date, measured from its expiry (only for `swaption`). */
  def swapMaturityDate(spec: SwaptionSpec, expiry: LocalDate): LocalDate =
    expiry.plusYears(spec.swapMaturityYears.toLong)

  /**
   * True when `asOf` is on or after the instrument's maturity date, i.e. the
   * instrument's cashflows have all fallen due and its value is zero.
   */
  def hasMatured(instrument: InstrumentSpec, start: LocalDate, asOf: LocalDate): Boolean =
    !asOf.isBefore(maturityDate(instrument, start))
}
