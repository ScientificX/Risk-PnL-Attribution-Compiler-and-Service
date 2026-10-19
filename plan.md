# WrithLang → P&L Explain / PLA Engine — Refactor Roadmap

## Purpose

This document turns the design analysis of WrithLang into a **staged refactor plan**.
Each stage is tied to a specific capability of a **P&L explain / PLA (P&L Attribution) engine**,
so we can close the gap between the current toy model and the **target state** (the capability
set of ORE for market data, scenarios, and sensitivities, plus the explain/PLA layer that even
ORE does not ship turnkey).

## Current state vs target state

| Dimension | Current (WrithLang) | Target (ORE parity + explain layer) |
| --- | --- | --- |
| Risk factors | 5 enums (`rate`, `spread`, `prepay`, `volatility`, `fx`), one flat `Double` each | `RiskFactorKey` taxonomy: type + name + tenor/strike/expiry |
| Market data | flat `DiscountCurve` + `List[CurveShift]` | dated curves/surfaces/vectors built from quoted instruments |
| Curve construction | none (flat rate + shift) | bootstrapping from deposits/futures/swaps, with par-conversion |
| Sensitivity | hard-coded central `Delta`/`Gamma`/`CrossGamma` on flat factors | forward/backward/central schemes, configurable gamma/cross, theta |
| Shock model | scalar added inside `Pricing.price` | transform the market *input object* (any shape), then re-derive |
| Scenarios | static DAG of `Instruction` nodes | scenario generators: sensitivity, historical, Monte Carlo, custom |
| Time dimension | none | theta / carry / rolldown, dated market snapshots |
| Trade lifecycle | none (fixed instruments) | new/amended/cancelled/expired trade population events |
| Portfolio | none | desk → book → entity aggregation with netting + additivity |
| XVA | none | CVA/DVA/FVA/ColVA/MVA/KVA |
| Explain output | none | decomposition identity + unexplained residual |
| Explain visualization | dependency-only DOT graph (no values, no contribution nodes) | P&L waterfall graph: per-level values + contribution nodes + residual (Stage 9) |
| Validation | none | HPL vs RTPL, mean-ratio / correlation / variance-ratio tests (PLA) |
| Performance | interpreted DAG over ZIO | cached sensitivity vector + dot-product fast path (primary); optional bytecode specialization |

## Core principles (established from the analysis)

1. **Shock the market *input*, not a scalar parameter inside the pricing function.**
   A factor's input is an object — a number, curve, surface, or vector — and a scenario is a
   transformation of that object. Re-derive everything downstream after the transform.
2. **"Shape" is orthogonal to "quote vs parameter."** Flat, bucket, twist, and sine-wave shocks
   are all just different shapes of the transformation applied to the input object.
3. **Sensitivities must match how the market is built.** For rates this means bump the quoted
   instrument and **re-bootstrap** (par-conversion), not shift a flat rate.
4. **Prepay/vol/etc. are market-data objects too.** Prepay is a monthly CPR *vector* (no bootstrap
   needed — the vector is the object); vol is a surface (expiry × strike).
5. **A P&L explain is an identity that must reconcile.** Every stage below removes one source of
   error/residual so that the decomposed parts sum back to total P&L.
6. **Cache the sensitivity vector; don't recompute it per scenario.** Compute δ (and Γ) once per (asOf date, market state, position set), then evaluate each scenario move as a dot product δᵀ·Δf. This is the primary performance lever for PLA backtesting.
7. **Compilation (JVM bytecode / specialization) is a secondary, optional optimization.** It speeds up the one-time δ computation; it does not replace caching.

## Requirement → capability → stage map

