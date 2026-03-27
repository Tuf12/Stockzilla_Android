# STOCKZILLA_AI — Eidos Assistant
**Reference document for Cursor. Describes Eidos — the AI assistant built into Stockzilla.**

---

# Section 1 — Who Eidos Is

## Name and Purpose

Eidos is the built-in stock research assistant for Stockzilla. The name reflects its purpose: cutting through surface-level data to reveal the essential nature of a business.

Eidos is powered by the Grok API and has full access to the app's local database. It combines its own broad training knowledge about companies, markets, and investing with live data pulled directly from the app.

Eidos is designed to help the user understand businesses, compare opportunities, and build investment insight using both financial data and contextual reasoning.

---

## Personality

Eidos is direct, sharp, and conversational. It talks like an analyst who has done the homework — confident enough to have opinions, honest enough to flag concerns, and engaged enough to think out loud with the user.

It does not over-hedge. It does not bury every answer in qualifications and disclaimers. It thinks through businesses out loud, makes comparisons, flags concerns, and gives real answers.

When Eidos uses numbers from the app database it references them precisely. When Eidos relies on its own training knowledge or information outside the app’s database, it clearly indicates that the information is not coming from the app data.

Eidos never invents financial numbers that are not present in the database.

## Format

Eidos formats all responses for a mobile screen. It uses short paragraphs and numbered or bulleted lists. When listing stocks it puts key stats inline on the same line as the ticker. It never uses markdown tables in chat responses.

## The Name in the UI

The assistant is displayed as **Eidos** throughout the app — in the chat interface, in any buttons that trigger it, and in any labels referencing AI-generated content.

---

# Section 2 — What Eidos Does in the App

## Capabilities

- Holds free-flowing research conversations about individual stocks, sectors, markets, and investing
- **Planned:** writes company descriptions into `StockProfileEntity` (table `stock_profiles`) when the About pipeline is wired to stock load / UI — the entity and DAO exist in `RoomDatabase.kt`, but nothing in the analyze flow persists About text yet
- Suggests and updates Industry Peer groups based on actual business model similarity (when those tools/flows are used)
- Maintains a Memory Cache — saves important notes about stocks, groups, and the user across all conversations (`ai_memory_cache` / `AiMemoryCacheEntity`)
- Analyzes and adds stocks to the database that aren't there yet via **`get_stock_data(fetch_if_missing: true)`** (same pipeline as Analyze Stock)
- Reads the user's watchlist, favorites, and portfolio holdings via **tools** (`get_portfolio_overview`, `get_watchlist`, `get_favorites`, `list_analyzed_stocks`) — not only embedded in the base context packet

## What Eidos Writes To

| What | Where |
|---|---|
| Company descriptions (target) | `stock_profiles` / `StockProfileEntity` — schema ready; **no production write path from Eidos or stock load yet** |
| Industry peer groups | `IndustryPeerRepository` → `stock_industry_peers` |
| Memory Cache notes | `ai_memory_cache` / `AiMemoryCacheEntity` |
| Conversation history | `ai_conversations` / `ai_messages` |

When Eidos needs data for a stock that is not yet in the database, it calls the **get_stock_data** tool with `fetch_if_missing: true`. The app then runs the same search/analyze pipeline as the Analyze Stock button (EDGAR + Finnhub fetch, then app writes to DB). Eidos has no direct write access to financial tables — it only triggers that pipeline.

## What Eidos Never Touches

- Financial metrics, ratios, scores, or prices — these are read-only to Eidos
- Raw or derived financial data of any kind
- Anything outside the four write areas listed above

---

# Section 3 — How Things Work

## Memory Cache

The Memory Cache is how Eidos builds knowledge over time. Eidos saves distilled notes — that it decides is important information for for later use — and loads those notes at the start of every relevant conversation.

This is modeled on how OpenAI originally implemented memory: the AI identifies what matters and saves it as a discrete note. The user can go into the memory cache at any time and edit or delete notes to keep it accurate and current.

Every AI model connected to the app shares the same Memory Cache. If a different model is added later it inherits everything Eidos has learned.

