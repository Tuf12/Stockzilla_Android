# Stockzilla Composite Scoring Specification

## Prerequisite Data Contract

Scoring logic depends on clean input domains:

1. **Raw SEC facts** are persisted and queryable without scoring transforms.
2. **Standard derived financial metrics** are computed deterministically from raw facts (+ price where needed).
3. **Scoring outputs** are persisted separately from financial facts.

**Critical UI rule**: Full Analysis is not a scoring surface. It consumes only raw + standard derived financial metrics.

---

## Architecture: Three Independent Pillars → One Composite

```
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│  FINANCIAL HEALTH   │  │   GROWTH SCORE      │  │  BANKRUPTCY RISK    │
│  (Piotroski F-Score)│  │   (Business Growth) │  │  (Altman Z-Score)   │
│  modified f -score                │  │                     │  │                     │
│  9 strict annual    │  │  5 growth metrics   │  │  Standard 5-ratio   │
│  pass/fail tests    │  │  Tiered scoring     │  │  formula (raw)      │
│  full-score /10     │  │  Simple average /10 │  │  Zone → 0-3 level   │
└────────┬────────────┘  └────────┬────────────┘  └────────┬────────────┘
         │                        │                        │
         │ × 0.40                 │ × 0.40                 │ × 0.20
         │                        │                        │
         └────────────┬───────────┴────────────────────────┘
                      │
              ┌───────▼───────┐
              │  COMPOSITE    │
              │  SCORE (0-10) │
              └───────────────┘
```

Each pillar calculates its own score independently. No pillar uses another pillar's functions. No metrics are shared between pillars.

---

## Pillar Specifications (separate files)

| Pillar | Weight | Score Range | Spec File |
|--------|--------|-------------|-----------|
| Financial Health | 40% | 0–10 | [HEALTH_SCORE_SPEC.md](HEALTH_SCORE_SPEC.md) |
| Growth | 40% | 0–10 | [GROWTH_SCORE_SPEC.md](GROWTH_SCORE_SPEC.md) |
| Bankruptcy Risk | 20% | 0–3 (scaled to 0–10) | [ALTMAN_Z_SCORE_SPEC.md](ALTMAN_Z_SCORE_SPEC.md) |

---

## Composite Calculation

```
composite = (financial_health_score × 0.40) +
            (growth_score × 0.40) +
            (resilience_score × 0.20)
```

All three sub-scores are on a 0–10 scale before combining. Composite result: 0–10, rounded to integer.

If a pillar returns null (insufficient data), that pillar’s contribution to the composite is treated as **0.0**. The UI must surface that the pillar score is unavailable due to missing inputs; it must not present a neutral or mid-range placeholder as if it were a real score.

---

## Quick Reference: Which Metric Goes Where?

| Metric | Pillar | NOT In |
|--------|--------|--------|
| Positive ROA / profitability | Financial Health | Growth |
| Positive Operating Cash Flow | Financial Health | Growth |
| Operating income sign matches net income sign | Financial Health | Growth |
| Operating Cash Flow > Net Income | Financial Health | Growth |
| Operating Cash Flow / Net Income > 1.0 (only when both positive) | Financial Health | Growth |
| Non-operating income < 50% of net income (only when net income is positive) | Financial Health | Growth |
| No new shares issued | Financial Health | Growth |
| Higher Gross Margin vs prior year | Financial Health | Growth |
| Higher Asset Turnover vs prior year | Financial Health | Growth |
| Revenue Growth (YoY + Average) | Growth | Financial Health |
| Net Income Growth (YoY + Average) | Growth | Financial Health |
| Free Cash Flow Growth (YoY + Average) | Growth | Financial Health |
| Operating Cash Flow Growth (YoY + Average) | Growth | Financial Health |
| Gross Profit Margin Growth (YoY delta) | Growth | Financial Health |
| Working Capital / Assets | Bankruptcy Risk | — |
| Retained Earnings / Assets | Bankruptcy Risk | — |
| EBITDA / Assets | Bankruptcy Risk | — |
| Market Cap / Liabilities | Bankruptcy Risk | — |
| Revenue / Assets | Bankruptcy Risk | — |

---

## Normalization Summary

| Pillar | Method | Details |
|--------|--------|---------|
| Financial Health | Strict annual Modified Piotroski F-Score: 9 pass/fail tests scaled to 0–10 | See HEALTH_SCORE_SPEC.md |
| Growth | Tiered lookup: growth rate → score 0–10, then simple average | See GROWTH_SCORE_SPEC.md |
| Bankruptcy Risk | Altman Z-Score formula → zone classification → 0–3 level | See ALTMAN_Z_SCORE_SPEC.md |

**DO NOT** use sigmoid normalization for any pillar.
**DO NOT** mix metrics between pillars.
**DO NOT** expose scoring internals on Full Analysis.

---

## Score Snapshot Versioning

Current model version tag: `v6-earnings-quality-piotroski`

Score snapshots are persisted in the `score_snapshots` table with the model version so historical scores can be compared across algorithm changes.

---

## Related implementation files

| Role | Path under `app/src/main/java/com/example/stockzilla/` |
|------|--------------------------------------------------------|
| Composite + pillars | `scoring/FinancialHealthAnalyzer.kt` |
| Score persistence | `data/RoomDatabase.kt` (`ScoreSnapshotEntity`, `ScoreSnapshotDao`) |
| Types | `scoring/FinancialHealthAnalyzer.kt` (`StockData`, `HealthScore`), `scoring/HealthScoreDetail.kt` |
| UI | `feature/HealthScoreDetailsActivity.kt`, `feature/HealthScoreExplanationDialogFragment.kt` |
