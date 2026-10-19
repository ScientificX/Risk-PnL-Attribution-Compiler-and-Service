package com.writhlang.marketdata.bootstrap

import com.writhlang.risk.Tenor

import java.time.LocalDate

/**
  * A quoted market instrument used to bootstrap a discount curve.
  *
  * Each par instrument carries a quoted rate (a decimal, so `0.04` = 4%) and a
  * tenor. The bootstrapper walks these in maturity order, solving for one
  * discount factor per instrument at the instrument's **pillar date** — the
  * calendar date its tenor resolves to from the valuation date. `rate` is the
  * *observable market quote*; this is what a sensitivity bump perturbs
  * (par-conversion), never an interpolated curve point.
  */
sealed trait ParInstrument {
  /** The quoted rate (decimal). */
  def rate: Double

  /** The tenor the quote is notionally observed at (for shock shapes). */
  def tenorYears: Double

  /** The maturity at which this instrument pins a discount factor. */
  def maturityYears: Double

  /** The calendar date at which this instrument pins a discount factor. */
  def pillarDate(asOf: LocalDate): LocalDate

  /** A copy with the quoted rate replaced (used when applying a shock). */
  def withRate(newRate: Double): ParInstrument
}

/**
  * A cash deposit: lends money to `tenor`, earning the quoted rate with
  * continuous compounding. It pins `df(T)` directly:
  * `df(T) = e^(-rate · yearFraction(asOf, T))`.
  */
final case class Deposit(tenor: Tenor, rate: Double) extends ParInstrument {
  def tenorYears: Double    = tenor.years
  def maturityYears: Double = tenor.years
  def pillarDate(asOf: LocalDate): LocalDate = tenor.resolve(asOf)
  def withRate(newRate: Double): Deposit = copy(rate = newRate)
}

/**
  * An interest-rate future / FRA: a forward rate over `[start, end]`. The quoted
  * `rate` is a continuously-compounded forward rate; it implies the discount
  * factor at `end` from the factor at `start`:
  * `df(end) = df(start) · e^(-rate · yearFraction(start, end))`.
  */
final case class Future(start: Tenor, end: Tenor, rate: Double) extends ParInstrument {
  def tenorYears: Double    = start.years
  def maturityYears: Double = end.years
  def pillarDate(asOf: LocalDate): LocalDate = end.resolve(asOf)
  def withRate(newRate: Double): Future = copy(rate = newRate)
}

/**
  * A par interest-rate swap: pays the fixed `rate` `freq` times a year to
  * `tenor`. At par the fixed-leg value equals the floating-leg value, which
  * pins the final discount factor via the par equation
  * `df(T) = (1 - rate · Σ accrualᵢ·df(tᵢ)) / (1 + rate · accrual_N)`.
  *
  * The coupon schedule starts on the valuation date (a spot-starting swap) and
  * every period accrues on the day-count basis between its two actual dates.
  */
final case class Swap(tenor: Tenor, rate: Double, freq: Int = 1) extends ParInstrument {
  def tenorYears: Double    = tenor.years
  def maturityYears: Double = tenor.years
  def pillarDate(asOf: LocalDate): LocalDate = tenor.resolve(asOf)
  def withRate(newRate: Double): Swap = copy(rate = newRate)
}
