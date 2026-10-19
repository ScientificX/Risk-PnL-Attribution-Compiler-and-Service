# WrithLang DSL Reference

This document describes the WrithLang DSL: the grammar for **portfolios** and **shocks**, the instrument and field rules, and the separate **market-data** JSON format. It is derived from `com.writhlang.dsl.Parser`, `com.writhlang.dsl.Ast`, `com.writhlang.marketdata.MarketDataJson`, and the bootstrapper in `com.writhlang.marketdata.bootstrap`.

The DSL has two parts with distinct lifecycles:

- **The `.dsl` file** describes *what to value* (a recursive portfolio of trades) and *how to stress it* (shocks). It never contains market numbers — instruments and shocks *reference* market objects by name.
- **Market data** is a separate JSON document supplying the quotes/surfaces/vectors/spots those names resolve to.

Conventions throughout:

- **Rates are decimals, not percentages.** `0.04` means 4%, and `0.0001` means 1 basis point (bp).
- **Discounting is continuous.** A zero rate `r(t)` at year fraction `t` has discount factor `df(t) = e^(-r(t)·t)`.
- **Time is date-derived.** A tenor resolves to a calendar date, and every time used in pricing is the day-count year fraction from the valuation date to that date. A "1Y" pillar starting on 2024-01-01 is 366 days out, i.e. 366/365 years.

## 1. Program structure

```
shocks? node*
```

A program is an optional `shocks` block followed by zero or more top-level nodes. A top-level `instrument` is allowed and is simply a leaf at the root of the portfolio tree (its book path is empty).

## 2. Lexical elements

| Token | Pattern | Notes |
| --- | --- | --- |
| identifier | `[a-zA-Z][a-zA-Z0-9_.]*` | instrument ids, portfolio names, market-object names (may contain `.`, e.g. `EUR.CREDIT`) |
| number | `[+-]?[0-9]+(\.[0-9]+)?` | decimal (parsed as `Double`) |
| date | `YYYY-MM-DD` | a trade's start date (`tradeDate`) |
| currency | `[A-Z]{3}` | a trade's currency (`currency`) |
| tenor | `<number>[YM]` | `2Y` = 2 years, `3M` = 0.25 years |

Reserved keywords (case-sensitive): `shocks`, `shock`, `portfolio`, `instrument`, `discountcurve`, `creditcurve`, `indexcurve`, `yieldcurve`, `fxspot`, `prepay`, `swaptionvol`, `capfloorvol`, `parallel`, `bucket`, `twist`, `sine`, `absolute`, `relative`, `bond`, `mortgage`, `mbs`, `swap`, `cap`, `swaption`, `fxforward`, `call`, `put`. The property names `tradeDate` and `currency` are recognised inside an instrument block.

## 3. Grammar (EBNF)

```ebnf
program         := ws shocksBlock? (ws node)* ws ;
node            := portfolioBlock | instrumentBlock ;
portfolioBlock  := "portfolio" ws ident ws "{" ws node* ws "}" ;
instrumentBlock := "instrument" ws kind ws ident ws "{" ws prop* ws "}" ;
kind            := "bond" | "mortgage" | "mbs" | "swap" | "cap" | "swaption" | "fxforward" ;

shocksBlock     := "shocks" ws "{" ws shockEntry* ws "}" ;
shockEntry      := "shock" ws ident ws "{" ws shockMove* ws "}" ;
shockMove       := curveMove | scalarMove ;
curveMove       := curveKw ws ident ws (curvePointTail | curveShapeTail) entryEnd ;
curvePointTail  := tenor ws shiftType? ws number ;
curveShapeTail  := "parallel" ws number
                 | "bucket" ws number ws number
                 | "twist" ws number ws number (ws number)?
                 | "sine" ws number ws number (ws number)? ;
scalarMove      := scalarKw ws ident ws shiftType? ws number entryEnd ;
curveKw         := "discountcurve" | "creditcurve" | "indexcurve" | "yieldcurve" ;
scalarKw        := "fxspot" | "prepay" | "swaptionvol" | "capfloorvol" ;
shiftType       := "absolute" | "relative" ;

prop            := refProp | flagProp | dateProp | currencyProp | numberProp ;
refProp         := ("discountCurve" | "creditCurve" | "prepayCurve" | "volSurface"
                 | "domesticCurve" | "foreignCurve" | "fxSpot") ws ident entryEnd ;
flagProp        := ("call" | "put") entryEnd ;
dateProp        := "tradeDate" ws date entryEnd ;
currencyProp    := "currency" ws currency entryEnd ;
numberProp      := ident ws number entryEnd ;

entryEnd        := ws ";"? ws ;
ws              := [ \r\n\t]* ;
```

