# WrithLang Low-Level Design

This document is the **low-level design reference**: the public API (types, signatures), the shapes of data flowing between components, and — for software engineers with no finance background — the plain-language meaning of every pricing concept and formula. It complements [architecture.md](architecture.md) (the pipeline) and [dsl-reference.md](dsl-reference.md) (the grammar). Finance terms are defined in place or in the glossary in [AGENTS.md](../AGENTS.md).

## 1. Conventions

- **Rates are decimals, not percentages.** `0.04` means 4%; `0.0001` is one basis point.
- **Time is date-derived.** Every `t` in `e^(-r·t)` is a year fraction from a reference date under a `DayCount`, never a nominal tenor count.
- **Time bases are explicit in names.** `maturityYears`/`expiryYears` are years; `termMonths`/`wamMonths` are months; dates are `LocalDate`.
- **Effect separation.** Pure functions return `Either[String, A]`; the executor is effectful (`zio.IO`).
- **Sign convention.** A positive shock moves a factor up. Long positions are positive notional; a short is a negative notional, which nets in the roll-up.
- **A curve is derived, never stored as quotes.** Market data holds *quotes*; a `Curve` is bootstrapped from them.
- **Money is in the trade's currency.** Translation to a reporting currency happens only in the portfolio layer.

## 2. Pipeline at a glance

| Stage | Component | Input | Output |
| --- | --- | --- | --- |
| Parse market data | `marketdata.MarketDataJson.parse` | `String` (JSON), optional `TimeConventions` | `Either[String, MarketData]` |
| Parse DSL | `dsl.Parser.parseProgram` | `String` | `Either[String, dsl.Program]` |
| Build populations | `portfolio.TradePopulation.fromProgram` | `Program`, `MarketData` | `Either[String, TradePopulation]` |
| Diff populations | `portfolio.PopulationDiff.between` | two populations | `Either[String, List[PopulationEvent]]` |
| Compile (single date) | `engine.DslCompiler.build` | `Program`, `MarketData`, `SensitivityConfig` | `Either[String, Dag]` |
| Compile (period) | `engine.DslCompiler.buildPeriodPnl` | two `(Program, MarketData)` pairs, config, reporting currency | `Either[String, PeriodPnl]` |
| Execute | `engine.Executor.run` | `Dag` | `zio.IO[ExecutionError, Map[String, Double]]` |
| Render | `render.DotRenderer.toDot` | `Dag` | `String` (Graphviz DOT) |

## 3. `com.writhlang.dsl` — AST and parser

Source: `dsl/Ast.scala`, `dsl/Parser.scala`, `dsl/InstrumentTiming.scala`.

### 3.1 Instrument terms

```scala
sealed trait InstrumentSpec { def id: String }

case class BondSpec(id, notional, coupon, maturityYears: Int,
                    discountCurve: RiskFactorKey, creditCurve: RiskFactorKey, couponFreq: Int)
case class MortgageSpec(id, notional, noteRate, termMonths: Int,
                        discountCurve, creditCurve, prepayCurve: RiskFactorKey)
case class MbsPoolSpec(id, notional, wac, wamMonths: Int,
                       discountCurve, creditCurve, prepayCurve: RiskFactorKey)
case class SwapSpec(id, notional, fixedRate, maturityYears: Int,
                    discountCurve, creditCurve, freq: Int)
case class CapSpec(id, notional, strike, maturityYears: Int, freq: Int,
                   discountCurve, creditCurve, volSurface: RiskFactorKey)
case class SwaptionSpec(id, notional, strike, expiryYears: Double, swapMaturityYears: Int, freq: Int,
                        isPayer: Boolean, discountCurve, creditCurve, volSurface: RiskFactorKey)
case class FxForwardSpec(id, notional, fxRate, maturityYears: Double,
                         domesticCurve, foreignCurve, fxSpot: RiskFactorKey)
```

`notional`, `coupon`, `noteRate`, `fixedRate`, `strike`, `fxRate` are **trade terms** (contractual). The `RiskFactorKey` fields are **market references** — the instrument says *which* market object to price with; the object's value comes from `MarketData`.

