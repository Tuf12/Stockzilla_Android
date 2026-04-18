# Government Data Sources — Feature Spec

## What This Feature Is

Stockzilla currently surfaces company-specific news via SEC 8-K/6-K filings. This feature adds a second news stream from free U.S. government primary sources — the raw documents before any media outlet interprets them.

The motivation: government sources (DOJ press releases, FDA drug decisions, FTC merger rulings, Federal Reserve data, etc.) are primary records. News outlets repackage and often distort this information. This feature goes directly to the source, scans for any company in the user's stock list, and surfaces matching documents summarized by Eidos — the same way 8-K filings are currently handled.

Real example that drove this: an SMCI indictment was reported by media with wildly inconsistent details (wrong defendant count, wrong dollar figures). The DOJ press release had the exact facts. This system would have found and summarized that document automatically.

---

## Sources to Integrate

All sources below are free. No paid subscriptions.

### DOJ — Department of Justice
- **What it covers:** Antitrust enforcement, indictments, press releases, consent decrees. Relevant to any stock facing regulatory or legal action.
- **Access:** No API key required.
- **RSS feed:** `https://www.justice.gov/news/rss`
- **How to use:** Poll the RSS feed, scan item titles and descriptions for company/ticker matches, fetch the full press release HTML for matched items, strip to plain text with Jsoup.

### FDA — Food and Drug Administration
- **What it covers:** Drug approvals, rejections, warning letters, safety recalls. Critical for any biotech or pharma holding.
- **Access:** No key required for RSS. openFDA API also free without key for basic use.
- **Press release RSS:** `https://www.fda.gov/about-fda/contact-fda/stay-informed/rss-feeds/press-releases/rss.xml`
- **openFDA docs:** `https://open.fda.gov/apis/`
- **How to use:** RSS feed for press releases. For drug-specific lookups, openFDA REST API can be queried by company name.

### FTC — Federal Trade Commission
- **What it covers:** Merger approvals and blocks, antitrust actions, consumer protection enforcement.
- **Access:** No API key required.
- **RSS feed:** `https://www.ftc.gov/feeds/press-release-v2.rss`
- **How to use:** Poll RSS, match tickers/company names, fetch full page text for matches.

### FRED — Federal Reserve Economic Data (St. Louis Fed)
- **What it covers:** Interest rates, inflation (CPI/PCE), money supply, GDP, unemployment. Macro context that affects the whole portfolio.
- **Access:** Free API key — register at `https://fred.stlouisfed.org/docs/api/api_key.html`
- **API docs:** `https://fred.stlouisfed.org/docs/api/fred/`
- **Releases endpoint:** `https://api.stlouisfed.org/fred/releases/news?api_key={key}&file_type=json`
- **How to use:** Poll releases endpoint for new publications. Macro context — not company-specific. Always auto-summarize.

### BLS — Bureau of Labor Statistics
- **What it covers:** Jobs report (Non-Farm Payrolls), unemployment rate, CPI, Producer Price Index. Major market-moving macro events.
- **Access:** Free API key — register at `https://www.bls.gov/developers/`
- **API docs:** `https://www.bls.gov/developers/api_signature_v2.htm`
- **News releases page:** `https://www.bls.gov/bls/news-release/` (HTML index — no RSS available)
- **How to use:** Fetch the news release index page periodically, parse HTML links to identify new publications. Macro context — always auto-summarize.

### EIA — Energy Information Administration
- **What it covers:** Crude oil prices, natural gas inventory, weekly petroleum status reports. Directly relevant to any energy stock.
- **Access:** Free API key — register at `https://www.eia.gov/opendata/register.php`
- **API docs:** `https://www.eia.gov/opendata/`
- **Press releases endpoint:** `https://api.eia.gov/v2/press-releases/?api_key={key}`
- **How to use:** Poll press releases endpoint. Apply symbol tier logic for company-specific matches. Also surfaces as macro context for general energy market items.

