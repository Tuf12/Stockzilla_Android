# Eidos — Implementation Tracker

Work through one item at a time. Reference STOCKZILLA_AI.md before starting each item.

---

## BUILT ✅

- AI Assistant screen with Navigation Drawer conversation sidebar
- Conversation list — saved and persists across sessions
- Per-conversation message history — persists across sessions
- Grok API connected and responding
- BYOK API key management via `ApiKeyManager`
- Editable About/description field — saves per stock to DB
- OkHttp timeouts — connect 30s / write 30s / read 120s
- System prompt — single combined message, general knowledge leads

---

## TO BUILD 🔲

### 1. AI Auto-Writes the About Section
Eidos generates a company description when the About field is empty.

- [ ] Confirm `StockProfileEntity` exists in Room — create if not
- [ ] On stock load: if About is empty and `editedByUser` is false, fire background AI call
- [ ] Write result into existing editable About field
- [ ] If API unavailable: leave empty, show "Generate with Eidos" button as fallback
- [ ] `editedByUser` = true when user edits manually — Eidos never overwrites this

---

### 2. Watchlist / Favorites / Portfolio into Context
Eidos knows what the user owns and watches.

- [ ] Identify DAOs for watchlist, favorites, and portfolio
- [ ] Add compact list to `buildContextPacket()` in `AiAssistantViewModel`
- [ ] Format: symbol + shares if portfolio, symbol only if watchlist/favorites
- [ ] Only include when a stock is in context — keep general conversations lean

---

### 3. Per-Stock Conversations
One dedicated conversation per ticker, auto-created or reopened from the stock page.

- [ ] On launch with a symbol: query for existing conversation with that symbol
- [ ] If found: open it — if not: auto-create named after the ticker
- [ ] One persistent General conversation — symbol = null, title = "General"
- [ ] Drawer order: stock conversations by recent activity, General pinned at bottom

---

### 4. Memory Cache
Eidos saves important notes per stock, group, and user — not summaries, just what matters.

- [ ] Create `AiMemoryCache` Room entity and DAO
- [ ] Expose a `write_memory_note` tool to the LLM (via the Grok client) that writes directly into `AiMemoryCache`
- [ ] Load stock + group + user notes from `AiMemoryCache` into the context packet before conversation history
- [ ] Prompt Eidos to decide when to call `write_memory_note` based on whether the conversation produced a durable, useful perspective about a stock, group, or the user
- [ ] Seed 2-3 default `USER_PREFERENCE` notes to start
- [ ] Build simple UI for user to view, edit, and delete memory notes

---

### 5. Suggested Question Chips
Tappable prompts shown at the start of a stock conversation.

- [ ] Horizontal scrollable chip row above the message input
- [ ] Show when conversation has fewer than 3 messages
- [ ] Default chips: "What does this company do?", "What metrics stand out?", "Biggest risks?", "How does it compare to peers?", "Anything concerning?"
- [ ] Tap sends the message automatically
- [ ] Hide once conversation has enough history

---

### 6. AI Peer Grouping
Eidos proposes better peer matches using business model and market cap similarity.

- [ ] Add "Ask Eidos" button to Industry Peers section
- [ ] Build grouping prompt: stock context + existing peers + DB stock list + market cap tier
- [ ] Eidos returns proposed list with brief rationale per ticker
- [ ] Show proposal UI: tickers + rationale, approve/reject per stock
- [ ] On approve: write peers via `IndustryPeerRepository`
- [ ] On approve/reject: write `PEER_RATIONALE` or `PEER_REJECTION` note to cache
- [ ] Unknown tickers: trigger `analyzeStock()` → add only if EDGAR resolves successfully
- [ ] Failed tickers: skip silently, log internally

---

### 7. Ticker Search Inside Eidos
Search a stock directly from the AI Assistant screen.

- [ ] Add search bar to AI Assistant screen
- [ ] If stock in DB: open or create its conversation
- [ ] If not in DB: trigger `analyzeStock()`, open conversation once complete
- [ ] Show loading state while analysis runs

---

## BACKLOG 💭

- Conversation trimming / summarization for very long threads
- Group-level memory cache wired across all stocks in a peer group
- Web scraping for companies not in Grok training data
- Bottom sheet Eidos overlay from analysis screens
- Additional AI models — all share the same Memory Cache