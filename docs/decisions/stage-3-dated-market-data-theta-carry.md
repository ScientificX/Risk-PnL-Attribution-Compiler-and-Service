# Stage 3 — Dated Market Data, Theta & Carry: decisions & discussion log

> Records the finance concepts, naming decisions, and open questions for Stage 3 of
> `plan.md` ("Dated market data, theta & carry"). Written **before** implementation to
> fix the conceptual foundation — carry vs. time decay vs. rolldown, and
> "curve rolls down" vs. "instrument rolls down" — so the code and its terminology are
> unambiguous. The reference docs (`architecture.md`, `low-level-design.md`,
> `dsl-reference.md`) will be rewritten when the stage is implemented; this doc carries
> the *why* and the worked arithmetic. Rates are decimals (`0.03` = 3%) and discounting
> is continuous (`df(t) = e^(-r·t)`), matching the engine.

## 1. Status

**Implemented in Stage 3**, with one part revised in Stage 4: `theta` is now a *clean*
price change and `carry` is accrued interest plus contractual cash, and the uniform
`elapsedYears` tenor shift was replaced by date-anchored schedules. See
[§9](#9-post-stage-4-update-recorded-after-implementation). The concepts below remain the
baseline the implementation followed.

## 2. The core question Stage 3 answers

Over a holding period with the market frozen, a position still makes or loses money
purely from **time passing**. Stage 3 must separate that "time passed" P&L from "the
market moved." The catch is that "time passing" decomposes into several distinct
mechanisms, and the industry naming for them is overloaded. This section fixes the
names and the arithmetic.

## 3. The four building blocks of time P&L

| Effect | What it is | Kind | Who has it |
| --- | --- | --- | --- |
| **Carry** | income earned minus cost of holding (coupon/interest − financing) | realized **cash** | bonds, swaps, FX |
| **Rolldown** | price change from the curve's **slope** as the maturity shortens | unrealized **price** | bonds, swaps (anything off a sloped curve) |
| **Pure time decay** ("pull-to-par") | price change from being `Δt` closer to each cashflow at an *unchanged* rate | unrealized **price** | everything |
| **Theta** | decay of an option's **time value** as expiry approaches | unrealized **price** | options (caps, swaptions) |

"Carry" is the income a **linear** position earns; "theta" is the decay an **option**
suffers. Same axis (time), opposite personality.

## 4. The decomposition identity

```
Total time P&L = Carry − Financing          (cash)
               + Pure time decay            (price: closer to each cashflow, rate held)
               + Rolldown                   (price: curve slope, as maturity shortens)
               + Theta                      (price: option time-value decay; options only)
```

- **Linear instruments** (bonds, swaps) → dominated by carry + rolldown.
- **Options** → dominated by theta (plus a small rolldown via their underlying curve).
- On a **flat curve**, rolldown = 0. Without coupons, carry = 0. Without optionality,
  theta = 0.

## 5. The two "rolling" operations — the key distinction

"Rolldown" means two different things depending on *what* rolls. This is the source of
most of the confusion, so it is fixed here precisely.

Let the curve at `t = 0` be a function of tenor `r(τ)`, and let `Δt` be the holding
period. There are two distinct ways to advance time:

| | **Instrument rolls down** (curve FROZEN) | **Curve rolls down** (term structure slides) |
| --- | --- | --- |
| What physically moves | the instrument's maturity | the curve itself |
| Remaining maturity | `T → T − Δt` | `T → T − Δt` |
| Rate the cashflow is discounted at | `r(T − Δt)` — read the frozen curve at the shorter tenor | `r(T)` — the curve is rebuilt so each absolute maturity keeps its rate |
| What it captures | pure time decay **+** rolldown | pure time decay only |

Formally, "curve rolls down by `Δt`" produces `r_roll(τ) = r_old(τ + Δt)`: the rate at
tenor `τ` on the rolled curve equals the **old** rate at tenor `τ + Δt`, i.e. the rate
attached to any **absolute calendar date** is preserved.

The identity that connects the two:

```
rolldown = (instrument rolls down) − (curve rolls down)
```

- **Instrument rolls down** = keep the curve and let the instrument age. It slides to a
  shorter tenor on a sloped curve, picking up *both* time decay and the slope (rolldown).
- **Curve rolls down** = keep each absolute maturity's rate and let the *calendar* age.
  This isolates pure time decay and removes the slope.
- On a **flat curve** `r(T − Δt) = r(T)`, so the two operations coincide and
  `rolldown = 0`.

## 6. Worked example (the numbers)

Curve (upward-sloping): `r(1Y) = 2%`, `r(2Y) = 3%` (linear interpolation in rates for
hand-checking; the engine uses log-linear in discount factors, same idea). Instrument: a
**2-year zero-coupon bond**, face `F = 100`. Holding period `Δt = 1/365 = 0.0027397` yr.

### 6.1 Base price

```
P0 = 100 · e^(-0.03 · 2) = 100 · 0.941765 = 94.1765
```

### 6.2 Instrument rolls down (curve frozen)

Remaining maturity `T' = 2 − 1/365 = 1.99726`. Rate at that tenor on the frozen curve:

```
r = 0.02 + 0.99726 · (0.03 − 0.02) = 0.029973  →  2.9973%
P_frozen = 100 · e^(-0.029973 · 1.99726) = 100 · 0.941893 = 94.1893
```

Total time P&L `= 94.1893 − 94.1765 = +0.0128`.

### 6.3 Pure time decay (rate held at 3%, one day closer)

```
P_pure = 100 · e^(-0.03 · 1.99726) = 100 · 0.941842 = 94.1842
pure time decay = 94.1842 − 94.1765 = +0.0077
```

### 6.4 Rolldown (the slope contribution)

```
rolldown = total − pure = 0.0128 − 0.0077 = +0.0051
```

And crucially, **rolling the curve** gives exactly `P_pure`:

```
r_roll(1.99726) = r_old(1.99726 + 0.00274) = r_old(2.00000) = 3.00%
P_roll = 100 · e^(-0.03 · 1.99726) = 94.1842 = P_pure
```

So `rolldown = P_frozen − P_roll = 94.1893 − 94.1842 = +0.0051`.

### 6.5 Carry — the cash piece (coupon bond)

2Y, 5% annual coupon, `F = 100`, same curve:

```
P0 = 5·e^(-0.02·1) + 105·e^(-0.03·2) = 4.90099 + 98.88527 = 103.7863
accrued coupon   = 5 · (1/365)          = +0.01370
financing cost   = 103.7863 · 0.03/365  = −0.00853
net carry        = +0.00517
```

A coupon bond's one-day P&L is therefore `carry + time decay + rolldown`.

### 6.6 Theta — option time-value decay

ATM Black-76 call (`F = K = 3%`, `σ = 20%`): `C = F · [2N(0.5·σ·√t) − 1]`.

```
t = 1:        0.5·σ·√t  = 0.10,    N(0.10) = 0.53983,  C  = 0.03 · 0.07966 = 0.002390
t' = 1−1/365: 0.5·σ·√t' = 0.09986, N ≈ 0.53977,        C' = 0.03 · 0.07955 = 0.002386
theta ≈ C' − C = −0.000004 (per unit notional, per day)
```

**Negative** for a long option: time value scales like `σ·√t`, so it decays as `t`
shrinks. An option also rides its underlying curve, so its total time P&L = theta +
its own small rolldown.

## 7. Design implications for Stage 3

1. **Stage 3's `theta` = "instrument rolls down."** Per `plan.md` — "value change
   holding the market fixed while advancing the valuation date" — this is the
   curve-frozen, instrument-aged revaluation, so **it already contains rolldown**.
   This must be reflected in how the number is named and commented in code, so it is
   not mistaken for the options-only "time value decay."
2. **Carry is the separate cash piece** (coupon/interest accrual − financing), additive
   to the price-based theta.
3. **Defer the fine split.** The third reprice that ages the dates but discounts each
   cashflow at its *original* tenor (the "curve rolls down" case) yields
   `pure time decay`, and `rolldown = theta − pure time decay`. This split is **not**
   needed for Stage 3's acceptance criterion (a one-day horizon with correct, non-zero
   time P&L) — defer it to Stage 6, where the P&L attribution identity must reconcile
   bucket-by-bucket.

