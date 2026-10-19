package com.writhlang.time

import java.time.LocalDate

/**
 * The date conventions the engine uses to turn calendar dates into year
 * fractions and to roll cashflow dates off non-business days.
 *
 * Every time in pricing is a *year fraction measured from a reference date*
 * (a valuation date or a trade date). A year fraction is never a nominal
 * "one year"; it is whatever [[DayCount]] says the calendar distance is. That is
 * what makes a curve pillar, a coupon date and a theta horizon consistent with
 * one another.
 *
 * @param dayCount               how dates become year fractions (default Actual/365F)
 * @param calendar               which days are business days (default weekends-only)
 * @param businessDayConvention  how to roll a cashflow date off a non-business day
 */
final case class TimeConventions(
  dayCount: DayCount = DayCount.Actual365Fixed,
  calendar: Calendar = Calendar.weekend,
  businessDayConvention: BusinessDayConvention = BusinessDayConvention.Unadjusted
) {

  /** Years from `from` to `to` (can be negative) under the day-count convention. */
  def yearFraction(from: LocalDate, to: LocalDate): Double = dayCount.yearFraction(from, to)

  /** Roll `date` onto a business day under the convention. */
  def adjust(date: LocalDate): LocalDate = businessDayConvention.adjust(date, calendar)

  /** Step `days` business days from `date` and roll the result by the convention. */
  def advanceBusinessDays(date: LocalDate, days: Int): LocalDate =
    calendar.addBusinessDays(date, days)
}

object TimeConventions {
  /** The engine default: Actual/365F, weekends-only calendar, Unadjusted dates. */
  val default: TimeConventions = TimeConventions()
}
