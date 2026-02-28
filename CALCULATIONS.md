# Calculations & Derived Metrics

## Overview

Stockzilla calculates all ratios and derived values from two raw inputs: **live stock price** (Finnhub) and **fundamental financial data** (SEC EDGAR). Nothing is pulled pre-computed from a third party.

**TTM = Trailing Twelve Months** — sum of the last 4 quarters of data from 10-Q filings. This is used for income statement and cash flow metrics to get the most current annual picture.

---

## Core Valuation Ratios

| Metric | Formula | Inputs |
|---|---|---|
| **Market Cap** | `Stock Price × Shares Outstanding` | Finnhub price + EDGAR shares |
| **EPS** | `Net Income (TTM) ÷ Shares Outstanding` | EDGAR |
| **PE Ratio** | `Stock Price ÷ EPS` | Finnhub + EDGAR |
| **Revenue Per Share** | `Revenue (TTM) ÷ Shares Outstanding` | EDGAR |
| **PS Ratio** | `Stock Price ÷ Revenue Per Share` | Finnhub + EDGAR |
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

All growth metrics compare current period to prior period (typically YoY — current TTM vs prior year TTM, or current quarter vs same quarter prior year).

| Metric | Formula | Notes |
|---|---|---|
| **Revenue Growth (YoY)** | `(Current Revenue − Prior Revenue) ÷ Prior Revenue` | Top line growth |
| **Average Revenue Growth** | Average of multiple years' revenue growth rates | Smooths volatility for long-term trend |
| **Net Income Growth (YoY)** | `(Current Net Income − Prior Net Income) ÷ Prior Net Income` | Bottom line growth |
| **Average Net Income Growth** | Average of multiple years' net income growth rates | Long-term profitability trend |
| **Gross Margin Trend** | `Current Gross Margin − Prior Year Gross Margin` | Expanding = good, contracting = warning |
| **EBITDA Margin Growth** | `Current EBITDA Margin − Prior EBITDA Margin` | Operating leverage trend |
| **Free Cash Flow Growth** | `(Current FCF − Prior FCF) ÷ Prior FCF` | Real cash generation trend |

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