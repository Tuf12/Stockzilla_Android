# Financial Health Composite Score

## Overview

Stockzilla's financial health system is a **three-pillar weighted composite** that produces a single 1–10 score. It is NOT a simple binary checklist like the Piotroski F-Score — it uses continuous sigmoid-normalized scoring, sector-aware dynamic weighting, and market-cap-adjusted ranges to produce nuanced assessments.

**Composite Formula:**
```
Composite = (Core Health × 0.40) + (Growth & Forecast × 0.30) + (Resilience × 0.30)
```

The composite score is rounded to an integer 0–10.

**Score Interpretation:**
| Score | Meaning | UI Label |
|---|---|---|
| 7–10 | Strong financial health | "Strong Buy - Excellent financial health" |
| 4–6 | Mixed signals | "Hold/Consider - Mixed signals" |
| 0–3 | Concerning metrics | "Caution - Concerning metrics" |

**Default Neutral Values** (used when data is missing):
- Core Health default: 5.0
- Growth default: 5.0
- Resilience default: 1.5 (out of 3)

---

## Pillar 1: Core Financial Health (40% of Composite)

### How It Works

The core health score evaluates ~21 individual financial metrics. Each metric is:

1. **Looked up** from `StockData` or computed from raw fields
2. **Assigned a direction** (higher-is-better or lower-is-better)
3. **Placed within a range** (min/max bounds, adjusted by sector and market cap)
4. **Normalized** to a 0-to-1 fraction using sigmoid normalization
5. **Weighted** by importance (varies by sector, market cap, and growth profile)
6. **Aggregated** into a weighted average, then scaled to 0–10

### Metrics & Default Configuration

| Metric | Default Range | Base Weight | Direction | Transform |
|---|---|---|---|---|
| `revenue` | 0 – 1B | 0.15 | Higher is better | — |
| `net_income` | -5M – 50M | 0.15 | Higher is better | — |
| `eps` | -1.0 – 5.0 | 0.08 | Higher is better | — |
| `pe_ratio` | 5 – 50 | 0.08 | Lower is better | log |
| `ps_ratio` | 1 – 15 | 0.08 | Lower is better | log |
| `roe` | 0 – 0.30 | 0.12 | Higher is better | — |
| `debt_to_equity` | 0 – 2.0 | 0.08 | Lower is better | — |
| `pb_ratio` | 0.5 – 15 | 0.06 | Lower is better | log |
| `ebitda` | -100M – 500M | 0.10 | Higher is better | — |
| `free_cash_flow` | -200M – 400M | 0.08 | Higher is better | — |
| `operating_cash_flow` | -150M – 500M | 0.07 | Higher is better | — |
| `free_cash_flow_margin` | -0.30 – 0.30 | 0.06 | Higher is better | — |
| `net_margin` | -0.20 – 0.35 | 0.07 | Higher is better | — |
| `ebitda_margin` | -0.10 – 0.40 | 0.06 | Higher is better | — |
| `current_ratio` | 0.7 – 3.0 | 0.06 | Higher is better | — |
| `liability_to_asset_ratio` | 0.2 – 1.2 | 0.06 | Lower is better | — |
| `working_capital_ratio` | -0.20 – 0.40 | 0.05 | Higher is better | — |
| `retained_earnings` | -5B – 200B | 0.05 | Higher is better | log |
| `outstanding_shares` | 5M – 10B | 0.04 | Lower is better | log |
| `total_assets` | 50M – 2T | 0.05 | Higher is better | log |
| `total_liabilities` | 10M – 1T | 0.05 | Lower is better | log |

### Sigmoid Normalization

Instead of linear min/max scaling, the system uses a **logistic (sigmoid) function** that compresses extreme values and provides more differentiation in the middle range:

```
fraction = (value - min) / (max - min)
scaled = (fraction - 0.5) × 6.0
normalized = 1 / (1 + e^(-scaled))
```

This produces an S-curve where:
- Values near the min → score near 0
- Values near the midpoint → score near 0.5
- Values near the max → score near 1
- Extreme outliers don't dominate

