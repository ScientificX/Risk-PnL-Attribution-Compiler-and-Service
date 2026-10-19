package com.writhlang.portfolio

import com.writhlang.dsl._
import com.writhlang.marketdata.MarketData
import com.writhlang.time.TimeConventions

import java.time.LocalDate

/**
  * A stable trade identifier.
  *
  * In this version a trade and its instrument are one and the same thing, so the
  * id is the DSL instrument id. The type exists so that the trade layer never has
  * to treat an instrument id as a bare string, and so a future version can split
  * a trade from its legs without changing the population API.
  */
final case class TradeId(value: String) {
  override def toString: String = value
}

/**
  * One trade in one snapshot: its terms, the date it started, where it sits in the
  * book tree, and the currency it is denominated in.
  *
  * `tradeDate` is the trade's own start date. Schedules and the maturity date are
  * measured from it, so the same trade terms describe a *seasoned* position when
  * the valuation date is later than the trade date. That is what makes a trade
  * age, and what makes expiry a calendar fact rather than a convention.
  *
  * `currency` absent means the trade is denominated in the reporting currency, so
  * no translation is needed.
  */
final case class TradeInstance(
  id: TradeId,
  spec: InstrumentSpec,
  tradeDate: LocalDate,
  path: List[String],
  currency: Option[Currency] = None
) {

  /** The date the trade's last cashflow falls due, rolled onto a business day. */
  def settlementDate(conventions: TimeConventions): LocalDate =
    conventions.adjust(InstrumentTiming.maturityDate(spec, tradeDate))

  /** Active before settlement, expired on or after it. */
  def stateAt(asOf: LocalDate, conventions: TimeConventions): TradeState =
    if (asOf.isBefore(settlementDate(conventions))) TradeState.Active else TradeState.Expired

  /** The book path rendered as a slash-separated key, e.g. `RatesDesk/BookA`. */
  def bookPath: String = path.mkString("/")
}

/** The lifecycle state of a trade at a valuation date. */
sealed trait TradeState
object TradeState {
  case object Active extends TradeState
  case object Expired extends TradeState

  val all: List[TradeState] = List(Active, Expired)
}

/**
  * The book at one valuation date: every trade that was on the books, keyed by id.
  *
  * A population is a *snapshot*, not a history. Comparing two populations is how
  * the engine learns what happened to the book between two dates (see
  * [[PopulationDiff]]).
  */
final case class TradePopulation(
  asOf: LocalDate,
  conventions: TimeConventions,
  trades: Map[TradeId, TradeInstance]
) {
  def trade(id: TradeId): Option[TradeInstance] = trades.get(id)
  def contains(id: TradeId): Boolean = trades.contains(id)

  /** State of a specific trade at this population's valuation date. */
  def stateOf(trade: TradeInstance): TradeState = trade.stateAt(asOf, conventions)

  def activeTrades: List[TradeInstance] = trades.values.toList.sortBy(_.id.value).filter(stateOf(_) == TradeState.Active)
  def expiredTrades: List[TradeInstance] = trades.values.toList.sortBy(_.id.value).filter(stateOf(_) == TradeState.Expired)
}

object TradePopulation {

  /**
    * Build a population from a parsed DSL program and the snapshot it is booked
    * against.
    *
    * Every trade needs a start date. If the DSL omits `tradeDate`, the trade is
    * treated as entered on the valuation date — which is correct for a
    * single-snapshot run and *wrong* for a period run, so the period compiler
    * rejects a missing `tradeDate` rather than silently re-anchoring the trade at
    * each snapshot.
    *
    * Duplicate ids are rejected: the DAG is keyed by trade id, so a duplicate
    * would silently overwrite another trade's nodes.
    */
  def fromProgram(program: Program, market: MarketData): Either[String, TradePopulation] = {
    val leaves = Program.trades(program.nodes)
    val duplicatedIds = leaves
      .groupBy(_._1.id)
      .collect { case (id, occurrences) if occurrences.size > 1 => id }
      .toList
      .sorted
    if (duplicatedIds.nonEmpty)
      Left(s"duplicate trade id(s): ${duplicatedIds.mkString(", ")}")
    else {
      val trades = leaves.map { case (spec, declaredTradeDate, currency, path) =>
        val trade = TradeInstance(TradeId(spec.id), spec, declaredTradeDate.getOrElse(market.asOf), path, currency)
        trade.id -> trade
      }.toMap
      Right(TradePopulation(market.asOf, market.conventions, trades))
    }
  }
}