## 4. Portfolios

A `portfolio` is a recursive container: a book may nest sub-books and instruments, and may be empty.

```
portfolio RatesDesk {
  portfolio BookA {
    instrument bond B1 { ... }
  }
  instrument swap S1 { ... }
}
```

Portfolio names are preserved as a path (e.g. `RatesDesk/BookA`) and drive the book-level roll-up. Instrument ids must be unique across the whole program: the DAG is keyed by trade id, so duplicates are rejected rather than silently overwritten.

## 5. Instruments

```
instrument <kind> <id> { <field> <value>; ... }
```

Fields are numbers, market references, a trade date, a currency, or the `call`/`put` flag. Market references resolve a name to a risk-factor key by the *field name*:

| Field | Risk-factor key |
| --- | --- |
| `discountCurve <n>` | `DiscountCurve:<n>` |
| `creditCurve <n>` | `CreditCurve:<n>` |
| `prepayCurve <n>` | `Prepay:<n>` |
| `volSurface <n>` | `SwaptionVolatility:<n>` |
| `domesticCurve <n>` / `foreignCurve <n>` | `DiscountCurve:<n>` |
| `fxSpot <n>` | `FxSpot:<n>` |

Two fields are common to every kind:

| Field | Type | Req | Notes |
| --- | --- | --- | --- |
| `tradeDate` | date | no* | the trade's own start date. Absent, the trade is treated as entered on the snapshot's valuation date. **Required in a period run** (`--prev-input`), because an implicit date would re-anchor the trade at each snapshot and it would never age. |
| `currency` | code | no | the trade's currency, three uppercase letters. Absent, the trade is in the reporting currency. Must not change between snapshots. |

