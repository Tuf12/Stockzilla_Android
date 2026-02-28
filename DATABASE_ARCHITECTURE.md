# Database Architecture

## Overview

Stockzilla uses a **Room database** (SQLite under the hood) for local data persistence. The database currently handles favorites and short-term caching. The planned expansion adds a persistent **analyzed stocks table** that accumulates data over time, enabling dynamic benchmark calculations to replace hardcoded industry averages.

---

## Current Schema (Version 4)

### Table: `favorites`

Stores stocks the user has explicitly saved. Preserves full financial data so favorites can display scores without re-fetching.

| Column | Type | Notes |
|---|---|---|
| `symbol` | TEXT (PK) | Ticker symbol |
| `companyName` | TEXT? | Company name |
| `price` | REAL? | Last known price |
| `marketCap` | REAL? | Last known market cap |
| `revenue` | REAL? | Revenue |
| `netIncome` | REAL? | Net income |
| `eps` | REAL? | Earnings per share |
| `peRatio` | REAL? | PE ratio |
| `psRatio` | REAL? | PS ratio |
| `roe` | REAL? | Return on equity |
| `debtToEquity` | REAL? | Debt to equity ratio |
| `freeCashFlow` | REAL? | Free cash flow |
| `pbRatio` | REAL? | Price to book ratio |
| `ebitda` | REAL? | EBITDA |
| `outstandingShares` | REAL? | Shares outstanding |
| `totalAssets` | REAL? | Total assets |
| `totalLiabilities` | REAL? | Total liabilities |
| `totalCurrentAssets` | REAL? | Current assets |
| `totalCurrentLiabilities` | REAL? | Current liabilities |
| `retainedEarnings` | REAL? | Retained earnings |
| `workingCapital` | REAL? | Working capital |
| `netCashProvidedByOperatingActivities` | REAL? | Operating cash flow |
| `sector` | TEXT? | Sector classification |
| `industry` | TEXT? | Industry classification |
| `healthScore` | INTEGER? | Composite score at time of save |
| `addedDate` | INTEGER | Timestamp when first favorited |
| `lastUpdated` | INTEGER | Timestamp of last data update |
| `notes` | TEXT? | User notes (not yet used in UI) |

**DAO Operations:**
- `getAllFavorites()` — returns all, ordered by addedDate DESC
- `insertFavorite()` — insert or replace (UPSERT)
- `deleteFavoriteBySymbol()` — remove by ticker
- `getFavoriteBySymbol()` — lookup single favorite

**Update behavior:** When updating a favorite, the system preserves the original `addedDate` and `notes` while refreshing all financial fields and `lastUpdated`.

### Table: `stock_cache`

Short-term cache for recently searched stocks. Prevents redundant API calls within the same trading session.

| Column | Type | Notes |
|---|---|---|
| `symbol` | TEXT (PK) | Ticker symbol |
| `stockDataJson` | TEXT | Full StockData serialized as JSON (via Gson) |
| `cachedAt` | INTEGER | When data was cached (epoch millis) |
| `expiresAt` | INTEGER | When cache expires (epoch millis) |

**Cache expiration logic** (`StockCacheRepository`):
- Cache expires at the start of the **next trading day** (Eastern time)
- Weekends are skipped — Friday cache expires Monday morning
- On cache hit, the price is separately refreshed from Finnhub to get the latest quote while reusing cached fundamentals
- Expired entries are pruned on access

**Sufficiency check** (`hasSufficientFinancialData()`):
- Cached data is only used if it has ≥ 4 key financial metrics AND historical data
- Otherwise treated as a cache miss and fresh data is fetched

---

## Planned: Analyzed Stocks Table

### Purpose

Every stock that gets analyzed (searched by the user) should be persisted in a long-term table. This table serves two critical purposes:

1. **Dynamic benchmark calculation** — replace hardcoded PE/PS averages with live averages computed from actual analyzed stocks in the same group
2. **Similar stocks discovery** — the "Similar Stocks" feature queries this table for peers

### Proposed Schema: `analyzed_stocks`