## 8. Deferred / future work

- **`rolldown` vs. `pure time decay` sub-split** → Stage 6/9 (attribution identity).
- **Date-anchored schedules & accrual** (clean/dirty price, coupon-date re-anchoring
  across accrual boundaries) — Stage 3 uses a uniform `elapsedYears` tenor shift, exact
  for a one-day horizon, approximate beyond the first accrual period. *(Superseded in
  Stage 4 — see §9.)*
- **Financing / repo cost as an explicit input** — currently only the accrual side of
  carry is exercised; funding cost is deferred with the XVA/portfolio layers.

## 9. Post-Stage-4 update (recorded after implementation)

Stage 3 shipped `Pricing.price(instrument, market, elapsedYears)`: a uniform tenor shift
in which `theta` was the *dirty* value change (it contained accrual) and `carry` was the
accrued coupon. Stage 4 replaced the time axis with date-derived year fractions
(`TimeConventions`, `Schedule`), so a valuation is a function of the valuation date and
the trade's own date. Two consequences for this log:

- **`theta` is now a clean-price change.** The dirty change contains accrual, so adding
  an accrual-based carry would double-count it. Stage 4 defines
  `theta = clean(t1) − clean(t0)` and `carry = Δaccrued + contractual cash in the window`;
  the total is unchanged, and the split now matches §4's decomposition (carry is cash,
  time decay is price). A premium bond correctly shows negative clean theta (premium
  amortisation) and positive carry.
- **Date-anchored schedules and accrual are no longer deferred.** Coupon dates are
  calendar dates measured from the trade date, each period accrues on the day-count basis
  between its actual dates, clean and dirty values are distinguished (`Pricing.accrued`),
  and a trade is worth zero at its settlement date. The `elapsedYears` shift is gone.

The rolldown-vs-pure-time-decay split (§7.3) and financing cost (§8) remain deferred to
Stage 6, as recorded. The Stage 4 identity and its buckets are in
[stage-4-trade-portfolio-layer.md](stage-4-trade-portfolio-layer.md).