### Census Bureau
- **What it covers:** Retail sales, housing starts, durable goods orders. Leading economic indicators.
- **Access:** Free API key — register at `https://api.census.gov/data/key_signup.html`
- **Press release RSS:** `https://www.census.gov/newsroom/press-releases.xml`
- **How to use:** RSS feed. Macro context — always auto-summarize.

### EDGAR Full-Text Search
- **What it covers:** Searches the full text of all SEC filings — catches cases where a company is mentioned inside someone else's filing (supplier agreements, litigation disclosures, etc.) even when that company filed nothing itself.
- **Access:** Free, no key. Already integrated for other purposes.
- **Search endpoint:** `https://efts.sec.gov/LATEST/search-index?q=%22TICKER%22&dateRange=custom&startdt=YYYY-MM-DD`
- **How to use:** Periodically query for each symbol in the user's stock list. Apply symbol tier logic.

---

## API Key Storage

FRED, BLS, EIA, and Census require free API keys. Store these using the same `ApiKeyManager` pattern already in the app. Add setup dialogs following the same pattern as `FinnhubApiKeySetupDialog.kt`. Do not hardcode keys anywhere.

DOJ, FDA (RSS), FTC, and EDGAR full-text search require no keys.

---

## How Ticker/Company Matching Works

Each source produces text (from RSS descriptions or fetched document text). That text is scanned for matches against the user's stock list (`user_stock_list` table — symbols map to company names via `edgar_raw_facts`).

Two match strategies:
1. **Ticker match** — the exact uppercase ticker symbol appears as a standalone word in the text (word boundary match). Skip single-letter symbols and common English words that happen to be valid tickers.
2. **Company name match** — the company name (from `edgar_raw_facts.companyName`) appears as a case-insensitive substring. Strip common suffixes (Inc, Corp, Ltd, LLC, Co.) before comparing.

A single document can match multiple symbols — each match becomes a separate news item.

Macro sources (FRED, BLS, Census) do not require ticker matching — they are stored without a symbol.

---

## Summarization Strategy — Symbol Tier Logic

This is the core design decision for token efficiency. The background poller behaves differently depending on where the matched symbol sits in the user's stock list.

### Tier 1 — Auto-summarize (Holdings, Watchlist, Favorites)
- Symbol is in porfolio/holdings or watchlist, OR is in the favorites table
- **Action:** Fetch full document text, pass to Eidos for summarization, store Eidos summary in DB, fire notification
- Raw document text is not stored — only the Eidos-generated summary is persisted, same as the 8-K pipeline

### Tier 2 — Flag and notify, no auto-summarize (All other analyzed stocks) 
- Symbol is in `edgar_raw_facts` but not in holdings, watchlist, or favorites
- **Action:** Store a flag row in DB with source, document URL, title (from RSS), and publish date — do NOT fetch the full document or call Eidos
- Fire a notification so the user knows a match was found
- Appears on the Gov News page where the user can choose to trigger summarization for that stock symbol

### Macro items — Always auto-summarize
- Items from FRED, BLS, Census with no symbol attached
- treat same as tier 2 


---

## Data Storage

Gov news items live in a single `gov_news_items` table, isolated from the 8-K pipeline (`news_metadata`, `news_summaries`). The existing 8-K pipeline must not be touched.

The table stores: source ID, matched symbol (nullable for macro items), company name, document URL, title, short summary (nullable until summarized), detailed summary (nullable until summarized), impact (nullable until summarized), publish date, fetch timestamp, summarization status.

**Status values:**
- `FLAGGED` — Tier 2 item, document not yet fetched, no summary yet. Title from RSS is stored.
- `PENDING` — document fetched, waiting for Eidos summarization (Tier 1 auto flow or user-triggered from Gov News page)
- `SUMMARIZED` — Eidos summary stored
- `FAILED` — summarization attempted and failed

Raw document text is not stored in the DB. It is fetched, passed to Eidos, and discarded after the summary is written.


---

## Eidos Assistant Tools

