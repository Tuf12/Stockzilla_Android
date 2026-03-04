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

## Current Reality (v7)

Current implementation stores mixed data in `analyzed_stocks`:

- Domain A fields
- Domain B fields
- Domain C fields

This mixed table works operationally, but it blurs provenance and can cause confusion. The docs now treat this as transitional and recommend explicit separation.

---

## Target Storage Contract

Use explicit separation at the schema level (or, at minimum, strict field-level boundaries):

1. `edgar_raw_facts` (Domain A only)
2. `financial_derived_metrics` (Domain B only, with formula provenance)
3. `score_snapshots` (Domain C only, versioned by model/algorithm version)

If full table split is deferred, enforce separation in one table using clear naming and strict DAO/read-model boundaries.

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

## Migration Sequence (Documentation Plan)

1. **Phase 1 (now):** enforce documentation and UI boundaries (Full Analysis = Domain A + B only).
2. **Phase 2:** split persistence layer for A/B/C domains and add provenance labels for derived metrics.
3. **Phase 2.5:** move benchmark/peer read paths to separated domains.
4. **Phase 2.6:** remove runtime fallback reads from `analyzed_stocks`.
5. **Phase 2.7:** stop dual-write to `analyzed_stocks`; retire legacy DAO read access.
6. **Phase 2.8 (completed):** remove legacy table from Room entities and add migration (`9 -> 10`) to drop `analyzed_stocks`.
7. **Phase 3:** rebuild scoring model using separated inputs, then version and persist scoring snapshots independently.

### Legacy Table Drop Plan (Implemented in v10)

- **Version N+1:** keep legacy table physically present but unused at runtime (completed in phase 2.7).
- **Version N+2:** run migration:
  - `DROP TABLE IF EXISTS analyzed_stocks`
  - remove `AnalyzedStockEntity` from Room entities
- **Post-drop (completed):** obsolete stepwise migration chain was removed; only current migration support remains.

---

## Key Files

| File | Role |
|---|---|
| `RoomDatabase.kt` | Current Room entities/DAOs (mixed model today) |
| `SecEdgarService.kt` | EDGAR extraction and raw fact assembly |
| `StockApiService.kt` | Derived metrics and data orchestration |
| `FinancialHealthAnalyzer.kt` | Scoring domain logic (Domain C) |