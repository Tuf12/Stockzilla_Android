# Data Sources

## Overview

Stockzilla uses a hybrid data architecture with two primary sources:
- **Finnhub** — Live **quote** data plus **company profile2** and **symbol search** (and optional **stock/peers** for discovery UX). Fundamentals for ratios and scoring still come from EDGAR, not Finnhub financial statements.
- **SEC EDGAR** — All fundamental financial data from official SEC filings

This separation minimizes API costs (EDGAR is free) while still providing live pricing.

The project data contract now separates:
- **Raw facts** (EDGAR facts + metadata)
- **Standard derived metrics** (deterministic finance calculations from raw facts)
- **Scoring outputs** (health/composite/growth/resilience and normalization internals)

Scoring outputs are not a data source and must not be treated as financial facts.

---

## Finnhub (Live & discovery)

Finnhub is **not** used for income-statement or balance-sheet fundamentals. Those come from EDGAR. Finnhub supplies price, light company metadata, search, and optional peer tickers.

| Data Point | Endpoint | Notes |
|---|---|---|
| **Stock Price** | `/quote` | Current price, open, high, low, previous close |
| **Company profile** | `/stock/profile2` | Name, industry label, market cap / shares (display & discovery only — EDGAR remains source of truth for filings-based fundamentals) |
| **Symbol search** | `/search` | Broader ticker resolution / disambiguation |
| **Peer tickers** | `/stock/peers` | Optional seed list for industry UI (not authoritative for fundamentals) |

**API Details:**
- Base URL: `https://finnhub.io/api/v1`
- Authentication: API key as query parameter (`?token=YOUR_KEY`)
- Free tier rate limit: 60 calls/minute
- Caching strategy: Cache prices for short periods (e.g., 1–5 minutes) to avoid hitting limits

**Why EDGAR for fundamentals?** Statement facts and histories are authoritative from filings. Third-party summaries often lag or use different definitions. Price (and optional Finnhub metadata) layers on top for market ratios and UX.

---

## SEC EDGAR (Fundamental Data)

EDGAR provides all financial statement data from official SEC filings (10-K annual, 10-Q quarterly). This is the authoritative source — it's the same data companies file with the government.

### API Endpoints

**Company Facts (primary):**
```
https://data.sec.gov/api/xbrl/companyfacts/CIK{cik_number_zero_padded}.json
```
Returns all XBRL-tagged financial data for a company across all filings. Quarterly facts persisted in-app may store an **`accessionNumber`** from companyfacts when present; **symbol tag overrides** support an optional **`scopeKey`** (`FYyyyy:Qn`) for quarter-specific XBRL mappings, with **`""`** meaning all periods. For **Full Analysis** filing links, the app may match **submissions** `filings.recent` (form, accession, **reportDate**) to the fiscal column when no fact-level accession exists.

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
| Revenue | `Revenues`, `RevenueFromContractWithCustomer*`, `SalesRevenueNet`, `ServiceRevenue`, `SalesRevenueGoodsNet`, `SalesRevenueServicesNet` (consolidated only; 10-K annual primary, TTM from 4 standalone 10-Q quarters when available) | PS ratio, revenue growth, asset turnover |
| Net Income | `NetIncomeLoss`, `ProfitLoss` | PE ratio, net income growth, ROE |
| Operating Income (EBIT) | `OperatingIncomeLoss` | Altman-Z, operating efficiency |
| Gross Profit | `GrossProfit` | Gross margin calculations |
| EBITDA (in app) | **Calculated:** Operating Income + Depreciation & Amortization (not read from a standalone EBITDA tag) | EBITDA margin, Altman-style ratios, core health score |
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

Different companies file the same economic idea under different XBRL concept names. Stockzilla uses a **two-tier** resolver (see `EVOLVING_TAGGING_SYS.md`):

1. **Bare standard tags** — short ordered lists in `EdgarConcepts` (`EdgarModels.kt`), tried under `us-gaap` then `ifrs-full` (and `dei` for EPS where applicable).
2. **Per-symbol overrides** — Room table `symbol_tag_overrides` (`SymbolTagOverrideEntity`); applied only after standards miss. Overrides store `taxonomy` (`us-gaap`, `ifrs-full`, or `dei`) plus local `tag` and `metricKey` (`EdgarMetricKey`).

`SecEdgarService` loads overrides when `StockRepository` fetches from EDGAR. Full Analysis shows **Find tag (Eidos)** on missing raw metrics; Eidos can call `set_symbol_tag_override` to persist a mapping and trigger a refresh.