Two new tools are added to the **Eidos general assistant only** (`AiAssistantViewModel` / `buildEidosTools()`). These are not added to the Eidos Analyst.

The assistant also exposes a **dedicated Gov News conversation** (pinned below General) with its own context packet and optional **one-turn article focus** when opened from an item detail — see the section **Eidos Gov News chat** (after the tool definitions in this document).

Follow the existing tool patterns

---

### Tool 1: `fetch_url`

Lets Eidos fetch and read any public web page URL the user pastes into chat. Foundation for the automated pipeline and immediately useful on its own.

**When Eidos uses it:** User pastes a URL into chat.

**Tool definition:**
```
name: fetch_url
description: Fetches the text content of a public web page URL. Use when the user provides a link and wants you to read or summarize it. Only fetches URLs starting with https://.
parameters:
  url (string, required): The full https:// URL to fetch
```

**Implementation:**
- Validate URL starts with `https://`
- Use OkHttp (already in project) to GET the URL — 5 second connect timeout, 15 second read timeout
- Pass response HTML through Jsoup: `Jsoup.parse(html).text()` to strip tags and return clean readable text
- Truncate to 75,000 characters if longer
- Return plain text as the tool result — Eidos reads it and responds in chat

**Jsoup dependency to add to `build.gradle.kts`:**
```
implementation("org.jsoup:jsoup:1.17.2")
```

---

### tool 2: search_gov_sources
Lets Eidos query the local gov_news_items DB during conversation for already-summarized government news. Local DB query only — no network calls. If no results found, Eidos directs the user to the Gov News page.
Tool definition:
```
name: search_gov_sources
description: Query the local government news database for summarized items matching a symbol, company name, or topic. Returns only summarized items. If nothing found, suggest the user check the Gov News page.
parameters:
  query (string, required): Symbol, company name, or topic (e.g. "NVDA", "interest rates")
  sources (array of strings, optional): Filter by source — "DOJ", "FDA", "FTC", "FRED", "BLS", "EIA", "CENSUS", "EDGAR". Omit to search all.
  
```

**Implementation — lookup behavior:**

1. Query `gov_news_items` WHERE symbol or title/summary matches the query, filtered by source if specified
2. If results found with status `SUMMARIZED` — return summaries directly to Eidos
3. If results found with status `FLAGGED` — direct user to News Gov page to run extracting process.


**Return format:** JSON array of matched items with source, symbol, title, summary, impact, publish date, and document URL. Eidos uses this to compose its chat response.

---

## Eidos Gov News chat (dedicated conversation)

The main **Eidos assistant** (`AiAssistantActivity` / `AiAssistantViewModel`) includes a **single, pinned Gov News conversation** in addition to the per-stock threads and the General chat. This is **not** one chat per article: all Gov News discussion shares **one** persistent thread, while still allowing the model to know **which article the user just opened** for their **next** question only.

### Why a separate thread

- **General** stays lean (memory + tools; no standing gov feed in context).
- **Per-stock** chats stay about that ticker’s fundamentals.
- **Gov News** chat gets a standing **`recentGovNewsItems`** slice in the context JSON so Eidos can reason about the local gov feed without the user pasting links every time.
- Tools such as **`search_gov_sources`** and **`fetch_url`** remain available the same as in General; the default “conversation stock” for tool fallbacks is not a ticker (same pattern as General).

### Data model (conversations)

- Stored in existing `ai_conversations` / `ai_messages` tables.
- The Gov News thread is identified by a **reserved internal symbol** (not a real ticker): `__EIDOS_GOV_NEWS__` (`AiAssistantViewModel.RESERVED_SYMBOL_GOV_NEWS`).
- **Exactly one** row with that symbol is ensured on assistant load (`ensureGovNewsPinnedConversation()`).
- **Pinned ordering** in the drawer: **General** (`symbol IS NULL`) first, **Gov News** second, then all other conversations by `updatedAt DESC` (`AiConversationDao.getAllOrderedByUpdated()`).
- **Rename and delete** are blocked for this row (same UX pattern as General: long-press “delete” is a no-op).

