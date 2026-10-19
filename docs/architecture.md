# WrithLang Architecture

This document explains the internal design of the WrithLang compiler and execution engine. It complements [README.md](../README.md), [dsl-reference.md](dsl-reference.md) (grammar and market data), and `low-level-design.md` (types and formulas).

## 1. Overview

WrithLang turns a textual DSL (a recursive portfolio of trades plus shocks) and a separate JSON market-data snapshot into numeric results and a visualization. There are two pipelines, sharing the same compiler and executor:

```
Single snapshot                              Two dated snapshots
──────────────                               ────────────────────
DSL (.dsl)      Market data (JSON)           DSL_prev + market_prev   DSL_curr + market_curr
   │ Parser        │ MarketDataJson              │                        │
   ▼               ▼                             ▼                        ▼
dsl.Program ── marketdata.MarketData         portfolio.TradePopulation   portfolio.TradePopulation
   │                                               └──────► PopulationDiff ◄──────┘
   │ engine.DslCompiler.build                     │            (events)
   ▼                                              │ engine.DslCompiler.buildPeriodPnl
engine.Dag (Instruction values)                   ▼
   │ engine.Executor.run (ZIO, level-parallel)  engine.Dag (values + roll-ups + pnl buckets)
   ▼                                              │ engine.Executor.run
Map[String, Double]                               ▼
   │ render.DotRenderer.toDot                 Map[String, Double] → period report
   ▼
Graphviz DOT
```

Market data is **external**: the DSL never contains quotes. Instruments and shocks reference market objects by `RiskFactorKey`; the quotes, surfaces, vectors and spots come from the JSON snapshot.

There are **two independent compiler systems**:

