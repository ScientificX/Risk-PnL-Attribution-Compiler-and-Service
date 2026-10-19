# Stage 4 — Trade & Portfolio Layer: decisions, implementation record, corrections

> Records the decisions and rationale for Stage 4 of `plan.md`, the deviations the
> implementation forced, and the corrections found while building it. The reference
> docs (`architecture.md`, `low-level-design.md`, `dsl-reference.md`) describe *what*
> the code is; this document records *why*, and where reality differed from the plan.

## 1. Status

**DONE** — milestones M1–M7 complete, `./gradlew build` green, 66 tests.

What shipped:

- **Date basis.** `TimeConventions`, `Schedule`, date-resolved curve pillars, schedule-driven pricing, day-count accrual, and zero valuation at settlement.
- **Trade layer.** `com.writhlang.portfolio`: `TradeInstance` (with `tradeDate` and optional `currency`), `TradePopulation`, `TradeState`, `PopulationDiff` (unchanged / amended / new / cancelled / expired) and `FxConversion`.
- **DAG.** Trade-keyed node ids, `Op.Sum` roll-ups (`agg:<metric>:<path>`), `Op.Combine` P&L buckets, duplicate-id rejection, per-call `NormalDistribution`.
- **Period P&L.** `DslCompiler.buildPeriodPnl`, the exact cash identity, and the CLI period report (`--prev-input`, `--prev-market`, `--reporting-currency`).

## 2. Scope (as approved)

**In:** date basis; trades with `tradeDate` and `Expired`; population events; additive and netted roll-up; two-date P&L; CLI period run; duplicate-id errors; `Pricing.normal` fix; currency levels to a reporting currency.

**Out:** intraday event timing; discretionary cash (unwind prices, entry prices, amendment fees); FX P&L of the period; typed legal-entity hierarchy; RTPL/Taylor residuals (Stage 6); backtesting (Stage 7); caching (Stage 8); waterfall rendering (Stage 9).

## 3. Decisions

### 3.1 D1 — Two snapshots, events derived (deviation from `plan.md`)

`plan.md` describes `Trade(id, instrumentSpec, state)` with a declared state. The implementation instead takes **two full book snapshots** (one per valuation date) and derives the event with `PopulationDiff`. Each trade id is classified exactly once.

**Why:** a declared `state` cannot express an amendment without embedding the previous terms, and two snapshots are how a trade system exports a book. The derived event type is the plan's `state` realised as a classification, and it cannot disagree with the book it describes. The deviation is recorded in `plan.md`.

### 3.2 D2 — `Expired` implemented via `tradeDate` (superseding the earlier deferral)

A trade's `tradeDate` plus its term gives a calendar **settlement date**; on or after it the trade is `Expired` and its value is zero.

**Why:** without an origination date, expiry is not representable — maturity would be relative to whichever date is being valued, so a trade could never reach it. Disappearance from the book alone cannot distinguish an expiry from a cancellation.

**Consequence:** a trade that is amended **and** then matures inside the period gets both `inception` and `expired` buckets, so the settlement is still reconstructed exactly.

### 3.3 D3 — Date basis extended to the curves

Making trades date-anchored while curves stayed on nominal tenors would disagree at every leap year: a "1Y" pillar from 2024-01-01 is 366 days (366/365 years), not 1.0. Curve pillars therefore resolve to dates and every discount factor is quoted at the day-count year fraction.

**Consequence:** every published number moved in the 4th–6th decimal (e.g. the 1Y discount factor `0.965605` → `0.965513`). Existing test expectations were re-baselined with the basis stated in the test, and the worked example in `dsl-reference.md` was recomputed.

### 3.4 D4 — Theta/carry redefined on clean values

Stage 3's theta was a **dirty** value change, so it already contained accrual; adding an accrual-based carry would have double-counted. Stage 4 defines:

- `theta` = clean value at the advanced date − clean value at the start (no accrual),
- `carry` = Δaccrued + contractual cash paid in the window.

**Why:** the total is unchanged, but the split now matches the four-way decomposition in the Stage 3 log (carry is cash, time decay is price). A premium bond correctly shows negative clean theta (premium amortisation) with a positive carry.

### 3.5 D5 — Book value vs economic P&L (recorded boundary)

`+value` for a new trade and `−value` for a cancelled one are **value transitions**, not realised P&L: the cash leg is not in the data. The period report is therefore labelled a book-value change plus contractual cash. Contractual cashflows — coupons, amortisation, final settlement — *are* in the data and are included; discretionary cash is not claimed.

**Why:** inventing entry or unwind prices would fabricate P&L. Understating is visible; fabrication is not.

### 3.6 D6 — Currency is a level translation, not an FX P&L model

A trade carries a currency; translation to a reporting currency uses the closing snapshot's spot, direct pair first, else the inverse, and a missing rate is an error naming both pairs.

**Why:** using the closing rate for every amount means the translation itself creates no gain or loss, so the decomposition contains no FX bucket. FX P&L is a Stage 6 bucket, and pretending otherwise would silently mix translation with economics.

**Guards:** a single-snapshot book mixing currencies is rejected (it would sum unconverted amounts); a period book mixing currencies without a reporting currency is rejected; a trade's currency may not change between snapshots.