### Opening the Gov News chat

| Entry point | Behavior |
|-------------|----------|
| **Gov News tab** — “Eidos Assistant — Gov News chat” button at top of feed | `AiAssistantActivity.startGovNewsChat(context)` — `OpenMode.FORCE_GOV_NEWS_IF_NO_SYMBOL`, no article focus. |
| **Article detail** — “Chat with Eidos about this item” | `AiAssistantActivity.startGovNewsChat(context, itemId)` — same mode, plus **ephemeral article focus** (see below). |
| **Programmatic** | `start(context, null, OpenMode.FORCE_GOV_NEWS_IF_NO_SYMBOL)` with optional intent extra `extra_gov_news_focus_item_id` (long ≥ 0) for focus. |

`onCreate` reads the optional focus extra and calls `setPendingGovNewsFocusItemId` on the ViewModel.

### Ephemeral “opened from this article” context

When the user opens the assistant from a **detail screen** with an item id:

1. The ViewModel keeps **`pendingGovNewsFocusItemId`** (memory only; **not** written to `ai_messages`).
2. On the **next** request that builds the Gov News context packet, if that id is still set and the row exists in `gov_news_items`, the JSON includes:
   - **`openedFromArticle`** — id, `sourceId`, `symbol`, `companyName`, `title`, `documentUrl`, `status`, `publishedAt`, `impact`, and **truncated** `shortSummary` / `detailedSummary` (character cap for token safety).
   - **`articleNavigationHint`** — short instruction that the user may be asking about that item first, but the thread remains a **general** Gov News conversation (do not fixate on that article forever).
3. **`pendingGovNewsFocusItemId` is cleared** after a **successful** assistant reply in the Gov News conversation (so the second user message no longer carries that article unless the user navigates from another article again).
4. If the user **sends a message from any other conversation** (General, a stock thread, etc.), the pending id is **cleared** so focus cannot leak across threads.
5. If the **API call fails**, the pending id **remains** so a retry can still include the article context.

No separate “tag” row or persistent chat metadata is required; **chat history + Memory Cache** carry ongoing context after the first turn.

### Gov News context JSON shape (system packet)

When the active conversation is the Gov News thread, the assistant’s stock-context system message is JSON roughly of the form:

- **`conversation`**: `"gov_news"`
- **`memory`**: user / stock / group notes (same structure as other chats; stock notes are not keyed by the reserved symbol)
- **`recentGovNewsItems`**: recent rows from `gov_news_items` (summarized / flagged / pending as configured in `GovNewsRepository.listRecentForAssistantContext`), for feed awareness
- **Optional (one turn):** **`openedFromArticle`** + **`articleNavigationHint`** as above

### Implementation pointers

- **ViewModel:** `AiAssistantViewModel` — `OpenMode.FORCE_GOV_NEWS_IF_NO_SYMBOL`, `loadConversations()` target selection, `buildGovNewsContextPacket()`, `sendMessage()` focus consume/clear rules, `createConversationIfNeeded()` respects the **currently selected** conversation so drawer switches behave correctly.
- **UI:** `GovNewsFragment` (list CTA), `GovNewsDetailActivity` + `activity_gov_news_detail.xml` (detail CTA), `fragment_gov_news.xml`.
- **Strings:** e.g. `ai_conversation_gov_news_title`, `gov_news_eidos_assistant_button`, `gov_news_detail_chat_with_eidos`, `ai_gov_news_article_context_hint`.

---

## Dedicated Gov News Page

This feature gets its own page in the app, accessible two ways:
1. Tapping a notification deep-links to it (see Notification System below for exact behavior per tier)
2. Swiping in the main pager — add as a new tab in `MainPagerAdapter` (currently 3 tabs at positions 0, 1, 2 — add Gov News at position 3, the rightmost tab)

### Feed Sort Order

Single feed with priority ordering:

