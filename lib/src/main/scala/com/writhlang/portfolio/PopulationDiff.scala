package com.writhlang.portfolio

import scala.collection.mutable.ListBuffer

/**
  * What happened to one trade between two valuation dates.
  *
  * These are derived by comparing two [[TradePopulation]] snapshots; the DSL does
  * not declare them. A trade is classified exactly once, so the P&L decomposition
  * built on top of these events attributes every trade's value change to exactly
  * one bucket.
  *
  * - [[Unchanged]] — active at both dates with identical terms.
  * - [[Amended]]  — active at both dates, terms changed (an amendment effective at
  *   the start of the period, per the recorded convention).
  * - [[NewTrade]] — not on the book at the start, on it at the end.
  * - [[Cancelled]] — removed from the book before it reached maturity
  *   (discretionary removal; the unwind price is not in the data).
  * - [[Expired]] — reached its contractual maturity date during the period. The
  *   settling cashflow is contractual, so this event is economically meaningful,
  *   unlike a cancellation. `curr` is present when the trade was still listed at
  *   the end date (e.g. amended and then matured).
  */
sealed trait PopulationEvent

final case class Unchanged(trade: TradeInstance) extends PopulationEvent
final case class Amended(prev: TradeInstance, curr: TradeInstance) extends PopulationEvent
final case class NewTrade(curr: TradeInstance) extends PopulationEvent
final case class Cancelled(prev: TradeInstance) extends PopulationEvent
final case class Expired(prev: TradeInstance, curr: Option[TradeInstance]) extends PopulationEvent

/**
  * Classifies what happened between two book snapshots.
  *
  * Rules (each trade id is classified exactly once):
  *   - present in both, same terms and trade date  → Unchanged
  *   - present in both, different terms            → Amended
  *   - present only in the later population        → NewTrade
  *   - present only in the earlier population, but settled by the later date → Expired
  *   - present only in the earlier population, still alive at the later date    → Cancelled
  *   - present in both, settled by the later date  → Expired
  *
  * Rejected rather than guessed:
  *   - the later valuation date precedes the earlier one;
  *   - a trade's `tradeDate` changes between snapshots (that would silently move
  *     every cashflow, so it can never be an amendment);
  *   - a trade that is already settled at the earlier date (it should not be on
  *     that book at all).
  */
object PopulationDiff {

  def between(prev: TradePopulation, curr: TradePopulation): Either[String, List[PopulationEvent]] = {
    if (curr.asOf.isBefore(prev.asOf))
      Left(s"current valuation date ${curr.asOf} precedes previous valuation date ${prev.asOf}")
    else {
      val errors = ListBuffer.empty[String]
      val allIds = (prev.trades.keySet ++ curr.trades.keySet).toList.sortBy(_.value)

      val events = allIds.flatMap { id =>
        (prev.trades.get(id), curr.trades.get(id)) match {
          case (Some(prevTrade), Some(currTrade)) =>
            if (prevTrade.tradeDate != currTrade.tradeDate) {
              errors += s"trade $id changed its tradeDate from ${prevTrade.tradeDate} to ${currTrade.tradeDate} between snapshots"
              None
            } else if (prevTrade.currency != currTrade.currency) {
              errors += s"trade $id changed its currency from ${prevTrade.currency.map(_.code).getOrElse("(reporting)")} to ${currTrade.currency.map(_.code).getOrElse("(reporting)")} between snapshots"
              None
            } else if (prev.stateOf(prevTrade) == TradeState.Expired) {
              errors += s"trade $id is already settled at the previous valuation date ${prev.asOf}"
              None
            } else if (curr.stateOf(currTrade) == TradeState.Expired) {
              Some(Expired(prevTrade, Some(currTrade)))
            } else if (prevTrade.spec == currTrade.spec) {
              Some(Unchanged(currTrade))
            } else {
              Some(Amended(prevTrade, currTrade))
            }

          case (None, Some(currTrade)) =>
            Some(NewTrade(currTrade))

          case (Some(prevTrade), None) =>
            if (prev.stateOf(prevTrade) == TradeState.Expired) {
              errors += s"trade $id is already settled at the previous valuation date ${prev.asOf}"
              None
            } else if (curr.stateOf(prevTrade) == TradeState.Expired) {
              // It disappeared because it matured, not because it was cancelled.
              Some(Expired(prevTrade, None))
            } else {
              Some(Cancelled(prevTrade))
            }

          case (None, None) => None
        }
      }

      if (errors.nonEmpty) Left(errors.mkString("; ")) else Right(events)
    }
  }

  /** Convenience: the events grouped by their label, e.g. for reporting. */
  def bucketOf(event: PopulationEvent): String = event match {
    case _: Unchanged => "unchanged"
    case _: Amended   => "amended"
    case _: NewTrade  => "new"
    case _: Cancelled => "cancelled"
    case _: Expired   => "expired"
  }
}
