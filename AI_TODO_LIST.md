# Eidos — Implementation Tracker

Work through one item at a time. Reference `STOCKZILLA_AI.md` before starting each item.

---

## BUILT ✅

- AI Assistant screen with navigation drawer conversation list (`AiAssistantActivity`, `AiConversationAdapter`)
- Per-conversation message history — persists in `ai_conversations` / `ai_messages`
- Grok API + tool-calling loop (`GrokApiClient`, `AiAssistantViewModel`)
- BYOK API key management via `ApiKeyManager`
- OkHttp timeouts — connect 30s / write 30s / read 120s
- System prompt — single combined message; context from `buildContextPacket()` + tools
- **Memory Cache** — `AiMemoryCacheEntity` / `ai_memory_cache`; `write_memory_note` tool; USER + STOCK notes in context; **GROUP notes stubbed** (`groupNotes = emptyList()` in `buildContextPacket`); Memory Cache screen (`AiMemoryCacheActivity`)
- **Portfolio / watchlist / favorites** — exposed as **`get_portfolio_overview`**, **`get_watchlist`**, **`get_favorites`** tools (not inlined into every context packet by design)
- **`list_analyzed_stocks`** — symbols from `edgar_raw_facts`
- **`get_stock_data`** — full join context + optional `fetch_if_missing` analyze pipeline
- **Per-symbol + General conversations** — `AiAssistantViewModel` + `AiAssistantActivity.start(..., openMode)`; General conversation pinned / non-rename rules in UI
- **SEC filing discovery (chat)** — `sec_search_filings` tool + discovery card UI; `saveAndAnalyzeFilings` persists metadata and runs analysis (`AiAssistantViewModel`)
- **Eidos Analyst** — `EidosAnalystActivity` / `EidosAnalystViewModel`; filing search/chunk/FS tools; proposal sheet + Accept → **`eidos_analyst_confirmed_facts`**; app **`rePersistFundamentalsAfterAnalystAccept`** / **`applyAnalystConfirmedFacts`** materialize merged `StockData` into **`edgar_raw_facts`** / **`financial_derived_metrics`** (see [EIDOS_AS_ANALYST.md](EIDOS_AS_ANALYST.md))

---

## TO BUILD 🔲

### 1. AI auto-writes the About section

Full Analysis reads/writes **`company_profiles.about`** (`CompanyProfileEntity`). `StockProfileEntity` / `stock_profiles` remains unused for this path.

- [x] Confirm profile storage in Room (`company_profiles` + `CompanyProfileEntity`)
- [ ] On stock load: if About is empty and `aboutEditedByUser` is false, fire background AI call
- [x] Eidos can write About via tools: main assistant **`set_full_analysis_about`**, analyst **`analyst_set_full_analysis_about`** (same table); `get_stock_data` / `analyst_get_app_financial_data` JSON includes `fullAnalysisAbout` / `fullAnalysisAboutEditedByUser`
- [ ] If API unavailable: leave empty or show “Generate with Eidos” fallback
- [x] Set `aboutEditedByUser = true` when the user saves manually on Full Analysis

---

### 2. (Optional) Inline portfolio snapshot in context

**Done** via tools. If product wants **zero-tool** access for common cases, add a compact holdings block to `buildContextPacket()` when `symbol != null` (keep General chat lean).

---

### 3. (Done) Per-stock conversations

Implemented — reopen item only if regression testing fails.

---

### 4. Group-scope memory

- [ ] Wire GROUP-scope notes when stable peer **group IDs** exist in DB/context (replace stub in `buildContextPacket`)
- [ ] Optional: seed default USER notes; optional: edit UI for notes (view/delete exist)

---

### 5. Suggested question chips

- [ ] Horizontal chip row above input when message count is under 3
- [ ] Default prompts; tap sends message; hide when thread is long enough

---

### 6. AI peer grouping (dedicated UX)

Industry peers screen exists (`IndustryStocksActivity`) with discover / my group — **no** dedicated “Eidos proposes peers” flow yet.

- [ ] Entry point (e.g. “Ask Eidos” on industry peers screen)
- [ ] Grouping prompt: context + DB symbol list + cap tier
- [ ] Proposal UI with approve/reject per ticker
- [ ] On approve: `IndustryPeerRepository.replacePeers` / `addPeer`
- [ ] On decision: memory notes `PEER_RATIONALE` / `PEER_REJECTION`
- [ ] Unknown tickers: `get_stock_data(..., fetch_if_missing: true)` or `StockViewModel.analyzeStock`

---

### 7. Ticker search inside Eidos

- [ ] Search bar on `AiAssistantActivity`
- [ ] Resolve symbol → open or create conversation; if missing from DB, run analyze then open
- [ ] Loading state

---

## BACKLOG 💭

- Conversation trimming / summarization for very long threads
- Group-level memory cache wired across all stocks in a peer group
- Web scraping for companies not in Grok training data
- Bottom sheet Eidos overlay from analysis screens
- Additional AI models — all share the same Memory Cache
