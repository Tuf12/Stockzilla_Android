# Altman Z-Score (Resilience Pillar)

## Overview

The Altman Z-Score predicts bankruptcy probability using 5 weighted financial ratios. In Stockzilla, it serves as the **Resilience pillar** (30% of the composite score), answering the question: "Is this company at risk of going bankrupt?"

---

## Formula

**Z = 1.2×A + 1.4×B + 3.3×C + 0.6×D + 1.0×E**

| Variable | Ratio | Formula | Data Source | What It Measures |
|---|---|---|---|---|
| **A** | Working Capital / Total Assets | `(Current Assets − Current Liabilities) ÷ Total Assets` | EDGAR | Short-term liquidity relative to company size |
| **B** | Retained Earnings / Total Assets | `Retained Earnings ÷ Total Assets` | EDGAR | How much of assets are funded by cumulative profits |
| **C** | EBIT / Total Assets | `EBITDA ÷ Total Assets` (EBITDA used as proxy) | EDGAR | Asset productivity — how well assets generate earnings |
| **D** | Market Value / Total Liabilities | `Market Cap ÷ Total Liabilities` (log-normalized) | Finnhub + EDGAR | Equity cushion — how far market value exceeds obligations |
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

## Stockzilla-Specific Modifications

### 1. Log-Normalized Market Cap to Liabilities (Variable D)

Standard Altman-Z uses raw `Market Cap ÷ Total Liabilities`, but this can produce extreme values for highly-valued growth companies (e.g., a tech company with $100B market cap and $5B liabilities = ratio of 20, which would dominate the entire Z-score).

Stockzilla applies a **logarithmic transformation**:
```
raw_ratio = market_cap / total_liabilities
transformed = ln(raw_ratio), clamped to [ln(0.1), ln(10.0)]
scaled = ((transformed - ln(0.1)) / (ln(10.0) - ln(0.1))) × 10.0
```
This scales the ratio to 0–10, preventing any single variable from overwhelming the score.

### 2. EBITDA as EBIT Proxy (Variable C)

The code uses EBITDA rather than EBIT for variable C. EBITDA is more commonly available in standardized reporting and provides a reasonable approximation.

### 3. Two-Year Consecutive Loss Penalty

After calculating the base Z-level (0–3), the system checks if the company had **net losses in both of the most recent two reporting periods**. If yes, the level is reduced by 1 (minimum 0).

```
if company had losses in year N AND year N-1:
    level = max(level - 1, 0)
```

This catches cases where balance sheet ratios still look acceptable but the company is actively burning money.

### 4. Conversion to Composite

The 0–3 level is converted to a 0–10 scale for inclusion in the composite:
```
resilience_score = (level / 3.0) × 10.0
```

Then weighted at 30% in the final composite.

---

## Notes for Future Development

**Modified Z''-Score for Non-Manufacturing:**

The original Altman Z-Score was calibrated for manufacturing companies. A modified version (Z''-Score) exists for service/non-manufacturing companies that drops variable E (asset turnover, which is less meaningful for asset-light businesses) and adjusts coefficients:

```
Z'' = 6.56×A + 3.26×B + 6.72×C + 1.05×D
```

| Z''-Score | Zone |
|---|---|
| > 2.60 | Safe |
| 1.10 – 2.60 | Grey |
| < 1.10 | Distress |

Consider implementing both versions and selecting based on the company's SIC code (manufacturing vs service).

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