A payment `freq` must divide 12 (1, 2, 3, 4, 6, 12): cashflow schedules are generated in whole months.

### 3.2 Trades and portfolios

```scala
final case class Currency(code: String)   // three uppercase letters

sealed trait PortfolioNode
case class InstrumentLeaf(spec: InstrumentSpec,
                          tradeDate: Option[LocalDate] = None,
                          currency: Option[Currency] = None) extends PortfolioNode
case class Portfolio(name: String, children: List[PortfolioNode]) extends PortfolioNode

case class Program(shocks: List[Shock], nodes: List[PortfolioNode])

object Program {
  /** `(terms, trade date, currency, book path)` in document order. */
  def trades(nodes: List[PortfolioNode]): List[(InstrumentSpec, Option[LocalDate], Option[Currency], List[String])]
}
```

`tradeDate` anchors the trade's schedules. Absent, the trade is treated as entered on the snapshot's valuation date — valid for a single snapshot, rejected in a period run (an implicit date would re-anchor the trade at each snapshot and it would never age).

`currency` names the trade's currency; absent means the reporting currency.

### 3.3 Shocks

```scala
sealed trait CurveShift
case object NoCurveShift; case class FlatShift(amount); case class BucketShift(tenorYears, amount)
case class TwistShift(shortAmount, longAmount, pivotYears); case class SineShift(amplitude, omega, phase)

sealed trait ShockMove
case class PointMove(key: RiskFactorKey, shiftType: ShiftType, amount: Double) extends ShockMove
case class ShapeMove(key: RiskFactorKey, shape: CurveShift) extends ShockMove

case class Shock(name: String, moves: List[ShockMove])
```

### 3.4 Parser and timing

```scala
object Parser { def parseProgram(input: String): Either[String, Program] }
```

`parseProgram` parses the DSL (FastParse) and validates every instrument, aggregating errors into a `; `-separated `Left`.

```scala
object InstrumentTiming {
  def monthsPerPeriod(instrument: InstrumentSpec): Int   // payment period in months (mortgage/MBS: 1)
  def periods(instrument: InstrumentSpec): Int           // number of periods to maturity
  def maturityDate(instrument: InstrumentSpec, start: LocalDate): LocalDate
  def swapMaturityDate(spec: SwaptionSpec, expiry: LocalDate): LocalDate
  def hasMatured(instrument: InstrumentSpec, start: LocalDate, asOf: LocalDate): Boolean
}
```

`maturityDate` is a calendar fact: bond/swap/cap = start + maturity years; mortgage/MBS = start + term/wam months; swaption = **option expiry** (start + expiry months); fx forward = delivery date. The swaption's swap maturity is measured from expiry.

## 4. `com.writhlang.risk` — taxonomy

```scala
sealed trait KeyType { def name: String }        // DiscountCurve, CreditCurve, FxSpot, SwaptionVolatility, Prepay, …
final case class Tenor(years: Double) {
  def canonical: String                          // "5Y"
  def resolve(asOf: LocalDate): LocalDate        // whole years by year, otherwise by whole months
}
final case class RiskFactorKey(keyType, name, tenor, expiry, swapTenor, strike, lossLevel) {
  def canonical: String                          // "DiscountCurve:EUR:2Y"
  def withoutTenor: RiskFactorKey
}
object RiskFactorKey { def parse(canonical: String): Either[String, RiskFactorKey] }

sealed trait ShiftType { def name: String }       // Absolute | Relative
object ShiftType { def defaultFor(keyType: KeyType): ShiftType }
```

Two keys are equal iff their `canonical` strings are equal, so they are safe as map keys and in DAG node ids.

## 5. `com.writhlang.time` — dates, schedules, conventions

