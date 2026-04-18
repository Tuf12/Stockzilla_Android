# Stockzilla Android

Stockzilla is an Android app for looking up US-listed equities, pulling fundamentals from **SEC EDGAR**, live quotes (and light market metadata) from **Finnhub**, and surfacing composite **health / growth / resilience** scoring. It includes **Eidos**, an in-app Grok-powered assistant with tool calling against local Room data.

The client is **Kotlin**, **Material Components**, **View Binding**, and **XML layouts** (not Jetpack Compose).

## Features

- Ticker search and analysis: EDGAR fundamentals, TTM vs annual display rules, composite scoring
- Favorites and a personal profile / portfolio area (`PersonalProfileFragment`)
- Recently viewed symbols (`ViewedStocksFragment`)
- Industry peer discovery and saved peer groups (`IndustryStocksActivity`, `IndustryPeerRepository`)
- SEC “news” filings pipeline (8-K, ownership forms, etc.) with AI summaries (`NewsRepository`, `EightKNewsAnalyzer`)
- Government-source news tab and summaries (`GovNewsFragment`, `GOV_DATA_NEWS.md`)
- Eidos AI assistant: conversations, memory cache, SEC filing discovery tools (`AiAssistantActivity`, `AiAssistantViewModel`)
- Eidos Analyst on Full Analysis: filing-grounded metric proposals and accepted values (`EidosAnalystActivity`, `EIDOS_AS_ANALYST.md`)

## Getting started

1. **Clone the repository** (adjust URL to your remote).
   ```bash
   git clone <your-remote-url>
   cd Stockzilla
   ```
2. **Open in Android Studio** (recent stable channel recommended).
3. **API keys** — Finnhub and Grok keys are managed in-app (`ApiKeyManager`, settings dialogs). Optional `local.properties` entries depend on how you wire secrets locally.

## Building

- Android Studio: **Build → Make Project**.
- Command line:
  ```bash
  ./gradlew assembleDebug
  ```

## Testing

Unit tests live under `app/src/test/`. Example:

```bash
./gradlew test
```

## Documentation

Architecture and scoring details are in the repo root `*.md` files (e.g. `APP_STRUCTURE.md`, `DATA_SOURCES.md`, `DATABASE_ARCHITECTURE.md`, `STOCKZILLA_AI.md`, `SEC_NEWS_SPEC.md`).

## Contributing

Contributions are welcome. Please open an issue to discuss major changes before submitting a pull request.

## License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE), which allows personal and non-commercial use while prohibiting commercial exploitation without permission.