1. **Portfolio/Holdings**  - newest items first
2. **Watchlist** — newest first
3. **Favorites**  — newest first
4. **Remaining analyzed stocks** full stock list — newest first
5. **Macro context items** (no symbol — FRED, BLS, Census) — newest first
**Note**- Remaining analyzed stocks and Macro context items rank equally for newest first. 

Within each tier, sort by publish date descending.

### What Each Item Shows

**Summarized items (Tier 1 / macro):**
- Source badge: `DOJ`, `FDA`, `FTC`, `FRED`, `BLS`, `EIA`, `CENSUS`, `EDGAR`
- Stock symbol and company name (omit for macro items)
- Publish date
- Short summary (one sentence)
- Impact badge: POSITIVE / NEGATIVE / NEUTRAL

**Flagged items (Tier 2, not yet summarized):**
- Source badge
- Stock symbol and company name
- Publish date
- Title from RSS (no summary yet)
- Visually distinct from summarized items — clearly indicates summary is not yet loaded
- Tapping triggers document fetch + Eidos summarization + updates the row in place on the page

Tapping a summarized item opens a detail view: source, date, company, detailed summary, tappable URL to the original document in browser, and **Chat with Eidos about this item** (opens the single Gov News assistant thread with ephemeral focus on that row — see **Eidos Gov News chat** above).

At the **top of the Gov News feed**, **Eidos Assistant — Gov News chat** opens the same pinned conversation without attaching a specific article.

---

## Notification System

**All tiers fire a notification** when a match is found. The difference between tiers is when the notification fires and where tapping it takes the user.

### Tier 1 — Notification fires after summarization completes
- The poller finds a match, fetches the document, Eidos summarizes it
- Notification fires once the summary is ready
- **Title:** symbol and source — e.g. `SMCI — DOJ`
- **Body:** Eidos short summary (one sentence)
- **Tap action:** Deep-link directly to the detail view for that item, showing the full summary

### Tier 2 — Notification fires immediately on match (no summarization yet)
- The poller finds a match, saves the flag row
- Notification fires immediately — no waiting for Eidos
- **Title:** symbol and source — e.g. `NVDA — FTC`
- **Body:** RSS title of the item (e.g. "FTC Opens Investigation into NVIDIA Acquisition")
- **Tap action:** Deep-link to the Gov News page with that flagged item scrolled into view and visually highlighted — user decides whether to tap it and trigger summarization

### Macro items (treat same as tier 2) — Notification fires after summarization completes
- Same as Tier 1 behavior
- **Title:** source and report name — e.g. `BLS — Jobs Report`


### Notification channel
Create a dedicated channel `gov_news_alerts` ("Government News Alerts") so the user can control this independently in Android system settings.

### Android 13+
`POST_NOTIFICATIONS` is a runtime permission — request it properly. Manifest declaration alone is not sufficient.

---

Poll Schedule
All times are Eastern Time, weekdays only. Government sources (DOJ, FDA, FTC) do not publish on weekends. Macro sources (BLS, FRED) release on scheduled weekday dates only.
Time (ET)Reason7:30 AMPre-market — catches BLS/FRED/EIA before their 8:30 AM scheduled drop8:30 AMExactly when major economic releases (BLS, FRED) publish10:00 AM~30 minutes after market open — catches opening bell regulatory news12:00 PMMidday2:30 PM~1 hour before market close4:00 PMAt market close5:30 PMAfter hours — catches anything that dropped late in the day
WorkManager should schedule these as time-constrained periodic jobs with network required. Use setRequiredNetworkType(NetworkType.CONNECTED) — if network is unavailable at scheduled time, execute at next available opportunity.
A manual refresh button on the Gov News page triggers an immediate on-demand poll outside this schedule.

Summarization (Background Pipeline)
Once a matching document is fetched and cleaned to plain text, pass it to Eidos (Grok API via GrokApiClient) for summarization — same approach as EightKNewsAnalyzer. The prompt should ask Eidos to summarize the document's relevance to the matched company, identify impact (positive/negative/neutral), and produce a short headline and a detailed summary paragraph. For macro items with no symbol, ask for a plain summary of what the report says and what it means for markets generally.
(see NOTE) Truncate document  text to 100,000 characters before sending. **NOTE**- if we could search the docs for relevant information and chunk those searchs to get a summary that would be better. i believe we do this with Eidos Analyst. 