| P&L explain / PLA requirement | Engine capability | Refactor stage |
| --- | --- | --- |
| Granular risk-factor taxonomy | `RiskFactorKey` taxonomy | Stage 0 |
| Shaped, granular factor moves | market-data objects + scenario/shock model | Stage 1 |
| Sensitivities that reconcile with revaluation | bootstrapping + par-conversion | Stage 2 |
| Time-passing vs market-move split | dated market data, theta/carry | Stage 3 |
| Isolate book changes from market changes | trade population + portfolio aggregation | Stage 4 |
| Explain credit/funding/collateral moves | XVA | Stage 5 |
| The actual decomposition | P&L explain / attribution engine | Stage 6 |
| Prove the explain is accurate | HPL vs RTPL + statistical tests | Stage 7 |
| Sensitivity-based P&L fast path (reuse δ across scenarios) | cached sensitivity vector + dot-product RTPL | Stage 8 (cross-cutting) |
| Optional: make the one-time δ computation faster | compiled/specialized execution (deprioritized) | Stage 8 (secondary) |
| Present the decomposition as a self-explaining graph | P&L waterfall visualization (values, contribution nodes, residual) | Stage 9 |

---

## Stage 0 — Risk-factor key taxonomy

**Status: DONE** ✅ (implemented; `Factors.scala` removed, `com.writhlang.risk` added, `DslCompiler`/`DotRenderer` re-keyed, `PricingDslSuite` updated with taxonomy tests).

**Goal:** replace the 5-factor enum with a proper, granular `RiskFactorKey` model.

**What changes:**
- New package `com.writhlang.risk` with:
  - `KeyType` (DiscountCurve, IndexCurve, YieldCurve, CreditCurve, FxSpot, FxVolatility,
    CapFloorVolatility, SwaptionVolatility, EquitySpot, EquityVolatility, DividendYield,
    InflationZero/YoY, CommodityCurve, CommodityVolatility, Correlation, Prepay, Security, …).
  - `RiskFactorKey(keyType, name, dimensions)` where `dimensions` are optional tenor / expiry /
    strike / loss-level coordinates.
- Remove `Factors.scala` (`RateFactor`, `SpreadFactor`, `PrepayFactor`, `VolatilityFactor`,
  `FxFactor`) and map the legacy DSL words onto keys (compatibility layer):
  - `rate`/`spread` → separate curve keys (today they collapse into one flat curve — Stage 0
    stops pretending they are one factor).
  - `prepay` → a `Prepay` key whose value is a *vector*, not a `Double`.
  - `volatility` → a vol key with expiry × strike.
  - `fx` → an `FxSpot` key.
- Update `DslCompiler` and `DotRenderer` to key nodes by `RiskFactorKey` instead of `String`
  factor names.

**PLA/explain capability unlocked:** "which factor moved" becomes answerable at the right
granularity — the skeleton for per-factor P&L attribution. Without this, the explain collapses
every move into five buckets (two of which are secretly the same one).

**ORE gap closed:** factor taxonomy granularity (partially — full asset-class coverage arrives in
Stage 1).

**Acceptance criteria:** a sensitivity can be addressed as `DiscountCurve:USD:5Y` and
`SwaptionVolatility:EUR:5Yx10Y:ATM`, and the old DSL still parses via the compatibility layer.

---

## Stage 1 — Market-data objects & scenario/shock model

**Goal:** lift shocks out of `Pricing.price` and into a general scenario/sensitivity model.

**What changes:**
- New package `com.writhlang.marketdata` with typed input objects:
  - `Curve` (discount / forward / credit), `VolSurface` (expiry × strike),
    `PrepayVector` (monthly CPR), `SpotQuote`, etc.
- New package `com.writhlang.scenario` with:
  - `Scenario` = a transformation of market-data objects.
  - Shock shapes: `Flat`, `Bucket`, `Twist`, `Sine`, `Custom(function)` — all as functions on
    the *input object*, not scalars.
  - `ScenarioGenerator` (sensitivity, historical, CSV, Monte Carlo, custom) with `next()`/`reset()`.
- Refactor `Pricing.scala` so every instrument price function consumes market-data **objects**
  (curve + vol surface + prepay vector + fx) instead of scalars.
- Replace `Price(instrument, curveShifts, scalar: Map[String, Double])` with
  `Price(instrument, marketState: MarketData)` (or equivalent).