```scala
sealed trait DayCount { def name; def yearFraction(from: LocalDate, to: LocalDate): Double }
// Actual365Fixed (default), Actual360, Thirty360

trait Calendar { def isBusinessDay(date); def addBusinessDays(date, days) }
object Calendar { val weekend: Calendar }

sealed trait BusinessDayConvention { def name; def adjust(date, calendar) }
// Following, ModifiedFollowing, Preceding, Unadjusted (default)

final case class ThetaPeriod(days: Int) { def elapsedYears(asOf, calendar, dayCount): Double }

final case class TimeConventions(dayCount, calendar, businessDayConvention) {
  def yearFraction(from, to): Double
  def adjust(date): LocalDate
  def advanceBusinessDays(date, days): LocalDate
}
object TimeConventions { val default: TimeConventions }

object Schedule {
  def monthsPerPeriod(freq: Int): Either[String, Int]
  def dates(start: LocalDate, periods: Int, monthsPerPeriod: Int, conventions: TimeConventions): Vector[LocalDate]
}
```

`Schedule.dates` adds whole months to the anchor and rolls each date onto a business day, so a coupon schedule is "same day-of-month every period" with calendar month-end roll-over (`2024-01-31` + 1M = `2024-02-29`). Accrual is always the day-count fraction between two actual (adjusted) dates.

## 6. `com.writhlang.marketdata` — market-data objects

### 6.1 `Curve`

```scala
trait Curve {
  def rateAt(tenorYears: Double): Double                 // annual zero rate (decimal)
  def df(tenorYears: Double): Double = math.exp(-rateAt(tenorYears) * tenorYears)
  def shifted(delta: Double => Double): Curve
  def plus(other: Curve): Curve                          // pointwise rate addition
}
object Curve { def flat(rate: Double): Curve; def function(f: Double => Double): Curve }
final case class BootstrappedCurve(pillars: Vector[(Double, Double)]) extends Curve
```

`BootstrappedCurve` holds `(yearFractionFromAsOf, discountFactor)` pillars; interpolation is log-linear in DF (flat forward) by `DiscountCurveInterpolator.df(pillars, yearFraction)`.

### 6.2 `MarketData`

```scala
final case class MarketData(
  asOf: LocalDate = LocalDate.of(2024, 1, 1),
  conventions: TimeConventions = TimeConventions.default,
  curves: Map[RiskFactorKey, Curve] = Map.empty,
  curveQuotes: Map[RiskFactorKey, QuoteSet] = Map.empty,
  volSurfaces: Map[RiskFactorKey, VolSurface] = Map.empty,
  prepayVectors: Map[RiskFactorKey, PrepayVector] = Map.empty,
  fxSpots: Map[RiskFactorKey, SpotQuote] = Map.empty,
  correlations: Map[RiskFactorKey, CorrelationMatrix] = Map.empty
) {
  def withAsOf(date): MarketData
  def curve(key); def quotes(key); def volSurface(key); def prepayVector(key); def fxSpot(key)
  def combinedCurve(discountKey, creditKey): Curve        // discount.plus(credit)
  def yearFraction(from, to): Double
  def withCurve / withCurveAndQuotes / withVolSurface / withPrepayVector / withFxSpot / withCorrelation
}
```

`curveQuotes` is the *input* (par instruments); `curves` the *derived* result. A shock transforms the quotes and re-bootstraps. `combinedCurve` adds the credit curve to the discount curve pointwise in **zero rates**: `r_eff(t) = r_discount(t) + r_credit(t)`, hence `df_eff(t) = df_discount(t) · e^(-r_credit(t)·t)` — a credit curve is a **spread over the base**, not a standalone risky curve.

### 6.3 Other objects

```scala
final case class VolSurface(volAt: (Double, Double) => Double) { def volatility(expiry, strike); def shifted(delta) }
object VolSurface { def flat(volatility: Double): VolSurface }

final case class PrepayVector(values: Vector[Double]) { def months; def cpr(monthIndex); def shifted(delta) }
object PrepayVector { def constant(cpr, months); def fromRamp(start, end, rampMonths, months) }

final case class SpotQuote(value: Double) { def shiftedRelative(amount); def shiftedAbsolute(amount) }

final case class CorrelationMatrix(labels: Vector[String], values: Vector[Vector[Double]]) { def apply(i, j) }
```

### 6.4 Bootstrap

