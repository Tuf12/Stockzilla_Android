# Valuation Methodology

## Overview

Stockzilla determines whether a stock is undervalued, fairly valued, or overvalued by comparing its current PE or PS ratio against industry/sector benchmark averages. Growth signals are then used to confirm or deny the valuation signal.

---

## Valuation Assessment (`assessValuation()`)

### Ratio Selection

The system automatically selects the appropriate ratio based on profitability:
- **Net Income > 0** → Use **PE Ratio** vs industry average PE
- **Net Income ≤ 0** → Use **PS Ratio** vs industry average PS

### Deviation Calculation

```
deviation = (stock's ratio − benchmark ratio) ÷ benchmark ratio
```

### Classification Thresholds

| Deviation | Classification |
|---|---|
| ≤ -15% | **UNDERVALUED** — stock trades at a meaningful discount to peers |
| -15% to +15% | **FAIRLY_VALUED** — stock trades near peer average |
| ≥ +15% | **OVERVALUED** — stock trades at a premium to peers |
| Data unavailable | **UNKNOWN** |

### Normalized Valuation Score

The deviation is also converted to a 0-to-1 normalized score for use in the Growth & Forecast pillar (5% weight):

```
clamped_deviation = clamp(deviation, -1.0, 1.0)
fraction = (-clamped_deviation + 1.0) / 2.0
normalized_score = sigmoid(fraction)
```

This means: lower ratio relative to benchmark → higher score (more undervalued = better).

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

### Current Implementation

The current app uses the `industry` and `sector` strings from the data source to look up hardcoded benchmarks in `BenchmarkData.kt`. The "Similar Stocks" button conceptually uses the same grouping but the dynamic peer discovery from EDGAR SIC codes is not yet implemented.

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

These growth signals are formally captured in the Growth & Forecast pillar of the composite score (see `FINANCIAL_HEALTH_SCORE.md`), but conceptually they serve as the "is this value real or a trap?" check.

---

## User-Facing Valuation Display

On the main stock card:
- Show the stock's PE (or PS if unprofitable) alongside the industry average
- Arrow indicator: ▼ if below average (potentially undervalued), ▲ if above
- Fair Value Price estimate: what the stock would cost at the industry average multiple
- Recommendation text derived from composite score (7-10 = Strong Buy, 4-6 = Hold/Consider, 0-3 = Caution)