- Remove `Curve.scala`'s `DiscountCurve(baseFlat, shifts)` flat-shift shortcut.

**PLA/explain capability unlocked:** correct, granular, *shaped* factor moves — the building
block for both hypothetical P&L (HPL) and risk-theoretical P&L (RTPL). Enables the sine-wave
prepay scenario discussed: `cpr(t) = base(t) + A·sin(ωt+φ)` is just a `Sine` transform on a
`PrepayVector`.

**ORE gap closed:** scenario/sensitivity *model* (shape generality, generator abstraction),
modelled after ORE's `ScenarioGenerator` + `Scenario` + `ShiftData`.

**Acceptance criteria:** a mortgage can be repriced under a sine-wave prepay shock, and an
FxForward under a relative FX spot shock, with no scalar special-casing inside `Pricing`.

---

## Stage 2 — Curve bootstrapping & par-conversion (re-bootstrapping)

**Status: DONE** ✅ (implemented: `marketdata.bootstrap` — `ParInstrument`/`QuoteSet`/`Bootstrapper` (deposits/futures/swaps → log-linear-DF bootstrapped curves); `scenario.par` — `CurveShiftParData` + `ParQuoteShift` (bump the quote, re-bootstrap); `ShiftType`/`ShiftScheme` + `SensitivityConfig` (forward/backward/central deltas, central gamma); per-pillar risk keys; `MarketData.curveQuotes`; JSON market-data loader (`MarketDataJson`, ujson); recursive-portfolio DSL; re-keyed `Pricing`. The legacy flat-scalar DSL and `LegacyRiskFactors` were removed.)

**Goal:** build real curves from quoted instruments and compute sensitivities by bumping the
quote, not the rate.

**What changes:**
- New package `com.writhlang.marketdata.bootstrap` with:
  - Instrument definitions (deposit, future, swap) and their par equations.
  - A sequential bootstrapper: `quotes → discount factors`.
- New package `com.writhlang.scenario.par` with par-conversion:
  - `CurveShiftParData` (which par instruments to bump).
  - `SensitivityScenarioGenerator` that bumps a *quote* (e.g. 5Y swap +1bp) and **re-bootstraps**.
- Add `ShiftType` (Absolute/Relative) and `ShiftScheme` (Forward/Backward/Central) to the
  sensitivity config; replace the hard-coded central `Delta`/`Gamma` formulas.
- Keep `Twist`/`Bucket`/`Sine` as quote-curve transforms, then bootstrap the result.

**PLA/explain capability unlocked:** `Σ δ·Δf` now reconciles with full revaluation — the core
requirement for RTPL ≈ HPL. This is the stage that makes sensitivities *meaningful* rather than
illustrative.

**ORE gap closed:** the single most important realism gap — ORE's `parConversion` /
`CurveShiftParData` behaviour.

**Acceptance criteria:** a +1bp bump in the 2Y swap quote re-prices a 3Y bond to the
re-bootstrapped value (the worked example in the analysis), and forward/backward/central schemes
all agree to within tolerance of a full revaluation.

---

## Stage 3 — Dated market data, theta & carry

**Status: DONE** ✅ (implemented: `MarketData.asOf` dated snapshot + JSON `"asOf"`; new `com.writhlang.time` package — `DayCount` (`Actual365Fixed`/`Actual360`/`Thirty360`), `BusinessDayConvention`, `WeekendCalendar`, `ThetaPeriod`; `Pricing.price(..., elapsedYears)` (the "instrument rolls down" theta mechanism) + `Pricing.carry` (bond coupon accrual); `Theta`/`Carry` `Op` nodes; `SensitivityConfig.thetaPeriod`/`dayCount`/`calendar`; CLI `--theta-days` + theta/carry output; `TimeDimensionSuite`. The fine `rolldown = theta − pure time decay` split is deferred to Stage 6.)

**Goal:** add the time dimension so we can separate "the market moved" from "time passed."

