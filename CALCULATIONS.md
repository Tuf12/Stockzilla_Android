# Calculations & Derived Metrics

## Overview

Stockzilla calculates all ratios and derived values from two raw inputs: **live stock price** (Finnhub) and **fundamental financial data** (SEC EDGAR). Nothing is pulled pre-computed from a third party.

This document defines **financial calculations only**. Score normalization and composite scoring belong to the scoring spec and are intentionally separate.

**TTM = Trailing Twelve Months** — sum of **four consecutive fiscal quarters** (latest `fy`/`fp` chain on quarterly forms only; each period ~60–140 days). If that chain is incomplete, TTM is **null** for that metric. When TTM is unavailable, the latest 10-K annual value is used for display where applicable.

**Annual = Latest 10-K** — the authoritative full-year value from the most recent 10-K/20-F/40-F filing. Always used for YoY growth calculations and as the fallback when TTM is not available.

**Data sourcing rule**: Finnhub is used **only** for current stock price. All fundamentals (revenue, net income, shares, etc.) come exclusively from SEC EDGAR.

---

## Metric Domain Classification

### Domain A: Raw SEC Facts

Examples: `revenue`, `netIncome`, `eps`, `operatingIncome` (EBIT), `depreciationAmortization`, `totalAssets`, `totalLiabilities`, `currentAssets`, `currentLiabilities`, `retainedEarnings`, `operatingCashFlow`, `capex`, `outstandingShares`. Stored `ebitda` / `ebitdaTtm` / `ebitdaHistory` are **calculated** as operating income plus depreciation and amortization when both exist (not a direct XBRL EBITDA tag).

These should be stored as extracted filing facts (or direct metadata), without scoring transforms.

### Domain B: Standard Derived Financial Metrics

Examples: `marketCap`, `peRatio`, `psRatio`, `pbRatio`, `roe`, `debtToEquity`, `currentRatio`, `netMargin`, `ebitdaMargin`, `fcfMargin`, `workingCapital`, YoY growth.

These are deterministic formula outputs from Domain A (+ price where needed) and are valid in Full Analysis. In the **financial history** grid, margin percentages and other **derived-from-raw** series (including stored EBITDA and free cash flow rows) do not offer per-cell XBRL tag fix — missing cells there are fixed by correcting the underlying raw facts or tags.

### Domain C: Scoring Metrics (Not Financial Facts)

Examples: normalized values, weighted contributions, `compositeScore`, `healthSubScore`, `growthSubScore`, `resilienceSubScore`.

These are scoring outputs and must not be treated as raw or standard financial metrics.

---

## Core Valuation Ratios

| Metric | Formula | Inputs |
|---|---|---|
| **Market Cap** | `Stock Price × Shares Outstanding` | Finnhub price + EDGAR shares |
| **EPS** | `Net Income (TTM preferred, fallback Annual) ÷ Shares Outstanding` | EDGAR only |
| **PE Ratio** | `Stock Price ÷ EPS` | Finnhub price + EDGAR fundamentals |
| **Revenue Per Share** | `Revenue (TTM preferred, fallback Annual) ÷ Shares Outstanding` | EDGAR only |
| **PS Ratio** | `Stock Price ÷ Revenue Per Share` | Finnhub price + EDGAR fundamentals |
| **Price to Book** | `Stock Price ÷ (Stockholders' Equity ÷ Shares Outstanding)` | Finnhub + EDGAR |
| **Free Cash Flow** | `Operating Cash Flow − \|Capital Expenditures\|` | EDGAR — **only** when both operating cash flow and capex are available; **no** fallback to operating cash flow alone |
| **EBITDA (stored)** | `Operating Income + Depreciation & Amortization` | EDGAR — **only** when both inputs exist for that period (annual / TTM / aligned history); no standalone EBITDA tag |
| **FCF Per Share** | `Free Cash Flow ÷ Shares Outstanding` | EDGAR |
| **Price to FCF** | `Stock Price ÷ FCF Per Share` | Finnhub + EDGAR |

---

## Profitability & Efficiency Ratios