```scala
sealed trait ParInstrument {
  def rate: Double; def tenorYears: Double; def maturityYears: Double
  def pillarDate(asOf: LocalDate): LocalDate
  def withRate(newRate: Double): ParInstrument
}
case class Deposit(tenor: Tenor, rate: Double) extends ParInstrument
case class Future(start: Tenor, end: Tenor, rate: Double) extends ParInstrument
case class Swap(tenor: Tenor, rate: Double, freq: Int = 1) extends ParInstrument

final case class QuoteSet(instruments: List[ParInstrument]) {
  def sorted: List[ParInstrument]
  def shifted(delta: Double => Double): QuoteSet
  def shiftOne(tenorYears: Double, amount: Double): QuoteSet
}

object Bootstrapper {
  def bootstrap(quotes: QuoteSet, asOf: LocalDate, conventions: TimeConventions = TimeConventions.default): Either[String, BootstrappedCurve]
  def bootstrapOrThrow(quotes: QuoteSet, asOf: LocalDate, conventions: TimeConventions = TimeConventions.default): BootstrappedCurve
}
```

Bootstrapping walks instruments in maturity order at their **resolved pillar dates**:

- deposit: `df(T) = e^(-rate · yf(asOf, T))`;
- future: `df(end) = df(start) · e^(-rate · yf(start, end))`, `df(start)` interpolated;
- swap: `df(T) = (1 − K·Σ_{i<N} accrualᵢ·df(tᵢ)) / (1 + K·accrual_N)`, with each `accrualᵢ` the day-count fraction between the swap's actual coupon dates.

Returns `Left` on a non-positive or non-decreasing discount factor.

### 6.5 `MarketDataJson`

```scala
object MarketDataJson { def parse(json: String, conventions: TimeConventions = TimeConventions.default): Either[String, MarketData] }
```

Loads the JSON schema documented in [dsl-reference.md](dsl-reference.md); curves are bootstrapped at the snapshot's `asOf` with the supplied conventions.

## 7. `com.writhlang.scenario` — shocks & sensitivities

```scala
sealed trait ShiftShape { def at(x: Double): Double }
case class Flat(amount); case class Bucket(tenor, amount); case class Twist(short, long, pivot)
case class Sine(amplitude, omega, phase); case class Custom(f: Double => Double)

sealed trait ScenarioShift
case class AdditiveShift(shape: ShiftShape) extends ScenarioShift
case class ParQuoteShift(tenorYears: Double, amount: Double) extends ScenarioShift
case class RelativeSpotShift(amount: Double) extends ScenarioShift
case class AbsoluteSpotShift(amount: Double) extends ScenarioShift

final case class Scenario(name: String, shifts: Map[RiskFactorKey, List[ScenarioShift]]) {
  def applyTo(market: MarketData): MarketData
}
```

`applyTo` folds the per-factor shifts: a quote-backed curve is shocked at its quotes and re-bootstrapped; a flat curve is shifted as a rate function; prepay/vol/spot are shifted in place.

```scala
sealed trait ShiftScheme; object ShiftScheme { case object Forward; case object Backward; case object Central }

final case class SensitivityConfig(
  shiftScheme: ShiftScheme = ShiftScheme.Central,
  bumpFor: RiskFactorKey => Double = SensitivityConfig.defaultBump,
  thetaPeriod: Option[ThetaPeriod] = Some(ThetaPeriod(1)),
  dayCount: DayCount = DayCount.Actual365Fixed,
  calendar: Calendar = Calendar.weekend,
  businessDayConvention: BusinessDayConvention = BusinessDayConvention.Unadjusted
) {
  def conventions: TimeConventions
  def thetaValuationDate(asOf: LocalDate): Option[LocalDate]   // advanced date, rolled to a business day
}
```

`defaultBump` is `1e-4` for rates/spreads (1bp), `1e-2` for prepay, `1e-3` for vol/fx. Theta is a **full revaluation at `thetaValuationDate`** on the frozen market, not a tenor shift.

```scala
trait ScenarioGenerator { def next(): Option[Scenario]; def reset(): Unit }
final class SensitivityScenarioGenerator(factors, bumpFor, shiftFor) extends ScenarioGenerator
final class ListScenarioGenerator(scenarios: List[Scenario]) extends ScenarioGenerator

final case class CurveShiftParData(curveKey: RiskFactorKey, parTenorYears: Double)
```

