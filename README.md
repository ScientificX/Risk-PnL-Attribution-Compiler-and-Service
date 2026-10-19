# WrithLang

A Scala **domain-specific language (DSL) compiler and execution engine** for financial **risk and pricing traceability**.

WrithLang lets you describe a book of trades (a recursive portfolio) and market **shocks** in a small, human-readable DSL, with market data supplied as a separate JSON snapshot. From that it:

1. **parses** the DSL (FastParse) and the market data (ujson),
2. **bootstraps** discount curves from quoted instruments (deposits, futures, swaps),
3. **builds** a dependency graph (DAG) of pricing, sensitivity and P&L computations,
4. **executes** the DAG level-parallel (via ZIO),
5. **rolls the results up** from trade to book, desk and whole book, and
6. **renders** a Graphviz `.dot` graph of the computation, and reports values, Greeks and scenario prices — or, given two dated snapshots, a reconciled period P&L decomposition.

The long-term target is a P&L explain / PLA (P&L attribution) engine; the roadmap is in [plan.md](plan.md).

---

## Table of contents

- [Tech stack](#tech-stack)
- [Features](#features)
- [Architecture overview](#architecture-overview)
- [Modules](#modules)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Usage](#usage)
- [DSL quick reference](#dsl-quick-reference)
- [Market data](#market-data)
- [Output](#output)
- [Project structure](#project-structure)
- [Testing](#testing)
- [Known limitations and roadmap](#known-limitations-and-roadmap)
- [License](#license)

---

## Tech stack

| Concern | Technology |
| --- | --- |
| Language | Scala 2.13.16 |
| Build | Gradle 9.2.1 (Kotlin DSL), JDK 21 toolchain |
| Effects / concurrency | ZIO 2 (`zio_2.13` 2.0.18) |
| Parsing | FastParse 2.3.3 |
| Statistics | Apache Commons Math 3 (Black-76) |
| Logging | Logback Classic 1.5.22 + scala-logging 3.9.5 |
| Testing | ScalaTest 3.2.19 + JUnit 4 |
| Visualization | Graphviz `dot` (optional, PNG output only) |

---

## Features

- **Market data as data.** Curves are bootstrapped from quoted par instruments; the DSL only *references* market objects by name. A JSON snapshot carries the valuation date, quotes, vol surfaces, prepay vectors and FX spots.
- **Date-derived time.** Every time in pricing is the day-count year fraction between two calendar dates: a 1Y pillar from a leap year is 366/365 years, coupon dates are calendar anniversaries, and a trade is worth zero at its settlement date. Day count, calendar and business-day convention are configurable.
- **7 instrument types**: bond, mortgage, MBS pool, swap, cap, swaption, FX forward.
- **Par-conversion sensitivities**: a shock bumps a *quoted* instrument and re-bootstraps the curve; every curve pillar is its own risk factor.
- **Greeks by finite differences**: forward / backward / central delta, central gamma, cross-gamma; theta as a full revaluation at an advanced valuation date.
- **Scenario pricing**: exact full re-price, plus linear and quadratic Taylor approximations.
- **Dated trades**: `tradeDate` anchors each trade's schedules and its lifecycle (`Active` / `Expired`); `currency` marks the trade's denomination.
- **Roll-up**: base values, Greeks, scenario values, theta, carry and P&L buckets are summed per book path (`agg:` nodes), which also *nets* sensitivities across trades.
- **Period P&L**: two dated snapshots produce unchanged / amended / new / cancelled / expired events and an exactly reconciling decomposition (`market`, `theta`, `carry`, `inception`, population buckets), translated to a reporting currency when requested.
- **DAG execution** with topological ordering and parallel evaluation per level.
- **Graphviz DOT** visualization, clustered by trade, with roll-up and P&L nodes labelled.
- **Early-stage native code path** that compiles a toy `let`/`print` language to x86-64 GAS assembly (not wired into the CLI).

---

## Architecture overview

There are two largely independent code paths under the `com.writhlang` package:

1. **Risk DSL + engine** (active, tested): `dsl` → `engine` → `render`, with `risk`, `marketdata`, `scenario`, `time` and `portfolio` supporting it.
2. **Native compiler stub** (early stage, not wired into the CLI): `compiler` → `interpreter` → `examples`.

The main pipeline:

```
DSL source (.dsl)                       Market data (JSON)
   │  dsl.Parser.parseProgram              │  marketdata.MarketDataJson.parse
   ▼                                       ▼
dsl.Program ────────────────────────── marketdata.MarketData
   │  engine.DslCompiler.build (single snapshot)
   │  engine.DslCompiler.buildPeriodPnl (two snapshots, via portfolio.TradePopulation)
   ▼
engine.Dag (Instruction values, trade-keyed, with roll-ups)
   │  engine.Executor.run (ZIO, level-parallel)
   ▼
Map[String, Double] (node id → value)
   │  render.DotRenderer.toDot
   ▼
Graphviz DOT (and optional PNG via `dot`)
```

See [docs/architecture.md](docs/architecture.md) for the full design, [docs/low-level-design.md](docs/low-level-design.md) for every type and formula, and [docs/dsl-reference.md](docs/dsl-reference.md) for the grammar and market-data schema.

---

## Modules

| Module | Type | Purpose |
| ------ | ---- | ------- |
| `lib`  | library | Core DSL parser, AST, DAG builder, bootstrapper, pricing engine, executor, portfolio layer, renderer, and the native compiler stub. |
| `app`  | app | ZIO CLI entry point (`com.writhlang.app.Main`). |

The `app` module depends on `lib` and only adds the command-line driver.

---

## Prerequisites

- **JDK 21** (the `lib` module sets a Java 21 toolchain; Gradle's foojay resolver can provision it automatically).
- **Graphviz** (`dot` on `PATH`) — optional, only required to render PNG output.
- A network connection on first build to download Gradle and dependencies.

---

## Getting started

All commands are run from the repository root. On Windows, replace `./gradlew` with `gradlew.bat`.

```bash
# Compile everything and run tests
./gradlew build

# Run tests only
./gradlew test

# Run the CLI (uses the built-in example DSL and market data when no files are given)
./gradlew :app:run

# Run the CLI with your own files
./gradlew :app:run --args="--input portfolio.dsl --market market.json"
```

Gradle writes build outputs under `build/` (git-ignored).

---

## Usage

The CLI accepts the following arguments (from `com.writhlang.app.Main`):

| Argument | Meaning | Default |
| --- | --- | --- |
| `--input <path>` | DSL source file to read (UTF-8). A bare positional path also works. | built-in example DSL |
| `--market <path>` | Market-data JSON (UTF-8). | built-in example market data |
| `--prev-input <path>` | Previous DSL snapshot; turns on a **period run**. Must be given with `--prev-market`. | — |
| `--prev-market <path>` | Previous market-data JSON. | — |
| `--reporting-currency <code>` | Translate period P&L to this currency using the snapshot's FX spots (e.g. `EUR`). | no translation |
| `--dot <path>` | Where to write the Graphviz DOT graph. | `build/writhlang_dag.dot` |
| `--png <path>` | Where to render the PNG (requires Graphviz `dot`). | `build/writhlang_dag.png` |
| `--theta-days <n>` | Business-day horizon for single-snapshot theta/carry (default `1`). | `1` |

Examples:

```bash
# Single snapshot: values, Greeks, scenario prices
./gradlew :app:run --args="--input portfolio.dsl --market market.json --theta-days 1"

# Period P&L between two dated snapshots, reported in EUR
./gradlew :app:run --args="--input curr.dsl --market curr-market.json \
  --prev-input prev.dsl --prev-market prev-market.json --reporting-currency EUR"
```

A period run requires an explicit `tradeDate` on every trade: an implicit date would re-anchor the trade at each snapshot, so it would never age.

---

## DSL quick reference

A program is an optional `shocks` block followed by zero or more `portfolio` or `instrument` blocks.

```text
shocks {
  shock up2y { discountcurve EUR 2Y absolute +0.0001; }
  shock fxup { fxspot EURUSD relative +0.01; }
}

portfolio RatesBook {
  instrument bond BondA {
    notional 1000000
    coupon 0.05
    maturity 3
    discountCurve EUR
    creditCurve EUR.CREDIT
    freq 1
    tradeDate 2024-01-01
    currency EUR
  }
}
```

- **References, not numbers.** `discountCurve EUR` names a market object; the quotes live in the JSON. The `creditCurve` is a *spread over* the discount curve.
- **`tradeDate`** (optional in a single snapshot, required in a period run) anchors schedules and lifecycle.
- **`currency`** (optional) marks the trade's denomination; absent means the reporting currency.
- **`freq`** must divide 12 (1, 2, 3, 4, 6, 12).
- **Shocks** are named bundles of `PointMove`s (`fxspot EURUSD relative +0.01`) and shape moves (`discountcurve EUR twist -0.001 0.001 10`, `parallel`, `bucket`, `sine`).

The full grammar and validation rules are in [docs/dsl-reference.md](docs/dsl-reference.md).

---

## Market data

Market data is a separate JSON document. A curve is either bootstrapped from quoted par instruments or flat (typically a credit spread):

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
  },
  "fxSpots": { "EURUSD": 1.25 },
  "volSurfaces": { "EUR.SWAPTION": { "flat": 0.20 } },
  "prepayVectors": { "EUR.MBS": { "termMonths": 360, "flat": 0.02 } }
}
```

The worked example — quotes in, bootstrapped curve out, and the credit spread on top — is in [docs/dsl-reference.md §9](docs/dsl-reference.md#9-worked-example-from-quotes-to-curves-and-a-credit-spread).

---

## Output

**Single snapshot.** For each trade: `base` value, one-day `theta` (clean price change at the advanced date) and `carry` (accrued interest plus cash), then per shock the exact re-priced value (`full`) alongside `linear` and `quadratic` Taylor approximations:

```
--- Scenario Prices ---
BondA base=1009497.1952 theta=150.4533 carry=136.9863
  up2y: full=1009496.3149 linear=1009496.3148 quadratic=1009496.3149
```

**Period run.** One row per trade classified as unchanged / amended / new / cancelled / expired, with its buckets, then book totals and a reconciliation line that must print zero:

```
--- Period P&L (2024-01-01 -> 2024-01-02) ---
trade  event          market    theta    carry  inception      new    total
A      unchanged     -0.1887   0.0090   0.1096                      -0.0701
B      amended       -0.0031  -0.0141   0.1644    27.6790            27.8262
N      new                                           504.6361       504.6361
BOOK                 -0.1918  -0.0051   0.2740    27.6790  504.6361 532.3922
reconciliation: sum(buckets) - total = 0.0000000000
```

These are **book-value** changes plus contractual cash: discretionary cash (unwind prices, entry prices, amendment fees) is not in the data and is not claimed. The identity and its bucket definitions are in [docs/low-level-design.md §10](docs/low-level-design.md#10-period-pl-identity).

The `.dot` file visualizes the same computation graph left-to-right, clustered per trade, with roll-up (`Σ`) and P&L nodes.

---

## Project structure

```text
risk-traceability-compiler/
├── app/                          # ZIO CLI application module
│   └── src/main/scala/com/writhlang/app/Main.scala
├── lib/                          # Core library module
│   └── src/
│       ├── main/scala/com/writhlang/
│       │   ├── dsl/                       # AST, FastParse parser, terms → dates
│       │   ├── engine/                    # DAG builder + period compiler, executor, pricing
│       │   ├── risk/                      # factor taxonomy, canonical keys, shift types
│       │   ├── marketdata/                # curves, surfaces, vectors, spots, snapshot
│       │   │   └── bootstrap/             # par instruments, bootstrapper
│       │   ├── scenario/                  # shocks, scenarios, sensitivity config
│       │   │   └── par/                   # pillar → par-instrument mapping
│       │   ├── time/                      # day counts, calendars, schedules, conventions
│       │   ├── portfolio/                 # trades, populations, events, FX translation
│       │   ├── render/                    # Graphviz DOT output
│       │   ├── compiler/                  # early-stage native GAS backend (stub)
│       │   ├── interpreter/               # wrapper around the native compiler
│       │   └── examples/                  # native-compiler demo
│       └── test/scala/com/writhlang/      # ScalaTest suites
├── docs/                         # architecture, low-level design, DSL reference, decisions
├── plan.md                       # staged roadmap
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── README.md
```

---

## Testing

```bash
./gradlew test                                    # all tests
./gradlew :lib:test --tests com.writhlang.engine.PricingDslSuite   # one suite
```

The suites cover the DSL and pricing (`PricingDslSuite`), the date basis (`TimeBasisSuite`), theta/carry (`TimeDimensionSuite`), trade populations (`PopulationDiffSuite`), roll-up and netting (`PortfolioAggregationSuite`), the period P&L identity (`TradePnlSuite`), and currency translation (`FxConversionSuite`).

---

## Known limitations and roadmap

- **The explain/PLA layers are future stages.** Stages 0–4 are implemented (taxonomy, market-data objects, bootstrapping, dated theta/carry, trade/portfolio layer with period P&L). XVA (5), the full attribution identity with a residual (6), PLA backtesting (7), sensitivity caching (8) and the waterfall visualization (9) are not.
- **The period report is a book-value decomposition.** Discretionary cash (unwind and entry prices, amendment fees) and FX P&L of the period are not modelled; translation uses the closing spot. Both are Stage 6 buckets.
- **Single-curve engine.** One curve discounts and projects; there is no separate forwarding curve. A swap's floating leg assumes its next reset is the valuation date (no fixing history).
- **Credit is a spread, not a credit model.** No default probability, recovery, hazard-rate or CDS bootstrap; the credit curve is flat-only in JSON.
- **Market-data coverage.** Vol surfaces are function-valued (JSON supports flat only), correlation matrices are not loadable from JSON, and business-day adjustment defaults to Unadjusted on a weekends-only calendar.
- **Date basis.** Day count, calendar and business-day convention are engine settings, not JSON fields; changing them changes every number.
- **The native compiler** (`com.writhlang.compiler`) is an early-stage stub; it is not wired into the CLI and has no behavioural tests.
- `org.example.LibrarySuite` is a leftover placeholder test from the Gradle `init` template and can be removed.

The staged roadmap and its acceptance criteria are in [plan.md](plan.md); per-stage decisions and rationale are in [docs/decisions](docs/decisions).

---

## License

No `LICENSE` file is present in this repository. Please add one before redistributing.