| Column | Type | Notes |
|---|---|---|
| `symbol` | TEXT (PK) | Ticker symbol |
| `companyName` | TEXT? | Company name |
| `sicCode` | TEXT? | SIC code from EDGAR filing metadata |
| `sector` | TEXT? | Sector (from EDGAR or current source) |
| `industry` | TEXT? | Industry (from EDGAR or current source) |
| `marketCapTier` | TEXT? | Computed tier: "micro", "small", "mid", "large", "mega" |
| `price` | REAL? | Price at time of analysis |
| `marketCap` | REAL? | Market cap at time of analysis |
| `revenue` | REAL? | Revenue (TTM) |
| `netIncome` | REAL? | Net income (TTM) |
| `eps` | REAL? | EPS |
| `peRatio` | REAL? | PE ratio at time of analysis |
| `psRatio` | REAL? | PS ratio at time of analysis |
| `roe` | REAL? | Return on equity |
| `debtToEquity` | REAL? | Debt to equity |
| `freeCashFlow` | REAL? | Free cash flow |
| `pbRatio` | REAL? | Price to book |
| `ebitda` | REAL? | EBITDA |
| `outstandingShares` | REAL? | Shares outstanding |
| `totalAssets` | REAL? | Total assets |
| `totalLiabilities` | REAL? | Total liabilities |
| `totalCurrentAssets` | REAL? | Current assets |
| `totalCurrentLiabilities` | REAL? | Current liabilities |
| `retainedEarnings` | REAL? | Retained earnings |
| `workingCapital` | REAL? | Working capital |
| `operatingCashFlow` | REAL? | Operating cash flow |
| `grossProfit` | REAL? | Gross profit |
| `operatingIncome` | REAL? | Operating income |
| `revenueGrowth` | REAL? | Most recent revenue growth |
| `netIncomeGrowth` | REAL? | Most recent net income growth |
| `fcfMargin` | REAL? | Free cash flow margin |
| `grossMargin` | REAL? | Gross margin |
| `netMargin` | REAL? | Net margin |
| `ebitdaMargin` | REAL? | EBITDA margin |
| `currentRatio` | REAL? | Current ratio |
| `compositeScore` | INTEGER? | Stockzilla composite score |
| `healthSubScore` | INTEGER? | Core health sub-score |
| `growthSubScore` | INTEGER? | Growth & forecast sub-score |
| `resilienceSubScore` | INTEGER? | Resilience sub-score (0-3) |
| `altmanZScore` | REAL? | Raw Altman Z-Score value |
| `lastFilingDate` | TEXT? | Date of the most recent SEC filing used |
| `analyzedAt` | INTEGER | Timestamp of analysis (epoch millis) |
| `lastUpdated` | INTEGER | Timestamp of last data refresh (epoch millis) |

### Market Cap Tier Calculation

Computed when storing and used for peer grouping:

```
tier = when {
    marketCap < 300_000_000        → "micro"
    marketCap < 2_000_000_000      → "small"
    marketCap < 10_000_000_000     → "mid"
    marketCap < 200_000_000_000    → "large"
    else                           → "mega"
}
```

### Proposed DAO Operations

```kotlin
@Dao
interface AnalyzedStockDao {
    // Insert or update when re-analyzed
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStock(stock: AnalyzedStockEntity)

    // Get single stock
    @Query("SELECT * FROM analyzed_stocks WHERE symbol = :symbol")
    suspend fun getStock(symbol: String): AnalyzedStockEntity?

    // Get all stocks in a peer group (for dynamic benchmarks)
    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE sicCode = :sicCode 
        AND marketCapTier = :capTier
        AND peRatio IS NOT NULL AND peRatio > 0
    """)
    suspend fun getPeersByPeGroup(sicCode: String, capTier: String): List<AnalyzedStockEntity>

    // Get peers by SIC code only (when cap tier group is too small)
    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE sicCode = :sicCode
        AND peRatio IS NOT NULL AND peRatio > 0
    """)
    suspend fun getPeersBySic(sicCode: String): List<AnalyzedStockEntity>

    // Get peers by sector (broadest fallback)
    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE sector = :sector
        AND peRatio IS NOT NULL AND peRatio > 0
    """)
    suspend fun getPeersBySector(sector: String): List<AnalyzedStockEntity>

    // Similar stocks for the Similar Stocks screen
    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE sicCode = :sicCode 
        AND symbol != :excludeSymbol
        ORDER BY compositeScore DESC
    """)
    suspend fun getSimilarStocks(sicCode: String, excludeSymbol: String): List<AnalyzedStockEntity>

    // Count peers in a group (to decide if group is large enough)
    @Query("""
        SELECT COUNT(*) FROM analyzed_stocks 
        WHERE sicCode = :sicCode 
        AND marketCapTier = :capTier
    """)
    suspend fun countPeersInGroup(sicCode: String, capTier: String): Int

    // Get all stocks (for bulk operations)
    @Query("SELECT * FROM analyzed_stocks ORDER BY lastUpdated DESC")
    suspend fun getAllStocks(): List<AnalyzedStockEntity>

    // Find stocks needing refresh (filing date check)
    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE lastUpdated < :cutoff
        ORDER BY lastUpdated ASC
        LIMIT :limit
    """)
    suspend fun getStaleStocks(cutoff: Long, limit: Int): List<AnalyzedStockEntity>
}
```