## 8. `com.writhlang.portfolio` — trades, populations, currency

```scala
final case class TradeId(value: String)

final case class TradeInstance(id: TradeId, spec: InstrumentSpec, tradeDate: LocalDate,
                               path: List[String], currency: Option[Currency] = None) {
  def settlementDate(conventions: TimeConventions): LocalDate
  def stateAt(asOf: LocalDate, conventions: TimeConventions): TradeState
  def bookPath: String
}

sealed trait TradeState; object TradeState { case object Active; case object Expired }

final case class TradePopulation(asOf: LocalDate, conventions: TimeConventions, trades: Map[TradeId, TradeInstance]) {
  def trade(id); def contains(id); def stateOf(trade)
  def activeTrades; def expiredTrades
}
object TradePopulation {
  def fromProgram(program: Program, market: MarketData): Either[String, TradePopulation]
}

sealed trait PopulationEvent
case class Unchanged(trade: TradeInstance)
case class Amended(prev: TradeInstance, curr: TradeInstance)
case class NewTrade(curr: TradeInstance)
case class Cancelled(prev: TradeInstance)
case class Expired(prev: TradeInstance, curr: Option[TradeInstance])

object PopulationDiff {
  def between(prev: TradePopulation, curr: TradePopulation): Either[String, List[PopulationEvent]]
  def bucketOf(event: PopulationEvent): String
}
```