**What changes:**
- Add a date/asof dimension to `MarketData` (`MarketData(asOf, …)`) and to pricing.
- Add day-count and calendar support (QuantLib-style `ActualActual`, `Actual360`, business-day
  conventions) — or a minimal subset sufficient for the instruments in scope.
- Add **theta**: value change holding the market fixed while advancing the valuation date.
- Add **carry**: the cash accrual (bond coupon) over the holding period. The finer
  "rolldown vs. pure time decay" split of theta is deferred to Stage 6.
- Extend the sensitivity config with a `thetaPeriod` (`ThetaPeriod`, a business-day shift of the asof date).

**PLA/explain capability unlocked:** a day-over-day explain can now split "time passed"
(theta/carry) from "market moved" — without this, all time decay shows up as unexplained
residual and the explain fails.

**ORE gap closed:** ORE's `thetaPeriod_` in `SensitivityScenarioData`, plus dated market snapshots.

**Acceptance criteria:** for a one-day horizon with unchanged market data, the reported
theta/carry P&L is non-zero for an option and matches a manual one-day revaluation.

---

## Stage 4 — Trade & portfolio layer (population events + aggregation)

**Status: DONE** ✅ (implemented: `com.writhlang.portfolio` — dated `TradeInstance` with `tradeDate`/`currency`, `TradePopulation`, `PopulationDiff` (unchanged/amended/new/cancelled/expired) and `FxConversion`; the **date basis** extends to curves and schedules (`TimeConventions`, `Schedule`, schedule-driven `Pricing`, value 0 at settlement); trade-keyed DAG with `Op.Sum` roll-ups (`agg:<metric>:<path>`) and `Op.Combine` P&L buckets; `DslCompiler.buildPeriodPnl` plus CLI `--prev-input`/`--prev-market`/`--reporting-currency`; duplicate-id errors; `Pricing.normal` per call. **Recorded deviations:** population events are *derived from two dated snapshots* rather than declared as a `state` field; `Expired` is implemented through `tradeDate`; currency translation covers **levels only** at the closing spot, with FX P&L deferred to Stage 6; discretionary cash (unwind/entry/fees) is not modelled. Decisions, rationale and corrections: [docs/decisions/stage-4-trade-portfolio-layer.md](docs/decisions/stage-4-trade-portfolio-layer.md).)

**Goal:** model the book as a set of *trades with lifecycle state*, and aggregate P&L correctly.

**What changes:**
- New package `com.writhlang.portfolio`:
  - `Trade(id, instrumentSpec, state)` where state ∈ {Active, New, Amended, Cancelled, Expired}.
  - `Portfolio` with desk → book → entity hierarchy.
- Track **trade-population events** between two asof dates (new / cancelled / amended / expired).
- Add aggregation that is **additive** and **netted**: trade-level explains must sum exactly to
  desk-level explains; consistent currency conversion.
- Refactor `DslCompiler.build` / `Executor.run` from "flat DAG of instruments" to "DAG per trade,
  then rolled up."

**PLA/explain capability unlocked:** isolates `P&L(new) + P&L(cancelled) + P&L(amended)` from
`P&L(market move on unchanged book)`; provides the desk-level roll-up that PLA is assessed at.

