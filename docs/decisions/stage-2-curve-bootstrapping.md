# Stage 2 — Curve Bootstrapping & Par-Conversion: decisions & discussion log

> Records the design decisions, questions raised, rationale, and mid-implementation corrections from Stage 2 of `plan.md`. The reference docs (`architecture.md`, `low-level-design.md`, `dsl-reference.md`) say *what* the code is; this doc records *why* we built it that way.

## 1. Goal

Build real curves from quoted instruments; compute sensitivities by **bumping the quote and re-bootstrapping** (par-conversion); **retire the legacy flat-scalar DSL**.

## 2. Decisions and rationale

### 2.1 Drop the legacy DSL — a clean break, not a compatibility layer

The legacy DSL embedded flat market numbers (`rate`, `spread`, `prepay`, `volatility`, `fx`) inside each instrument, with a single flat curve. We removed it entirely rather than wrapping it: `LegacyRiskFactors` was deleted, instrument specs were re-keyed to `RiskFactorKey` references, and the DSL was rewritten.

**Why:** the flat model was the exact "shock a scalar inside pricing" anti-pattern Stage 1 removed. Keeping a compatibility path would have preserved that confusion and doubled the code to maintain.

### 2.2 Market data is external (JSON), never in the DSL

The `.dsl` file describes *trades* (a portfolio) and *shocks* (scenarios). Market data lives in a **separate JSON document**, loaded by `MarketDataJson`. Instruments and shocks only *reference* market objects by `RiskFactorKey` (e.g. `discountCurve EUR`).

**Why:** in a bank, trades, market data, and scenarios live in different systems with different lifecycles. A curve is *derived* from quotes, not a declared object — so we declare **quotes** and name the bootstrapped result. This also makes Stage 3 (dated snapshots) a "pass a different snapshot" change, and Stage 4 (trade lifecycle) independent of market data.

### 2.3 Recursive portfolios

`Program.nodes` is a recursive tree (`Portfolio(name, children)` / `InstrumentLeaf`), not a flat instrument list.

**Why:** the old "one flat list = one implicit portfolio" was a hidden assumption. Nested books are the natural forward-compatible shape for Stage 4's desk → book → entity roll-up.

### 2.4 Shock model: named bundles of key-targeted moves

A `Shock` is a named bundle of `PointMove(key, shiftType, amount)` / `ShapeMove(key, shape)`. Scenario names are arbitrary labels — `up`/`down` have no intrinsic meaning; direction is the sign, and shift type (`absolute`/`relative`) defaults by factor kind (rates/prepay → absolute; fx/vol → relative).