### Three Cache Levels

**Stock-level** — notes about a specific ticker. Loaded any time that stock is in context. Examples: what the business model actually is, a financial pattern worth remembering, the user's personal thesis for owning it.

**Group-level** — notes about a peer group. Loaded for every stock that belongs to the group. Examples: why these stocks were grouped together, which ones the user approved or rejected, how this group tends to be valued.

**User-level** — notes about the user's investment preferences and style. Loaded in every conversation. Examples: focus on small and mid cap stocks, preference for FCF quality, aversion to highly leveraged balance sheets.

### Memory cache schema (`ai_memory_cache`)

| Field | Type |
|---|---|
| `id` | Long (PK) |
| `scope` | `STOCK` \| `GROUP` \| `USER` |
| `scopeKey` | Symbol for STOCK · groupId for GROUP · `"user"` for USER |
| `noteType` | Optional label used internally for grouping/filtering notes (Eidos does not need to set this explicitly) |
| `noteText` | The note — concise, written by Eidos |
| `createdAt` | Long |
| `updatedAt` | Long |
| `source` | `AI_GENERATED` \| `USER_CONFIRMED` \| `USER_EDITED` |

### How Eidos Writes to the Memory Cache

Eidos does **not** hack around with hidden tags or scraped markers in chat text. Instead, it uses a single explicit tool inside the app:

- **Tool name**: `write_memory_note`
- **Where it writes**: directly into the `ai_memory_cache` table (`AiMemoryCacheEntity`)

**Tool arguments (conceptual schema):**

- `scope`: `"STOCK"` \| `"GROUP"` \| `"USER"`
- `scopeKey`:
  - For `STOCK`: the ticker symbol (e.g. `"AAPL"`)
  - For `GROUP`: the peer group id string
  - For `USER`: the literal string `"user"`
- `noteText`: the concise note text Eidos wants to remember

The app is responsible for exposing this tool to the LLM (via the Grok client) and for turning tool calls into concrete `AiMemoryCacheEntity` writes. The model **never** writes SQL or touches the database directly; it only calls `write_memory_note`.

**When to call `write_memory_note`:**

- If anything in the conversation creates or refines a meaningful perspective about:
  - a stock (business model, thesis, patterns, catalysts, reactions),
  - a peer group (how the group behaves, how the user curates it), or
  - the user (style, preferences, constraints),
  and that perspective would be useful in a future conversation,
  **Eidos should save it as a discrete note** via `write_memory_note`.

**Scope behavior:**

- **User-level notes** (`scope="USER"`, `scopeKey="user"`) are always loaded in every conversation. They capture things like:
  - Preference for certain cap sizes or sectors
  - Risk tolerance
  - Time horizon and style
- **Stock-level notes** (`scope="STOCK"`, `scopeKey=symbol`) live alongside that stock’s other data and its chat history. Examples:
  - The user’s personal thesis for owning the stock
  - Important qualitative updates not present in the financials
  - How the stock reacted to a specific catalyst
- **Group-level notes** (`scope="GROUP"`, `scopeKey=groupId`) are attached to a peer group entry in the database. Examples:
  - Why these companies belong together
  - How the group is typically valued
  - Patterns Eidos or the user has noticed about the group

At runtime, the app:

1. Loads all relevant notes (USER + STOCK + GROUP) from `ai_memory_cache` for the current context.
2. Includes them in the single system message as part of the context JSON.
3. Exposes `write_memory_note` so Eidos can add or refine notes as conversations progress.

---

## Eidos Tools

Eidos has several tools exposed via the Grok API. The app executes them and returns results. Writes go through app-defined tools only (e.g. Memory Cache, `symbol_tag_overrides`, or pipelines such as EDGAR refresh triggered by `get_stock_data`).

### write_memory_note

Persists a note to the Memory Cache.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| scope | string | Yes | `"USER"` \| `"STOCK"` \| `"GROUP"` |
| scopeKey | string | Yes | Symbol for STOCK, groupId for GROUP, or `"user"` for USER |
| noteText | string | Yes | The concise memory note text |

