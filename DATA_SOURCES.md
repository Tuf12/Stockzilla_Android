# Data Sources

## Overview

Stockzilla uses a hybrid data architecture with two sources:
- **Finnhub** — Real-time market price data only
- **SEC EDGAR** — All fundamental financial data from official SEC filings

This separation minimizes API costs (EDGAR is free) while still providing live pricing.

---

## Finnhub (Live Data)

Finnhub provides **only the current stock price**. All other values are either pulled from EDGAR or calculated mathematically.

| Data Point | Endpoint | Notes |
|---|---|---|
| **Stock Price** | `/quote` | Current price, open, high, low, previous close |

**API Details:**
- Base URL: `https://finnhub.io/api/v1`
- Authentication: API key as query parameter (`?token=YOUR_KEY`)
- Free tier rate limit: 60 calls/minute
- Caching strategy: Cache prices for short periods (e.g., 1–5 minutes) to avoid hitting limits

**Why only price?** Every other metric can be calculated more accurately from EDGAR fundamentals + live price. Pre-computed ratios from third-party APIs often use stale or differently-calculated figures.

---

## SEC EDGAR (Fundamental Data)

EDGAR provides all financial statement data from official SEC filings (10-K annual, 10-Q quarterly). This is the authoritative source — it's the same data companies file with the government.

### API Endpoints

**Company Facts (primary):**
```
https://data.sec.gov/api/xbrl/companyfacts/CIK{cik_number_zero_padded}.json
```
Returns all XBRL-tagged financial data for a company across all filings.

**Company Search:**
```
https://efts.sec.gov/LATEST/search-index?q={company_name_or_ticker}
```

**Company Tickers (CIK lookup):**
```
https://www.sec.gov/files/company_tickers.json
```

### API Requirements
- **User-Agent header required**: Must include app name and contact email per SEC policy
  - Example: `User-Agent: Stockzilla/1.0 (contact@email.com)`
- **Rate limit**: 10 requests per second
- **Cost**: Completely free
- **Data format**: JSON with XBRL tags

### Data Fields Needed from EDGAR

**From Income Statement (10-K / 10-Q):**

| Data Point | Common XBRL Tags | Used For |
|---|---|---|
| Revenue | `Revenues`, `RevenueFromContractWithCustomerExcludingAssessedTax`, `SalesRevenueNet` | PS ratio, revenue growth, asset turnover |
| Net Income | `NetIncomeLoss`, `ProfitLoss` | PE ratio, net income growth, ROE |
| Operating Income (EBIT) | `OperatingIncomeLoss` | Altman-Z, operating efficiency |
| Gross Profit | `GrossProfit` | Gross margin calculations |
| EBITDA | `EarningsBeforeInterestTaxesDepreciationAndAmortization` (rare — often must be calculated: Operating Income + Depreciation & Amortization) | EBITDA margin, core health score |
| EPS | `EarningsPerShareBasic`, `EarningsPerShareDiluted` | Core health score |
| Cost of Revenue | `CostOfRevenue`, `CostOfGoodsAndServicesSold` | Gross profit calculation if not directly available |

**From Balance Sheet (10-K / 10-Q):**

| Data Point | Common XBRL Tags | Used For |
|---|---|---|
| Total Assets | `Assets` | Financial health, Altman-Z, ratio calculations |
| Total Liabilities | `Liabilities`, `LiabilitiesAndStockholdersEquity` minus `StockholdersEquity` | Financial health, Altman-Z |
| Current Assets | `AssetsCurrent` | Current ratio, working capital |
| Current Liabilities | `LiabilitiesCurrent` | Current ratio, working capital |
| Total Stockholders' Equity | `StockholdersEquity` | ROE, debt-to-equity |
| Total Debt | `LongTermDebt`, `ShortTermBorrowings`, `LongTermDebtAndCapitalLeaseObligations` | Debt-to-equity |
| Cash & Cash Equivalents | `CashAndCashEquivalentsAtCarryingValue` | Liquidity analysis |
| Retained Earnings | `RetainedEarningsAccumulatedDeficit` | Altman-Z |
| Shares Outstanding | `CommonStockSharesOutstanding`, `EntityCommonStockSharesOutstanding` | Market cap, per-share calculations |

