# Valuation Methodology

## Overview

The app **displays** valuation context by comparing the stock’s **P/E or P/S** (chosen from profitability) to **industry or sector benchmark averages**, and by showing an **implied fair value** at the peer multiple. That logic lives in `BenchmarkData` (`getDisplayMetrics`, `calculateFairValue`) and is wired from `StockViewModel` / `MainFragment`.

**Important:** There is **no** standalone `assessValuation()` function and **no** valuation deviation baked into the **Growth** pillar anymore — see `GROWTH_SCORE_SPEC.md` (“Valuation assessment removed from scoring model”). The composite recommendation text comes from **health / growth / resilience scores**, not from an UNDERVALUED/OVERVALUED enum.

---

## Conceptual read (not a separate code path)

Analysts often bucket “cheap vs rich vs fair” using deviation from peer multiples. You can still reason that way from the numbers on the card (stock multiple vs benchmark), but the codebase does **not** persist an `UNDERVALUED` / `OVERVALUED` label from fixed ±15% thresholds.

### Ratio selection (implemented)

- **Net Income > 0** → primary display metric is **P/E** vs benchmark P/E  
- **Net Income ≤ 0** → primary display metric is **P/S** vs benchmark P/S  

(`BenchmarkData.getDisplayMetrics`)

### Fair value (implemented)

`calculateFairValue` answers: “At the industry/sector average multiple, what price would this company have?” using the same PE-vs-PS rule as display.

---

## Benchmark Data (`BenchmarkData.kt`)

### Current Implementation: Hardcoded Lookup Tables

The app currently uses static PE and PS averages organized at two levels:

**1. Industry-level benchmarks (most specific, preferred):**

Examples from current data:
| Industry | Avg PE | Avg PS |
|---|---|---|
| Software | 28.5 | 8.2 |
| Semiconductors | 22.1 | 5.8 |
| Biotechnology | null (often unprofitable) | 12.4 |
| Banks | 11.4 | 1.2 |
| Restaurants | 28.4 | 2.1 |
| Oil & Gas | 12.1 | 1.2 |

**2. Sector-level benchmarks (fallback):**

| Sector | Avg PE | Avg PS |
|---|---|---|
| Technology | 24.2 | 6.1 |
| Healthcare | 17.4 | 5.8 |
| Financial Services | 12.8 | 1.8 |
| Consumer Cyclical | 18.1 | 1.4 |
| Industrials | 15.7 | 1.4 |
| Energy | 14.2 | 1.6 |
| Communication Services | 17.8 | 2.9 |
| Real Estate | 18.4 | 4.2 |
| Utilities | 18.9 | 2.1 |
| Materials | 12.4 | 1.8 |
| Consumer Defensive | 20.6 | 2.1 |

**Lookup priority:**
1. Try industry-specific benchmark first
2. Fall back to sector-level benchmark
3. If neither matches, return null (valuation assessment becomes UNKNOWN)

### Future Enhancement: Dynamic Peer Averages

When migrated to SEC EDGAR, the plan is to replace hardcoded benchmarks with dynamically calculated averages from actual peer companies grouped by SIC code + market cap tier. See the Stock Grouping section below.

---

## Stock Grouping

### Purpose

Grouping stocks allows calculation of meaningful peer averages for PE, PS, and other metrics. A stock should only be compared against genuinely similar companies.

### Primary Method: SIC Code + Market Cap Tier

Every SEC filing includes a **SIC (Standard Industrial Classification) code**. This is available in EDGAR filing metadata.

**SIC Code Granularity:**
- 2-digit = broad industry (e.g., 73 = Business Services)
- 3-digit = narrower (e.g., 737 = Computer Programming & Services)
- 4-digit = most specific (e.g., 7372 = Prepackaged Software)

**Grouping Algorithm:**
1. Start with **4-digit SIC code** for tightest peer comparison
2. If fewer than 10 companies in the group, broaden to **3-digit SIC**
3. If still fewer than 10, broaden to **2-digit SIC**
4. Within the SIC group, filter by **market cap tier**:

| Tier | Market Cap Range |
|---|---|
| Micro Cap | < $300M |
| Small Cap | $300M – $2B |
| Mid Cap | $2B – $10B |
| Large Cap | $10B – $200B |
| Mega Cap | > $200B |

**Example:** A $5B software company (SIC 7372) gets compared against other SIC 7372 companies in the Mid Cap tier.

### Current implementation

The app uses `industry` and `sector` strings on `StockData` for hardcoded benchmarks in `BenchmarkData.kt`, with an optional **`DynamicBenchmarkRepository`** override when peer stats exist in the database. **Industry peers UI** is `IndustryStocksActivity` (discover + saved group), backed by `IndustryPeerRepository` and EDGAR/Finnhub data — not the same code path as the static tables, but the same idea (compare to something peer-like).

### Future: NAICS Codes

NAICS (North American Industry Classification System) codes are a newer, more granular classification also available in EDGAR. Could be used as an alternative or supplement to SIC codes.

---

## Growth Confirmation

A low PE or PS alone doesn't make a stock a good value — it might be cheap for a reason. The system cross-references growth signals:

**Positive growth signals (confirming value):**
- Revenue growing year-over-year
- Net income growing year-over-year
- Free cash flow growing year-over-year
- Gross margin expanding
- Operating income / EBITDA margin expanding

**Warning signals (potential value trap):**
- Below-average PE/PS BUT declining revenue
- Below-average PE/PS BUT declining net income
- Below-average PE/PS BUT negative or shrinking free cash flow

These growth signals are formally captured in the **Growth** pillar of the composite score (`GROWTH_SCORE_SPEC.md`, `FinancialHealthAnalyzer.calculateGrowthScore`). Conceptually they are the “cheap for a reason?” check alongside multiples on the card.

---

## User-facing display

On the main stock card (`MainFragment`):

- Primary multiple and benchmark from `BenchmarkData.getDisplayMetrics` (with dynamic benchmark when provided)
- Fair value line from `calculateFairValue` when inputs exist
- Recommendation text from **composite score bands** (see `APP_STRUCTURE.md`), not from a valuation enum

---

## Related implementation files

| Role | Path |
|------|------|
| Benchmarks & fair value | `app/src/main/java/com/example/stockzilla/scoring/BenchmarkData.kt` |
| Dynamic peer averages | `app/src/main/java/com/example/stockzilla/data/DynamicBenchmarkRepository.kt` |
| Card UI | `app/src/main/java/com/example/stockzilla/feature/MainFragment.kt` |
| Stock state | `app/src/main/java/com/example/stockzilla/stock/StockViewModel.kt` |