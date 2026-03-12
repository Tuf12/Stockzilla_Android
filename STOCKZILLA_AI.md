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
- Writes company descriptions in the About section for any stock
- Suggests and updates Industry Peer groups based on actual business model similarity
- Maintains a Memory Cache — saves important notes about stocks, groups, and the user across all conversations
- Analyzes and adds stocks to the database that aren't there yet, when needed during peer grouping
- Reads the user's watchlist, favorites, and portfolio holdings for context during conversations

## What Eidos Writes To

| What | Where |
|---|---|
| Company descriptions | `StockProfileEntity` |
| Industry peer groups | `IndustryPeerRepository` |
| Memory Cache notes | `AiMemoryCache` |
| Conversation history | `AiConversationEntity` / `AiMessageEntity` |

When Eidos needs to analyze an unknown stock it triggers the existing `analyzeStock()` pipeline. If the ticker resolves in EDGAR the stock is added to the database normally. If it fails the ticker is skipped silently.

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

### AiMemoryCache Schema

| Field | Type |
|---|---|
| `id` | Long (PK) |
| `scope` | `STOCK` \| `GROUP` \| `USER` |
| `scopeKey` | Symbol for STOCK · groupId for GROUP · `"user"` for USER |
| `noteType` | `BUSINESS_MODEL` · `FINANCIAL_OBSERVATION` · `INVESTMENT_THESIS` · `PEER_RATIONALE` · `PEER_REJECTION` · `GROUP_PATTERN` · `USER_PREFERENCE` |
| `noteText` | The note — concise, written by Eidos |
| `createdAt` | Long |
| `updatedAt` | Long |
| `source` | `AI_GENERATED` \| `USER_CONFIRMED` \| `USER_EDITED` |

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
5. Current stock context packet (derived metrics — see below)
6. Recent conversation history — last 20 messages
7. User's new message

Memory Cache notes always load before conversation history. Eidos reads what it already knows before it reads what was just said.

### Context Packet Fields

| Category | Fields |
|---|---|
| Identity | symbol, companyName, sector, industry, SIC, market cap tier |
| Valuation | price, PE, PS, PB, market cap |
| Profitability | net margin, EBITDA margin, FCF margin, ROE |
| Growth | 5yr avg revenue growth, latest YoY revenue and net income growth, FCF growth |
| Balance Sheet | debt-to-equity, current ratio, total assets, total liabilities |
| Scores | composite, health sub-score, growth sub-score, resilience level |
| Peers | current peer list with symbol, cap tier, PE/PS |
| Portfolio | watchlist, favorites, holdings when contextually relevant |
| Memory Cache | notes filtered by scope and type |
| About | existing company description if present |

---

## About the Business

On every stock load the app checks whether a company description exists for that ticker. If the field is empty and the user has not manually edited it, Eidos automatically generates one in the background and writes it to `StockProfileEntity`. This happens without the user having to do anything.

Once the user manually edits the About field, `editedByUser` is set to true and Eidos will not overwrite it on future loads. The user is always in control of what stays in that field.

### StockProfileEntity Schema

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

The current grouping system uses sector and SIC labels which are too broad and mix market cap sizes. Eidos fixes this by grouping stocks based on what they actually do and enforcing market cap tier as a hard boundary.

When the user taps the "Ask Eidos" button in the Industry Peers section, Eidos reviews the current stock's About text, financial profile, and market cap tier alongside every stock already in the database. It proposes a peer list with a brief reason for each inclusion. The user reviews the proposal and approves or rejects each stock individually. Those decisions are saved to the Memory Cache as `PEER_RATIONALE` and `PEER_REJECTION` notes so Eidos learns the user's grouping preferences over time.

If Eidos identifies a strong peer candidate that isn't in the database yet it triggers `analyzeStock()` for that ticker. Only stocks that successfully resolve through EDGAR get added. Failed or invalid tickers are skipped silently.

---

## Technical Notes

- API: Grok (`grok-4-1-fast-reasoning`)
- Key: User-entered in app settings via `ApiKeyManager` — never hardcoded
- OkHttp timeouts: connect 30s / write 30s / read 120s — required because reasoning models hold the full response until thinking is complete
- Context JSON is appended inline into the single system message — never sent as a second system message, which causes models to narrow their responses