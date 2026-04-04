# Database Architecture

## Direction (Authoritative)

This document now follows the project rule:

1. Separate data domains in storage and UI.
2. Make Full Analysis show only raw + allowed standard derived metrics.
3. Rebuild health/composite/growth/resilience on top of that clean base.

The intent is to avoid mixing scoring artifacts with financial statement data.

---

## Domain Separation Model

### Domain A: Raw Financial Facts (EDGAR + identity metadata)

These are direct facts from SEC EDGAR filings (or filing metadata), without score normalization:

- Company identifiers and classification: `symbol`, `companyName`, `cik`, `sicCode`, `naicsCode`, `sector`, `industry`
- Income/cash flow/balance sheet facts: `revenue`, `netIncome`, `eps`, `ebitda`, `operatingCashFlow`, `freeCashFlow`, `totalAssets`, `totalLiabilities`, `totalCurrentAssets`, `totalCurrentLiabilities`, `retainedEarnings`, `outstandingShares`
- Filing metadata and freshness: `lastFilingDate`, `analyzedAt`, `lastUpdated`
- History / periodized EDGAR facts (recommended explicit persistence): annual history arrays and TTM values

### Domain B: Standard Derived Financial Metrics

These are deterministic finance calculations from Domain A (+ price where required). They are not score-normalization artifacts.

Examples:
- `marketCap`, `peRatio`, `psRatio`, `pbRatio`
- `debtToEquity`, `roe`, `currentRatio`
- `netMargin`, `ebitdaMargin`, `fcfMargin`, `workingCapital`
- Growth rates such as YoY and multi-year averages (if clearly labeled as derived)
- `marketCapTier` for peer grouping

### Domain C: Scoring Outputs (separate from financial domains)

These belong only to the scoring system and must not be treated as financial facts:

- `compositeScore`
- `healthSubScore`
- `growthSubScore`
- `resilienceSubScore`
- normalized component scores, weights, and other scoring internals

Rule: Domain C should never be used as source-of-truth financial data in Full Analysis.

---

## Current Reality (Room)

The legacy `analyzed_stocks` table has been **removed**. Financial persistence follows the A/B/C split in **`app/src/main/java/com/example/stockzilla/data/RoomDatabase.kt`**:

| Table / entity | Domain |
|----------------|--------|
| `edgar_raw_facts` / `EdgarRawFactsEntity` | A — raw SEC facts + histories + TTM fields |
| `financial_derived_metrics` / `FinancialDerivedMetricsEntity` | B — deterministic ratios, growth, benchmarks used for display/peers |
| `score_snapshots` / `ScoreSnapshotEntity` | C — serialized scoring outputs (versioned by model id in row) |
| `symbol_tag_overrides` / `SymbolTagOverrideEntity` | A-adjunct — per-symbol XBRL tag overrides for EDGAR parsing (`symbol`, `metricKey`, `taxonomy`, `tag`, `updatedAt`, `source`). PK `(symbol, metricKey)`. |
| `eidos_analyst_confirmed_facts` / `EidosAnalystConfirmedFactEntity` | **Display adjunct** — user-accepted **Eidos Analyst** values from filing text (not written by mechanical refresh; not merged into `edgar_raw_facts`). Full Analysis joins these rows by **`metricKey` + `periodLabel`**. **`periodLabel` rules** matter for history cells: see [EIDOS_AS_ANALYST.md — Proposal JSON contract](EIDOS_AS_ANALYST.md#proposal-json-contract-for-metrickey-and-periodlabel). |
| `eidos_analyst_chat_messages`, `eidos_analyst_audit_events` | Analyst chat and audit trail (separate from main assistant tables). |

Additional tables (same file): `favorites`, `stock_cache`, `stock_industry_peers`, `user_stock_list`, `company_profiles`, `stock_profiles`, `ai_memory_cache`, `ai_conversations`, `ai_messages`, `news_metadata`, `news_summaries`, portfolio tables, etc.

At runtime, **`StockData`** is often assembled by joining raw + derived (+ latest score) for the UI and scoring pipeline — but storage remains separated at the entity level.

---

## Target refinements (ongoing)

- Tighten DAO/read-model boundaries so Full Analysis reads only A+B.
- Optional: explicit formula provenance on derived fields.
- Versioned score snapshots already live in `score_snapshots`; extend model versioning as scoring evolves.

---

## Full Analysis Data Policy

Full Analysis must render only:

- Domain A (raw EDGAR facts)
- Domain B (allowed standard derived financial metrics)

Full Analysis must not render:

- Domain C scoring outputs
- normalization percentages
- score-driven verdict logic

This keeps the page as a financial facts workspace, not a scoring UI.

---

## Refresh and Recalculation Strategy

- EDGAR facts update on new filings (`submissions/CIK...json` freshness checks).
- Price updates independently from Finnhub.
- Domain B metrics are recomputed from latest Domain A (+ price when needed).
- Domain C scores are recomputed only by the scoring pipeline and stored separately.

---

## Migration note (historical)

Older builds used a single `analyzed_stocks` table. Migrations now **`DROP TABLE IF EXISTS analyzed_stocks`** during upgrade; the app’s canonical schema is the split above. See `RoomDatabase.kt` for the current `version` and `MIGRATION_*` constants.

---

## Key files (paths)

| File | Role |
|---|---|
| `app/src/main/java/com/example/stockzilla/data/RoomDatabase.kt` | Entities, DAOs, migrations |
| `app/src/main/java/com/example/stockzilla/data/SecEdgarService.kt` | EDGAR extraction and raw fact assembly |
| `app/src/main/java/com/example/stockzilla/data/StockApiService.kt` | Orchestration, derived metrics, API glue |
| `app/src/main/java/com/example/stockzilla/scoring/FinancialHealthAnalyzer.kt` | Scoring / Domain C logic |
| `app/src/main/java/com/example/stockzilla/data/StockCacheRepository.kt` | Short-lived `stock_cache` usage |
| `app/src/main/java/com/example/stockzilla/data/IndustryPeerRepository.kt` | `stock_industry_peers` |
| `app/src/main/java/com/example/stockzilla/data/NewsRepository.kt` | `news_metadata` / `news_summaries` |