### 3.7 D7 — Roll up every metric; netting is additive

For each metric — base value, each Greek per factor, each scenario value, theta, carry, each P&L bucket — the compiler emits a `Sum` node per book path and one for the whole book.

**Why:** all these metrics are additive across trades at a fixed market state, so the roll-up is exact, and a sum of deltas is a **netted** sensitivity (opposing positions cancel). Prices, Greeks and scenarios therefore roll up with the same machinery as the P&L buckets.

### 3.8 D8 — Hierarchy is free text; duplicate ids are errors

`portfolio` blocks remain an untyped nested tree; the path drives the roll-up. Duplicate trade ids are rejected at both the population and the DAG level.

**Why:** a typed desk/book/entity model needs reference data that does not exist here; the DAG is a `Map`, so a duplicate id would silently overwrite another trade's nodes.

## 4. The identity as implemented

```
Σ buckets = Σ_{trades at t1} dirty(t1, m1) + Σ cash(t0, t1] − Σ_{trades at t0} dirty(t0, m0)
```

| Event | Buckets |
| --- | --- |
| unchanged | `market` = clean(t1,m1) − clean(t1,m0); `theta` = clean(t1,m0) − clean(t0,m0); `carry` = Δaccrued + cash |
| amended | the same on the amended terms, plus `inception` = dirty(new, t0) − dirty(old, t0) |
| new | `dirty(t1, m1) + cash` |
| cancelled | `−dirty(t0, m0)` |
| expired | `cash − dirty(t0, m0)` (+ `inception` when amended before maturing) |

Each row telescopes, so the identity holds with **no residual**: the market/time split is taken at `t1`, not by a Taylor expansion. `TradePnlSuite` asserts the identity against values recomputed directly from `Pricing`, so a wrong market, date or sign in the wiring fails the test.

## 5. Acceptance evidence

CLI period run (2024-01-01 → 2024-01-02, 2Y quote +1bp), book: A unchanged, B amended 5% → 6%, N new, reporting EUR with `EURUSD = 1.25`:

```
trade   event          market    theta    carry  inception      new    total
A       unchanged     -0.1510   0.0072   0.0877                      -0.0561
B       amended       -0.0025  -0.0113   0.1315    22.1432            22.2610
N       new                                           504.6361       504.6361
BOOK                  -0.1535  -0.0041   0.2192    22.1432  504.6361 532.3922
reconciliation: sum(buckets) - total = 0.0000000000
```

(The untranslated run gives −0.1887 / 27.6790 / 504.6361 — exactly ÷ 1.25.)

The acceptance criterion from `plan.md` — an overnight amendment is split from the market effect and trade numbers sum to the book — is demonstrated by `TradePnlSuite` and this run.

## 6. Corrections found during implementation

1. **Trades did not age.** With no explicit trade date, each valuation snapshot re-anchored the schedule to its own date, so the theta revaluation re-priced the same instrument and theta came out as exactly 0, while carry came out doubled (cash plus a double accrual). Fix: the DAG pins the trade date **once, from the base snapshot**, and every node — bumped, scenario, advanced date — values that same trade. The explicit `tradeDate` is the same mechanism with a declared date.
2. **Duplicate factors for an FX forward whose domestic and foreign curves are the same.** `factorsFor` returned the shared curve twice; the old `Map` silently collapsed the duplicate nodes. Once duplicates became an error the case surfaced; `factorsFor` now returns distinct factors.
3. **FastParse rejects a range-with-trailing-dash character class.** `CharsWhileIn("0-9-")` fails at macro expansion (`MatchError`); the date token uses predicate-based `CharsWhile` instead.
4. **Unicode in CLI output.** `Σ` and `−` were rendered as `?` on a Windows console (default codepage); the report uses ASCII while the DOT file remains UTF-8.
5. **Inception sign.** The first draft of the plan measured inception as old − new, which breaks the identity by twice its value; the implemented definition is **new − old** (a coupon increase raises value, so inception is positive). `TradePnlSuite` recomputes it directly from `Pricing`, and the sign check fails loudly if reversed.

## 7. Deferred / follow-ups

| Item | Why it is out of Stage 4 | Where it belongs |
| --- | --- | --- |
| FX P&L of the period, translation policy | requires an FX bucket and a rate policy (closing vs average) | Stage 6 |
| Discretionary cash: unwind prices, entry prices, amendment fees | not in the data; needs a cash feed/trade system | Stage 6 |
| Intraday event timing | needs event timestamps and a market snapshot at each event | Stage 6 |
| Typed legal-entity hierarchy | needs reference data | later |
| Correlation matrices from JSON | loader not implemented | later |
| Point-based vol surfaces | function-valued surfaces only | later |
| Residual (`RTPL` vs full revaluation) | Taylor path is Stage 6 | Stage 6 |

## 8. Documents updated with this stage

`architecture.md`, `low-level-design.md`, `dsl-reference.md` (including the recomputed worked example), `plan.md` (Stage 4 status, deviations and risks). These were rewritten wholesale rather than patched, per `AGENTS.md`.