**Why:** the legacy `shock up { rate +x }` conflated "scenario name" with "direction" (and even inverted prepay's sign). Decoupling them makes each move explicit.

### 2.5 Bootstrap conventions

Continuous-compound deposits, **log-linear discount-factor interpolation** (flat forward), annual swaps by default.

**Why:** these are the market-standard choices — log-linear DF keeps discount factors monotonic and forward rates non-negative (no arbitrage).

### 2.6 Per-pillar risk keys + par-conversion

For a quote-backed curve, each par instrument becomes its own `RiskFactorKey` (`DiscountCurve:EUR:2Y`). A bump is a **`ParQuoteShift`** — bump that quote, re-bootstrap — never a shift of an interpolated rate.

**Why:** you hedge the 5Y swap with the 5Y swap, so sensitivity must be w.r.t. that quote. Bumping the quote propagates consistently through the curve (par-conversion), which is what makes risk-theoretical P&L (`Σ δ·Δf`) reconcile with full revaluation — the core of FRTB P&L attribution.

### 2.7 `ShiftType` / `ShiftScheme` + `SensitivityConfig`

Delta supports **forward/backward/central** differencing (default central); **gamma is always central**. Bump sizes live in one `SensitivityConfig`.

**Why:** central is second-order accurate and the industry default; forward/backward are used at boundaries, for speed, or by convention (theta uses backward). A second derivative is inherently central.

### 2.8 `Scenario.shifts` is `Map[RiskFactorKey, List[ScenarioShift]]`

See [§4.1](#41-correction-listkey-shift--mapkey-listshift).

### 2.9 Credit is represented as a spread over the base curve

A `CreditCurve` is stored as a **zero spread over the base curve**, not as a standalone risky discount curve. `MarketData.combinedCurve(discountKey, creditKey)` returns `curve(discountKey).plus(curve(creditKey))`, and `Curve.plus` adds the two rate functions pointwise: `r_eff(t) = r_base(t) + spread(t)`, hence `df_eff(t) = df_base(t) · e^(-spread(t)·t)`.

**Why:** with continuous compounding, rate addition is exactly discount-factor multiplication, and the `Curve` algebra only composes that way (`plus` / `shifted`; there is no curve product). Keeping credit as a spread also keeps the two inputs independently observable and independently shockable — a base-curve move cannot silently rewrite the credit assumption — and keeps rates and credit as separate risk factors with separate hedges and separate P&L buckets (Stages 5–6).

**Consequence:** the flat value must be a spread in rate units — an annual decimal such as `0.0015` for 15bp — not an absolute credit-risky yield, which would double-count the base rate. It is a discounting spread, not a credit model: no default probability, recovery, or CDS bootstrap, and the curve is flat-only in JSON.

See [dsl-reference.md §8.7–8.8](../dsl-reference.md#87-the-credit-curve-a-spread-over-the-base-curve).

## 3. Concepts clarified during the conversation

### 3.1 Bump-and-reprice

Sensitivities are finite differences on the **market object**: bump a quote → re-bootstrap → reprice, then `(up − down)/(2·bump)`. Example instrument with the most inputs is the **swaption**: `reval(discountCurve, creditCurve, volSurface)` — one quote bump rewrites the whole curve (and every instrument priced off it) consistently.

### 3.2 PV01 vs the raw δ-vector

They are the **same sensitivity in different units**: `PV01 = δ × 1bp`. The engine stores the raw per-pillar δ (dollars per unit rate); a "dollar sensi" is `δ × 0.0001` (dollars per bp). The vector is the primitive (from it you derive PV01, scenario P&L `Σ δᵢΔfᵢ`, and the total); dollar amounts are the human-facing, hedgeable form.

### 3.3 Per-pillar δ-vector

A bond's sensitivity is one delta **per curve pillar**, not one blended "rates" number — so P&L can be attributed to "the 2Y swap moved" vs "the 5Y swap moved". This vector is exactly the `δᵀ` the Stage 8 fast path (`δᵀ·Δf`) needs.

### 3.4 Bootstrapping is causal — a 2Y bond is blind to the 5Y point

Bootstrapping solves pillars in maturity order; each step depends only on *earlier* pillars. So `df(2Y)` is pinned before the 5Y swap is touched, and bumping the 5Y swap leaves a 2Y bond's price unchanged (**delta_5Y = 0**, proven by test). The reverse is not true: bumping the 2Y swap *does* move `df(5Y)` (par propagation). General rule: a bullet bond is sensitive to curve points at/before its maturity and blind to points beyond.

### 3.5 Commutativity and "market memory"

`Scenario.applyTo` **folds** over shifts, so within one scenario the market *does* carry state — quotes accumulate, and each re-bootstrap reads the accumulated quotes. Across scenarios the DAG starts each scenario from the base snapshot (stateless), which is correct for sensitivities and single-shock reval. Real "memory" (time/theta, dated snapshots, MC paths) is Stage 3/7; the `MarketData`-in/market-out shape already supports chaining.

## 4. Corrections made during implementation

### 4.1 Correction: `List[(Key, Shift)]` → `Map[Key, List[Shift]]`

Originally `Scenario.shifts` was a flat `List[(RiskFactorKey, ScenarioShift)]` — a hasty way to dodge a `Map` collision (two pillar moves on one curve). Review flagged it; the correct shape is `Map[RiskFactorKey, List[ScenarioShift]]`: a scenario is "for each factor, its ordered transforms". Order matters only *within* a factor; across factors shifts commute.

### 4.2 Correction: "order matters within a factor" was overstated

A property test proved cross-factor shifts commute **and** additive within-factor shifts (two pillar bumps) also commute — only **mixed absolute+relative on the same spot** is genuinely order-dependent (`(s+a)(1+b) ≠ s(1+b)+a`). The `List` is still right (preserves order for that case), but the earlier rationale was imprecise and was corrected in code comments and the test.

### 4.3 Correction: par-conversion sawtooth

A single swap-point bump moves adjacent discount factors in *opposite* directions (sawtooth), so a bond's delta to a specific point has **no fixed sign**. The acceptance test therefore asserts **reconciliation** (`quadratic` at least as close to `full` as `linear`), not direction.

## 5. Deferred / future work

- **Stage 3** — dates, day-count, theta/carry/rolldown: the real "market has memory" time axis.
- **`VolSurface` point-based + interpolation** (currently function-valued; same fix class as `Curve`).
- **Stage 8** — prune zero-sensitivity pillars (e.g. 5Y for a 2Y bond); cached `δᵀ·Δf` fast path.