The referenced market objects must exist in the market-data snapshot; the DSL cannot see the snapshot, so a missing object is not a parse error (see [§10](#10-validation)).

### 5.1 `bond`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `coupon` | number | yes | — | annual coupon rate |
| `maturity` | int | yes | — | years; `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `freq` | int | no | `1` | coupons/year; must divide 12 |

### 5.2 `mortgage`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `noteRate` | number | yes | — | the loan's contractual rate (a trade term, not market data) |
| `term` | int | yes | — | months; `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `prepayCurve` | ref | yes | — | |

### 5.3 `mbs`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `wac` | number | yes | — | weighted-average coupon |
| `wam` | int | yes | — | weighted-average maturity (months); `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `prepayCurve` | ref | yes | — | |

### 5.4 `swap`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `fixedRate` | number | yes | — | |
| `maturity` | int | yes | — | years; `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `freq` | int | no | `1` | payments/year; must divide 12 |

### 5.5 `cap`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `strike` | number | yes | — | |
| `maturity` | int | yes | — | years; `> 0` |
| `freq` | int | no | `4` | caplets/year; must divide 12 |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `volSurface` | ref | yes | — | |

### 5.6 `swaption`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `strike` | number | yes | — | |
| `expiry` | number | yes | — | years; `> 0` |
| `maturity` | int | yes | — | swap maturity (years); `> 0` |
| `freq` | int | no | `2` | payments/year; must divide 12 |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `volSurface` | ref | yes | — | |
| `call`/`put` | flag | no | `call` | `call` = payer, `put` = receiver |

### 5.7 `fxforward`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `fxRate` | number | yes | — | the contracted forward rate (trade term) |
| `maturity` | number | yes | — | years; `> 0` |
| `domesticCurve` | ref | yes | — | |
| `foreignCurve` | ref | yes | — | |
| `fxSpot` | ref | yes | — | |

## 6. Instrument dates

Terms are counts; the engine turns them into calendar dates measured from the trade date:

| Instrument | Settlement (life ends) |
| --- | --- |
| `bond`, `swap`, `cap` | tradeDate + `maturity` years |
| `mortgage` / `mbs` | tradeDate + `term` / `wam` months |
| `swaption` | tradeDate + `expiry` months (the option expires; the swap is measured from expiry) |
| `fxforward` | tradeDate + `maturity` months |

Coupon and payment dates are generated by adding whole months from the trade date (calendar month-end roll-over, e.g. 2024-01-31 + 1M = 2024-02-29) and rolling each date onto a business day under the snapshot's business-day convention (default Unadjusted). Each period accrues on the day-count basis between its two actual dates. A trade is worth **zero** on or after its settlement date; its final cashflow is cash.

## 7. Shocks

A `shock` is a named bundle of moves. The scenario name is an arbitrary label; the *direction* is the sign of each move, and the *shift type* (`absolute`/`relative`) defaults by factor kind (rates/prepay → absolute; fx/vol → relative).

```
shock <name> { <move>; <move>; ... }
```

**Curve pillar move** — bump one par instrument's quote, then re-bootstrap (par-conversion):

```
discountcurve EUR 2Y absolute +0.0001
```

**Curve shape move** — transform all of a curve's quotes, then re-bootstrap:

```
discountcurve EUR twist -0.001 0.001 10
discountcurve EUR parallel +0.0005
discountcurve EUR bucket 5 0.001
discountcurve EUR sine 0.005 0.5 0
```

Shape arguments: `parallel <amount>`; `bucket <tenorYears> <amount>` (tent of 1-year width); `twist <shortAmount> <longAmount> [pivotYears]` (pivot defaults to `10`); `sine <amplitude> <omega> [phase]` (phase defaults to `0`).

**Scalar move** — bump a spot/surface/vector:

```
fxspot EURUSD relative +0.01
prepay EUR.MBS +0.005
swaptionvol EUR.SWAPTION relative +0.01
capfloorvol EUR.CAP relative +0.01
```

A move whose target curve is **quote-backed** bumps the quote and re-bootstraps the curve; a move on a **flat** curve (e.g. a credit spread) is applied directly to the curve's rate function.

## 8. Market data (JSON)

Market data is a separate JSON document. All blocks are optional; missing blocks simply leave those objects absent. The loader (`MarketDataJson.parse`) reads:

| Block | Shape |
| --- | --- |
| `asOf` | ISO date string, `"YYYY-MM-DD"` (defaults to `2024-01-01`) |
| `curves` | name → `{ "type": <KeyType name>, "instruments": [...] }` **or** `{ "type": ..., "flat": rate }` |
| `fxSpots` | name → number |
| `volSurfaces` | name → `{ "flat": volatility }` |
| `prepayVectors` | name → `{ "termMonths": n, "flat": cpr }` or `{ "termMonths": n, "ramp": { "start": cpr, "end": cpr, "months": n } }` |

Correlation matrices exist in the model (`MarketData.correlations`) but are not yet loadable from JSON. Day-count/calendar conventions are not in the JSON: they come from `TimeConventions` supplied to the loader (default Actual/365F, weekends-only, Unadjusted) and are carried on the snapshot.

### 8.1 Curves

A curve entry takes one of two forms:

- **Bootstrapped** — `"instruments": [...]` lists the quoted par instruments; the curve is solved from them ([§9](#9-worked-example-from-quotes-to-curves-and-a-credit-spread)).
- **Flat** — `"flat": rate` declares a constant zero rate, for objects that are not bootstrapped (typically a credit spread).

`type` must be a `KeyType` name. The curve-shaped types are `DiscountCurve`, `IndexCurve`, `YieldCurve`, `CreditCurve`, `CommodityCurve`, `InflationZero`, `InflationYoY`, and `DividendYield`; the engine's instruments currently consume only `DiscountCurve` and `CreditCurve`.

The three par-instrument kinds accepted inside `instruments`:

| `kind` | Fields | Pins |
| --- | --- | --- |
| `deposit` | `tenor`, `rate` | `df(tenor)` directly |
| `future` | `start`, `end`, `rate` | `df(end)` from the interpolated `df(start)` (a forward rate over `[start, end]`) |
| `swap` | `tenor`, `rate`, optional `freq` (default `1`) | `df(tenor)` through the par-swap equation |

Tenors are written `"3M"`, `"2Y"`, etc.; they resolve to calendar dates from `asOf` and every discount factor is quoted at the day-count year fraction to that date. Instruments may be listed in any order — the bootstrapper sorts by maturity.

### 8.2 Other market objects

- `fxSpots.<name>` is a spot quote. The pair convention is `EURUSD = 1.25` meaning **1 EUR buys 1.25 USD**; translation to a reporting currency uses a direct pair when present, otherwise the inverse.
- `volSurfaces.<name>.flat` is a flat volatility; point-based surfaces are not yet supported by the JSON loader.
- `prepayVectors.<name>` is either a constant CPR (`flat`) or a linear ramp (`ramp`); `termMonths` sets the vector length.

## 9. Worked example: from quotes to curves and a credit spread

This section walks a two-curve market-data file end to end. The two most easily misread things are (a) that swap quotes are **par rates**, not zero rates, and (b) that a `CreditCurve` holds a **spread**, not a rate.

### 9.1 The input

```json
{
  "asOf": "2024-01-01",
  "curves": {
    "EUR": { "type": "DiscountCurve", "instruments": [
      { "kind": "deposit", "tenor": "1Y", "rate": 0.035 },
      { "kind": "swap", "tenor": "2Y", "rate": 0.040, "freq": 1 },
      { "kind": "swap", "tenor": "3Y", "rate": 0.045, "freq": 1 }
    ]},
    "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0015 }
  }
}
```

Two curves, two origins: `EUR` is **derived** from three quotes; `EUR.CREDIT` is a **declared** flat 15bp.

### 9.2 Dates before factors

From `asOf = 2024-01-01` the tenors resolve to 2025-01-01, 2026-01-01 and 2027-01-01. 2024 is a leap year, so:

| Period | Days | Year fraction (Actual/365F) |
| --- | --- | --- |
| asOf → 1Y | 366 | 1.00273973 |
| 1Y → 2Y | 365 | 1.00000000 |
| 2Y → 3Y | 365 | 1.00000000 |

### 9.3 Step 1 — the deposit pins `df(1Y)`

A deposit quote is a continuously-compounded cash rate, so it inverts directly:

```
df(1Y) = e^(-0.035 × 1.00273973) = 0.96551283
```

### 9.4 Step 2 — the 2Y swap pins `df(2Y)`

A **par swap** is quoted at the fixed rate that makes it worth zero at inception: fixed leg = floating leg. On a single curve the floating leg is worth `1 − df(T)` (a floating-rate note is worth par at each reset, so the legs telescope to today's factor minus the maturity factor), and the fixed leg is a strip of discounted fixed payments:

```
K·Σ_{i=1..N} accrualᵢ·df(tᵢ) = 1 − df(T)
```

The **last** coupon's factor is `df(T)` — the single unknown — so isolating it gives the bootstrap equation:

```
df(T) = (1 − K·Σ_{i<N} accrualᵢ·df(tᵢ)) / (1 + K·accrual_N)
```

with `accrualᵢ` the day count between the swap's actual coupon dates. For the 2Y swap at 4.0% with `freq 1`:

```
Σ accrual·df = 1.00273973 × 0.96551283          = 0.96815807
df(2Y)       = (1 − 0.040 × 0.96815807) / 1.040 = 0.92430161
```

The intuition: the 2Y quote fixes the *average* discounting out to 2Y; everything before 2Y is already known from the deposit, so the residual is one equation in one unknown.

### 9.5 Step 3 — the 3Y swap pins `df(3Y)`

Both earlier factors are now known:

```
Σ accrual·df = 1.00273973×0.96551283 + 1.0×0.92430161 = 1.89245968
df(3Y)       = (1 − 0.045 × 1.89245968) / 1.045       = 0.87544432
```

### 9.6 The resulting curve

| Pillar | Date | Discount factor | Zero rate `−ln(df)/yf` | Implied forward into the pillar |
| --- | --- | --- | --- | --- |
| 1Y | 2025-01-01 | 0.96551283 | 3.5000% | — (deposit quote) |
| 2Y | 2026-01-01 | 0.92430161 | 3.9305% | 1Y→2Y: 4.3621% |
| 3Y | 2027-01-01 | 0.87544432 | 4.4301% | 2Y→3Y: 5.4307% |

Note the difference between the input quotes (3.5% / 4.0% / 4.5%, which are **par** rates) and the solved zero rates (3.5% / 3.93% / 4.43%).

### 9.7 Between and beyond the pillars

Interpolation is **log-linear in discount factors**: `ln df` is linear in the year fraction, i.e. the instantaneous forward rate is constant between pillars ("flat forward"). Midway between the 2Y and 3Y pillars (`t = 2.5`):

```
df(2.5) = exp[ ½·ln 0.92430161 + ½·ln 0.87544432 ] = 0.89967517
```

- **Before the first pillar** the curve runs a flat forward from the anchor `(0, 1)` to the first pillar.
- **After the last pillar** it extrapolates the last segment's forward flat: with this file `df(5.0) = 0.87544432 · e^(-0.054307·(5.0 − 3.00273973)) = 0.78545767`.

Two consequences worth knowing:

- Extrapolation past the final pillar is **silent**. A 10Y trade priced against a 3Y curve is valued on a flat 5.43% forward, and nothing warns you.
- Intermediate coupon dates are interpolated from pillars solved *so far*. With no pillar solved yet the interpolator returns `1.0` for every tenor; once a first pillar exists, earlier tenors use a flat forward from the anchor.

### 9.8 The credit curve: a spread over the base curve

`EUR.CREDIT` is **not** a second discount curve that replaces `EUR`. It is a **spread curve added to it** at pricing time: `MarketData.combinedCurve` returns the base curve `plus` the credit curve, and `plus` adds *rates* pointwise.

Because discounting is continuous, adding rates multiplies discount factors:

```
r_eff(t) = r_EUR(t) + spread(t)
df_eff(t) = df_EUR(t) · e^(-spread(t)·t)
```

For the 2Y point of this file:

| Quantity | Value |
| --- | --- |
| Base 2Y zero rate (from `EUR`) | 3.9305% |
| Credit spread | 0.0015 = 15bp = 0.15% |
| Effective 2Y zero rate | 4.0805% |
| `df(2Y)` risk-free → with credit | 0.92430161 → 0.92152908 |

Three points of care:

1. **The base is the named `discountCurve`, not the par quote.** The spread is added to the *zero* rate at each cashflow's tenor — 3.9305% at 2Y for this file. That is a different number from the 4.00% 2Y par swap quote, which is a blend of the 1Y zero rate and the 1Y→2Y forward and sits *above* the 2Y zero rate. A 15bp spread is therefore not "15bp over the 4.0% swap".
2. **It must stay a spread.** Because the engine *adds* the two rates, putting an absolute credit-risky yield (say `0.04`) in `EUR.CREDIT` would double-count the base rate and price trades near 7.9%. The flat value is a spread in the same units as a rate — an annual decimal.
3. **It is a discounting spread on a single-curve engine.** There is no separate projection curve: the same combined curve discounts cashflows *and* produces forward rates, so the 15bp also shifts any forward derived from that curve.

### 9.9 Why a spread, and not a rate

Storing credit as a spread over a base curve (rather than as a standalone risky discount curve) is the representation the rest of the engine assumes. The reasons:

- **The algebra is additive.** With `df = e^(-r·t)`, adding rates is multiplying discount factors. `Curve` composes by rate addition (`Curve.plus`, `Curve.shifted`); there is no curve-product operation. A spread therefore composes cleanly, and its contribution is exactly the multiplier `e^(-spread·t)`.
- **The two inputs move independently.** A base-curve move and a credit move are different market events driven by different data feeds. Keeping credit as a spread means a larger/smaller base curve does not silently rewrite the credit assumption, and each can be shocked on its own.
- **Credit is quoted that way.** Credit markets quote *relative* to a base — a CDS spread, an asset-swap spread, a z-spread — not an absolute risk-free-plus-credit zero rate.
- **Risk and explain granularity.** They are distinct risk factors with distinct hedges and distinct P&L buckets; the attribution work in `plan.md` Stage 5/6 needs them separated.

What this is **not**: it is not a credit model. There is no default probability, no recovery rate, no hazard-rate or CDS bootstrap, and the curve is flat-only in JSON. It is a discounting spread layered on the base curve.

### 9.10 Which trades consume which curves

| Instrument | Curves used |
| --- | --- |
| `bond`, `mortgage`, `mbs`, `swap`, `cap`, `swaption` | `combinedCurve(discountCurve, creditCurve)` — base + spread |
| `fxforward` | `curve(domesticCurve)` and `curve(foreignCurve)` **raw** — no credit spread is added |

### 9.11 How shocks move each kind of curve

| Curve kind | Shock form | Effect |
| --- | --- | --- |
| Bootstrapped (`instruments`) | `discountcurve EUR 2Y absolute +0.0001` | Bump that one quote, re-bootstrap; later pillars move too |
| Bootstrapped, shaped | `discountcurve EUR twist -0.001 0.001 10` | Transform every quote by the shape, re-bootstrap |
| Flat (`flat`) | `creditcurve EUR.CREDIT absolute +0.0001` | Add to the flat rate function; 15bp becomes 16bp |

Because a quote-backed curve exposes one risk factor **per pillar** (`DiscountCurve:EUR:1Y`, `:2Y`, `:3Y`) while a flat curve exposes a single undimensioned key (`CreditCurve:EUR.CREDIT`), the two curve kinds also produce different Greeks.

## 10. Validation

`Parser.parseProgram` returns `Left(error)` when a required field is missing, a field has the wrong shape, `maturity`/`term`/`wam`/`freq` is not a positive integer, `maturity`/`expiry`/`term` is not positive, `freq` does not divide 12, `tradeDate` is not a valid date, `currency` is not three uppercase letters, or the `kind` is unknown. `MarketDataJson.parse` returns `Left` on malformed JSON, an unknown `KeyType` name, an unknown par-instrument kind, or a curve that fails to bootstrap (a non-positive or non-decreasing discount factor). Errors are `; `-separated.

The DSL parser has no knowledge of the market-data snapshot, so **references are not validated against it**. A trade naming `creditCurve EUR.CREDIT` when that entry is absent still parses; the failure appears when the referenced object is looked up during execution.

The compiler adds its own guards: duplicate trade ids are rejected, a single-snapshot book mixing currencies is rejected, and a period run requires an explicit `tradeDate` on every trade.

## 11. Running a single snapshot

```
./gradlew :app:run --args="--input portfolio.dsl --market market.json --theta-days 1"
```

prints, per trade, the base value, one-day theta, carry, and per shock the exact re-priced value plus its linear and quadratic Taylor approximations.

## 12. Running a period: two dated snapshots

```
./gradlew :app:run --args="--input curr.dsl --market curr-market.json \
  --prev-input prev.dsl --prev-market prev-market.json --reporting-currency EUR"
```

Both books declare explicit `tradeDate`s. The CLI classifies what happened between the snapshots (unchanged / amended / new / cancelled / expired) and prints one row per trade with its P&L buckets, the book totals and a reconciliation line:

```
--- Period P&L (2024-01-01 -> 2024-01-02 in EUR) ---
trade           event          market    theta    carry  inception      new   total
A               unchanged     -0.1510   0.0072   0.0877                      -0.0561
B               amended       -0.0025  -0.0113   0.1315    22.1432            22.2610
BOOK                          -0.1535  -0.0041   0.2192    22.1432    0.0000  22.2049
reconciliation: sum(buckets) - total = 0.0000000000
```

The numbers are book-value changes plus contractual cash; discretionary unwind and entry prices are not in the data and are not claimed. Amounts are translated to the reporting currency at the closing snapshot's spot.