**From Cash Flow Statement (10-K / 10-Q):**

| Data Point | Common XBRL Tags | Used For |
|---|---|---|
| Operating Cash Flow | `NetCashProvidedByOperatingActivities` | Free cash flow, cash flow quality |
| Capital Expenditures | `PaymentsToAcquirePropertyPlantAndEquipment` | Free cash flow |
| Depreciation & Amortization | `DepreciationDepletionAndAmortization` | EBITDA calculation |

**From Filing Metadata:**

| Data Point | Source | Used For |
|---|---|---|
| SIC Code | Company filing header / CIK lookup | Stock grouping by industry |
| Filing Date | Filing metadata | Determining data freshness |
| Fiscal Period | Filing metadata | TTM calculations |

### XBRL Tag Mapping Notes

Different companies may use slightly different XBRL tags for the same data. The app should try multiple tag variants in priority order. For example, for revenue:
1. Try `Revenues`
2. Try `RevenueFromContractWithCustomerExcludingAssessedTax`
3. Try `SalesRevenueNet`
4. Try `SalesRevenueGoodsNet`

### Data Persistence Strategy

EDGAR data serves two purposes: **immediate display** AND **long-term accumulation** for dynamic benchmarks.

**On every stock analysis:**
1. Fetch fundamental data from EDGAR
2. Display to user with scores
3. **Save to `analyzed_stocks` table** in Room database — this builds the peer database over time
4. Cache in `stock_cache` for short-term reuse (expires next trading day)

**Why we save every analyzed stock:**
- The `analyzed_stocks` table accumulates over time, building a local database of financial data
- This data calculates **dynamic industry averages** (PE, PS) that replace hardcoded benchmarks
- It powers the **Similar Stocks** feature by providing a pool of peers to query
- It enables **dynamic normalization ranges** — instead of hardcoded min/max for each metric, ranges are derived from actual stock distributions in each sector/cap tier
- The more stocks the user analyzes, the more accurate the app becomes

**Refresh triggers:**
- EDGAR fundamental data only changes when new filings are published (quarterly)
- Check for new filings via `https://data.sec.gov/submissions/CIK{cik_number}.json`
- Compare the most recent filing date against `lastFilingDate` stored in `analyzed_stocks`
- If a newer filing exists, pull fresh data from EDGAR and update the record
- Favorited stocks get periodic background checks; other stocks refresh on next user access

**Finnhub price caching:**
- Price data cached for short periods (1–5 minutes) or until next trading day
- On cache hit for fundamentals, price is still refreshed separately from Finnhub
- Price-dependent ratios (PE, PS, market cap) are recalculated at display time using latest price + stored fundamentals

See `DATABASE_ARCHITECTURE.md` for full schema details on the `analyzed_stocks`, `favorites`, and `stock_cache` tables.

---

## What Goes Where — Summary

| Need | Source | Method |
|---|---|---|
| Stock Price | **Finnhub** | Live API call |
| All Financial Statements | **SEC EDGAR** | API call, cached in Room |
| Market Cap | **Calculated** | Price × Shares Outstanding |
| PE Ratio | **Calculated** | Price ÷ (Net Income TTM ÷ Shares) |
| PS Ratio | **Calculated** | Price ÷ (Revenue TTM ÷ Shares) |
| All Growth Metrics | **Calculated** | YoY comparison from EDGAR quarterly data |
| Industry Averages | **Calculated** | Average PE/PS from BenchmarkData (currently hardcoded, future: dynamic from SIC peers) |
| Financial Health Score | **Calculated** | Three-pillar composite from EDGAR data |
| Altman Z-Score | **Calculated** | 5-variable formula from EDGAR + Market Cap |
| Stock Grouping | **SIC Code + Market Cap** | SIC from EDGAR metadata, market cap calculated |