1. **The risk DSL + engine** (`dsl`, `engine`, `risk`, `marketdata`, `scenario`, `time`, `portfolio`, `render`) — the primary, tested system described here.
2. **The native compiler stub** (`compiler`, `interpreter`, `examples`) — a toy `let`/`print` → x86-64 GAS backend; see [§12](#12-native-compiler-stub).

## 2. Modules

| Module | Type | Responsibility |
| ------ | ---- | -------------- |
| `lib`  | library | DSL parser/AST, DAG builder, bootstrapper, pricing, executor, portfolio layer, renderer, native compiler stub. |
| `app`  | application | ZIO CLI (`com.writhlang.app.Main`); depends on `lib`. |

## 3. Time is date-derived

Every time in the engine is a **year fraction measured from a reference date**, produced by `com.writhlang.time`:

- `TimeConventions(dayCount, calendar, businessDayConvention)` — carried by `MarketData`, so a snapshot is self-describing. Default: Actual/365F, weekends-only calendar, Unadjusted.
- Curve pillar dates: `Tenor.resolve(asOf)` (whole years by year, otherwise by whole months), then `yearFraction(asOf, pillarDate)`.
- Cashflow dates: `Schedule.dates(start, periods, monthsPerPeriod, conventions)` — whole months from the anchor (calendar roll-over, e.g. 2024-01-31 + 1M = 2024-02-29), each rolled onto a business day.
- Accrual of a period: `yearFraction(previousDate, nextDate)` between the actual (adjusted) dates.
- Accrual interest: coupon rate × notional × day count since the last payment (`Pricing.accrued`).
- Settlement: `settlementDate` = adjusted contractual maturity. **On or after it a trade is worth zero** and its final cashflow is cash, not value.

Nothing shifts a tenor by "years elapsed": a value is a function of the valuation date and the trade's start date. That is what makes a leap year, a quarter-end or a seasoned trade honest.

## 4. Package walkthrough (`com.writhlang`)

| Package | Key files | Role |
| --- | --- | --- |
| `dsl` | `Ast.scala`, `Parser.scala`, `InstrumentTiming.scala` | AST, FastParse parser + validation, terms → dates. |
| `engine` | `DslCompiler.scala` | Translates `Program` + `MarketData` into a `Dag`; also the period P&L compiler. |
| | `Instruction.scala`, `Dag.scala` | Computation-node and graph model. |
| | `Executor.scala` | ZIO-based topological, level-parallel execution. |
| | `Pricing.scala` | Schedule-driven closed-form pricing, accrual and cash. |
| `risk` | `KeyType.scala`, `RiskFactorKey.scala`, `ShiftType.scala` | Risk-factor taxonomy, canonical keys, shift types, `Tenor.resolve`. |
| `marketdata` | `Curve.scala`, `MarketData.scala`, `VolSurface.scala`, `PrepayVector.scala`, `SpotQuote.scala`, `CorrelationMatrix.scala` | Market-data objects + the dated snapshot (including `conventions`). |
| | `DiscountCurveInterpolator.scala` | Log-linear discount-factor interpolation (flat forward). |
| | `bootstrap/` (`ParInstrument`, `QuoteSet`, `Bootstrapper`) | Curve bootstrapping at resolved pillar dates. |
| | `MarketDataJson.scala` | JSON loader (ujson). |
| `scenario` | `ShiftShape.scala`, `Scenario.scala`, `ScenarioGenerator.scala` | Shock shapes and scenario transformations. |
| | `ShiftScheme.scala` | `ShiftScheme` + `SensitivityConfig` (including the theta horizon and date conventions). |
| | `par/CurveShiftParData.scala` | Pillar → par-instrument mapping. |
| `time` | `DayCount.scala`, `Calendar.scala` | Day counts, calendar, business-day conventions, `ThetaPeriod`. |
| | `Schedule.scala`, `TimeConventions.scala` | Cashflow date generation and the combined conventions value. |
| `portfolio` | `Trade.scala` | Trade identity, dated trade instance, population, lifecycle state. |
| | `PopulationDiff.scala` | Unchanged / amended / new / cancelled / expired classification. |
| | `FxConversion.scala` | Translation to a reporting currency. |
| `render` | `DotRenderer.scala` | Graphviz DOT emission. |
| `compiler` | `Compiler.scala` + subpackages | Native x86-64 GAS backend (stub). |
| `interpreter` | `Interpreter.scala` | Thin wrapper around `Compiler`. |
| `examples` | `VariableAssignment.scala` | Demo of the native compiler. |

## 5. DSL AST

`Parser.parseProgram` returns `Either[String, Program]`. A `Program` is a list of shocks plus a recursive tree of `Portfolio(name, children)` and `InstrumentLeaf(spec, tradeDate, currency)`.

Instrument specs carry trade terms and reference market objects by `RiskFactorKey`. A trade leaf additionally carries its **own start date** (what makes it age) and an optional **currency** (what makes it translatable). `Program.trades` flattens the tree to `(terms, tradeDate, currency, book path)`.

## 6. Engine: the computation DAG

### 6.1 Instruction model

`Instruction(id, op, deps)`; `Op` is a sealed trait:

| Op | Meaning |
| --- | --- |
| `Const(v)` | literal shock value (leaf) |
| `Price(spec, market, tradeDate)` | value at the snapshot's valuation date for a trade started on `tradeDate` (leaf) |
| `Theta(baseId, thetaEvalId)` | the value at the advanced valuation date minus the base |
| `Carry(spec, from, to, tradeDate)` | cash in the window plus the change in accrued interest (single-snapshot theta reporting) |
| `Accrued(spec, market, tradeDate)` | accrued interest at a date (leaf) |
| `CashFlow(spec, market, from, to, tradeDate)` | contractual cash in `(from, to]` (leaf) |
| `Sum` | roll-up: the sum of its dependencies |
| `Combine(terms)` | signed sum `Σ coefficient × dependency`, i.e. a P&L bucket definition |
| `DeltaCentral` / `DeltaForward` / `DeltaBackward` | first-order sensitivity (`(up−down)/2h`, `(up−base)/h`, `(base−down)/h`) |
| `Gamma` | second-order `(up−2·base+down)/h²` (always central) |
| `CrossGamma` | mixed second-order |
| `LinearScenario` / `QuadraticScenario` | Taylor scenario prices |

### 6.2 Trade identity and node ids

Every node id is keyed by the **trade id**. Duplicate trade ids are rejected by the compiler — the DAG is a `Map`, so a duplicate would silently overwrite another trade's nodes.

| Pattern | Meaning |
| --- | --- |
| `shock:<name>:<key>` | shock literal |
| `price:base:<trade>` | base value |
| `price:bump:<trade>:<key>:<up\|down>` | one-factor bumped value |
| `price:cross:<trade>:<k1>:<k2>` | two-factor bumped value |
| `greek:delta:<trade>:<key>` / `greek:gamma:<trade>:<key>` | Greeks |
| `greek:cross:<trade>:<k1>:<k2>` | cross-gamma |
| `price:linear:<trade>:<shock>` / `price:quad:<trade>:<shock>` / `price:full:<trade>:<shock>` | scenario values |
| `price:thetaeval:<trade>` / `price:theta:<trade>` / `price:carry:<trade>` | theta evaluation, theta, carry |
| `agg:<metric>:TOTAL` / `agg:<metric>:<path>` | roll-up of a metric for the whole book / for a book path |
| `pnl:price:<label>:<trade>` / `pnl:accrued:<label>:<trade>` / `pnl:cash:<trade>` | period P&L inputs |
| `pnl:<bucket>:<trade>` / `pnl:total:<trade>` | period P&L buckets and trade total |

### 6.3 Roll-up

For every reportable metric — base price, each Greek per factor key, each scenario value, theta, carry, and each P&L bucket — the compiler emits one `Sum` node per book path and one for the whole book. Because every such metric is additive across trades at a fixed market state, book figures are exactly the sum of trade figures, and a sum of deltas is a **netted** sensitivity (opposing positions cancel).

### 6.4 Execution

`Dag.levels` does a Kahn-style topological sort; `Executor.run` processes levels sequentially and evaluates each level's nodes in parallel (`ZIO.foreachPar`), accumulating into a `Ref[Map[String, Double]]`.

## 7. Market data & bootstrap

`MarketData(asOf, conventions, curves, curveQuotes, volSurfaces, prepayVectors, fxSpots, correlations)` holds the snapshot; `curveQuotes` is the *input* and `curves` the *derived* result.

`Bootstrapper.bootstrap(quotes, asOf, conventions)` walks instruments in maturity order, resolving each tenor to a date and solving one discount factor per instrument:

- deposit: `df(T) = e^(-rate · yf(asOf, T))`;
- future: `df(end) = df(start) · e^(-rate · yf(start, end))`;
- swap: the par equation with each period accruing between its actual coupon dates.

Interpolation is log-linear in discount factors (`DiscountCurveInterpolator`), i.e. flat forward between pillar year fractions. Non-positive or non-decreasing discount factors are rejected.

## 8. Pricing model notes

All prices are closed-form present values of date-scheduled cashflows, discounted at the day-count year fraction from the valuation date. Each instrument resolves its market objects from its key references, and a shock transforms the snapshot (quotes → re-bootstrap), never a scalar inside `Pricing`. A trade past its settlement date is worth zero.

| Instrument | Model |
| --- | --- |
| `bond` | coupon × accrual each period + principal, discounted at each payment date |
| `mortgage` / `mbs` | monthly amortisation (annuity payment, CPR → SMM prepayment), simulated from the trade date |
| `swap` | fixed-leg PV − `N·(1 − df(settlement))` floating leg |
| `cap` | strip of Black-76 caplets on schedule periods |
| `swaption` | Black-76 on the forward swap rate, scaled by the annuity |
| `fxforward` | covered-interest-parity forward value at the delivery date |

## 9. Portfolio layer

A **trade** is `TradeInstance(id, spec, tradeDate, path, currency)`. Its lifecycle state at a date is `Active` before the adjusted settlement date and `Expired` on/after it.

`TradePopulation.fromProgram(program, market)` builds the book at a snapshot, rejecting duplicate ids and defaulting a missing `tradeDate` to the valuation date. `PopulationDiff.between(prev, curr)` classifies each id exactly once:

| Present at `t0` | Present at `t1` | Event |
| --- | --- | --- |
| yes, terms equal | yes | `Unchanged` |
| yes, terms differ | yes | `Amended` |
| no | yes | `NewTrade` |
| yes, still alive | no | `Cancelled` |
| yes | gone or present, matured by `t1` | `Expired` |

Rejected rather than guessed: a backwards period; a `tradeDate` or `currency` change between snapshots; a trade already settled at the earlier date.

A **currency** on a trade is translated to a reporting currency by `portfolio.FxConversion`, using the FX spots in the snapshot: a direct pair `FxSpot:<from><to>` is used when present, otherwise the inverse is inverted; a missing rate is an error naming both pairs. Translation applies to *levels* at the closing spot — the period decomposition therefore contains no FX bucket (FX P&L is Stage 6).

## 10. Period P&L

`DslCompiler.buildPeriodPnl((prevProgram, prevMarket), (currProgram, currMarket), config, reportingCurrency)` requires an explicit `tradeDate` on every trade, diffs the populations, and emits the bucket nodes. Market and theta use **clean** values so accrual is counted once, in carry:

| Event | Buckets |
| --- | --- |
| unchanged | `market` = clean(t1,m1) − clean(t1,m0); `theta` = clean(t1,m0) − clean(t0,m0); `carry` = Δaccrued + cash paid |
| amended | the same on the amended terms, plus `inception` = dirty(new,t0) − dirty(old,t0) |
| new | `dirty(t1,m1) + cash` |
| cancelled | `−dirty(t0,m0)` |
| expired | `cash − dirty(t0,m0)` (+ `inception` if amended before maturing) |

The identity, which holds exactly because each bucket telescopes:

```
Σ buckets = Σ_{trades at t1} dirty(t1, m1) + Σ cash(t0, t1] − Σ_{trades at t0} dirty(t0, m0)
```

These are **book-value** numbers: discretionary cash (unwind prices, entry prices, amendment fees) is not in the data and is not claimed. Expiry is contractual, so its settling cashflow is known.

## 11. Rendering

`DotRenderer.toDot(dag)` emits a left-to-right `digraph`, coloured by `Op` type, clustered per trade, with `agg:` and `pnl:` nodes labelled. The CLI writes the DOT file and, if Graphviz `dot` is on `PATH`, renders a PNG.

## 12. Native compiler stub

A separate, early-stage path under `com.writhlang.compiler` compiles a tiny `let`/`print` language to x86-64 GAS assembly. It is **not** wired into the CLI and has no tests.

## 13. Error handling

- **Parsing/validation** — `Parser.parseProgram` returns `Either[String, Program]`; frequencies that cannot form a monthly schedule are rejected.
- **Market data** — `MarketDataJson.parse` and `Bootstrapper.bootstrap` return `Either[String, _]`.
- **Compilation** — `DslCompiler.build` / `buildPeriodPnl` return `Either[String, _]` for duplicate ids, mixed currencies without a reporting currency, and missing `tradeDate` in a period run.
- **Population** — `TradePopulation.fromProgram` and `PopulationDiff.between` return `Either[String, _]`.
- **Execution** — `Executor.run` returns `IO[ExecutionError, Map[String, Double]]`.
- **CLI** — `Main` maps failures to human-readable messages.
