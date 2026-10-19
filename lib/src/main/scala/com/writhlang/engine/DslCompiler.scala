package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.portfolio._
import com.writhlang.risk._
import com.writhlang.scenario._

import java.time.LocalDate

/**
  * Translates a [[Program]] (portfolio tree + shocks) and a [[MarketData]]
  * snapshot into a computation [[Dag]].
  *
  * Two ideas drive the shape of the DAG:
  *
  *   1. **Trade identity.** Every node id is keyed by the trade id, and a trade's
  *      start date anchors its schedules. Duplicate trade ids are rejected: the
  *      DAG is a map, so a duplicate would silently overwrite another trade.
  *   2. **Roll-up.** Each reportable metric (price, every Greek, every scenario
  *      price, theta, carry) also gets one `agg:` node per book path and one for
  *      the whole book. The aggregation nodes are plain sums, so book-level
  *      numbers are exactly the sum of trade-level numbers — and sensitivities
  *      *net* across trades (a long and a short of the same factor cancel).
  *
  * Sensitivities are per-pillar: for a quote-backed curve, each par instrument
  * becomes its own [[RiskFactorKey]] (`DiscountCurve:EUR:2Y`), and a bump is a
  * par-conversion — bump that quote and re-bootstrap the curve.
  */
object DslCompiler {

  /** A compiled node, plus — when it is a reportable metric — the metric it rolls up under. */
  private final case class CompiledNode(id: String, op: Op, deps: List[String], metric: Option[String])

  def build(
    program: Program,
    market: MarketData,
    config: SensitivityConfig = SensitivityConfig()
  ): Either[String, Dag] = {
    val trades = Program.trades(program.nodes)
    val duplicatedTradeIds = trades
      .groupBy(_._1.id)
      .collect { case (id, occurrences) if occurrences.size > 1 => id }
      .toList
      .sorted
    val currencies = trades.flatMap(_._3).distinct
    if (duplicatedTradeIds.nonEmpty)
      Left(s"duplicate trade id(s): ${duplicatedTradeIds.mkString(", ")}")
    else if (currencies.size > 1)
      Left(
        s"a single-snapshot build reports in each trade's own currency, but the book mixes " +
          s"${currencies.map(_.code).sorted.mkString(", ")} — use a period run with a reporting currency to translate"
      )
    else {
      val shockNodes = program.shocks.flatMap(buildShockNodes)
      val compiled: List[(List[String], CompiledNode)] = trades.flatMap { case (instrument, tradeDate, _, path) =>
        buildInstrumentNodes(instrument, program.shocks, market, config, tradeDate).map(node => path -> node)
      }
      val rollupNodes = buildRollups(compiled)
      val brainNodes = compiled.map { case (_, node) => node.id -> Instruction(node.id, node.op, node.deps) }
      val nodes = shockNodes ++ brainNodes ++ rollupNodes

      val duplicatedNodeIds = nodes
        .groupBy(_._1)
        .collect { case (id, occurrences) if occurrences.size > 1 => id }
        .toList
        .sorted
      if (duplicatedNodeIds.nonEmpty)
        Left(s"duplicate DAG node id(s): ${duplicatedNodeIds.mkString(", ")}")
      else Right(Dag(nodes.toMap))
    }
  }

  /** Flatten a recursive portfolio tree into `(instrument, portfolio path)`. */
  def flattenInstruments(nodes: List[PortfolioNode]): List[(InstrumentSpec, List[String])] =
    Program.trades(nodes).map { case (spec, _, _, path) => (spec, path) }

  /** A `Const` node per scalar (point) shock move. */
  private def buildShockNodes(shock: Shock): List[(String, Instruction)] =
    shock.moves.collect { case PointMove(key, _, amount) =>
      val id = shockFactorId(shock.name, key)
      id -> Instruction(id, Const(amount), Nil)
    }

