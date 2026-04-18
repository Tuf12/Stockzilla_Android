# App Structure & User Flow

## Overview

The launcher activity is **`com.example.stockzilla.feature.MainActivity`**. It hosts a **ViewPager2** with **four** pages (see `MainPagerAdapter`):

| Page | Fragment | Role |
|------|-----------|------|
| 0 | `PersonalProfileFragment` | User profile, portfolio-related entry points |
| 1 | `MainFragment` | **Primary stock lookup** — search, summary card, favorites list, recent SEC news list |
| 2 | `ViewedStocksFragment` | History of viewed tickers |
| 3 | `GovNewsFragment` | Government-source documents feed (see [GOV_DATA_NEWS.md](GOV_DATA_NEWS.md)) |

The app opens on the stock lookup tab (position 1).

---

## Main Screen: Stock Lookup (`MainFragment` inside `MainActivity`)

### Search

- Text input for ticker symbol (e.g., AAPL, MSFT)
- Search runs `StockViewModel.searchStock(ticker)` (also exposed as `analyzeStock(ticker)`, which delegates to the same path) → resolves symbol → loads EDGAR + price → scoring via `FinancialHealthAnalyzer`

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

Accessed via the **"Full Analysis"** button (`FullAnalysisActivity`).

**Authoritative spec:** [FULL_ANALYSIS.md](FULL_ANALYSIS.md). **Formulas & planned metric rows:** [CALCULATIONS.md](CALCULATIONS.md).

At a glance:
- **Eidos Analyst** button — filing-text chat and proposals ([EIDOS_AS_ANALYST.md](EIDOS_AS_ANALYST.md)); distinct from **Find tag (Eidos)** (XBRL overrides)
- **Raw Financial Facts** (EDGAR) with **Find tag (Eidos)** when a metric is missing
- **Standard Derived Metrics** (Domain B style ratios/margins)
- **Multi-Year Financial History** with **Yearly / Quarterly** toggle, **TTM** column, quarterly SEC filing link when applicable
- Business profile / about text; external “stock analysis” link

*(PE/PS trend charts and narrative “value position” copy may evolve; the spec file tracks what the implementation actually binds to.)*

---

## Industry peers (`IndustryStocksActivity`)

Accessed from the main flow as **industry / similar stocks** (peer discovery and “My group”).

- Discover mode: candidates from sector/industry + Finnhub/EDGAR-backed lists (`StockRepository.getIndustryPeers`)
- My group: persisted peers in `stock_industry_peers` via `IndustryPeerRepository`
- Menu can open **Eidos** (`AiAssistantActivity`) without rebinding the current chat symbol

---

## Favorites

- **Add to Favorites** on the summary card persists to Room table `favorites` (`FavoriteEntity`)
- Favorites appear in a **RecyclerView on `MainFragment`**, not a separate activity

---

## SEC filing news (on `MainFragment`)

- Recent filing summaries in a list; **See all** opens `AllNewsBottomSheet`
- Row tap opens `NewsDetailBottomSheet`
- **Analyze** (when present) runs `StockViewModel.analyzeRecentNews()` — Stage-2 fetch + AI summary pipeline (see `SEC_NEWS_SPEC.md`)

---

## Other activities (see `AndroidManifest.xml`)

| Activity | Package / path |
|----------|----------------|
| `HealthScoreDetailsActivity` | `feature` |
| `FullAnalysisActivity` | `feature` |
| `IndustryStocksActivity` | `feature` |
| `AddPeerActivity` | `feature` |
| `PortfolioChartActivity` | `feature` |
| `DiagnosticLogActivity` | `feature` |
| `AiAssistantActivity` | `ai` |
| `AiMemoryCacheActivity` | `ai` |
| `EidosAnalystActivity` | `analyst` |
| `GovNewsDetailActivity` | `feature` |

Toolbar actions on `MainActivity` include Finnhub API key setup, Eidos entry points, and diagnostic log.

---

## Navigation Map (simplified)

```
MainActivity (ViewPager2)
    ├─ PersonalProfileFragment
    ├─ MainFragment (search, card, favorites, news)
    ├─ ViewedStocksFragment
    └─ GovNewsFragment

From MainFragment / card actions:
    ├─ HealthScoreDetailsActivity
    ├─ FullAnalysisActivity (includes Eidos Analyst entry)
    ├─ IndustryStocksActivity → AddPeerActivity
    ├─ AiAssistantActivity (Eidos)
    ├─ PortfolioChartActivity
    └─ DiagnosticLogActivity
```

---

## Key source files (Kotlin)

| Type | Path under `app/src/main/java/com/example/stockzilla/` |
|------|--------------------------------------------------------|
| Main shell | `feature/MainActivity.kt`, `feature/MainFragment.kt`, `feature/MainPagerAdapter.kt` |
| Stock logic | `stock/StockViewModel.kt`, `stock/StockDetailsDialog.kt`, `stock/QuoteDataSource.kt` |
| Data / EDGAR | `data/SecEdgarService.kt`, `data/StockApiService.kt`, `data/StockCacheRepository.kt`, `data/RoomDatabase.kt`, `data/NewsRepository.kt` |
| Scoring | `scoring/FinancialHealthAnalyzer.kt` (`StockData`, `HealthScore`), `scoring/BenchmarkData.kt`, `scoring/HealthScoreDetail.kt` |
| News UI | `news/NewsAdapter.kt`, `news/AllNewsBottomSheet.kt`, `news/NewsDetailBottomSheet.kt` |
| SEC news analysis | `sec/EightKNewsAnalyzer.kt`, `sec/EightKModels.kt`, `sec/SecXmlExtraction.kt` |
| AI | `ai/AiAssistantActivity.kt`, `ai/AiAssistantViewModel.kt`, `data/GrokApiClient.kt` |
| Eidos Analyst | `analyst/EidosAnalystActivity.kt`, `analyst/EidosAnalystViewModel.kt`, `analyst/EidosAnalystToolExecutor.kt` |
| Gov news | `feature/GovNewsFragment.kt`, `feature/GovNewsViewModel.kt`, `data/GovNewsRepository.kt` |
| Favorites list UI | `FavoritesAdapter.kt` (package root) |
| HTTP | Retrofit services in `data/` (e.g. `FinnhubApiService.kt`) |