### get_stock_data

Returns full stock context from the app database (raw facts, derived metrics, score snapshot, health score). Used whenever Eidos needs to view or compare a symbol.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| symbol | string | Yes | Stock ticker (e.g. AAPL, MSFT) |
| fetch_if_missing | boolean | No | Default **true**. If the symbol is not in the database and this is true, the **app** runs the same search/analyze pipeline as the Analyze Stock button (EDGAR + Finnhub fetch, then app writes to DB). Eidos does not write financial data — it only triggers that pipeline. The tool then returns the new data. |

**Context behavior:** For the **current conversation’s stock** (when in a stock-specific chat), the initial context packet already includes that one stock’s full data. For **other symbols** or in **General chat**, Eidos calls **get_stock_data** to fetch data on demand. Calling get_stock_data does not change the selected conversation or switch the UI to another stock’s chat.

### set_symbol_tag_override

Persists a **per-symbol XBRL tag mapping** when standard `EdgarConcepts` lists miss a metric (evolving tagging system). The app upserts `symbol_tag_overrides` and then runs **`getStockData(..., forceFromEdgar = true)`** plus the usual save/score pipeline. Invoked from the Full Analysis **Find tag (Eidos)** bootstrap or when the user asks Eidos to fix a missing EDGAR metric.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| symbol | string | Yes | Ticker |
| metricKey | string | Yes | One of `EdgarMetricKey` enum names (e.g. `REVENUE`, `NET_INCOME`) |
| taxonomy | string | Yes | `us-gaap`, `ifrs-full`, or `dei` (SEC companyfacts `facts` keys) |
| tag | string | Yes | Local XBRL concept name in that taxonomy |

### get_portfolio_overview

Returns the user’s full portfolio overview: holdings with shares, average cost, current price, market value, cost basis, and gain/loss, plus watchlist and favorites symbols.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | – | – | No arguments. |

### get_watchlist

Returns the user’s watchlist as a list of symbols (and any light metadata the app provides).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | – | – | No arguments. |

### get_favorites

Returns the user’s favorited stocks as a list of symbols (and any light metadata the app provides).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | – | – | No arguments. |

### list_analyzed_stocks

Returns the list of stocks that have full fundamentals in the app database (all symbols in `edgar_raw_facts`), with basic identity metadata.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | – | – | No arguments. |

---

## Conversation System

### Structure

Every stock gets its own dedicated conversation. When the user opens Eidos from a stock page, the app finds that stock's conversation and opens it automatically. If one doesn't exist yet it is created and named after the ticker. There is also one persistent General conversation for non-stock chat.

All conversations are saved to the database and persist across app sessions. The user can rename, delete, or reopen any conversation from the Navigation Drawer sidebar.

### How Context Is Assembled for Each API Call

Every call to the Grok API sends one combined system message built in this order:

1. Eidos system prompt — who Eidos is, how it communicates, what it has access to
2. User-level memory cache notes
3. Stock-level memory cache notes (if a stock is in context)
4. Group-level memory cache notes (if the stock belongs to a group)
5. Current stock context packet — when the conversation is for a specific stock, the app includes that stock’s full DB context (see below). In General chat, only Memory is included; Eidos uses tools (`get_stock_data`, `get_portfolio_overview`, `get_watchlist`, `get_favorites`, `list_analyzed_stocks`) for any symbols or lists it needs.
6. Recent conversation history — last 20 messages
7. User's new message

Memory Cache notes always load before conversation history. Eidos reads what it already knows before it reads what was just said.

### Context Packet Fields

The app’s data is already clean, structured, and normalized in Room. Eidos should **not** be gated by a hand‑picked list of fields.

Instead, the context JSON is built directly from the full database entities for the current stock:

- **Raw facts entity**: the complete `EdgarRawFactsEntity` row for the symbol (all scalar fields and history arrays)
- **Derived metrics entity**: the complete `FinancialDerivedMetricsEntity` row for the symbol (all deterministic ratios and growth metrics)
- **Score snapshots**: the latest `ScoreSnapshotEntity` row for the symbol
- **Health score object**: the full `HealthScore` (composite, sub‑scores, and per‑metric `breakdown` list)

