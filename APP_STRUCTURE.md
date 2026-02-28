# App Structure & User Flow

## Overview

Stockzilla's UI is built around a single stock lookup flow. The user searches for a ticker, sees a summary card with key metrics and scores, then can drill down into three detail views.

---

## Main Screen: Stock Lookup (`MainActivity`)

### Search

- Text input for ticker symbol (e.g., AAPL, MSFT)
- Search triggers `viewModel.searchStock(ticker)` → fetches data from APIs → runs `FinancialHealthAnalyzer.calculateCompositeScore()`

### Stock Summary Card

Once data loads, the card displays:

```
┌─────────────────────────────────────────┐
│  Company Name                           │
│  TICKER          $Price                 │
│  Sector                                 │
│  Fair Value Price (Industry Avg XX): $XX│
│                                         │
│  Market Cap     Revenue    Net Income   │
│                                         │
│  [PE or PS]     [Avg PE or PS]          │
│  (auto-selected based on profitability) │
│                                         │
│  Composite Score: X/10                  │
│  Core Health: X/10                      │
│  "Strong Buy" / "Hold" / "Caution"     │
│                                         │
│  Last Refreshed: [timestamp]            │
│                                         │
│  [Add to Favorites]                     │
│  [Full Analysis]                        │
│  [View Health Score Details]            │
│  [Similar Stocks]                       │
└─────────────────────────────────────────┘
```

### Smart Display Logic

**PE vs PS auto-selection** (`BenchmarkData.getDisplayMetrics()`):
- If Net Income > 0: Show PE Ratio + Industry Avg PE
- If Net Income ≤ 0: Show PS Ratio + Industry Avg PS

**Fair Value Price** (`BenchmarkData.calculateFairValue()`):
- Calculates what stock price would be at industry average multiple
- Shows which multiple was used (PE or PS)

**Score Color Coding** (`getScoreColor()`):
- 7–10: Green (`scoreGood`)
- 4–6: Yellow/Orange (`scoreMedium`)
- 0–3: Red (`scorePoor`)

**Recommendation Text:**
- 7–10: "Strong Buy - Excellent financial health"
- 4–6: "Hold/Consider - Mixed signals"
- 0–3: "Caution - Concerning metrics"

---

## Health Score Details (`HealthScoreDetailsActivity`)

Accessed via the **"View Health Score Details"** button.

Shows a detailed breakdown of all three scoring pillars with per-metric explanations.

### Sections:

**1. Financial Snapshot**
- Raw metric values displayed (revenue, net income, EPS, PE, PS, etc.)

**2. Composite Score Overview**
- Shows composite score and how it's composed
- Sub-scores: Core Health (X/10), Growth & Forecast (X/10), Bankruptcy Risk Checker (X/3)

**3. Core Financial Health Breakdown**
- Each metric listed with:
    - Current value
    - Weight in the score
    - Normalized percentage (how it scored)
    - Performance classification (Strong / Average / Weak)
    - Rationale text explaining why this metric matters

**4. Growth & Forecast Breakdown**
- Average Revenue Growth (20% weight)
- Average Net Income Growth (20% weight)
- Recent Revenue Growth (30% weight)
- Free Cash Flow Margin (15% weight)
- EBITDA Margin Trend (10% weight)
- Relative Valuation (5% weight) — shows stock ratio vs peer benchmark

**5. Bankruptcy Risk Checker (Resilience)**
- Five Altman-Z input ratios with coefficients:
    - Working Capital / Assets (×1.2)
    - Retained Earnings / Assets (×1.4)
    - EBITDA / Assets (×3.3)
    - Market Cap / Liabilities (×0.6)
    - Revenue / Assets (×1.0)
- Two-year loss penalty notation if applicable
- Overall Z-score and risk level

### Performance Classification

Based on normalized percentage:
- **Strong**: Normalized ≥ 65%
- **Average**: Normalized 35–65%
- **Weak**: Normalized < 35%

---

## Full Analysis Page

Accessed via the **"Full Analysis"** button.

Deep dive view showing:
- All ratios and scores with historical context
- Charts showing PE/PS trends vs industry averages over time
- Growth trend charts (revenue, net income, FCF across quarters)
- Narrative assessment of the stock's value position

---

## Similar Stocks Page

Accessed via the **"Similar Stocks"** button.

Shows stocks in the same industry/sector group:
- Each stock displays: ticker, name, PE, PS, growth rates, composite health score
- Sorted by valuation signal (most undervalued first)
- Helps user discover other opportunities in the same space

**Current implementation**: Based on sector/industry string matching
**Future**: Dynamic peer discovery using SIC codes + market cap tiers from EDGAR

---

## Favorites System

- **"Add to Favorites"** / **"Update Favorite"** button on main card
- Stores stock data + health score in local Room database
- Favorites screen shows saved stocks with last-known scores
- Button text changes based on whether stock is already favorited

---

## Navigation Map

```
Main Screen (Stock Lookup)
    │
    ├─→ [View Health Score Details] → HealthScoreDetailsActivity
    │       └─ Core Health breakdown
    │       └─ Growth & Forecast breakdown
    │       └─ Resilience breakdown
    │
    ├─→ [Full Analysis] → Stock Analysis Page
    │       └─ Historical charts
    │       └─ Detailed narrative
    │
    ├─→ [Similar Stocks] → Industry Comparison Page
    │       └─ Peer list with scores
    │
    ├─→ [Add to Favorites] → Saves to Room DB
    │
    └─→ [Favorites tab] → FavoritesActivity
            └─ Saved stocks with scores
```

---

## Key Architecture Components

| Component | Role |
|---|---|
| `MainActivity` | Stock search, summary card display, navigation |
| `StockViewModel` | Search logic, API calls, data management |
| `FinancialHealthAnalyzer` | All scoring calculations |
| `BenchmarkData` | Industry/sector benchmarks, fair value, display metrics |
| `HealthScoreDetailsActivity` | Detailed score breakdown UI |
| `StockData` (data class) | Core data model holding all financial fields |
| `HealthScore` (data class) | Composite + sub-scores + breakdown list |
| Room Database | Local caching of favorites and EDGAR data |
| Retrofit | API integration (Finnhub, EDGAR) |