## Build Phases

Build in this order — each phase is independently testable before moving on.

**Progress**

| Phase | Status |
|-------|--------|
| 1 — `fetch_url` | **Done** |
| 2 — DB + repository | **Done** |
| 3 — `search_gov_sources` | **Done** |
| 4 — Poller (DOJ/FDA/FTC) | **Done** |
| 5 — Summarization + Tier 1 notifications | **Done** |
| 6 — Gov News UI + tab | **Done** |
| 7 — Remaining sources + API keys | Pending |

**Phase 1 — `fetch_url` Tool for Eidos Assistant** — **Done**  
Add `fetch_url` to `buildEidosTools()` and handle it in `handleToolCalls()`. Uses OkHttp + Jsoup. Add Jsoup dependency. Immediately useful before anything else is built.

**Phase 2 — DB Table and Repository** — **Done**  
New `gov_news_items` table, DAO, and repository. No UI yet. Migration `35_36` (DB version 36).

**Phase 3 — `search_gov_sources` Tool for Eidos Assistant** — **Done**  
Add `search_gov_sources` to `buildEidosTools()` and handle it in `handleToolCalls()`. Depends on Phase 2 DB. Local `gov_news_items` query only (no network); surface `FLAGGED` rows by directing the user to the Gov News page.

**Phase 4 — Source Definitions and Background Poller** — **Done**  
Start with DOJ, FDA, FTC — no keys needed. WorkManager job polling every 4 hours (network required). Ticker matching against user's stock list. **Implemented:** `GovNewsPollWorker`, `GovNewsWorkScheduler` (enqueue from `MainActivity`), RSS fetch/parse, tier match + insert **PENDING** (tier 1 after URL check) or **FLAGGED** (tier 2), notification channel + tier 2 notifications (`POST_NOTIFICATIONS` requested on Tiramisu+). Tier 1 **summarize + notify** remains **Phase 5**.

**Phase 5 — Eidos Summarization and Tier 1 Notifications** — **Done**  
For each PENDING item (Tier 1 / macro), call Eidos, store summary, mark SUMMARIZED, fire notification. Mark FAILED on error. **Implemented:** `GovNewsSummarizer` (tool `save_gov_news_summary`), `GovNewsDocumentText`, `GovNewsSummarizeWorker`, periodic + one-shot enqueue (`schedulePeriodicSummarize`, `enqueueSummarizeOneTime` after poll inserts), tier-1 notifications (`notifyTier1Summarized` / macro variant).

**Phase 6 — Gov News Page and Tab** — **Done**  
**Implemented:** `GovNewsFragment` at ViewPager position 3 (`MainPagerAdapter`), `GovNewsViewModel` + `GovNewsAdapter`, `fragment_gov_news.xml` / `item_gov_news.xml`, pull-to-refresh (poll + summarize one-shots), tier sort via `GovNewsFeedSorter` / `GovNewsTierSets`, `GovNewsDetailActivity` for summarized rows, `GovNewsUserSummarize` for FLAGGED/FAILED taps, `GovNewsIntents` + `MainActivity` (`singleTop`) deep link to tab + highlight row, tier-1 / macro notifications open detail activity, tier-2 passes inserted row id into `notifyFlaggedMatch`.

**Gov News ↔ Eidos assistant (post–Phase 6):** Pinned **`__EIDOS_GOV_NEWS__`** conversation in `ai_conversations`, drawer sort (General → Gov News → others), `OpenMode.FORCE_GOV_NEWS_IF_NO_SYMBOL`, `AiAssistantActivity.startGovNewsChat`, list + detail entry buttons, ephemeral `openedFromArticle` / `articleNavigationHint` in the Gov News context JSON (`buildGovNewsContextPacket`, `pendingGovNewsFocusItemId`), `GovNewsDao.listRecentForAssistantContext` / `GovNewsRepository.listRecentForAssistantContext`.