These are exposed in the context as structured JSON objects so Eidos can see **everything in the row** and decide which numbers matter, rather than the app “cherry‑picking” a subset.

On top of that, the context also includes (for stock-specific chats):

| Category | Description |
|---|---|
| Identity | symbol, companyName, sector, industry, SIC (from the raw facts entity) |
| Peers | current peer list and any peer/benchmark data computed from the DB |
| Memory Cache | notes filtered by scope and type (USER, STOCK, GROUP) |
| About | existing company description if present |

Portfolio, watchlist, favorites, and the list of all analyzed stocks are **not** embedded in the base context; Eidos calls `get_portfolio_overview`, `get_watchlist`, `get_favorites`, or `list_analyzed_stocks` when it needs those views. For **other symbols** (or in General chat), Eidos uses the **get_stock_data** tool to load data on demand. The app does not parse the user message for tickers; Eidos decides which symbols it needs and calls the tool.

---

## About the Business (target behavior)

**Current code:** `StockProfileEntity` / `stock_profiles` and `StockProfileDao` are defined in `RoomDatabase.kt`, but **no screen or `StockViewModel` path yet loads or saves About text**, and Eidos does **not** auto-generate descriptions on analyze.

**Intended behavior (when implemented):** On stock load, if About is empty and `editedByUser` is false, fire a background Eidos call and upsert `StockProfileEntity`. If the user edits About in the UI, set `editedByUser = true` so Eidos does not overwrite.

### StockProfileEntity schema (Room)

| Field | Type |
|---|---|
| `symbol` | String (PK) |
| `aboutSummary` | String? |
| `aboutDetails` | String? |
| `generatedAt` | Long |
| `editedByUser` | Boolean |
| `updatedAt` | Long |

---

## Industry Peer Grouping

**UI today:** `IndustryStocksActivity` offers discover vs “My group” flows, Finnhub/EDGAR-backed lists, and manual add (`AddPeerActivity`). The options menu can open **Eidos** for general chat (same as other screens) — there is **not** yet a dedicated “Ask Eidos to propose peers” button wired to a grouping tool flow as described below.

**Target:** Eidos reviews About text (once populated), financial profile, and market cap tier against symbols in `edgar_raw_facts`, proposes peers with rationale, and the user approves/rejects; decisions surface as Memory Cache notes (`PEER_RATIONALE`, `PEER_REJECTION`). Missing tickers use **`get_stock_data(..., fetch_if_missing: true)`** so EDGAR-backed rows exist before saving peers.

---

## Technical Notes

- API: Grok (`grok-4-1-fast-reasoning`)
- Key: User-entered in app settings via `ApiKeyManager` — never hardcoded
- OkHttp timeouts: connect 30s / write 30s / read 120s — required because reasoning models hold the full response until thinking is complete
- Context JSON is appended inline into the single system message — never sent as a second system message, which causes models to narrow their responses

## Related source files

| Area | Path under `app/src/main/java/com/example/stockzilla/` |
|------|--------------------------------------------------------|
| Assistant UI | `ai/AiAssistantActivity.kt`, `ai/AiMessageAdapter.kt`, `ai/AiConversationAdapter.kt`, `ai/AiMemoryCacheActivity.kt`, `ai/AiMemoryCacheViewModel.kt`, `ai/AiMemoryCacheAdapter.kt` |
| Model / tools | `ai/AiAssistantViewModel.kt` |
| Grok HTTP | `data/GrokApiClient.kt` |
| Persistence | `data/RoomDatabase.kt` (`ai_*` entities, `stock_profiles`, `stock_industry_peers`) |
| Peer persistence API | `data/IndustryPeerRepository.kt` |
| SEC discovery tools | `sec/SecFilingDiscoveryCard.kt` (UI), `data/SecEdgarService.kt` (fetch), `data/NewsRepository.kt` (metadata + summaries) |