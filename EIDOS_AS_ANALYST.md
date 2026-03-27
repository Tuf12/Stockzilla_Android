Here’s a concise breakdown you can use as a product/design north star.

---

## The problem the app is facing

**1. Structured fundamentals are only as good as the mapping**  
Most metrics come from SEC **companyfacts** (XBRL tags). Filers use different concepts (US GAAP vs IFRS, rev-rec labels, debt across several tags). A **global or oversized tag list** pulls the wrong line for some tickers; a **too-narrow** list leaves holes. **Per-symbol overrides** help but don’t explain *why* a number is missing or suspicious.

**2. “Mechanical” pipelines can’t negotiate meaning**  
The app can loop tags and apply rules, but it cannot interpret **footnotes, restatements, non-GAAP adjustments, or tables that aren’t a single tag**. So users see **N/A**, odd jumps, or numbers that “parse” but aren’t the metric they care about—with little narrative.

**3. Intelligence was split across surfaces**  
Silent Grok tag-fix + evolving tagging added **tokens, complexity, and duplication** without a single place where the user **sees the reasoning** and **approves** the fix. That’s the “mechanical vs intelligent” tension you called out.

**4. Trust and education**  
Power users want to tie numbers to **a specific filing and line**, not only to a tag name. The app today doesn’t default to a **transparent, discussable** path for “this row is wrong—show me why and fix it.”

---

## What “Eidos as an analyst” should be

A **Full Analysis–scoped chat** where Eidos acts as a **research analyst**: it works **with** the user on **missing or questionable metrics**, using **filings as ground truth**, not only XBRL shortcuts.

It is **not** a replacement for the whole app—it’s the **escalation path** when mechanics aren’t enough.

---

## How it should work to be most effective

**1. Ground every turn in context**  
Pass in (internally): symbol, CIK, what’s missing/wrong, latest `StockData` / health inputs, and **links or accession** to the right 10-K/10-Q. Eidos should **never** free-associate a number without tying it to a **document**.

**2. Form-first for disputes**  
When tags fail or numbers look wrong, Eidos’s job is to **identify the right filing and section/table** (and eventually extract from **HTML/PDF or structured filing text** per your roadmap), not to guess tags from memory. XBRL tag override becomes **secondary** or **one of several** fixes.

**3. Human-in-the-loop by design**  
Propose: “Here’s revenue from this table in this filing; here’s the period.”  
User: **Accept** → write into your pipeline (raw facts, override, or analyst-confirmed field—your schema choice).  
**Decline** → Eidos adjusts (different line, different period, different filing).

**4. Narrow scope per session**  
One analyst thread should focus on **one symbol (and maybe one metric cluster at a time)** so prompts stay small and decisions stay auditable.

**5. Clear separation from “silent” mechanics**
- **Background pipeline:** standards + per-symbol overrides + simple verification loops—**no** chat, minimal tokens.
- **Analyst chat:** dialogue, citations, accept/decline—**this** is where “intelligence” and trust live.

**6. Audit trail**  
Store enough to reproduce: accession, excerpt or pointer, what was accepted, timestamp. That supports debugging and future “why is this number here?” in the UI.

---

## How Eidos-as-analyst solves the problem (one paragraph)

It moves **correctness and trust** from **hidden tag plumbing** to a **visible, user-approved workflow**: when mechanical extraction fails or is doubtful, the user collaborates with Eidos on **the actual filings**, accepts or rejects proposed values, and only then commits data—reducing wrong cross-ticker mappings, explaining gaps, and reserving LLM cost for moments where **judgment and consent** matter.

---
