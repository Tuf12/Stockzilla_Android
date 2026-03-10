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
│                     │  │                     │  │                     │
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

If a pillar returns null (insufficient data), a neutral fallback of 5.0 is used for that pillar. For resilience, the neutral fallback level is 1.5 (out of 3).

---

## Quick Reference: Which Metric Goes Where?

| Metric | Pillar | NOT In |
|--------|--------|--------|
| Positive ROA / profitability | Financial Health | Growth |
| Positive Operating Cash Flow | Financial Health | Growth |
| Delta ROA | Financial Health | Growth |
| Operating Cash Flow > Net Income | Financial Health | Growth |
| Lower Long-Term Debt / Assets vs prior year | Financial Health | Growth |
| Higher Current Ratio vs prior year | Financial Health | Growth |
| No new shares issued | Financial Health | Growth |
| Higher Gross Margin vs prior year | Financial Health | Growth |
| Higher Asset Turnover vs prior year | Financial Health | Growth |
| 5-Year Avg Revenue Growth | Growth | Financial Health |
| Recent YoY Revenue Growth | Growth | Financial Health |
| 5-Year Avg Net Income Growth | Growth | Financial Health |
| Recent YoY Net Income Growth | Growth | Financial Health |
| FCF Growth (year over year) | Growth | Financial Health |
| Working Capital / Assets | Bankruptcy Risk | — |
| Retained Earnings / Assets | Bankruptcy Risk | — |
| EBITDA / Assets | Bankruptcy Risk | — |
| Market Cap / Liabilities | Bankruptcy Risk | — |
| Revenue / Assets | Bankruptcy Risk | — |

---

## Normalization Summary

| Pillar | Method | Details |
|--------|--------|---------|
| Financial Health | Strict annual Piotroski F-Score: 9 pass/fail tests scaled to 0–10 | See HEALTH_SCORE_SPEC.md |
| Growth | Tiered lookup: growth rate → score 0–10, then simple average | See GROWTH_SCORE_SPEC.md |
| Bankruptcy Risk | Altman Z-Score formula → zone classification → 0–3 level | See ALTMAN_Z_SCORE_SPEC.md |

**DO NOT** use sigmoid normalization for any pillar.
**DO NOT** mix metrics between pillars.
**DO NOT** expose scoring internals on Full Analysis.

---

## Score Snapshot Versioning

Current model version tag: `v5-strict-annual-piotroski`

Score snapshots are persisted in the `score_snapshots` table with the model version so historical scores can be compared across algorithm changes.