For "lower is better" metrics, the result is inverted: `1.0 - normalized`

For metrics with `transform = "log"`, the value, min, and max are all passed through `ln(x + 1)` before normalization. This tames heavy-tailed distributions (ratios like PE and PS that can have extreme values).

### Sector-Specific Weight Overrides

Certain sectors have different weight profiles reflecting what matters most for that type of business:

**Technology:**
| Metric | Override Weight |
|---|---|
| revenue | 0.20 (up from 0.15) |
| ps_ratio | 0.15 (up from 0.08) |
| net_income | 0.08 (down from 0.15) |
| pe_ratio | 0.05 (down from 0.08) |

**Healthcare:**
| Metric | Override Weight |
|---|---|
| net_income | 0.18 |
| roe | 0.18 |
| ps_ratio | 0.08 |

**Financial Services:**
| Metric | Override Weight |
|---|---|
| roe | 0.25 |
| net_income | 0.20 |
| revenue | 0.08 |
| debt_to_equity | 0.15 |

### Sector-Specific Range Overrides

Several sectors have custom min/max ranges for their metrics. Currently defined for: Technology, Healthcare, Financial Services, Communication Services. These override the defaults when a stock belongs to that sector.

Example — Technology sector ranges differ from defaults:
- `pe_ratio`: 8–80 (vs default 5–50) — tech stocks trade at higher multiples
- `ps_ratio`: 2–20 (vs default 1–15)
- `roe`: 0–0.35 (vs default 0–0.30)

### Market Cap Dynamic Adjustments

**Weight adjustments based on market cap + growth profile:**

| Market Cap | High Growth (>15% rev growth) | Normal Growth |
|---|---|---|
| **< $2B (Small Cap)** | Revenue ×1.4, PS ×1.35, Net Income ×0.75, ROE ×0.9, D/E ×0.9 | Revenue ×1.2, PS ×1.2, Net Income ×0.75 |
| **$2B – $100B (Mid Cap)** | Revenue ×1.25, PS ×1.2 | No adjustment |
| **> $100B (Large Cap)** | ROE ×1.15, D/E ×1.1, PS ×0.85, Revenue ×1.05 | ROE ×1.3, D/E ×1.25, PS ×0.85, Revenue ×0.9 |

**Range adjustments for absolute metrics** (revenue, net income, EBITDA, FCF, etc.):
- Ranges scale with market cap using `cap^0.25` as a multiplier
- This prevents a $500M company from being measured against the same dollar ranges as Apple