All resolution uses **consolidated facts only** (no segment dimensions).

**Data-source priority (post-refactor):**

- **Annual (10-K)**: The primary and authoritative source for all fundamentals. Stored in `revenue`, `netIncome`, `eps`, etc. in `StockData`. Used exclusively for YoY growth calculations.
- **TTM (Trailing Twelve Months)**: Computed only when the **latest reported fiscal quarter** and the **three immediately preceding fiscal quarters** (same `fy`/`fp` chain, e.g. …Q2→Q3→Q4→Q1) all have a suitable **quarterly** filing fact (10-Q / 10-Q/A / 6-K / 6-K/A), consolidated, with duration ~one quarter (60–140 days). **10-K / annual facts are not used** for TTM. Values are summed over those four periods. If any quarter in the chain is missing, TTM is **null** (no fallback to “any four quarter rows”). Stored in `revenueTtm`, `netIncomeTtm`, `epsTtm`, etc.
- **EBITDA**: Stored as **operating income + depreciation and amortization** when both are available for that period (annual / TTM / history). No direct XBRL EBITDA tag is used.
- **Free cash flow**: **Operating cash flow − |capex|** only when **both** inputs exist for that period (annual / TTM / history row). **Never** substitutes operating cash flow alone when capex is missing.
- **Finnhub**: Used **only** for current stock price. No fundamentals (shares, marketCap, revenue, etc.) are sourced from Finnhub. When EDGAR lacks shares outstanding, price-dependent ratios (PE, PS, PB) are left as N/A rather than backfilled.
- **Display logic**: The UI prefers TTM when available, falls back to annual 10-K, and labels each metric accordingly (`(TTM)` or `(Annual)`).

### Data Persistence Strategy

EDGAR data serves two purposes: **immediate display** AND **long-term accumulation** for dynamic benchmarks.

**On every stock analysis:**
1. Fetch fundamental data from EDGAR
2. Display raw/derived financial data in Full Analysis and scoring outputs only in score-specific UI
3. **Persist raw facts first** (with clean provenance), then persist derived financial metrics
4. Cache in `stock_cache` for short-term reuse (expires next trading day)

**Why we save every analyzed stock:**
- Build a local time-series-friendly pool of SEC fundamentals
- Calculate dynamic peer metrics (PE/PS and other valuation context) from persisted financial data
- Power Similar Stocks and other peer discovery features
- Keep financial facts independent from scoring model changes

**Refresh triggers:**
- EDGAR fundamental data only changes when new filings are published (quarterly)
- Check for new filings via `https://data.sec.gov/submissions/CIK{cik_number}.json`
- Compare the most recent filing date against `lastFilingDate` stored in **`edgar_raw_facts`** (per symbol)
- If a newer filing exists, pull fresh data from EDGAR and update the record
- Favorited stocks get periodic background checks; other stocks refresh on next user access

**Finnhub price caching:**
- Price data cached for short periods (1–5 minutes) or until next trading day
- On cache hit for fundamentals, price is still refreshed separately from Finnhub
- Price-dependent ratios (PE, PS, market cap) are recalculated at display time using latest price + stored fundamentals

See `DATABASE_ARCHITECTURE.md` for schema details on `edgar_raw_facts`, `financial_derived_metrics`, `score_snapshots`, `favorites`, `stock_cache`, and related tables.

---

## What Goes Where — Summary

| Need | Source | Method |
|---|---|---|
| Stock Price | **Finnhub** | Live API call |
| All Financial Statements | **SEC EDGAR** | API call, cached in Room |
| Market Cap | **Calculated** | Price × Shares Outstanding |
| PE Ratio | **Calculated** | Price ÷ (Net Income TTM ÷ Shares) |
| PS Ratio | **Calculated** | Price ÷ (Revenue TTM ÷ Shares) |
| All Growth Metrics | **Calculated** | YoY comparison from EDGAR annual 10-K history only (never TTM-derived) |
| Industry Averages | **Calculated** | Average PE/PS from BenchmarkData (currently hardcoded, future: dynamic from SIC peers) |
| Financial Health Score | **Calculated** | Three-pillar composite from EDGAR data |
| Altman Z-Score | **Calculated** | 5-variable formula from EDGAR + Market Cap |
| Stock Grouping | **SIC Code + Market Cap** | SIC from EDGAR metadata, market cap calculated |

## UI Consumption Rules

- **Full Analysis page:** raw SEC facts + allowed standard derived metrics only.
- **Health score pages:** scoring outputs and normalization details.
- **Never mix UI contracts:** score normalization data should not appear in the Full Analysis financial facts view.