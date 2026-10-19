package com.writhlang.dsl

import com.writhlang.risk.{RiskFactorKey, ShiftType}

/**
  * Instrument specs carry trade terms (notional, coupon, strike, maturity, …)
  * and *reference* market objects by [[RiskFactorKey]]. Market inputs are no
  * longer embedded scalars — they come from a separate market-data snapshot.
  */
sealed trait InstrumentSpec { def id: String }

/** Fixed-coupon bond. */
case class BondSpec(
  id: String,
  notional: Double,
  coupon: Double,
  maturityYears: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  couponFreq: Int
) extends InstrumentSpec

/** Amortising mortgage loan. `noteRate` is the loan's contractual rate. */
case class MortgageSpec(
  id: String,
  notional: Double,
  noteRate: Double,
  termMonths: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  prepayCurve: RiskFactorKey
) extends InstrumentSpec

/** Pass-through MBS pool (WAC/WAM). */
case class MbsPoolSpec(
  id: String,
  notional: Double,
  wac: Double,
  wamMonths: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  prepayCurve: RiskFactorKey
) extends InstrumentSpec

/** Interest-rate swap. */
case class SwapSpec(
  id: String,
  notional: Double,
  fixedRate: Double,
  maturityYears: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  freq: Int
) extends InstrumentSpec

/** Interest-rate cap. */
case class CapSpec(
  id: String,
  notional: Double,
  strike: Double,
  maturityYears: Int,
  freq: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  volSurface: RiskFactorKey
) extends InstrumentSpec

/** Option to enter a swap. */
case class SwaptionSpec(
  id: String,
  notional: Double,
  strike: Double,
  expiryYears: Double,
  swapMaturityYears: Int,
  freq: Int,
  isPayer: Boolean,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  volSurface: RiskFactorKey
) extends InstrumentSpec

/** FX forward, priced by covered interest parity. `fxRate` is the contracted rate. */
case class FxForwardSpec(
  id: String,
  notional: Double,
  fxRate: Double,
  maturityYears: Double,
  domesticCurve: RiskFactorKey,
  foreignCurve: RiskFactorKey,
  fxSpot: RiskFactorKey
) extends InstrumentSpec

/**
  * A curve-shift shape expressed in the DSL. Applied to a curve's *quotes*
  * before re-bootstrapping (par-conversion), never to an interpolated rate.
  */
sealed trait CurveShift
case object NoCurveShift extends CurveShift
/** Uniform additive shift across all tenors. */
case class FlatShift(amount: Double) extends CurveShift
/** Localised tent bump centred on one tenor (width 1 year). */
case class BucketShift(tenorYears: Double, amount: Double) extends CurveShift
/** Twist from a short-end shift to a long-end shift, linear up to a pivot tenor. */
case class TwistShift(shortAmount: Double, longAmount: Double, pivotYears: Double) extends CurveShift
/** Sine-wave shift `A·sin(ω·x + φ)`. */
case class SineShift(amplitude: Double, omega: Double, phase: Double) extends CurveShift

/**
  * A single move inside a shock.
  *
  * - [[PointMove]] shifts one market object (a curve pillar, an FX spot, a vol
  *   surface, or a prepay vector) by a signed amount. `shiftType` says whether
  *   the amount is additive (rates) or relative (FX/vol).
  * - [[ShapeMove]] applies a whole-curve shape (parallel/bucket/twist/sine).
  */
sealed trait ShockMove
case class PointMove(key: RiskFactorKey, shiftType: ShiftType, amount: Double) extends ShockMove
case class ShapeMove(key: RiskFactorKey, shape: CurveShift) extends ShockMove

/** A named bundle of factor moves (scenario). */
case class Shock(name: String, moves: List[ShockMove])

/**
  * An ISO-style currency code (three uppercase letters).
  *
  * The engine never converts inside pricing: a trade is valued in its own
  * currency, and translation to a reporting currency happens one level up (the
  * portfolio/P&L layer), so the FX assumption is explicit and measurable.
  */
final case class Currency(code: String) {
  require(code.length == 3 && code.forall(c => c >= 'A' && c <= 'Z'), s"invalid currency code: $code")
  override def toString: String = code
}

/** A portfolio tree node: either a leaf instrument or a named sub-portfolio. */
sealed trait PortfolioNode

/**
  * A trade leaf: the instrument's terms, the trade's own start date, its
  * currency, and — as a derived property — the book path it sits under.
  *
  * `tradeDate` is what makes the trade a dated, ageing object: schedules and
  * maturity are measured from it, not from the valuation date. When it is absent
  * the trade is treated as entered on the snapshot's valuation date.
  *
  * `currency` names the currency the trade is denominated in; absent, it is taken
  * to be the reporting currency (no translation).
  */
case class InstrumentLeaf(
  spec: InstrumentSpec,
  tradeDate: Option[java.time.LocalDate] = None,
  currency: Option[Currency] = None
) extends PortfolioNode

case class Portfolio(name: String, children: List[PortfolioNode]) extends PortfolioNode

/** A whole DSL document: optional shocks plus a portfolio tree. */
case class Program(shocks: List[Shock], nodes: List[PortfolioNode])

object Program {

  /** Flatten the tree into `(terms, trade date, currency, book path)` in document order. */
  def trades(
    nodes: List[PortfolioNode]
  ): List[(InstrumentSpec, Option[java.time.LocalDate], Option[Currency], List[String])] = {
    def walk(
      node: PortfolioNode,
      path: List[String]
    ): List[(InstrumentSpec, Option[java.time.LocalDate], Option[Currency], List[String])] = node match {
      case InstrumentLeaf(spec, tradeDate, currency) => List((spec, tradeDate, currency, path))
      case Portfolio(name, children)                 => children.flatMap(child => walk(child, path :+ name))
    }
    nodes.flatMap(walk(_, Nil))
  }
}
