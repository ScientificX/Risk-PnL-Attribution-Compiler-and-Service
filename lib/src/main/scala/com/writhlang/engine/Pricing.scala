package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.time._

import java.time.LocalDate
import org.apache.commons.math3.distribution.NormalDistribution

/**
  * Closed-form pricing for the seven DSL instruments, consuming market-data
  * OBJECTS (curves, vol surface, prepay vector, FX spot) resolved from the
  * instrument spec's risk-factor-key references.
  *
  * Time is **date-derived**. Every instrument's cashflows are generated on a
  * calendar schedule anchored on the trade's start date, each cashflow is
  * discounted at the day-count year fraction between the valuation date and its
  * own date, and the instrument is worth zero once its final cashflow has fallen
  * due. Nothing here shifts a tenor by "years elapsed": a value is a function of
  * the valuation date and the trade's date.
  *
  * Nothing here knows about a "shock" — it prices what it is given.
  */
object Pricing {

  /** One amortising payment: the date cash moves, and the balance left after it. */
  private final case class AmortRow(date: LocalDate, cash: Double, balanceAfter: Double)

  // ---------------------------------------------------------------------------
  // Dates: start, settlement
  // ---------------------------------------------------------------------------

  /** The trade's start date; absent an explicit trade date, the snapshot's date. */
  def startDate(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None): LocalDate =
    tradeDate.getOrElse(market.asOf)

  /**
    * The date the instrument's life ends, rolled onto a business day. On or after
    * it the value is zero: every cashflow has fallen due and settled.
    */
  def settlementDate(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None): LocalDate =
    market.conventions.adjust(InstrumentTiming.maturityDate(instrument, startDate(instrument, market, tradeDate)))

  /** True when the valuation date is on or after the settlement date. */
  def hasMatured(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None): Boolean =
    !market.asOf.isBefore(settlementDate(instrument, market, tradeDate))

  // ---------------------------------------------------------------------------
  // Valuation
  // ---------------------------------------------------------------------------

  /** Present value at the snapshot's valuation date; zero once the trade has settled. */
  def price(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None): Double =
    if (hasMatured(instrument, market, tradeDate)) 0.0
    else {
      val start = startDate(instrument, market, tradeDate)
      instrument match {
        case spec: BondSpec      => bondPrice(spec, market, start)
        case spec: MortgageSpec  => mortgagePrice(spec, market, start)
        case spec: MbsPoolSpec   => mbsPrice(spec, market, start)
        case spec: SwapSpec      => swapPrice(spec, market, start)
        case spec: CapSpec       => capPrice(spec, market, start)
        case spec: SwaptionSpec  => swaptionPrice(spec, market, start)
        case spec: FxForwardSpec => fxForwardPrice(spec, market, start)
      }
    }

  /**
    * Accrued interest since the last payment date, used to split a value change
    * into a clean-price move and a carry move. Zero for instruments that do not
    * accrue a coupon (options, FX forwards, and a swap's floating leg).
    */
  def accrued(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None): Double =
    if (hasMatured(instrument, market, tradeDate)) 0.0
    else {
      val start = startDate(instrument, market, tradeDate)
      instrument match {
        case spec: BondSpec =>
          val paid = bondCashFlows(spec, market, start).filterNot(_._1.isAfter(market.asOf))
          val lastPaymentDate = paid.lastOption.map(_._1).getOrElse(start)
          spec.notional * spec.coupon * market.yearFraction(lastPaymentDate, market.asOf)
        case spec: MortgageSpec =>
          amortisingAccrued(
            spec.notional,
            spec.noteRate,
            amortisingRows(start, spec.notional, spec.noteRate, spec.termMonths, market.prepayVector(spec.prepayCurve), market.conventions),
            start,
            market
          )
        case spec: MbsPoolSpec =>
          amortisingAccrued(
            spec.notional,
            spec.wac,
            amortisingRows(start, spec.notional, spec.wac, spec.wamMonths, market.prepayVector(spec.prepayCurve), market.conventions),
            start,
            market
          )
        case _ => 0.0
      }
    }

