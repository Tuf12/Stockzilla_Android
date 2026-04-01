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
Eidos uses the raw filing document the company files (10-K, 10-Q, 20-F, 40-F, etc.)—not structured XBRL/companyfacts—as the basis for proposed numbers.

**1a. Search-first navigation, then financial statements (not generic “whole filing” preview)**  
Analyst mode must offer a **search tool** over the **full normalized primary** periodic filing so Eidos can locate headings, Item lines, table titles, or note labels **before** pulling large windows. Search returns **character offsets** and short snippets; Eidos then calls **chunk** at those offsets (possibly **multiple** chunk calls in sequence) to read the exact region. Separately, a **dedicated financial-statement tool** (and backing `SecEdgarService` API) can retrieve a best-effort **Item/Part slice** (US 10-K/10-Q; equivalents for 20-F/40-F) when a full FS block is useful. Both paths are **separate from** any generic “fetch periodic filing” or news-style path that truncates the primary document (e.g. first N characters) or skips exhibits. The **Grok tool loop** must allow **back-to-back** tool rounds (search → chunk → chunk → … → proposal) until the model finishes—not stop after a single tool batch when the model also emitted short assistant text alongside tool calls.

**2. Human-in-the-loop by design**  
User and Eidos discuss issues with missing or questionable metrics via chat on the Full Analysis screen (“Eidos as analyst”).
Eidos calls **search** on the primary filing text, then **chunk** (or the **financial-statement extraction tool**) as needed to locate and extract the missing or questionable metric from filing text.
Eidos provides a proposal for the user: a sheet with selectable options for the provided metrics, and **Accept** and **Decline** buttons.
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
- [x] **Proposal UI** — Scrollable **dialog** when Eidos calls `analyst_present_metric_proposal`: **one or more candidate metric values** (radio options), **Accept** / **Decline**, source snippet and filing reference. *Implemented: `EidosAnalystMetricProposal` + `bottom_sheet_eidos_analyst_proposal.xml` + `EidosAnalystProposalSheetUi` (AlertDialog); `EidosAnalystViewModel.presentProposal` from tool executor / `acceptProposal` / `declineProposal`.*
- [x] **Decline loop** — After decline, conversation continues; user can iterate until accept or abandon (no silent DB write of *metric values*). *Implemented: Decline clears the sheet, posts an assistant line, and appends an audit event (`PROPOSAL_DECLINED`). Accept upserts `eidos_analyst_confirmed_facts`, logs `PROPOSAL_ACCEPTED` audit, and posts the chat acknowledgment.*

### Data ground rules (per spec)

- [x] **Raw filing as source for numbers** — For proposed numerical values, the model path must use **SEC EDGAR raw filing documents** (e.g. 10-K, 10-Q, 20-F, 40-F), not structured XBRL/companyfacts. If the filing text cannot support a number, **do not invent** a value—surface uncertainty in chat. *Implemented: analyst system prompt + tools that load primary filing text; model must use `analyst_fetch_financial_statements` before numeric proposals.*

- [x] **Dedicated tool: financial statement text for analyst** — Implement a **separate** analyst tool + `SecEdgarService` API whose contract is to return text sufficient to ground **financial statement line items** (full statements and material notes as needed), **not** a truncated preview of the whole filing. Do **not** rely on news/long-document heuristics that take only a prefix of the primary document or omit exhibits by default. Options in the contract: (a) **search** over normalized primary text (phrases → offsets + snippets) so Eidos navigates before chunking, (b) section-scoped extraction (Item/Part for 10-K/10-Q; analogous anchors for 20-F/40-F), (c) optional **chunked** full primary document (`offset`/`max_chars`) when the metric spans or sits outside the default FS block—**multiple** chunk calls in one turn must be supported by the tool loop. *Implemented: `SecEdgarService.searchPrimaryDocumentForAnalyst` (`AnalystFilingTextSearch`), `fetchFinancialStatementsTextForAnalyst`, `fetchPrimaryDocumentChunkForAnalyst`; Grok tools `analyst_search_filing_text`, `analyst_fetch_financial_statements`, `analyst_fetch_filing_chunk`; `EidosAnalystViewModel.runAnalystGrokToolLoop` runs tool calls whenever present (no early exit on assistant text + tools), up to 12 rounds.*

- [ ] **Supplementary: generic periodic filing fetch (optional)** — A separate “overview” or “full form chunk” tool may exist for narrative context; it does **not** replace the financial-statement tool for numeric proposals.

### Persistence (survives refresh)

**Two kinds of data**

- **Standard data (SEC / XBRL pipeline)** — What the app loads the usual way: companyfacts → `StockData`, persisted in **`edgar_raw_facts`** (`EdgarRawFactsEntity`) on refresh, plus evolving tag overrides (`SymbolTagOverrideEntity`) where applicable. This layer is **mechanical**: it updates when you pull fundamentals again.
- **Eidos-as-analyst data** — Numbers the user **explicitly accepted** after Eidos proposed them from **filing text** (not from silent XBRL substitution). Stored only in **`eidos_analyst_confirmed_facts`** (`EidosAnalystConfirmedFactEntity`). This table is **never** written by the automated refresh job and is **not** merged into `edgar_raw_facts`—separation is by design.

**Same app experience, two sources**

- On **Full Analysis**, raw facts and the financial history grid are the **same screens** users already use for EDGAR-backed numbers. Analyst-approved values **appear on those rows and period columns** so the flow feels like “the metric is filled in,” but the **origin** is always visible: **XBRL** vs **Analyst** are shown on **separate lines** in the value cell (distinct colors—theme primary / accent for the pipeline line, `eidos_analyst_confirmed_value` for the analyst line). That mirrors the mental model: *same UI, separate DB rows; no silent overwrite of one store by the other.*