---

## Dynamic Benchmark Calculation

### The Goal

Replace the hardcoded `BenchmarkData.kt` lookup tables with live averages calculated from the `analyzed_stocks` table.

### Algorithm

```
fun getDynamicBenchmark(stock: AnalyzedStockEntity): Benchmark {
    // 1. Try tightest peer group: 4-digit SIC + market cap tier
    var peers = dao.getPeersByPeGroup(stock.sicCode, stock.marketCapTier)
    
    // 2. If fewer than 10 peers, broaden to SIC code only
    if (peers.size < 10) {
        peers = dao.getPeersBySic(stock.sicCode)
    }
    
    // 3. If still fewer than 10, broaden to sector
    if (peers.size < 10) {
        peers = dao.getPeersBySector(stock.sector)
    }
    
    // 4. If STILL fewer than 10, fall back to hardcoded BenchmarkData
    if (peers.size < 10) {
        return BenchmarkData.getBenchmarkAverages(stockData)
    }
    
    // 5. Calculate averages from peers
    val avgPe = peers.mapNotNull { it.peRatio }.average()
    val avgPs = peers.mapNotNull { it.psRatio }.average()
    
    return Benchmark(peAvg = avgPe, psAvg = avgPs)
}
```

**Key detail:** The hardcoded benchmarks remain as a fallback. They're only replaced once there are enough analyzed stocks in the database to produce meaningful averages. The system gets more accurate over time as more stocks are analyzed.

### Dynamic Normalization Ranges (Future)

The same principle applies to the min/max ranges used in the core health score normalization. Instead of hardcoded ranges like `pe_ratio: 5.0 to 50.0`, derive them from the actual distribution:

```
fun getDynamicRange(metric: String, sector: String, capTier: String): Pair<Double, Double> {
    val values = dao.getMetricValues(metric, sector, capTier)
    
    if (values.size < 20) {
        // Not enough data, use hardcoded defaults
        return DEFAULT_METRIC_CONFIG[metric].min to DEFAULT_METRIC_CONFIG[metric].max
    }
    
    // Use percentile-based ranges to exclude outliers
    val sorted = values.sorted()
    val min = sorted[(values.size * 0.05).toInt()]  // 5th percentile
    val max = sorted[(values.size * 0.95).toInt()]  // 95th percentile
    
    return min to max
}
```

This means the scoring system automatically adapts as it sees more data. The 5th/95th percentile approach prevents extreme outliers from distorting the ranges.

---

## Data Refresh Strategy

### When to Refresh Stock Data

**Trigger: New SEC Filing Detected**

SEC EDGAR provides filing indexes that can be checked to see if a company has filed a new 10-Q or 10-K since the last analysis:

```
https://data.sec.gov/submissions/CIK{cik_number}.json
```

This returns recent filings with dates. Compare the most recent filing date against `lastFilingDate` in the `analyzed_stocks` table. If newer, trigger a refresh.

**Refresh priorities:**
1. Stocks the user actively searches — always check for fresh filings
2. Favorited stocks — periodic background check (e.g., weekly)
3. Other analyzed stocks — refresh when stale and accessed (e.g., when showing Similar Stocks)

### Price Updates

Fundamental data (EDGAR) only changes quarterly, but prices change constantly. For the analyzed stocks table:
- Prices are updated whenever the stock is searched/viewed
- Ratios that depend on price (PE, PS, market cap) are recalculated at display time using the latest Finnhub price against stored fundamentals
- The `analyzedAt` timestamp reflects when fundamentals were last pulled, NOT when price was last updated

---

## Migration Path

### Current State (v4)
- `favorites` table with full financial data
- `stock_cache` table with JSON blob + expiration

### Next Migration (v5)
- Add `analyzed_stocks` table
- Auto-populate from existing favorites (they already have all the needed fields)
- Every new stock search writes to both `stock_cache` AND `analyzed_stocks`

### Future Migrations
- Add SIC code column once EDGAR integration is live
- Add `altmanZScore` as a stored field (currently computed on-the-fly)
- Add indexes on `sicCode`, `sector`, `marketCapTier` for fast peer queries

---

## Key Files

| File | Role |
|---|---|
| `RoomDatabase.kt` | Entity definitions, DAOs, database class, migrations |
| `StockCacheRepository.kt` | Cache read/write logic, expiration calculation, trading day awareness |
| `FavoritesRepository.kt` | Favorites CRUD operations (currently in RoomDatabase.kt) |
| `StockViewModel.kt` | Coordinates data loading, caching, and UI state |