**Phase 7 — Remaining Sources** — **Done**  
**Implemented:** `ApiKeyManager` prefs for FRED / BLS / EIA / Census; `GovDataApiKeysDialog` + main menu “Government data API keys” (registration links); `GovNewsPhase7Clients` (FRED `/fred/releases` JSON, BLS news-release index HTML with browser-like UA, EIA `/v2/press-releases/data`, SEC `efts.sec.gov` full-text search with `USER_AGENT_SEC_EDGAR`); `GovNewsRssFeeds.PHASE7_MACRO_RSS` (Census press XML, no key); `GovNewsPollWorker` runs macro + keyed APIs even with no watchlist symbols, EIA title matching → tier rows or macro fallback, EDGAR capped queries per poll (22 symbols × 12 hits, 14-day ET window).

### Build plan (execution note)

The seven phases above are the full plan. Use them as the checklist: each phase should compile and be smoke-tested before the next. **Phase 1 acceptance:** `fetch_url` is registered in the Eidos assistant, only `https://` URLs are allowed, OkHttp uses a 5s connect / 15s read timeout, response HTML is stripped to plain text with Jsoup, and output is capped at 75,000 characters with a truncation notice if longer.

**Primary files by phase (for navigation):** Phase 1 — `app/build.gradle.kts`, `AiAssistantViewModel.kt` (`buildEidosTools`, `handleToolCalls`, `eidosSystemPrompt`). Phase 2 — `GovNewsItemEntity.kt`, `GovNewsDao.kt`, `MIGRATION_35_36` in `RoomDatabase.kt`, `GovNewsModels.kt`, `GovNewsRepository.kt`. Phase 3 — `AiAssistantViewModel.kt` + `GovNewsDao` / `GovNewsRepository` (`search_gov_sources`). Phase 4 — `gov/` (`GovNewsPollWorker`, `GovNewsWorkScheduler`, RSS/match/http/notifications, …), `MainActivity`, `POST_NOTIFICATIONS`. Phase 5 — `GovNewsSummarizer.kt`, `GovNewsDocumentText.kt`, `GovNewsSummarizeWorker.kt`, `GovNewsNotifications` tier-1 methods, `GovNewsDao.listPendingOldestFirst`, `GovNewsWorkScheduler` summarize jobs. Phase 6 — `MainPagerAdapter`, `GovNewsFragment`, `GovNewsViewModel`, `GovNewsAdapter`, `GovNewsDetailActivity`, `GovNewsUserSummarize`, `GovNewsFeedSorter`, `GovNewsIntents`, `AndroidManifest.xml` (`GovNewsDetailActivity`, `MainActivity` `launchMode`), `GovNewsNotifications` / `GovNewsPollWorker` / `GovNewsNotificationResend` (tier-2 item id + intents). **Gov News Eidos chat** — `AiAssistantViewModel.kt` (reserved symbol, `OpenMode`, `buildGovNewsContextPacket`, focus id lifecycle), `AiAssistantActivity.kt`, `AiAssistantActivity` / `RoomDatabase.kt` (`AiConversationDao.getAllOrderedByUpdated` ordering), `fragment_gov_news.xml`, `activity_gov_news_detail.xml`, `strings.xml` (gov news + assistant strings). Phase 7 — `ApiKeyManager` (gov_* keys), `GovDataApiKeysDialog`, `dialog_gov_data_api_keys.xml`, `main_menu.xml` / `MainActivity`, `GovNewsPhase7Clients.kt`, `GovNewsHttp` (UA variants), `GovNewsRssFeeds.PHASE7_MACRO_RSS`, `GovNewsPollWorker` (FRED/BLS/EIA/EDGAR paths).

**Spec alignment:** Phase 3 implements `search_gov_sources` as **local DB only** (per tool definition above); ignore any stray “live fetch fallback” wording in older phase notes.