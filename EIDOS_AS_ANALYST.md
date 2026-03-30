Here’s a concise breakdown you can use as a product/design north star.

---

## The problem the app is facing

**1. Structured fundamentals are only as good as the mapping**  
Most metrics come from SEC **companyfacts** (XBRL tags). 
Filers use different concepts (US GAAP vs IFRS, rev-rec labels, debt across several tags). 
A **global or oversized tag list** pulls the wrong line for some tickers; 
a **too-narrow** list leaves holes. **Per-symbol overrides** help but don’t explain *why* a number is missing or suspicious.

**2. “Mechanical” pipelines can’t negotiate meaning**  
The app can loop tags and apply rules, but it cannot interpret **footnotes, restatements, non-GAAP adjustments, or tables that aren’t a single tag**. 
So users see **N/A**, odd jumps, or numbers that “parse” but aren’t the metric they care about—with little narrative.

**3. Intelligence was split across surfaces**  
Silent Grok tag-fix + evolving tagging added ** works to an extent but does not fully solve the problem **. 

**4. Trust and education**  
There is no way to add numerical values without it parsing through the SEC EDGAR XBRL tables and the user
needs an additional method that does not use XBRL or tags.
---

## What “Eidos as an analyst” is

A **Full Analysis–scoped chat** where Eidos acts as a **research analyst**: it works **with** the user on **missing or questionable metrics**, using **filings as ground truth**.

It is **not** a replacement for the XBRL parsing or evolving tagging system it is an **additional path** when those mechanics aren’t enough.

---

## How it should work to be most effective

**1. Use the SEC EDGAR Raw Filing Documents**  
All data retrieved must come from a SEC EDGAR form if not present no numerical value should be provided.
Eidos fetches the raw filing document the company uses(10-k,10-q, 20-f, 40-f, ect..) (as opposed to pulling structured XBRL data)

**2. Human-in-the-loop by design**  
User and Eidos discuss issues with missing or questionable metrics via chat on the Full Analysis screen (“Eidos as analyst”).
Eidos fetches Raw SEC EDGAR forms (10-k,10-q, 20-f, 40-f, ect), and locates and extracts the missing or questionable metric. utilize existing fetchAndNormalizeDocument() found in SecEdgarService.kt
Eidos provides a proposal for the user. a pop up window that has selectable options for the provided metrics, and accept or decline buttons.
User: **Accept** → Eidos saves the value to its own table in the database, keeping it safe from being overwritten when the symbol refreshes.  
**Decline** → Data does not get added to the database. Eidos and user discuss the decline and repeat the process.

**3. Symbol-focused**  
The conversation is anchored to the stock being analyzed, but doesn't restrict free flowing conversation.

**4. Audit trail**  
Keep a record of proposals, accepts, declines, and other context that helps explain changes over time. Use storage **separate from the main Eidos Assistant** (do not use the same chat DB or memory cache as general assistant).
---

## How Eidos-as-analyst solves the problem (one paragraph)

when mechanical extraction fails or is doubtful, the user collaborates with Eidos on **the actual filings**, accepts or rejects proposed values, and only then commits to the DB.

---

## Implementation checklist (app)

Use this as a working list while building the feature. Items reflect `EIDOS_AS_ANALYST.md` as the source of truth and the current codebase (tag-fix / `SymbolTagOverrides` is a **different** path—keep it, but do not confuse it with analyst mode).

### Product & UX

- [x] **Full Analysis entry point** — A dedicated way to open “Eidos as analyst” from the Full Analysis screen (distinct from the existing **Find tag (Eidos)** flow, which is companyfacts / override–oriented). *Implemented: “Eidos Analyst” outlined button between business profile (Save About) and Raw Financial Facts (EDGAR); opens `EidosAnalystActivity`.*
- [x] **Scoped chat UI** — Chat surface tied to the symbol under analysis (symbol anchor + free-form discussion), not the generic main-app Eidos list. **Persist** conversation history here, **not** in the main assistant’s chat tables (`Persistence` below). *Implemented: `EidosAnalystActivity` + `EidosAnalystViewModel`, Grok chat with analyst system prompt; history in `eidos_analyst_chat_messages` (Room v32).*
- [ ] **Proposal UI** — Dialog or bottom sheet: Eidos presents **one or more candidate metric values** (selectable options), plus **Accept** and **Decline** (and room to show source snippet / filing reference where possible).
- [ ] **Decline loop** — After decline, conversation continues; user can iterate until accept or abandon (no silent DB write).