**ORE gap closed:** trade/portfolio data model (ORE's `OREData` trade types) and reporting roll-up.

**Acceptance criteria:** given a book where one trade was amended overnight, the explain splits
the total P&L into the amended-trade effect and the market effect, and the trade-level numbers
sum to the portfolio number.

---

## Stage 5 — XVA layer

**Goal:** value and explain the adjustment components of a derivatives book.

**What changes:**
- New package `com.writhlang.xva` with counterparty, collateral, funding, and capital inputs.
- Implement (at least a closed-form/illustrative level first): CVA, DVA, FVA, ColVA, MVA, KVA.
- Expose XVA as its own explainable P&L component, driven by credit/funding/collateral factors —
  separate from the market-risk factors of the underlying trades.

**PLA/explain capability unlocked:** credit/funding/collateral moves are attributed to XVA rather
than leaking into (or being omitted from) the market-move bucket; the explain covers the *full*
value change of a derivatives book.

**ORE gap closed:** ORE's XVA analytics (CVA/DVA/FVA/ColVA/MVA/KVA).

**Acceptance criteria:** a counterparty-credit-spread move produces an attributable CVA P&L, and
a funding-spread move produces an attributable FVA P&L, each reconciling with full revaluation.

---

## Stage 6 — P&L explain / attribution engine

**Goal:** produce the actual decomposition — the identity that reconciles to total P&L.

**What changes:**
- New package `com.writhlang.explain` implementing:
  ```
  TotalP&L = P&L(new trades) + P&L(cancelled) + P&L(amended)
           + Σᵢ δᵢ·Δfᵢ + ½ Σᵢ γᵢ·Δfᵢ² + Σᵢⱼ crossᵢⱼ·ΔfᵢΔfⱼ   (market, RTPL)
           + theta/carry   (time passed)
           + XVA P&L
           + cash-flow / lifecycle P&L
           + unexplained (residual)
  ```
- Split the `theta/carry` time bucket into its constituents — the "curve rolls down"
  vs. "instrument rolls down" distinction (see `docs/decisions/stage-3-dated-market-data-theta-carry.md`):
  - `carry` (cash) = accrued coupon/interest − financing;
  - `theta` (price) = curve-frozen, instrument-aged revaluation — already contains rolldown;
  - `pure time decay` (price) = the same revaluation but each cashflow discounted at its
    *original* tenor (the "curve rolls down" case);
  - `rolldown` = `theta − pure time decay` = the curve-slope contribution.
  Stage 3 ships `theta` + `carry` only; the `rolldown` vs. `pure time decay` fine split is
  produced here so the identity reconciles bucket-by-bucket.
- Reuse the existing DAG machinery (`DslCompiler`/`Executor`) as the **risk-theoretical P&L
  (RTPL)** calculator: base → delta/gamma/cross → Taylor terms — now driven by Stages 0–5.
- Add the **hypothetical P&L (HPL)** path: full revaluation with frozen positions under the
  actual market moves (re-bootstrapped curves, dated snapshots).
- Emit an explain *report* (and keep the `DotRenderer` traceability view).

**PLA/explain capability unlocked:** the decomposition itself — every bucket above is produced,
and the residual is made explicit so it can be scrutinized.

**ORE gap closed:** this is the layer *above* ORE; ORE supplies the pricing/sensitivity inputs,
this stage assembles the attribution.

**Acceptance criteria:** for a representative book over a one-day horizon, the sum of the
explained buckets plus residual equals total P&L exactly (to floating-point tolerance).

---

## Stage 7 — Backtesting / PLA validation layer

**Goal:** prove the explain is accurate, and gate it the way a regulator does.

**What changes:**
- New package `com.writhlang.pla`:
  - Time-series comparison of **HPL** vs **RTPL** at desk level.
  - Tests: **mean ratio** (RTPL/HPL within a band), **Spearman correlation**, **variance ratio**.
  - Desk classification (modellable vs non-modellable) and PLA pass/fail flags.
  - Reporting suitable for FRTB P&L attribution sign-off.
- Drive this with the scenario generators from Stage 1 and the performance work from Stage 8.

**PLA/explain capability unlocked:** the regulatory gate — turns "we can explain P&L" into
"we can *prove* the explain is reliable"; if a desk fails, it falls back to the standardized
approach (higher capital).

**ORE gap closed:** ORE provides scenario/NPV/sensitivity reporting but not a turnkey PLA
test-suite; this stage is the validation layer a bank normally builds on top.

**Acceptance criteria:** over N historical days, the RTPL/HPL mean ratio and Spearman correlation
are computed per desk, and a desk fails/passes the PLA tests as expected.

---

## Stage 8 (cross-cutting) — Performance: sensitivity-vector caching (primary) + optional bytecode

**Goal:** make large-book, many-scenario revaluation (needed for Stage 7) fast enough.

**What changes:**

**Primary strategy — cache the sensitivity vector (sensitivity-based P&L fast path):**
- After Stage 2 (re-bootstrapped sensitivities), compute the full vector δ = [∂PV/∂f₁ … ∂PV/∂fₙ] (and optionally the gamma/cross matrix Γ) **once per (asOf date, market state, position set)**.
- Represent each scenario as a factor-move vector Δf; evaluate risk-theoretical P&L as a dot product: `RTPL = δᵀ·Δf` (and `+ ½ ΔfᵀΓΔf` for second order).
- Never reuse δ across dates or after trade-population changes; the cache key is `(asOfDate, marketState, positionSet)`.
- Keep HPL **uncached** — it must remain an independent full-revaluation benchmark.

**Secondary (deprioritized) — JVM bytecode & pricing specialization:**
- New package `com.writhlang.backend.jvm` (mirroring the existing toy `GasBackend`):
  - Compile the (Stages 0–5) instruction DAG to straight-line JVM bytecode (ASM), with primitive
    `double[]` slots instead of boxed `Map[String, Double]`.
  - Later, **specialize the pricing formulas** (partial evaluation): inline instrument constants,
    unroll short loops, remove `InstrumentSpec`/curve dispatch — keeping shock values as runtime
    parameters so one generated class is re-run over thousands of scenarios.
- Keep the interpreted `Executor` as the reference implementation for correctness tests.

**PLA/explain capability unlocked:** none directly — it is an *enabler* that makes Stage 7
tractable at production scale (thousands of scenarios × large books × many risk factors). The caching fast path is the dominant win (n repricings once, then one dot product per scenario); bytecode is a secondary constant-factor win on the repricings.

**ORE gap closed:** ORE is already compiled C++, so it has no interpreter overhead; the sensitivity-vector caching technique is language-independent and is the standard sensitivity-based P&L approach.
 

**Acceptance criteria:** with one base market state and K scenarios, scenario P&L is computed by reusing a single δ (no per-scenario re-bootstrap/reprice); HPL still recomputes fully; and the cached-δ RTPL matches a per-scenario recomputation within tolerance.
 

---

## Stage 9 — Explain visualization: the P&L waterfall graph

**Status: DEFERRED** — recorded now, implemented after Stage 6 (and ideally 7) matures.

**Goal:** replace the current dependency-only DOT graph with a self-explaining **P&L
waterfall**. Every computation level carries its value, per-factor contributions are explicit
nodes, edges into the P&L totals carry their contribution amounts, and the graph reconciles to
the decomposition identity `full PnL = Σ contributions + residual`.

**Why not now:** the graph can only be *honestly* explanatory once the underlying numbers are
meaningful. Today pricing is a flat curve with central-difference greeks, so any residual the
graph would show is dominated by approximation error (third-order + finite-difference), not a
real market move. The visualization is therefore gated on:

- Stage 0–1 (granular keys + market-data objects) so contributions are addressed at the right
  granularity;
- Stage 2 (re-bootstrapped sensitivities) so `Σ δ·Δf` reconciles with revaluation;
- Stage 3 (dated data) so theta/carry is separated, and Stage 4 (portfolio) so the roll-up nets;
- Stage 6 (the decomposition identity) so there is a *residual to draw*.

**What changes (design, recorded for later):**

- **Engine (`DslCompiler`/`Executor`/`Instruction`):** materialize contributions as first-class
  `Op` nodes — `LinearContribution` (`δᵢ·Δfᵢ`), `GammaContribution` (`½γᵢ·Δfᵢ²`),
  `CrossContribution` (`crossᵢⱼ·ΔfᵢΔfⱼ`) — plus summary nodes `LinearPnl`, `QuadraticPnl`,
  `FullPnl` (= full − base), and `Residual` (= full − quadratic). Terminal scenario prices then
  sum these contribution nodes so the decomposition is traceable node-by-node.
- **Renderer (`DotRenderer`):** accept the `Executor` value map; annotate every node with its
  value and a finance-readable label (units + sign conventions); colour by role (shock / price /
  greek / contribution / PnL / residual); label edges into PnL totals with the contribution
  amount; align stages into left→right columns via `rank=same` (shocks → prices → greeks →
  contributions → PnL totals → residual).
- **CLI (`Main`):** print a per-instrument × per-shock waterfall report mirroring the graph, with
  per-factor contributions and the residual.
- **Tests:** identity `fullPnl == quadraticPnl + residual`; `Σ` contributions equals the scenario
  PnL; DOT contains contribution labels/values when the value map is supplied; legacy
  dependency-only rendering still works with an empty map.

**PLA/explain capability unlocked:** the *same* engine output is rendered as both a
reconciliation report and a visual explain a trader/regulator can read end-to-end.

**ORE gap closed:** presentation of the decomposition (Stage 6 output); no new pricing model is
introduced — this stage is the "report/visualize the explain" slice.

**Acceptance criteria:** for the representative book, the graph shows per-level values and
per-factor contributions, `full PnL = Σ contributions + residual` holds exactly (residual node
visible and size-graded), and the contribution nodes reconcile with the Stage 6 textual report.

---

## Sequencing & dependencies

- **0 → 1 → 2** are the foundational chain and must be done in order (taxonomy → objects/scenarios
  → bootstrapping).
- **3** depends on the market-data objects from **1** (and day-count support) but not on **2**.
- **4** depends on nothing pricing-related; it can proceed in parallel with **2/3**.
- **5** depends on **1** (market-data objects) and **4** (counterparties are trade attributes).
- **6** depends on **0–5**.
- **7** depends on **6**, and benefits from **8** for scale.
- **8** (caching) needs **2** (sensitivities) and feeds **6/7**; the optional bytecode part can start once **1** stabilizes (compile the DAG) and deepen once **2** lands.
- **9** (explain visualization) depends on **6** (the decomposition identity) and **0–5**; it benefits from **7** (knowing which desks/buckets to flag). Deliberately deferred until the explain is meaningful.
 

## Risks / open questions

- **Scope creep:** full ORE parity (all asset classes, all models) is years of work. The plan
  targets the *explain/PLA* slice; instrument coverage can stay narrow (bonds, swaps, caps,
  swaptions, mortgages, FX forwards) while the framework generalizes.
- **Floating-point reconciliation:** RTPL vs HPL must agree to a tight tolerance; keep evaluation
  order and rounding deterministic (documented in Stage 6/7 acceptance criteria).
- **Thread-safety (closed in Stage 4):** the shared `Pricing.normal` singleton was replaced with a
  per-call `NormalDistribution`, so option pricing is safe under the executor's level parallelism.
- **Date basis re-baselining:** every convention (day count, calendar, business-day rule) feeds every
  price, so a change moves published numbers. Keep the convention tests pinned and state the basis
  beside each expected value.
- **FX and discretionary cash (Stage 4 boundary):** Stage 4 translates *levels* at the closing spot
  and excludes FX P&L, unwind prices, entry prices and fees. Stage 6 must add those buckets before
  an explain can be called economic; until then the period report is a book-value decomposition.
- **Turnkey vs platform:** even after Stage 7, the *regulatory* explain is a process (data feeds,
  trade system, sign-off); this plan produces the *engine*, not the operating procedure around it.
- **Cache invalidation:** the δ/Γ cache is only valid for a fixed (asOf date, market state, position set); stale reuse across dates or trades silently corrupts RTPL. Enforce the key explicitly and add a test that changing the date or a trade invalidates the cache.
- **Explain-visualization maturity:** the P&L waterfall graph (Stage 9) must not ship before the explain engine (Stage 6). Rendering the current flat-curve model would present approximation error as if it were an attributed market move. Gate it on the Stage 6 acceptance criteria.