  /** Contractual cash strictly inside `(from, to]`; used for the P&L cash bucket. */
  def cashIn(
    instrument: InstrumentSpec,
    market: MarketData,
    from: LocalDate,
    to: LocalDate,
    tradeDate: Option[LocalDate] = None
  ): Double = {
    val start = startDate(instrument, market, tradeDate)
    val rows: Vector[(LocalDate, Double)] = instrument match {
      case spec: BondSpec => bondCashFlows(spec, market, start)
      case spec: SwapSpec => swapFixedCashFlows(spec, market, start)
      case spec: MortgageSpec =>
        amortisingRows(start, spec.notional, spec.noteRate, spec.termMonths, market.prepayVector(spec.prepayCurve), market.conventions)
          .map(row => row.date -> row.cash)
      case spec: MbsPoolSpec =>
        amortisingRows(start, spec.notional, spec.wac, spec.wamMonths, market.prepayVector(spec.prepayCurve), market.conventions)
          .map(row => row.date -> row.cash)
      case _ => Vector.empty
    }
    rows.collect { case (date, cash) if date.isAfter(from) && !date.isAfter(to) => cash }.sum
  }

  /**
    * Carry over the period between two snapshots: cash paid in the window plus
    * the change in accrued interest. Together with the clean price change this
    * reconstructs the total value change exactly.
    */
  def carry(
    instrument: InstrumentSpec,
    from: MarketData,
    to: MarketData,
    tradeDate: Option[LocalDate] = None
  ): Double =
    cashIn(instrument, from, from.asOf, to.asOf, tradeDate) +
      accrued(instrument, to, tradeDate) -
      accrued(instrument, from, tradeDate)

  // ---------------------------------------------------------------------------
  // Schedules and cashflows
  // ---------------------------------------------------------------------------

  /** Bond cashflows from the start date: coupon × accrual each period, plus principal at the end. */
  private def bondCashFlows(spec: BondSpec, market: MarketData, start: LocalDate): Vector[(LocalDate, Double)] = {
    val conventions = market.conventions
    val periods = spec.maturityYears * spec.couponFreq
    val dates = Schedule.dates(start, periods, InstrumentTiming.monthsPerPeriod(spec), conventions)
    val rows = Vector.newBuilder[(LocalDate, Double)]
    var previous = start
    var index = 0
    while (index < dates.length) {
      val date = dates(index)
      val accrual = conventions.yearFraction(previous, date)
      val coupon = spec.notional * spec.coupon * accrual
      val cash = if (index == dates.length - 1) coupon + spec.notional else coupon
      rows += (date -> cash)
      previous = date
      index += 1
    }
    rows.result()
  }

  /** Swap fixed-leg cashflows from the start date. */
  private def swapFixedCashFlows(spec: SwapSpec, market: MarketData, start: LocalDate): Vector[(LocalDate, Double)] = {
    val conventions = market.conventions
    val periods = spec.maturityYears * spec.freq
    val dates = Schedule.dates(start, periods, InstrumentTiming.monthsPerPeriod(spec), conventions)
    val rows = Vector.newBuilder[(LocalDate, Double)]
    var previous = start
    dates.foreach { date =>
      val accrual = conventions.yearFraction(previous, date)
      rows += (date -> (spec.notional * spec.fixedRate * accrual))
      previous = date
    }
    rows.result()
  }

  /**
    * Level-payment amortisation schedule: the annuity payment is split into
    * interest and scheduled principal each month, and prepayment retires
    * additional principal. The schedule is simulated from the start date, so the
    * balance at any valuation date is the contractual balance.
    */
  private def amortisingRows(
    start: LocalDate,
    notional: Double,
    noteRate: Double,
    termMonths: Int,
    prepay: PrepayVector,
    conventions: TimeConventions
  ): Vector[AmortRow] = {
    val noteRateMonthly = noteRate / 12.0
    // Annuity payment: P = N·r / (1 − (1+r)^−n), the constant monthly payment
    // that exactly repays the loan at the note rate.
    val payment =
      if (noteRateMonthly == 0.0) notional / termMonths
      else notional * noteRateMonthly / (1.0 - math.pow(1.0 + noteRateMonthly, -termMonths.toDouble))

    var balance = notional
    val rows = Vector.newBuilder[AmortRow]
    var month = 1
    while (month <= termMonths && balance > 0.0) {
      val interest = balance * noteRateMonthly
      val scheduledPrincipal = payment - interest
      // CPR -> SMM (Single Monthly Mortality): SMM = 1 - (1 - CPR)^(1/12).
      val cpr = clamp(prepay.cpr(month - 1), 0.0, 1.0) // 0..1 decimal, not %
      val smm = 1.0 - math.pow(1.0 - cpr, 1.0 / 12.0)
      // Prepayment = SMM applied to the principal remaining after scheduled amortisation.
      val prepayment = math.max(0.0, (balance - scheduledPrincipal) * smm)
      val totalPrincipal = scheduledPrincipal + prepayment
      val cash = interest + totalPrincipal
      balance -= totalPrincipal
      rows += AmortRow(conventions.adjust(start.plusMonths(month.toLong)), cash, balance)
      month += 1
    }
    rows.result()
  }

