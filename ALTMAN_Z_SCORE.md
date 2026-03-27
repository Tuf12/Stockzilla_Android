# Altman Z-Score (Resilience Pillar)

## Overview

The Altman Z-Score predicts bankruptcy probability using 5 weighted financial ratios. In Stockzilla, it serves as the **Resilience pillar** (20% of the composite score), answering the question: "Is this company at risk of going bankrupt?"

---

## Formula

**Z = 1.2×A + 1.4×B + 3.3×C + 0.6×D + 1.0×E**

| Variable | Ratio | Formula | Data Source | What It Measures |
|---|---|---|---|---|
| **A** | Working Capital / Total Assets | `(Current Assets − Current Liabilities) ÷ Total Assets` | EDGAR | Short-term liquidity relative to company size |
| **B** | Retained Earnings / Total Assets | `Retained Earnings ÷ Total Assets` | EDGAR | How much of assets are funded by cumulative profits |
| **C** | EBIT / Total Assets | `EBITDA ÷ Total Assets` (EBITDA used as proxy) | EDGAR | Asset productivity — how well assets generate earnings |
| **D** | Market Value / Total Liabilities | `Market Cap ÷ Total Liabilities` (from `StockData.marketCap` — live price × EDGAR shares when both exist) | EDGAR + live price | Equity cushion — how far market value exceeds obligations |
| **E** | Revenue / Total Assets | `Revenue ÷ Total Assets` | EDGAR | Asset turnover — business efficiency |

---

## Interpretation

| Z-Score | Zone | Risk Level | Stockzilla Level |
|---|---|---|---|
| **≥ 2.99** | Safe | Low bankruptcy risk | Level 3 |
| **2.30 – 2.99** | Upper Grey | Moderate risk, bears watching | Level 2 |
| **1.81 – 2.30** | Lower Grey | Elevated risk | Level 1 |
| **< 1.81** | Distress | High bankruptcy risk | Level 0 |

---

## Stockzilla-specific behavior

### 1. Market value to liabilities (Variable D)

The implementation uses the **direct ratio** `marketCap / totalLiabilities` with the standard formula coefficients — **no** log transform. `marketCap` on `StockData` is built from **live price × shares outstanding** (price from Finnhub; shares from EDGAR when available). See `buildResilienceInputs()` in `FinancialHealthAnalyzer.kt`.

### 2. EBITDA as EBIT proxy (Variable C)

The code uses EBITDA rather than EBIT for variable C. EBITDA is more commonly available in standardized reporting and provides a reasonable approximation.

### 3. Two-year consecutive loss penalty

After calculating the base Z-level (0–3), the system checks if the company had **net losses in both of the most recent two reporting periods**. If yes, the level is reduced by 1 (minimum 0).

```
if company had losses in year N AND year N-1:
    level = max(level - 1, 0)
```

This catches cases where balance sheet ratios still look acceptable but the company is actively burning money.

### 4. Manufacturing vs non-manufacturing (implemented)

`FinancialHealthAnalyzer` selects the **classic Z** (with revenue/assets term **E**) when `EdgarConcepts.isManufacturingSic(sicCode)` is true; otherwise it uses **Z''** (no **E** term) with the coefficients shown in `ALTMAN_Z_SCORE_SPEC.md`.

### 5. Conversion into the composite

The discrete **level 0–3** is stored on `HealthScore` as `zSubScore`. For the weighted composite, that level is mapped to a 0–10 scale:

```
resilienceScoreForComposite = (level / 3.0) × 10.0
```

then multiplied by the **20%** resilience weight with core and growth pillars.

---

## Required Data

All from EDGAR except Market Cap (calculated from Finnhub price × EDGAR shares outstanding):

- Current Assets
- Current Liabilities
- Total Assets
- Total Liabilities
- Retained Earnings
- EBITDA (or Operating Income + Depreciation & Amortization)
- Revenue
- Market Cap (Stock Price × Shares Outstanding)
- Net Income History (at least 2 periods, for the two-year loss check)