  private def buildInstrumentNodes(
    instrument: InstrumentSpec,
    shocks: List[Shock],
    market: MarketData,
    config: SensitivityConfig,
    declaredTradeDate: Option[java.time.LocalDate]
  ): List[CompiledNode] = {
    val factors = factorsFor(instrument, market)
    val baseId = basePriceId(instrument.id)
    // The trade date anchors every schedule. An explicit `tradeDate` wins; otherwise
    // the trade is treated as entered on the base snapshot's valuation date. It is
    // fixed once here: a bumped or later-dated market must revalue the SAME trade,
    // not re-anchor it to its own valuation date (which would make time never pass).
    val tradeDate = declaredTradeDate.orElse(Some(market.asOf))
    val base = CompiledNode(baseId, Price(instrument, market, tradeDate), Nil, Some("price:base"))

    // Per-factor up/down bumped prices and delta/gamma. The bumped prices are
    // intermediates (not reportable on their own); the Greeks are reportable.
    val greekNodes = factors.flatMap { key =>
      val bump = config.bumpFor(key)
      val upMarket = bumpScenario(key, bump).applyTo(market)
      val downMarket = bumpScenario(key, -bump).applyTo(market)
      val upId = bumpedPriceId(instrument.id, key, "up")
      val downId = bumpedPriceId(instrument.id, key, "down")
      val up = CompiledNode(upId, Price(instrument, upMarket, tradeDate), Nil, None)
      val down = CompiledNode(downId, Price(instrument, downMarket, tradeDate), Nil, None)

      val deltaNodeId = deltaId(instrument.id, key)
      val gammaNodeId = gammaId(instrument.id, key)
      val (deltaOp, deltaDeps) = config.shiftScheme match {
        case ShiftScheme.Central  => (DeltaCentral(baseId, upId, downId, bump), List(baseId, upId, downId))
        case ShiftScheme.Forward  => (DeltaForward(baseId, upId, bump), List(baseId, upId))
        case ShiftScheme.Backward => (DeltaBackward(baseId, downId, bump), List(baseId, downId))
      }
      val delta = CompiledNode(deltaNodeId, deltaOp, deltaDeps, Some(s"greek:delta:${key.canonical}"))
      val gamma = CompiledNode(
        gammaNodeId,
        Gamma(baseId, upId, downId, bump),
        List(baseId, upId, downId),
        Some(s"greek:gamma:${key.canonical}")
      )
      List(up, down, delta, gamma)
    }

    // Cross gammas for each unordered pair of factors (always central).
    val crossNodes = factors.combinations(2).toList.flatMap {
      case List(k1, k2) =>
        val bump1 = config.bumpFor(k1)
        val bump2 = config.bumpFor(k2)
        val upIId = bumpedPriceId(instrument.id, k1, "up")
        val upJId = bumpedPriceId(instrument.id, k2, "up")
        val upIJId = crossBumpedPriceId(instrument.id, k1, k2)
        val upIJMarket = bumpScenario(k2, bump2).applyTo(bumpScenario(k1, bump1).applyTo(market))
        val upIJ = CompiledNode(upIJId, Price(instrument, upIJMarket, tradeDate), Nil, None)
        val crossId = crossGammaId(instrument.id, k1, k2)
        val cross = CompiledNode(
          crossId,
          CrossGamma(baseId, upIId, upJId, upIJId, bump1, bump2),
          List(baseId, upIId, upJId, upIJId),
          Some(s"greek:cross:${k1.canonical}:${k2.canonical}")
        )
        List(upIJ, cross)
      case _ => Nil
    }

    val scenarioNodes = shocks.flatMap { shock =>
      buildScenarioNodes(instrument, shock, baseId, factors, market, tradeDate)
    }

    // Time dimension: theta is the value at the advanced valuation date minus the base,
    // and carry is the cash plus accrued-interest change over the same period. Theta is a
    // full revaluation on the frozen market at a later date, not a tenor shift.
    val timeNodes = config.thetaValuationDate(market.asOf) match {
      case Some(advancedDate) =>
        val advancedMarket = market.withAsOf(advancedDate)
        val thetaEvalNodeId = thetaEvalPriceId(instrument.id)
        val thetaEval = CompiledNode(thetaEvalNodeId, Price(instrument, advancedMarket, tradeDate), Nil, None)
        val thetaNodeId = thetaId(instrument.id)
        val theta = CompiledNode(thetaNodeId, Theta(baseId, thetaEvalNodeId), List(baseId, thetaEvalNodeId), Some("price:theta"))
        val carryNodeId = carryId(instrument.id)
        val carry = CompiledNode(
          carryNodeId,
          Carry(instrument, market, advancedMarket, tradeDate),
          Nil,
          Some("price:carry")
        )
        List(thetaEval, theta, carry)
      case None => Nil
    }

    base :: greekNodes ++ crossNodes ++ scenarioNodes ++ timeNodes
  }