| Metric | Formula | Direction |
|---|---|---|
| **ROE (Return on Equity)** | `Net Income ÷ Stockholders' Equity` | Higher is better |
| **Net Margin** | `Net Income ÷ Revenue` | Higher is better |
| **EBITDA Margin** | `EBITDA ÷ Revenue` | Higher is better |
| **Gross Margin** | `Gross Profit ÷ Revenue` | Higher is better |
| **Free Cash Flow Margin** | `Free Cash Flow ÷ Revenue` | Higher is better |
| **Asset Turnover** | `Revenue ÷ Total Assets` | Higher is better |

---

## Leverage & Liquidity Ratios

| Metric | Formula | Direction |
|---|---|---|
| **Debt to Equity** | `Total Debt ÷ Stockholders' Equity` | Lower is better |
| **Current Ratio** | `Current Assets ÷ Current Liabilities` | Higher is better |
| **Liability to Asset Ratio** | `Total Liabilities ÷ Total Assets` | Lower is better |
| **Working Capital Ratio** | `(Current Assets − Current Liabilities) ÷ Total Assets` | Higher is better |

---

## Growth Metrics

All growth metrics use **annual 10-K history only**. TTM values are never used for growth calculations to avoid mixing data sources or introducing noise from incomplete quarters.

| Metric | Formula | Notes |
|---|---|---|
| **Revenue Growth (YoY)** | `(FY N Revenue − FY N-1 Revenue) ÷ FY N-1 Revenue` | Annual 10-K only |
| **Average Revenue Growth** | Average of multiple years' annual revenue growth rates | Annual 10-K history |
| **Net Income Growth (YoY)** | `(FY N Net Income − FY N-1 Net Income) ÷ FY N-1 Net Income` | Annual 10-K only |
| **Average Net Income Growth** | Average of multiple years' annual net income growth rates | Annual 10-K history |
| **Operating Cash Flow Growth (YoY)** | `(FY N Operating Cash Flow − FY N-1 Operating Cash Flow) ÷ FY N-1 Operating Cash Flow` | Annual 10-K only |
| **Average Operating Cash Flow Growth** | Average of multiple years' annual operating cash flow growth rates | Annual 10-K history |
| **Gross Profit Margin Growth (YoY delta)** | `Current Gross Margin − Prior Year Gross Margin` | Annual 10-K data |
| **EBITDA Margin Growth** | `Current EBITDA Margin − Prior EBITDA Margin` | TTM-preferred for current, annual for prior |
| **Free Cash Flow Growth** | `(Current FCF − Prior FCF) ÷ Prior FCF` | Annual 10-K history |
| **Average Free Cash Flow Growth** | Average of multiple years' annual FCF growth rates | Annual 10-K history |

---

## Calculated Metrics Used in Core Health Score

The `FinancialHealthAnalyzer` computes these derived metrics from raw `StockData` fields:

```
net_margin         = Net Income ÷ Revenue
ebitda_margin      = EBITDA ÷ Revenue
current_ratio      = Current Assets ÷ Current Liabilities
liability_to_asset = Total Liabilities ÷ Total Assets
working_capital    = Current Assets − Current Liabilities
working_cap_ratio  = Working Capital ÷ Total Assets
```

These are computed on-the-fly in `computeMetricValues()` and fed into the scoring pipeline alongside the raw metrics from StockData.

Important: this section documents formula definitions only. It does not authorize score outputs on financial facts screens.

---

## Core Health Earnings-Quality Checks

The Financial Health pillar includes three earnings-quality checks that replace directional delta tests:

| Check | Formula / rule | Evaluation gate |
|---|---|---|
| **Operating income sign matches net income sign** | `sign(Operating Income) == sign(Net Income)` | Requires both values present |
| **OCF to NI greater than 1.0** | `Operating Cash Flow ÷ Net Income > 1.0` | Evaluated only when both OCF and NI are positive |
| **Non-operating income share below 50%** | `Non-operating Income = Net Income − Operating Income`; pass when `Non-operating Income < 0.5 × Net Income` | Evaluated only when net income is positive and operating income is available |

These checks reduce false positives from small year-over-year direction changes and favor profits supported by core operations and cash generation.

---

## PE/PS Smart Display Logic

The app dynamically chooses which ratio to display based on profitability:

- **If Net Income > 0**: Show PE Ratio as primary, with industry average PE as benchmark
- **If Net Income ≤ 0**: Show PS Ratio as primary, with industry average PS as benchmark

