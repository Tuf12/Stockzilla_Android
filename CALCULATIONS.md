# Calculations & Derived Metrics

## Overview

Stockzilla calculates all ratios and derived values from two raw inputs: **live stock price** (Finnhub) and **fundamental financial data** (SEC EDGAR). Nothing is pulled pre-computed from a third party.

This document defines **financial calculations only**. Score normalization and composite scoring belong to the scoring spec and are intentionally separate.

**TTM = Trailing Twelve Months** — sum of exactly 4 standalone 10-Q quarters (each 60–140 days). This is used for income statement and cash flow metrics to provide the most current annual picture. TTM is only computed when all four quarters are available; no partial-quarter annualization is performed. When TTM is unavailable, the latest 10-K annual value is used instead.

**Annual = Latest 10-K** — the authoritative full-year value from the most recent 10-K/20-F/40-F filing. Always used for YoY growth calculations and as the fallback when TTM is not available.

**Data sourcing rule**: Finnhub is used **only** for current stock price. All fundamentals (revenue, net income, shares, etc.) come exclusively from SEC EDGAR.

---

## Metric Domain Classification

### Domain A: Raw SEC Facts

Examples: `revenue`, `netIncome`, `eps`, `totalAssets`, `totalLiabilities`, `currentAssets`, `currentLiabilities`, `retainedEarnings`, `operatingCashFlow`, `outstandingShares`.

These should be stored as extracted filing facts (or direct metadata), without scoring transforms.

### Domain B: Standard Derived Financial Metrics

Examples: `marketCap`, `peRatio`, `psRatio`, `pbRatio`, `roe`, `debtToEquity`, `currentRatio`, `netMargin`, `ebitdaMargin`, `fcfMargin`, `workingCapital`, YoY growth.

These are deterministic formula outputs from Domain A (+ price where needed) and are valid in Full Analysis.

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
| **Free Cash Flow** | `Operating Cash Flow − Capital Expenditures` | EDGAR |
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
| **Gross Margin Trend** | `Current Gross Margin − Prior Year Gross Margin` | Annual 10-K data |
| **EBITDA Margin Growth** | `Current EBITDA Margin − Prior EBITDA Margin` | TTM-preferred for current, annual for prior |
| **Free Cash Flow Growth** | `(Current FCF − Prior FCF) ÷ Prior FCF` | Annual 10-K history |

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