  private def buildScenarioNodes(
    instrument: InstrumentSpec,
    shock: Shock,
    baseId: String,
    factors: List[RiskFactorKey],
    market: MarketData,
    tradeDate: Option[java.time.LocalDate]
  ): List[CompiledNode] = {
    // Full reprice: apply the whole shock as a scenario, then reprice.
    val fullMarket = shockScenario(shock).applyTo(market)
    val fullId = fullScenarioId(instrument.id, shock.name)
    val full = CompiledNode(fullId, Price(instrument, fullMarket, tradeDate), Nil, Some(s"price:full:${shock.name}"))

    // Taylor terms use only the scalar (point) moves present in this shock.
    val presentKeys = shock.moves.collect { case PointMove(key, _, _) => key }.filter(factors.contains)

    val linearTerms = presentKeys.map { key =>
      deltaId(instrument.id, key) -> shockFactorId(shock.name, key)
    }
    val gammaTerms = presentKeys.map { key =>
      gammaId(instrument.id, key) -> shockFactorId(shock.name, key)
    }
    val crossTerms = presentKeys.combinations(2).toList.map {
      case List(k1, k2) =>
        (crossGammaId(instrument.id, k1, k2),
          shockFactorId(shock.name, k1),
          shockFactorId(shock.name, k2))
      case _ => ("", "", "")
    }

    val linearId = linearScenarioId(instrument.id, shock.name)
    val linearDeps = baseId :: linearTerms.flatMap(term => List(term._1, term._2))
    val linear = CompiledNode(
      linearId,
      LinearScenario(baseId, linearTerms),
      linearDeps.distinct,
      Some(s"price:linear:${shock.name}")
    )

    val quadraticId = quadraticScenarioId(instrument.id, shock.name)
    val quadraticDeps = baseId ::
      linearTerms.flatMap(term => List(term._1, term._2)) ++
      gammaTerms.flatMap(term => List(term._1, term._2)) ++
      crossTerms.flatMap(term => List(term._1, term._2, term._3))
    val quadratic = CompiledNode(
      quadraticId,
      QuadraticScenario(baseId, linearTerms, gammaTerms, crossTerms),
      quadraticDeps.distinct,
      Some(s"price:quad:${shock.name}")
    )

    List(full, linear, quadratic)
  }