This is handled by `BenchmarkData.getDisplayMetrics()` and ensures unprofitable companies (common in small/growth stocks) still get meaningful valuation context.

---

## Fair Value Calculation

`BenchmarkData.calculateFairValue()` estimates what the stock price "should be" based on industry averages:

**For profitable companies (PE-based):**
```
Fair Value Price = (Net Income × Industry Avg PE) ÷ Shares Outstanding
```

**For unprofitable companies (PS-based):**
```
Fair Value Price = (Revenue × Industry Avg PS) ÷ Shares Outstanding
```

This gives the user a quick reference point: "If this stock traded at the industry average multiple, what would the price be?"

---

## UI Rendering Boundary

- **Full Analysis:** Domain A + Domain B only.
- **Health/Scoring screens:** Domain C (plus supporting financial context as needed).

This boundary prevents score-system changes from altering the meaning of financial statement views.

---

## Full Analysis — planned extensions (growth companies)

The following tables are **specification targets** for `FullAnalysisActivity` / `StockData` / `SecEdgarService` / quarterly persistence. Implement in code after facts exist in `StockData` or `quarterly_financial_facts`.

### Multi-year / quarterly history — additional rows

| Row | Why | Formula / source |
|-----|-----|------------------|
| **Gross profit** | Trend shows margin expansion/compression over years | Revenue − COGS (from EDGAR concepts already used or extended) |
| **Gross margin %** | More meaningful than raw gross profit YoY | Gross profit ÷ Revenue |
| **Net margin %** | Profitability trend at a glance | Net income ÷ Revenue |
| **Total debt** | Catch leverage creep YoY | See raw facts below (sum or single tag) |

### Derived metrics table — additional rows (`populateDerivedMetricsTable`)

| Metric | Why | Formula |
|--------|-----|---------|
| **Net debt** | More honest than raw debt alone | Total debt − cash & equivalents |
| **Net debt / EBITDA** | Common leverage check for growth stocks | Net debt ÷ EBITDA (guard EBITDA ≤ 0) |
| **Operating margin %** | Operating profitability (not only EBITDA) | Operating income ÷ Revenue |
| **DSO (days sales outstanding)** | Working-capital stress (e.g. receivables ballooning before OCF weakens) | Accounts receivable ÷ (Revenue / 365) |
| **Average FCF growth** | Third leg beside revenue and net income growth | CAGR or average YoY on FCF series (align with existing `averageRevenueGrowth` / `averageNetIncomeGrowth` style) |

### Raw facts table — additional XBRL-backed fields

| Display | Typical US GAAP tag (concept id) | Notes |
|---------|----------------------------------|--------|
| **Total debt** | Sum **LongTermDebtNoncurrent** + **ShortTermBorrowings** / **LongTermDebtCurrent** as applicable, or a single **Debt** / **LongTermDebt** total if consistently reported | Prefer explicit sum where filers split current/noncurrent |
| **Cash & equivalents** | **CashAndCashEquivalentsAtCarryingValue** (and/or **CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents** where used) | Match existing cash line in statements |
| **Accounts receivable** | **AccountsReceivableNetCurrent** | For DSO; quarterly + annual |
| **Operating income** | **OperatingIncomeLoss** | For operating margin % |

**Implementation note:** Some tags are point-in-time (balance sheet) vs period (income statement); quarterly history must use period-end AR with **that quarter’s** revenue for DSO.

---

## Related implementation files

| Role | Path under `app/src/main/java/com/example/stockzilla/` |
|------|--------------------------------------------------------|
| Derived metrics assembly | `data/StockApiService.kt` (includes `StockRepository`) |
| EDGAR facts | `data/SecEdgarService.kt` |
| Persisted derived row | `data/RoomDatabase.kt` (`FinancialDerivedMetricsEntity`) |
| Scoring (Domain C) | `scoring/FinancialHealthAnalyzer.kt` (`StockData`, `HealthScore` data classes live here) |
| Display / fair value helpers | `scoring/BenchmarkData.kt` |
| Full Analysis UI & history | `feature/FullAnalysisActivity.kt`; spec: [FULL_ANALYSIS.md](FULL_ANALYSIS.md) |