  private def amortisingAccrued(
    notional: Double,
    noteRate: Double,
    rows: Vector[AmortRow],
    start: LocalDate,
    market: MarketData
  ): Double = {
    val paid = rows.filterNot(_.date.isAfter(market.asOf))
    val balance = paid.lastOption.map(_.balanceAfter).getOrElse(notional)
    val baseDate = paid.lastOption.map(_.date).getOrElse(start)
    balance * noteRate * market.yearFraction(baseDate, market.asOf)
  }

  // ---------------------------------------------------------------------------
  // Instrument pricing
  // ---------------------------------------------------------------------------

  /** Fixed-rate bullet bond: each coupon discounted at its own date plus the final principal. */
  private def bondPrice(spec: BondSpec, market: MarketData, start: LocalDate): Double = {
    val curve = market.combinedCurve(spec.discountCurve, spec.creditCurve)
    bondCashFlows(spec, market, start).collect {
      case (date, cash) if date.isAfter(market.asOf) =>
        cash * curve.df(market.yearFraction(market.asOf, date))
    }.sum
  }

  private def mortgagePrice(spec: MortgageSpec, market: MarketData, start: LocalDate): Double = {
    val curve = market.combinedCurve(spec.discountCurve, spec.creditCurve)
    val rows = amortisingRows(
      start,
      spec.notional,
      spec.noteRate,
      spec.termMonths,
      market.prepayVector(spec.prepayCurve),
      market.conventions
    )
    rows.collect {
      case row if row.date.isAfter(market.asOf) =>
        row.cash * curve.df(market.yearFraction(market.asOf, row.date))
    }.sum
  }

  private def mbsPrice(spec: MbsPoolSpec, market: MarketData, start: LocalDate): Double = {
    val curve = market.combinedCurve(spec.discountCurve, spec.creditCurve)
    val rows = amortisingRows(
      start,
      spec.notional,
      spec.wac,
      spec.wamMonths,
      market.prepayVector(spec.prepayCurve),
      market.conventions
    )
    rows.collect {
      case row if row.date.isAfter(market.asOf) =>
        row.cash * curve.df(market.yearFraction(market.asOf, row.date))
    }.sum
  }

  /**
    * Interest-rate swap: fixed-leg PV minus floating-leg PV. The floating leg is
    * `N·(1 − df(settlement))` — the single-curve par-floating value assuming the
    * next reset is the valuation date (no historical fixings are modelled).
    */
  private def swapPrice(spec: SwapSpec, market: MarketData, start: LocalDate): Double = {
    val curve = market.combinedCurve(spec.discountCurve, spec.creditCurve)
    val fixedPv = swapFixedCashFlows(spec, market, start).collect {
      case (date, cash) if date.isAfter(market.asOf) =>
        cash * curve.df(market.yearFraction(market.asOf, date))
    }.sum
    val maturity = settlementDate(spec, market, Some(start))
    val floatingPv = spec.notional * (1.0 - curve.df(market.yearFraction(market.asOf, maturity)))
    fixedPv - floatingPv
  }

  /** Interest-rate cap: a strip of caplets priced with Black (Black-76). */
  private def capPrice(spec: CapSpec, market: MarketData, start: LocalDate): Double = {
    val conventions = market.conventions
    val curve = market.combinedCurve(spec.discountCurve, spec.creditCurve)
    val volSurface = market.volSurface(spec.volSurface)
    val periods = spec.maturityYears * spec.freq
    val dates = Schedule.dates(start, periods, InstrumentTiming.monthsPerPeriod(spec), conventions)
    var previous = start
    var pv = 0.0
    dates.foreach { paymentDate =>
      if (paymentDate.isAfter(market.asOf)) {
        // A period that started before the valuation date is priced from the
        // valuation date onward (no historical fixing is available).
        val resetDate = if (previous.isBefore(market.asOf)) market.asOf else previous
        val accrual = conventions.yearFraction(resetDate, paymentDate)
        if (accrual > 0.0) {
          val paymentTime = conventions.yearFraction(market.asOf, paymentDate)
          val expiryTime = conventions.yearFraction(market.asOf, resetDate)
          val forwardRate = (1.0 / curve.df(paymentTime) - 1.0) / accrual
          val volatility = math.max(volSurface.volatility(expiryTime, spec.strike), 0.0)
          val caplet = blackCall(forwardRate, spec.strike, volatility, math.max(expiryTime, 1e-9))
          pv += spec.notional * accrual * caplet * curve.df(paymentTime)
        }
      }
      previous = paymentDate
    }
    pv
  }