`fromProgram` rejects duplicate trade ids and defaults a missing `tradeDate` to the valuation date. `between` classifies each id once (rules in [architecture.md §9](architecture.md#9-portfolio-layer)) and returns `Left` for a backwards period, a `tradeDate` or `currency` change, or a trade already settled at the earlier date.

```scala
object FxConversion {
  def factor(tradeCurrency: Option[Currency], reportingCurrency: Option[Currency], market: MarketData): Either[String, Double]
  def rate(from: Currency, to: Currency, market: MarketData): Either[String, Double]
}
```

Pair convention: `FxSpot:EURUSD = 1.25` means 1 EUR buys 1.25 USD. A direct pair `<from><to>` is used when present, otherwise `<to><from>` is inverted. A missing rate is a `Left` naming both candidate pairs; a zero quote is rejected.

## 9. `com.writhlang.engine`

### 9.1 Instruction model

```scala
sealed trait Op
case class Const(value: Double)
case class Price(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None)
case class Theta(baseId: String, thetaEvalId: String)
case class Carry(instrument: InstrumentSpec, from: MarketData, to: MarketData, tradeDate: Option[LocalDate] = None)
case class Accrued(instrument: InstrumentSpec, market: MarketData, tradeDate: Option[LocalDate] = None)
case class CashFlow(instrument: InstrumentSpec, market: MarketData, from: LocalDate, to: LocalDate, tradeDate: Option[LocalDate] = None)
case object Sum
case class Combine(terms: List[(String, Double)])           // Σ coefficient × dependency
case class DeltaCentral(baseId, upId, downId, bump)
case class DeltaForward(baseId, upId, bump)
case class DeltaBackward(baseId, downId, bump)
case class Gamma(baseId, upId, downId, bump)
case class CrossGamma(baseId, upIId, upJId, upIJId, bumpI, bumpJ)
case class LinearScenario(baseId, terms: List[(String, String)])
case class QuadraticScenario(baseId, linear, gamma, cross)
case class Instruction(id: String, op: Op, deps: List[String])
```

`Sum` is a roll-up; `Combine` defines a signed sum, which is how each P&L bucket is expressed. Signed sums keep the bucket definition visible in the DAG rather than buried in code.

### 9.2 `Dag`

```scala
case class Dag(nodes: Map[String, Instruction]) { def levels: Either[String, List[List[Instruction]]] }
```

### 9.3 `DslCompiler`

```scala
object DslCompiler {
  def build(program: Program, market: MarketData, config: SensitivityConfig = SensitivityConfig()): Either[String, Dag]
  def buildPeriodPnl(previous: (Program, MarketData), current: (Program, MarketData),
                     config: SensitivityConfig = SensitivityConfig(),
                     reportingCurrency: Option[Currency] = None): Either[String, PeriodPnl]

  final case class PeriodPnl(dag: Dag, from: LocalDate, to: LocalDate,
                             events: List[PopulationEvent], reportingCurrency: Option[Currency])

  def flattenInstruments(nodes: List[PortfolioNode]): List[(InstrumentSpec, List[String])]
  def factorsFor(instrument: InstrumentSpec, market: MarketData): List[RiskFactorKey]   // distinct
  def pnlBuckets(event: PopulationEvent): List[String]
  def aggregateId(metric: String, path: List[String]): String
  def totalAggregateId(metric: String): String
  def pnlId(bucketName: String, tradeId: String): String
  def pnlTotalId(tradeId: String): String
  def pnlMetric(bucketName: String): String
  // node-id helpers: shockFactorId, basePriceId, bumpedPriceId, deltaId, gammaId, …, thetaId, carryId
}
```

`build` emits base value, per-factor up/down bumped values + delta/gamma, cross gammas, scenario values, theta/carry, and the roll-up nodes. It rejects duplicate trade ids and a mixed-currency book (a single-snapshot build reports in trade currency).

`buildPeriodPnl` requires an explicit `tradeDate` on every trade and emits the P&L bucket nodes plus their roll-ups, translating each trade's buckets to the reporting currency at the **closing** spot.

### 9.4 `Executor`

```scala
object Executor {
  sealed trait ExecutionError { def message: String }
  case class MissingDependency(node, dep) extends ExecutionError
  case class GraphError(reason) extends ExecutionError
  def run(dag: Dag): zio.IO[ExecutionError, Map[String, Double]]
}
```

`Sum` and `Combine` are evaluated in dependency order (deterministic, left-to-right), so equal inputs always give bit-identical sums.

### 9.5 `Pricing`

```scala
object Pricing {
  def startDate(instrument, market, tradeDate: Option[LocalDate] = None): LocalDate
  def settlementDate(instrument, market, tradeDate: Option[LocalDate] = None): LocalDate
  def hasMatured(instrument, market, tradeDate: Option[LocalDate] = None): Boolean
  def price(instrument, market, tradeDate: Option[LocalDate] = None): Double
  def accrued(instrument, market, tradeDate: Option[LocalDate] = None): Double
  def cashIn(instrument, market, from: LocalDate, to: LocalDate, tradeDate: Option[LocalDate] = None): Double
  def carry(instrument, from: MarketData, to: MarketData, tradeDate: Option[LocalDate] = None): Double
}
```

Formulas (cashflows are dropped once their date is not after `asOf`; all values are 0 at/after settlement):

- **bond** — `Σ_i cf_i · df(yf(asOf, date_i))`, `cf_i = N·c·accrual_i`, plus `N` at maturity.
- **mortgage / mbs** — level-payment amortisation: annuity `P = N·(r/12)/(1 − (1+r/12)^−n)`, each month interest + scheduled principal + prepayment (`SMM = 1 − (1−CPR)^(1/12)` applied to the remaining balance), discounted at the month's date. Simulated from the trade date, so the balance is contractual.
- **swap** — `fixedPV − N·(1 − df(settlement))`; the floating leg is the single-curve par-floating value assuming the next reset is the valuation date.
- **cap** — strip of Black-76 caplets over the schedule: forward `= (1/df(pay) − 1)/accrual`, Black price, `× N·accrual·df(pay)`.
- **swaption** — expiry date from the trade; annuity over the swap schedule; forward swap rate `= (df(expiry) − df(swapEnd))/annuity`; Black call (payer) or put (receiver); `× N·annuity`.
- **fxforward** — covered interest parity at delivery: `N·(spot·dfForeign − contracted·dfDomestic)`.
- **accrued** — bonds: `N·c·yf(lastPaymentDate, asOf)`; mortgage/MBS: balance × note rate × `yf(lastPaymentDate, asOf)`; zero for options, FX forwards and a swap's floating leg.
- **cashIn** — contractual cash strictly inside `(from, to]`: bond coupons + principal, swap fixed coupons, mortgage/MBS monthly payments.
- **carry** — `cashIn(from, to] + accrued(to) − accrued(from)`, the cash-plus-accrual component of a value change.

## 10. Period P&L identity

With `dirty(s, m, t)` the value at date `t` and `clean = dirty − accrued`:

```
Σ buckets = Σ_{t1 book} dirty(t1, m1) + Σ cash(t0, t1] − Σ_{t0 book} dirty(t0, m0)
```

| Event | Buckets |
| --- | --- |
| unchanged | `market = clean(t1,m1) − clean(t1,m0)`; `theta = clean(t1,m0) − clean(t0,m0)`; `carry = Δaccrued + cash` |
| amended | the same on the amended terms, plus `inception = dirty(new,t0) − dirty(old,t0)` |
| new | `dirty(t1,m1) + cash` |
| cancelled | `−dirty(t0,m0)` |
| expired | `cash − dirty(t0,m0)` (+ `inception` when amended before maturing) |

Each row telescopes, so the identity holds with no residual and no approximation. These are **book-value** numbers: discretionary cash (unwind prices, entry prices, amendment fees) is absent from the data, so it is not claimed; contractual cash (coupons, amortisation, final settlement) is included.

## 11. `com.writhlang.render`

```scala
object DotRenderer { def toDot(dag: Dag): String }
```

Emits a left-to-right `digraph`, coloured by `Op` type and clustered per trade; roll-up nodes are labelled `Σ` and P&L nodes `pnl`.

## 12. `com.writhlang.app` — the CLI

```scala
object Main extends ZIOAppDefault {
  val defaultMarketJson: String
  val defaultDsl: String
}
```

Flags: `--input`, `--market`, `--dot`, `--png`, `--theta-days`, and for a period run `--prev-input`, `--prev-market`, `--reporting-currency`. Without `--prev-input` the CLI prints base values, theta, carry and scenario values per trade; with it, a per-trade bucket table, book totals and a reconciliation line.

## 13. Node-id reference

| Pattern | Meaning |
| --- | --- |
| `shock:<name>:<key>` | shock literal |
| `price:base:<trade>` | base value |
| `price:bump:<trade>:<key>:<dir>` | one-factor bumped value |
| `price:cross:<trade>:<k1>:<k2>` | two-factor bumped value |
| `greek:delta:<trade>:<key>` / `greek:gamma:<trade>:<key>` | delta / gamma |
| `greek:cross:<trade>:<k1>:<k2>` | cross-gamma |
| `price:linear:<trade>:<shock>` / `price:quad:<trade>:<shock>` / `price:full:<trade>:<shock>` | scenario values |
| `price:thetaeval:<trade>` / `price:theta:<trade>` / `price:carry:<trade>` | theta evaluation / theta / carry |
| `agg:<metric>:TOTAL` / `agg:<metric>:<path>` | roll-up (metric e.g. `price:base`, `greek:delta:<key>`, `pnl:market`) |
| `pnl:price:<label>:<trade>` / `pnl:accrued:<label>:<trade>` / `pnl:cash:<trade>` | period P&L inputs |
| `pnl:<bucket>:<trade>` / `pnl:total:<trade>` | period P&L buckets / trade total |

A canonical key may itself contain `:` (`DiscountCurve:EUR:2Y`), so ids are parsed positionally where needed.

## 14. Errors

| Layer | Shape | Examples |
| --- | --- | --- |
| Parser | `Either[String, Program]` | missing property, bad date, frequency not dividing 12, invalid currency |
| Market data | `Either[String, MarketData]` | malformed JSON, unknown curve type, bootstrap failure |
| Compilation | `Either[String, Dag]` / `Either[String, PeriodPnl]` | duplicate trade id, mixed currencies without a reporting currency, missing `tradeDate` in a period run |
| Population | `Either[String, _]` | duplicate id, backwards period, `tradeDate`/`currency` changed, settled at the earlier date |
| Execution | `IO[ExecutionError, _]` | missing dependency, cyclic graph |