**Range tightening for large caps:**
- PE ratio max range is reduced by 15% for >$100B companies (they shouldn't trade at extreme multiples)
- ROE min is raised by 10% of range (large companies should have established ROE)

### Score Aggregation

```
for each metric:
    weighted_score = normalized_fraction × metric_weight
    
total_score = sum(weighted_scores) / sum(weights) × 10.0
clamped to 0–10
```

Only metrics with available, finite data are included. Missing metrics are simply excluded from the calculation (their weight drops out of both numerator and denominator).

---

## Pillar 2: Growth & Forecast (30% of Composite)

### Components

| Component | Weight | Normalization |
|---|---|---|
| Average Revenue Growth | 0.20 | Growth rate normalization |
| Average Net Income Growth | 0.20 | Growth rate normalization |
| Recent Revenue Growth (most current) | 0.30 | Growth rate normalization |
| Free Cash Flow Margin | 0.15 | Margin normalization |
| EBITDA Margin Trend | 0.10 | Margin trend normalization |
| Relative Valuation Score | 0.05 | From `assessValuation()` |

### Normalization Functions

**Growth Rate Normalization:**
```
capped = clamp(growth, -0.50, 0.60)
fraction = (capped + 0.50) / 1.10
result = sigmoid(fraction)
```
Range: -50% to +60% growth. Values outside this range are capped to prevent outlier dominance.

**Margin Normalization:**
```
capped = clamp(margin, -0.30, 0.30)
fraction = (capped + 0.30) / 0.60
result = sigmoid(fraction)
```
Range: -30% to +30% margin.

**Margin Trend Normalization:**
```
capped = clamp(trend, -0.20, 0.20)
fraction = (capped + 0.20) / 0.40
result = sigmoid(fraction)
```
Range: -20% to +20% change in margin.

### Score Aggregation

Uses `weightedScoreFromNormalizedComponents()`:
- Missing components default to 0.5 (neutral) but don't count as "actual values"
- If no actual values exist, returns null (defaults to 5.0 in composite)
- Final score: weighted average × 10.0, clamped to 0–10

---

## Pillar 3: Resilience / Bankruptcy Risk (30% of Composite)

### Altman Z-Score Implementation

See `ALTMAN_Z_SCORE.md` for the full formula. The resilience pillar converts the raw Z-score into a 0–3 level:

| Z-Score Range | Level | Meaning |
|---|---|---|
| < 1.81 | 0 | Distress zone — high bankruptcy risk |
| 1.81 – 2.30 | 1 | Lower grey zone |
| 2.30 – 2.99 | 2 | Upper grey zone |
| ≥ 2.99 | 3 | Safe zone — low risk |

### Two-Year Loss Penalty

If the company posted **net losses in both of the last two reporting periods**, the resilience level is reduced by 1 (minimum 0). This penalizes companies that are burning cash even if their balance sheet ratios look okay on paper.

```
if (hasTwoConsecutiveNetLosses) {
    adjustedLevel = max(baseLevel - 1, 0)
}
```

### Conversion to Composite Weight

The 0–3 level is scaled to a 0–10 score for inclusion in the composite:
```
resilienceScore = (level / 3.0) × 10.0
```

### Market Cap to Liabilities — Log Normalization

The D variable in Altman-Z (Market Cap ÷ Total Liabilities) can produce extreme values for highly-valued companies. The system applies **logarithmic transformation** to prevent this ratio from dominating:

```
raw_ratio = market_cap / total_liabilities
if raw_ratio > 0.1:
    transformed = ln(raw_ratio), clamped to [ln(0.1), ln(10.0)]
scaled = (transformed - ln(0.1)) / (ln(10.0) - ln(0.1)) × 10.0
```

This normalizes the ratio to a 0–10 scale before plugging into the Z formula.

---

## Data Flow Summary

```
StockData
    │
    ├─→ computeCoreHealthScore()
    │       ├─ computeMetricValues()     → raw metric values
    │       ├─ buildMetricData()         → with sector ranges + weights
    │       ├─ normalizeValueWithConfig() → sigmoid normalization per metric
    │       └─ weighted aggregation       → Core Health Score (0–10)
    │
    ├─→ calculateGrowthScore()
    │       ├─ normalizeGrowthRate()      → revenue & income growth
    │       ├─ normalizeMargin()          → FCF margin
    │       ├─ normalizeMarginTrend()     → EBITDA margin trend
    │       ├─ assessValuation()          → relative valuation score
    │       └─ weighted aggregation       → Growth Score (0–10)
    │
    ├─→ calculateResilienceScore()
    │       ├─ buildResilienceInputs()    → Z-score input ratios
    │       ├─ Altman Z formula           → raw Z-score
    │       ├─ Level classification       → 0–3
    │       ├─ Two-year loss penalty      → adjusted level
    │       └─ scale to 0–10             → Resilience Score (0–10)
    │
    └─→ calculateCompositeScore()
            ├─ Core × 0.40
            ├─ Growth × 0.30
            ├─ Resilience × 0.30
            └─ → Composite Score (0–10)
```

---

## Key Files

| File | Role |
|---|---|
| `FinancialHealthAnalyzer.kt` | All scoring logic — composite calculation, normalization, sector weights, ranges |
| `BenchmarkData.kt` | Industry/sector PE and PS averages, fair value calculation, display metric selection |
| `HealthScoreDetailsActivity.kt` | UI for showing score breakdown to user, with per-metric rationale strings |
| `strings.xml` | Human-readable rationale text for each metric explaining why it matters |