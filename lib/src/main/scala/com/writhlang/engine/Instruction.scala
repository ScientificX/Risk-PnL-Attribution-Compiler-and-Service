package com.writhlang.engine

import com.writhlang.dsl.InstrumentSpec
import com.writhlang.marketdata.MarketData

import java.time.LocalDate

sealed trait Op

/**
  * Price an instrument at its market snapshot's valuation date (leaf). The value
  * is date-derived: `tradeDate` anchors the instrument's cashflow schedule, and
  * the snapshot's `asOf` is the valuation date.
  */
case class Price(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None) extends Op

/** Theta (time decay): the valuation at the advanced date minus the base price. */
case class Theta(baseId: String, thetaEvalId: String) extends Op

/**
  * Carry over a period (leaf): contractual cash paid between the two snapshots
  * plus the change in accrued interest.
  */
case class Carry(
  instrument: InstrumentSpec,
  from: MarketData,
  to: MarketData,
  tradeDate: Option[LocalDate] = None
) extends Op

/** Literal shock value (leaf). */
case class Const(value: Double) extends Op

/**
  * Roll-up: the sum of its dependencies.
  *
  * Used for book/desk/total aggregation. Because every aggregated metric (price,
  * delta, scenario price, theta, carry) is additive across trades at a fixed
  * market state, a sum node is exact — and a sum of deltas is a *netted*
  * sensitivity, in which opposing positions cancel.
  */
case object Sum extends Op

/** Accrued interest at the snapshot's valuation date (leaf). */
case class Accrued(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None) extends Op

/** Contractual cash strictly inside `(from, to]` on the snapshot's market (leaf). */
case class CashFlow(
  instrument: InstrumentSpec,
  market: MarketData,
  from: LocalDate,
  to: LocalDate,
  tradeDate: Option[LocalDate] = None
) extends Op

/**
  * A signed sum: `Σ coefficient × dependency`.
  *
  * This is how a P&L bucket is defined. For example, the market bucket is the
  * clean-value change at the end date — `dirty(t1, m1) − dirty(t1, m0)` — and the
  * carry bucket is `accrued(t1) − accrued(t0) + cash paid in the window`. Keeping
  * each bucket as a node means the decomposition is traceable in the DAG.
  */
case class Combine(terms: List[(String, Double)]) extends Op

/** Central-difference delta: (up - down) / (2·bump). */
case class DeltaCentral(baseId: String, upId: String, downId: String, bump: Double) extends Op

/** Forward-difference delta: (up - base) / bump. */
case class DeltaForward(baseId: String, upId: String, bump: Double) extends Op

/** Backward-difference delta: (base - down) / bump. */
case class DeltaBackward(baseId: String, downId: String, bump: Double) extends Op

/** Gamma (always central): (up - 2·base + down) / bump². */
case class Gamma(baseId: String, upId: String, downId: String, bump: Double) extends Op

/** Cross gamma (central, two factors): (upIJ - upI - upJ + base) / (bumpI·bumpJ). */
case class CrossGamma(
  baseId: String,
  upIId: String,
  upJId: String,
  upIJId: String,
  bumpI: Double,
  bumpJ: Double
) extends Op

/** First-order Taylor scenario: base + sum(delta_i * shock_i). */
case class LinearScenario(baseId: String, terms: List[(String, String)]) extends Op

/** Second-order Taylor scenario: linear + 1/2 sum(gamma_i s_i^2) + sum(cross_ij s_i s_j). */
case class QuadraticScenario(
  baseId: String,
  linear: List[(String, String)],
  gamma: List[(String, String)],
  cross: List[(String, String, String)]
) extends Op

case class Instruction(id: String, op: Op, deps: List[String])
