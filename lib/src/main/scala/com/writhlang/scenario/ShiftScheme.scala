package com.writhlang.scenario

import com.writhlang.risk.{KeyType, RiskFactorKey}
import com.writhlang.time.{BusinessDayConvention, Calendar, DayCount, ThetaPeriod, TimeConventions}

import java.time.LocalDate

/**
  * The finite-difference scheme used to estimate a first-order sensitivity
  * (delta). See the glossary for the formulas.
  *
  * - `Central`  = `(P(x+h) - P(x-h)) / 2h` — second-order accurate, the default.
  * - `Forward`  = `(P(x+h) - P(x)) / h`   — one repricing, used at boundaries.
  * - `Backward` = `(P(x) - P(x-h)) / h`   — one repricing, used by convention
  *   for time (theta).
  *
  * Gamma (the second derivative) is always computed with the central formula
  * `(P(x+h) - 2P(x) + P(x-h)) / h²` regardless of the delta scheme, because a
  * second derivative is inherently symmetric.
  */
sealed trait ShiftScheme { def name: String }

object ShiftScheme {
  case object Forward  extends ShiftScheme { val name = "forward"  }
  case object Backward extends ShiftScheme { val name = "backward" }
  case object Central  extends ShiftScheme { val name = "central"  }

  val all: List[ShiftScheme] = List(Forward, Backward, Central)

  def fromName(name: String): Option[ShiftScheme] = all.find(_.name == name)
}

/**
  * Configuration for finite-difference sensitivities: the differencing scheme
  * plus per-factor bump sizes (a basis point is `1e-4`), and the date
  * conventions used to advance the valuation date for theta.
  */
final case class SensitivityConfig(
  shiftScheme: ShiftScheme = ShiftScheme.Central,
  bumpFor: RiskFactorKey => Double = SensitivityConfig.defaultBump,
  thetaPeriod: Option[ThetaPeriod] = Some(ThetaPeriod(days = 1)),
  dayCount: DayCount = DayCount.Actual365Fixed,
  calendar: Calendar = Calendar.weekend,
  businessDayConvention: BusinessDayConvention = BusinessDayConvention.Unadjusted
) {

  /** The date conventions this configuration implies. */
  def conventions: TimeConventions =
    TimeConventions(dayCount, calendar, businessDayConvention)

  /**
    * The valuation date advanced by the theta horizon and rolled onto a business
    * day. Theta is a full revaluation at this date, not a tenor shift.
    */
  def thetaValuationDate(asOf: LocalDate): Option[LocalDate] =
    thetaPeriod.map(period =>
      businessDayConvention.adjust(calendar.addBusinessDays(asOf, period.days), calendar)
    )
}

object SensitivityConfig {
  private val rateBump    = 0.0001 // 1bp
  private val spreadBump  = 0.0001 // 1bp
  private val prepayBump  = 0.01
  private val volBump     = 0.001
  private val spotBump    = 0.001

  /** Standard bump size for a factor kind (a basis point for rates/spreads). */
  def defaultBump(key: RiskFactorKey): Double = key.keyType match {
    case KeyType.DiscountCurve | KeyType.IndexCurve | KeyType.YieldCurve => rateBump
    case KeyType.CreditCurve => spreadBump
    case KeyType.Prepay => prepayBump
    case KeyType.SwaptionVolatility | KeyType.CapFloorVolatility |
         KeyType.FxVolatility | KeyType.EquityVolatility | KeyType.CommodityVolatility => volBump
    case KeyType.FxSpot | KeyType.EquitySpot => spotBump
    case _ => 0.0
  }
}