### Data ground rules (per spec)

- [ ] **Raw filing as source for numbers** — For proposed numerical values, the model path must use **SEC EDGAR raw filing documents** (e.g. 10-K, 10-Q, 20-F, 40-F) via app fetch/normalize, not structured XBRL/companyfacts alone. If the filing text cannot support a number, **do not invent** a value—surface uncertainty in chat.
- [ ] **Expose filing fetch for analyst** — Today `fetchAndNormalizeDocument()` in `SecEdgarService` is **private** and used inside news/8-K-style flows. Add a **supported API** for analyst (e.g. public/suspend method) that resolves the right document URL(s) for a symbol + form/period, downloads, strips HTML, and returns text capped safely for the model.

### Persistence (survives refresh)

**Two kinds of data**

- **Standard data** — What the app loads the usual way (SEC pipelines, evolving tag overrides, refresh). This is what gets updated when you pull fundamentals again.
- **Eidos-as-analyst data** — Numbers the user **explicitly accepted** after Eidos proposed them from filings. This must stay clearly separate so a refresh never wipes user-approved values.

**Checklist**

- [ ] **Separate storage for analyst-approved numbers** — Keep accepted values in **their own tables** (not only mixed into rows that refresh overwrites). Implementation: Room entities/DAOs/migrations registered on the app database like other features.
- [ ] **Confirmed facts** — For each accepted value, store at least: stock, metric (and period if needed), the value, and **where it came from** (e.g. filing link or accession) so you can always tell “user + analyst path” from “automatic extraction alone.” Normal EDGAR refresh must **not** delete these rows; the UI merges them when showing metrics or scores.
- [x] **Analyst chat memory (required)** — Persist Eidos-as-analyst **conversation history** in storage that is **fully separate** from the main **Eidos Assistant** (do not reuse `ai_conversations` / `ai_messages` or `AiMemoryCache` for this). Same app, different tables or scopes so analyst chat and assistant chat never mix. *Implemented: `eidos_analyst_chat_messages` + `EidosAnalystChatDao` (Room v32).*

### Application logic

- [ ] **Merge rules** — When rendering Full Analysis / raw facts / derived metrics, apply **analyst-confirmed overrides** in a deterministic order (e.g. after mechanical extraction, or only where mechanical value is null/suspect—product decision documented in code).
- [ ] **ViewModel(s)** — Analyst-specific ViewModel(s) for chat, proposal state, and DB writes; keep concerns separate from `AiAssistantViewModel` if that class is already crowded with tag-fix and general tools.
- [ ] **Grok / tools contract** — Define tools or structured prompts so Eidos can: request normalized filing text for a CIK/accession/form, return **candidates** (not silent DB writes), and only persist via **user Accept** (tool call or app-side confirmation step).

### Audit trail & memory

- [ ] **Analyst audit trail** — History of proposals, accepts, declines, and other notes useful for “why does this number look like this?” Keep this **separate from main Eidos Assistant** memory (use analyst-specific tables or scopes—see **Persistence** above). Use a seperate font color for displaying Eidos Analyst entered data, so they are visually distinct from automatically extracted data. 

### Quality & ops

- [ ] **Strings / accessibility** — Copy for analyst vs “Find tag (Eidos)” so users understand tag mapping vs filing-based value confirmation.
- [ ] **Diagnostics** — Optional logging category (similar to `EIDOS_TAG` prefix) for analyst flows to simplify support.
- [ ] **Tests** — Room migration tests for new tables; unit tests for merge logic and for “refresh does not delete analyst facts.”

### Lingering / related code today (not the analyst product)

The app already has: **Full Analysis** (`FullAnalysisActivity`), **Find tag (Eidos)** → `AiAssistantActivity` + `runTagFixBootstrap`, **`set_symbol_tag_override`**, `SymbolTagOverrideEntity`, `SecEdgarService.getCompanyFacts` / `buildFactsConceptIndexJson`, and **`AiMemoryCache`** (main assistant only). Those support the **evolving tag** path and general assistant; **Eidos as analyst** still needs **its own** confirmed-metric storage, **separate chat persistence**, **raw filing fetch**, and **proposal/accept UI**.

---