  /**
    * Emit one sum node per (metric, book path) and one per metric for the whole
    * book.
    *
    * Every trade's metric belongs to a path, e.g. `RatesDesk/BookA`. For each
    * metric we aggregate at every prefix of every path: the leaves are the
    * trades' own nodes, and an inner node sums its child aggregates plus any
    * trades sitting directly at that level. The total is the empty path.
    *
    * A sum of prices is a price; a sum of deltas is a *netted* sensitivity — a
    * long and a short of the same factor cancel. Both are exact because each
    * metric is additive across trades at a fixed market state.
    */
  private def buildRollups(compiled: List[(List[String], CompiledNode)]): List[(String, Instruction)] = {
    val byMetric: Map[String, List[(String, List[String])]] =
      compiled
        .flatMap { case (path, node) => node.metric.map(metric => (metric, node.id, path)) }
        .groupBy(_._1)
        .map { case (metric, entries) => metric -> entries.map(entry => (entry._2, entry._3)) }

    byMetric.toList.flatMap { case (metric, entries) =>
      val leavesByPath: Map[List[String], List[String]] =
        entries.groupBy(_._2).map { case (path, ids) => path -> ids.map(_._1).sorted }
      val paths: List[List[String]] =
        leavesByPath.keySet.flatMap(path => (0 to path.length).map(path.take(_))).toList.distinct
      paths.sortBy(path => (path.length, path.mkString("/"))).map { path =>
        val leafIds = leavesByPath.getOrElse(path, Nil)
        val childAggregates = paths
          .filter(child => child.length == path.length + 1 && child.startsWith(path))
          .map(child => aggregateId(metric, child))
          .sorted
        val id = aggregateId(metric, path)
        id -> Instruction(id, Sum, (childAggregates ++ leafIds).sorted.distinct)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Period P&L: two dated snapshots
  // ---------------------------------------------------------------------------

  /** The compiled period: the DAG, the dates, the events, and the reporting currency. */
  final case class PeriodPnl(
    dag: Dag,
    from: LocalDate,
    to: LocalDate,
    events: List[PopulationEvent],
    reportingCurrency: Option[Currency] = None
  )

  /** The P&L buckets a population event contributes. */
  def pnlBuckets(event: PopulationEvent): List[String] = event match {
    case _: Unchanged => List("market", "theta", "carry")
    case _: Amended   => List("market", "theta", "carry", "inception")
    case _: NewTrade  => List("new")
    case _: Cancelled => List("cancelled")
    case Expired(prev, Some(curr)) =>
      if (prev.spec == curr.spec) List("expired") else List("inception", "expired")
    case _: Expired => List("expired")
  }

  /**
    * Compile a period: two dated snapshots of the same book, and the exact P&L
    * decomposition between them.
    *
    * The identity is cash-based:
    *
    * {{{
    * Σ buckets = Σ_{t1 book} dirty(t1, m1) + Σ cash(t0, t1] − Σ_{t0 book} dirty(t0, m0)
    * }}}
    *
    * Per event:
    *   - unchanged / amended: `market` = clean(t1,m1) − clean(t1,m0);
    *     `theta` = clean(t1,m0) − clean(t0,m0); `carry` = Δaccrued + cash paid;
    *     `inception` (amended only) = dirty(new, t0) − dirty(old, t0).
    *   - new: `dirty(t1,m1) + cash` (assumed entered at t0);
    *   - cancelled: `−dirty(t0,m0)` (removed at t0; the unwind price is not data);
    *   - expired: `cash − dirty(t0,m0)`, plus `inception` when it was also amended.
    *
    * Market and theta use clean values so that accrual is counted exactly once, in
    * `carry`; the total is unaffected by that choice.
    *
    * Requires an explicit `tradeDate` on every trade: an implicit one would
    * re-anchor the trade at each snapshot, so it would never age.
    */
  def buildPeriodPnl(
    previous: (Program, MarketData),
    current: (Program, MarketData),
    config: SensitivityConfig = SensitivityConfig(),
    reportingCurrency: Option[Currency] = None
  ): Either[String, PeriodPnl] = {
    val (prevProgram, prevMarket) = previous
    val (currProgram, currMarket) = current

    for {
      _ <- requireExplicitTradeDates(prevProgram, "previous")
      _ <- requireExplicitTradeDates(currProgram, "current")
      prevPopulation <- TradePopulation.fromProgram(prevProgram, prevMarket)
      currPopulation <- TradePopulation.fromProgram(currProgram, currMarket)
      events <- PopulationDiff.between(prevPopulation, currPopulation)
      period <- compilePeriod(events, prevMarket, currMarket, reportingCurrency)
    } yield period
  }

  private def requireExplicitTradeDates(program: Program, label: String): Either[String, Unit] = {
    val missing = Program
      .trades(program.nodes)
      .collect { case (spec, None, _, _) => spec.id }
      .sorted
    if (missing.isEmpty) Right(())
    else
      Left(
        s"$label book: trade(s) ${missing.mkString(", ")} have no tradeDate; a period run requires an explicit tradeDate " +
          "because an implicit one would re-anchor the trade at each snapshot"
      )
  }

  private def compilePeriod(
    events: List[PopulationEvent],
    prevMarket: MarketData,
    currMarket: MarketData,
    reportingCurrency: Option[Currency]
  ): Either[String, PeriodPnl] = {
    val currencies = events.flatMap(eventCurrency).distinct
    if (reportingCurrency.isEmpty && currencies.size > 1)
      Left(
        s"the book mixes ${currencies.map(_.code).sorted.mkString(", ")}; " +
          "supply a reporting currency to translate the period P&L"
      )
    else {
      // Every amount is translated at the CLOSING snapshot's spot, so no FX gain or
      // loss is created by the translation itself (FX P&L is a Stage 6 bucket).
      val eventFactors = events.foldLeft[Either[String, List[(PopulationEvent, Double)]]](Right(Nil)) {
        case (accumulated, event) =>
          for {
            factors <- accumulated
            factor <- FxConversion.factor(eventCurrency(event), reportingCurrency, currMarket)
          } yield factors :+ (event -> factor)
      }
      eventFactors.flatMap { factors =>
        val compiled: List[(List[String], CompiledNode)] = events.zip(factors.map(_._2)).flatMap { case (event, factor) =>
          buildEventNodes(event, prevMarket, currMarket, factor).map(node => eventPath(event) -> node)
        }
        val rollupNodes = buildRollups(compiled)
        val nodes = compiled.map { case (_, node) => node.id -> Instruction(node.id, node.op, node.deps) } ++ rollupNodes
        val duplicatedNodeIds = nodes
          .groupBy(_._1)
          .collect { case (id, occurrences) if occurrences.size > 1 => id }
          .toList
          .sorted
        if (duplicatedNodeIds.nonEmpty) Left(s"duplicate DAG node id(s): ${duplicatedNodeIds.mkString(", ")}")
        else Right(PeriodPnl(Dag(nodes.toMap), prevMarket.asOf, currMarket.asOf, events, reportingCurrency))
      }
    }
  }

  private def eventCurrency(event: PopulationEvent): Option[Currency] = event match {
    case Unchanged(trade)    => trade.currency
    case Amended(_, curr)    => curr.currency
    case NewTrade(curr)      => curr.currency
    case Cancelled(prev)     => prev.currency
    case Expired(prev, curr) => curr.flatMap(_.currency).orElse(prev.currency)
  }

  private def eventPath(event: PopulationEvent): List[String] = event match {
    case Unchanged(trade)    => trade.path
    case Amended(_, curr)    => curr.path
    case NewTrade(curr)      => curr.path
    case Cancelled(prev)     => prev.path
    case Expired(prev, curr) => curr.map(_.path).getOrElse(prev.path)
  }

  /** The value, accrual, cash and bucket nodes for one population event. */
  private def buildEventNodes(
    event: PopulationEvent,
    m0: MarketData,
    m1: MarketData,
    currencyFactor: Double
  ): List[CompiledNode] = {
    val t0 = m0.asOf
    val t1 = m1.asOf

    def priceNode(
      spec: InstrumentSpec,
      market: MarketData,
      date: LocalDate,
      tradeDate: LocalDate,
      label: String,
      tradeId: String
    ): CompiledNode = {
      val id = s"pnl:price:$label:$tradeId"
      CompiledNode(id, Price(spec, market.withAsOf(date), Some(tradeDate)), Nil, None)
    }

    def accruedNode(
      spec: InstrumentSpec,
      market: MarketData,
      date: LocalDate,
      tradeDate: LocalDate,
      label: String,
      tradeId: String
    ): CompiledNode = {
      val id = s"pnl:accrued:$label:$tradeId"
      CompiledNode(id, Accrued(spec, market.withAsOf(date), Some(tradeDate)), Nil, None)
    }

    def cashNode(spec: InstrumentSpec, market: MarketData, tradeDate: LocalDate, tradeId: String): CompiledNode = {
      val id = s"pnl:cash:$tradeId"
      CompiledNode(id, CashFlow(spec, market, t0, t1, Some(tradeDate)), Nil, None)
    }

    // Bucket terms are scaled by the trade's currency factor, so every node and
    // roll-up is already in the reporting currency. The intermediate value, accrual
    // and cash nodes stay in the trade's own currency: only the bucket output is a
    // reporting amount.
    def bucket(tradeId: String, name: String, terms: List[(String, Double)]): CompiledNode = {
      val reportingTerms = terms.map { case (dependency, coefficient) => dependency -> coefficient * currencyFactor }
      CompiledNode(pnlId(name, tradeId), Combine(reportingTerms), reportingTerms.map(_._1), Some(pnlMetric(name)))
    }

    def tradeTotal(tradeId: String, buckets: List[CompiledNode]): CompiledNode = {
      val terms = buckets.map(node => node.id -> 1.0)
      CompiledNode(pnlTotalId(tradeId), Combine(terms), terms.map(_._1), Some("pnl:total"))
    }

    /** Market / theta / carry on the effective terms, plus inception when amended. */
    def cleanBuckets(prev: Option[TradeInstance], curr: TradeInstance): List[CompiledNode] = {
      val tradeId = curr.id.value
      val spec = curr.spec
      val tradeDate = curr.tradeDate

      val dirtyT1M1 = priceNode(spec, m1, t1, tradeDate, "t1m1", tradeId)
      val dirtyT1M0 = priceNode(spec, m0, t1, tradeDate, "t1m0", tradeId)
      val dirtyT0M0 = priceNode(spec, m0, t0, tradeDate, "t0m0", tradeId)
      val accruedT1 = accruedNode(spec, m1, t1, tradeDate, "t1", tradeId)
      val accruedT0 = accruedNode(spec, m0, t0, tradeDate, "t0", tradeId)
      val cash = cashNode(spec, m0, tradeDate, tradeId)

      val market = bucket(tradeId, "market", List(dirtyT1M1.id -> 1.0, dirtyT1M0.id -> -1.0))
      val theta = bucket(
        tradeId,
        "theta",
        List(dirtyT1M0.id -> 1.0, dirtyT0M0.id -> -1.0, accruedT1.id -> -1.0, accruedT0.id -> 1.0)
      )
      val carry = bucket(tradeId, "carry", List(accruedT1.id -> 1.0, accruedT0.id -> -1.0, cash.id -> 1.0))

      val inceptionNodes = prev.map { previous =>
        val dirtyPrevT0 = priceNode(previous.spec, m0, t0, previous.tradeDate, "prev-t0", tradeId)
        val inception = bucket(tradeId, "inception", List(dirtyT0M0.id -> 1.0, dirtyPrevT0.id -> -1.0))
        (dirtyPrevT0, inception)
      }

      val buckets = List(market, theta, carry) ++ inceptionNodes.map(_._2).toList
      List(dirtyT1M1, dirtyT1M0, dirtyT0M0, accruedT1, accruedT0, cash) ++
        inceptionNodes.map(_._1).toList ++ buckets :+ tradeTotal(tradeId, buckets)
    }

    event match {
      case Unchanged(curr)     => cleanBuckets(None, curr)
      case Amended(prev, curr) => cleanBuckets(Some(prev), curr)

      case Cancelled(prev) =>
        val tradeId = prev.id.value
        val dirtyT0M0 = priceNode(prev.spec, m0, t0, prev.tradeDate, "t0m0", tradeId)
        val cancelled = bucket(tradeId, "cancelled", List(dirtyT0M0.id -> -1.0))
        List(dirtyT0M0, cancelled, tradeTotal(tradeId, List(cancelled)))

      case NewTrade(curr) =>
        val tradeId = curr.id.value
        val dirtyT1M1 = priceNode(curr.spec, m1, t1, curr.tradeDate, "t1m1", tradeId)
        val cash = cashNode(curr.spec, m0, curr.tradeDate, tradeId)
        val newBucket = bucket(tradeId, "new", List(dirtyT1M1.id -> 1.0, cash.id -> 1.0))
        List(dirtyT1M1, cash, newBucket, tradeTotal(tradeId, List(newBucket)))

      case Expired(prev, currOpt) =>
        val tradeId = prev.id.value
        val dirtyPrevT0 = priceNode(prev.spec, m0, t0, prev.tradeDate, "t0m0", tradeId)
        currOpt match {
          case Some(curr) if curr.spec != prev.spec =>
            // Amended and then matured: separate the restatement from the settlement.
            val dirtyCurrT0 = priceNode(curr.spec, m0, t0, curr.tradeDate, "curr-t0", tradeId)
            val cash = cashNode(curr.spec, m0, curr.tradeDate, tradeId)
            val inception = bucket(tradeId, "inception", List(dirtyCurrT0.id -> 1.0, dirtyPrevT0.id -> -1.0))
            val expired = bucket(tradeId, "expired", List(cash.id -> 1.0, dirtyCurrT0.id -> -1.0))
            List(dirtyPrevT0, dirtyCurrT0, cash, inception, expired, tradeTotal(tradeId, List(inception, expired)))
          case _ =>
            val cash = cashNode(prev.spec, m0, prev.tradeDate, tradeId)
            val expired = bucket(tradeId, "expired", List(cash.id -> 1.0, dirtyPrevT0.id -> -1.0))
            List(dirtyPrevT0, cash, expired, tradeTotal(tradeId, List(expired)))
        }
    }
  }

  /** The id of a trade's P&L bucket node, e.g. `pnl:market:TRADE1`. */
  def pnlId(bucketName: String, tradeId: String): String = s"pnl:$bucketName:$tradeId"

  /** The id of a trade's total P&L node, `pnl:total:TRADE1`. */
  def pnlTotalId(tradeId: String): String = s"pnl:total:$tradeId"

  /** The roll-up metric key for a P&L bucket, e.g. `pnl:market` → `agg:pnl:market:TOTAL`. */
  def pnlMetric(bucketName: String): String = s"pnl:$bucketName"

  /**
    * The risk factors for an instrument: curve pillars + direct (prepay/vol/fx) keys.
    *
    * Distinct, because the same factor can arrive by two routes — an FX forward
    * whose domestic and foreign legs reference the same curve names that curve
    * twice, and bumping it twice would produce duplicate nodes (and a double
    * count of the same sensitivity).
    */
  def factorsFor(instrument: InstrumentSpec, market: MarketData): List[RiskFactorKey] =
    (curveKeysFor(instrument).flatMap(k => pillarKeys(k, market)) ++ directKeysFor(instrument)).distinct

  private def curveKeysFor(instrument: InstrumentSpec): List[RiskFactorKey] = instrument match {
    case s: BondSpec      => List(s.discountCurve, s.creditCurve)
    case s: MortgageSpec  => List(s.discountCurve, s.creditCurve)
    case s: MbsPoolSpec   => List(s.discountCurve, s.creditCurve)
    case s: SwapSpec      => List(s.discountCurve, s.creditCurve)
    case s: CapSpec       => List(s.discountCurve, s.creditCurve)
    case s: SwaptionSpec  => List(s.discountCurve, s.creditCurve)
    case s: FxForwardSpec => List(s.domesticCurve, s.foreignCurve)
  }

  private def directKeysFor(instrument: InstrumentSpec): List[RiskFactorKey] = instrument match {
    case s: MortgageSpec  => List(s.prepayCurve)
    case s: MbsPoolSpec   => List(s.prepayCurve)
    case s: CapSpec       => List(s.volSurface)
    case s: SwaptionSpec  => List(s.volSurface)
    case s: FxForwardSpec => List(s.fxSpot)
    case _                => Nil
  }

  /** One key per par instrument for a quote-backed curve; the curve key otherwise. */
  private def pillarKeys(curveKey: RiskFactorKey, market: MarketData): List[RiskFactorKey] =
    market.curveQuotes.get(curveKey) match {
      case Some(quotes) => quotes.instruments.map(i => curveKey.copy(tenor = Some(Tenor(i.tenorYears))))
      case None         => List(curveKey)
    }

  /** The market-object key + shift for a single-factor perturbation. */
  private def scenarioShiftFor(key: RiskFactorKey, shiftType: ShiftType, amount: Double): (RiskFactorKey, ScenarioShift) =
    key.keyType match {
      case KeyType.DiscountCurve | KeyType.IndexCurve | KeyType.YieldCurve | KeyType.CreditCurve =>
        val curveKey = key.withoutTenor
        key.tenor match {
          case Some(t) => curveKey -> ParQuoteShift(t.years, amount)
          case None    => curveKey -> AdditiveShift(Flat(amount))
        }
      case KeyType.Prepay =>
        key -> AdditiveShift(Flat(amount))
      case KeyType.SwaptionVolatility | KeyType.CapFloorVolatility |
           KeyType.FxVolatility | KeyType.EquityVolatility | KeyType.CommodityVolatility =>
        key -> AdditiveShift(Flat(amount))
      case KeyType.FxSpot | KeyType.EquitySpot =>
        key -> (if (shiftType == ShiftType.Relative) RelativeSpotShift(amount) else AbsoluteSpotShift(amount))
      case _ => key -> AdditiveShift(Flat(0.0))
    }

  /** A single-factor bump scenario (for up/down finite differences). */
  private def bumpScenario(key: RiskFactorKey, amount: Double): Scenario = {
    val (marketKey, shift) = scenarioShiftFor(key, ShiftType.defaultFor(key.keyType), amount)
    Scenario(s"bump:${key.canonical}:$amount", Map(marketKey -> List(shift)))
  }

  /** Translate a DSL curve shift into a scenario shift shape. */
  private def shapeToShiftShape(shift: CurveShift): ShiftShape = shift match {
    case NoCurveShift                    => Flat(0.0)
    case FlatShift(amount)               => Flat(amount)
    case BucketShift(tenorYears, amount) => Bucket(tenorYears, amount)
    case TwistShift(short, long, pivot)  => Twist(short, long, pivot)
    case SineShift(amp, omega, phase)    => Sine(amp, omega, phase)
  }

  /** Full market transformation for a shock (used for exact re-pricing). */
  private def shockScenario(shock: Shock): Scenario = {
    val grouped: Map[RiskFactorKey, List[ScenarioShift]] = shock.moves.map {
      case PointMove(key, shiftType, amount) => scenarioShiftFor(key, shiftType, amount)
      case ShapeMove(key, shape)             => key -> AdditiveShift(shapeToShiftShape(shape))
    }.groupMap(_._1)(_._2)
    Scenario(shock.name, grouped)
  }

  // --- id helpers ---

  def shockFactorId(shockName: String, key: RiskFactorKey): String = s"shock:$shockName:${key.canonical}"
  def basePriceId(instId: String): String = s"price:base:$instId"
  def bumpedPriceId(instId: String, key: RiskFactorKey, dir: String): String = s"price:bump:$instId:${key.canonical}:$dir"
  def crossBumpedPriceId(instId: String, k1: RiskFactorKey, k2: RiskFactorKey): String = s"price:cross:$instId:${k1.canonical}:${k2.canonical}"
  def deltaId(instId: String, key: RiskFactorKey): String = s"greek:delta:$instId:${key.canonical}"
  def gammaId(instId: String, key: RiskFactorKey): String = s"greek:gamma:$instId:${key.canonical}"
  def crossGammaId(instId: String, k1: RiskFactorKey, k2: RiskFactorKey): String = s"greek:cross:$instId:${k1.canonical}:${k2.canonical}"
  def linearScenarioId(instId: String, shockName: String): String = s"price:linear:$instId:$shockName"
  def quadraticScenarioId(instId: String, shockName: String): String = s"price:quad:$instId:$shockName"
  def fullScenarioId(instId: String, shockName: String): String = s"price:full:$instId:$shockName"
  def thetaEvalPriceId(instId: String): String = s"price:thetaeval:$instId"
  def thetaId(instId: String): String = s"price:theta:$instId"
  def carryId(instId: String): String = s"price:carry:$instId"

  /**
    * The id of the roll-up node for a metric at a book path. The empty path is the
    * whole book (`agg:<metric>:TOTAL`); a non-empty path is slash-joined
    * (`agg:<metric>:RatesDesk/BookA`).
    */
  def aggregateId(metric: String, path: List[String]): String =
    if (path.isEmpty) s"agg:$metric:TOTAL" else s"agg:$metric:${path.mkString("/")}"

  /** The id of the whole-book roll-up for a metric. */
  def totalAggregateId(metric: String): String = aggregateId(metric, Nil)
}