**Checklist**

- [x] **Separate storage for analyst-confirmed values** — Accepted values live in a dedicated Room table, isolated from the XBRL pipeline tables that the automated refresh cycle writes to. The refresh cycle must never touch this table. *Implemented: `eidos_analyst_confirmed_facts` (`EidosAnalystConfirmedFactEntity`), Room v33 / `MIGRATION_32_33`; no code path in mechanical refresh deletes this table.*
- [x] **Confirmed fact schema** — Each row stores: symbol, metric key, period (`periodLabel`: empty = primary scalar row; non-empty = scoped FY/quarter/TTM), display `valueText`, filing provenance (`filingFormType`, `accessionNumber`, `filedDate`, `viewerUrl`, `primaryDocumentUrl`, `sourceSnippet`), proposal metadata (`proposalId`, `candidateId`, `candidateLabel`), `confirmedAtMs`. These rows are the authoritative **analyst** source for that `(symbol, metricKey, periodLabel)`; they do **not** duplicate into `EdgarRawFactsEntity`.
- [x] **Analyst chat memory (required)** — Persist Eidos-as-analyst **conversation history** in storage that is **fully separate** from the main **Eidos Assistant** (do not reuse `ai_conversations` / `ai_messages` or `AiMemoryCache` for this). Same app, different tables or scopes so analyst chat and assistant chat never mix. *Implemented: `eidos_analyst_chat_messages` + `EidosAnalystChatDao` (Room v32).*

### Application logic

- [x] **Merge rules (display-only)** — Mechanical `StockData` / history series are computed first from the XBRL path. Then `EidosAnalystConfirmedFactMerge` and `EidosAnalystPeriodScope` join in rows from `eidos_analyst_confirmed_facts`. **When an analyst row exists for that metric (and period scope), the cell shows both:** a line labeled **XBRL:** (automated value or N/A) and a line labeled **Analyst:** (user-confirmed text). **Find tag** is suppressed when an analyst value is present for that primary cell, because the user has already committed a filing-backed candidate. **Derived metrics** (second table) still use mechanical `StockData` only—documented in `EidosAnalystConfirmedFactMerge.kt` as deferred.*
- [x] **ViewModel(s)** — Analyst-specific ViewModel(s) for chat, proposal state, and DB writes; keep concerns separate from `AiAssistantViewModel` that class is already provides tag-fix and general tools. *Implemented: `EidosAnalystViewModel` (chat, proposal, `acceptProposal` / `declineProposal` → confirmed facts + audit + chat messages); separate from `AiAssistantViewModel`.*
- [x] **Grok / tools contract** — Define tools so Eidos can: (0) **read** the app’s stored fundamentals for the symbol **read-only** (`analyst_get_app_financial_data`, same payload shape as main assistant `get_stock_data` when present—no refresh/write), (1) **search** the normalized primary filing for phrases and get **offsets**, (2) request **financial-statement-scoped** normalized text (dedicated tool/API above), (3) request **additional chunks** at chosen offsets **multiple times per reply chain** if needed, (4) call **`analyst_present_metric_proposal`** with **candidates** (no silent DB writes), (5) persist only via **user Accept** into analyst-confirmed storage. Keep these tools separate from any generic periodic fetch used for exploration only. *App read + search + multi-round loop + proposal UI + **Accept → `eidos_analyst_confirmed_facts`** implemented.*

### Audit trail & memory

- [x] **Visual distinction (XBRL vs Analyst)** — Analyst-confirmed values use **`R.color.eidos_analyst_confirmed_value`** on the **Analyst:** line; automated values use the normal theme/accent colors on the **XBRL:** line. Implemented on **Raw Financial Facts** and **financial history** (TTM, quarterly, annual) when a confirmed fact exists.
- [ ] **Analyst audit trail** — History of proposals, accepts, declines, and other notes useful for “why does this number look like this?” Keep this **separate from main Eidos Assistant** memory (use analyst-specific tables or scopes—see **Persistence** above). *Partially implemented: `eidos_analyst_audit_events`.*

### Quality & ops

- [ ] **Strings / accessibility** — Copy for analyst vs “Find tag (Eidos)” so users understand tag mapping vs filing-based value confirmation.
- [ ] **Diagnostics** — Optional logging category (similar to `EIDOS_TAG` prefix) for analyst flows to simplify support.
- [x] **Tests** — Room migration tests for new tables; unit tests for merge logic and for “refresh does not delete analyst facts.” *Implemented: `RoomMigration32To33Test`; `EidosAnalystConfirmedFactMergeTest`. **Not yet:** automated test that runs refresh and asserts analyst rows unchanged (invariant covered by schema separation + code review).*

### Lingering / related code today (not the analyst product)

The app already has: **Full Analysis** (`FullAnalysisActivity`), **Find tag (Eidos)** → `AiAssistantActivity` + `runTagFixBootstrap`, **`set_symbol_tag_override`**, `SymbolTagOverrideEntity`, `SecEdgarService.getCompanyFacts` / `buildFactsConceptIndexJson`, and **`AiMemoryCache`** (main assistant only). Those support the **evolving tag** path and general assistant. **Eidos as analyst** now has **its own** confirmed-metric storage (`eidos_analyst_confirmed_facts`), **audit events** (`eidos_analyst_audit_events`), **merge-on-display** for raw facts, **separate chat persistence**, **primary-document search**, a **dedicated financial-statement extraction path** (not the truncated generic periodic fetch), **chunked full-form** reads with **multi-round** tool execution, and **proposal/accept UI**.

---