  /** Swaption: an option to enter a swap, priced with Black (Black-76). */
  private def swaptionPrice(spec: SwaptionSpec, market: MarketData, start: LocalDate): Double = {
    val conventions = market.conventions
    val curve = market.combinedCurve(spec.discountCurve, spec.creditCurve)
    val volSurface = market.volSurface(spec.volSurface)
    val expiry = conventions.adjust(start.plusMonths(math.round(spec.expiryYears * 12.0)))
    if (!market.asOf.isBefore(expiry)) 0.0
    else {
      val expiryTime = conventions.yearFraction(market.asOf, expiry)
      val periods = spec.swapMaturityYears * spec.freq
      val dates = Schedule.dates(expiry, periods, InstrumentTiming.monthsPerPeriod(spec), conventions)
      var previous = expiry
      var annuity = 0.0
      dates.foreach { date =>
        val accrual = conventions.yearFraction(previous, date)
        annuity += accrual * curve.df(conventions.yearFraction(market.asOf, date))
        previous = date
      }
      // Forward swap rate: the fixed rate that makes the swap value zero.
      val forwardSwap =
        (curve.df(expiryTime) - curve.df(conventions.yearFraction(market.asOf, dates.last))) / annuity
      val volatility = math.max(volSurface.volatility(expiryTime, spec.strike), 0.0)
      val payoff =
        if (spec.isPayer) blackCall(forwardSwap, spec.strike, volatility, expiryTime)
        else blackPut(forwardSwap, spec.strike, volatility, expiryTime)
      spec.notional * annuity * payoff
    }
  }

  /**
    * FX forward, priced by covered interest parity:
    * `notional · (spot · dfForeign - contractedRate · dfDomestic)`.
    */
  private def fxForwardPrice(spec: FxForwardSpec, market: MarketData, start: LocalDate): Double = {
    val conventions = market.conventions
    val deliveryDate = conventions.adjust(start.plusMonths(math.round(spec.maturityYears * 12.0)))
    if (!market.asOf.isBefore(deliveryDate)) 0.0
    else {
      val timeToDelivery = conventions.yearFraction(market.asOf, deliveryDate)
      val domesticDiscount = market.curve(spec.domesticCurve).df(timeToDelivery)
      val foreignDiscount = market.curve(spec.foreignCurve).df(timeToDelivery)
      val spotFx = market.fxSpot(spec.fxSpot).value
      spec.notional * (spotFx * foreignDiscount - spec.fxRate * domesticDiscount)
    }
  }

  // ---------------------------------------------------------------------------
  // Black-76 helpers
  // ---------------------------------------------------------------------------

  /** A per-call normal distribution: the shared singleton was not thread-safe. */
  private def blackCall(forward: Double, strike: Double, volatility: Double, timeToExpiry: Double): Double = {
    if (forward <= 0.0 || strike <= 0.0) math.max(forward - strike, 0.0)
    else if (volatility <= 0.0 || timeToExpiry <= 0.0) math.max(forward - strike, 0.0)
    else {
      val standardNormal = new NormalDistribution()
      val sqrtT = math.sqrt(timeToExpiry)
      val d1 = (math.log(forward / strike) + 0.5 * volatility * volatility * timeToExpiry) / (volatility * sqrtT)
      val d2 = d1 - volatility * sqrtT
      forward * standardNormal.cumulativeProbability(d1) - strike * standardNormal.cumulativeProbability(d2)
    }
  }

  private def blackPut(forward: Double, strike: Double, volatility: Double, timeToExpiry: Double): Double = {
    if (forward <= 0.0 || strike <= 0.0) math.max(strike - forward, 0.0)
    else blackCall(forward, strike, volatility, timeToExpiry) - forward + strike
  }

  private def clamp(value: Double, min: Double, max: Double): Double =
    math.max(min, math.min(max, value))
}
