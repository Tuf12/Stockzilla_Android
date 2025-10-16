# Stockzilla Android

Stockzilla is an Android application that helps investors track market data, monitor watchlists, and stay informed with financial news. This repository contains the Android client, written in Kotlin and built with Jetpack Compose.

## Features
- Real-time and historical stock price tracking
- Personalized watchlists with alerting capabilities
- Aggregated market and company news feeds
- Modern material design interface optimized for phones and tablets

## Getting Started
1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/Stockzilla_Android.git
   cd Stockzilla_Android
   ```
2. **Open in Android Studio**
   - Use Android Studio Giraffe or newer.
   - Open the project using the *Open an Existing Project* option.
3. **Configure APIs (if required)**
   - Create a `local.properties` file if you need to add API keys or secrets.

## Building
- From Android Studio, select **Build > Make Project**.
- To build from the command line, run:
  ```bash
  ./gradlew assembleDebug
  ```

## Testing
The project currently does not ship with automated tests. When adding tests, run them with:
```bash
./gradlew test
```

## Contributing
Contributions are welcome! Please open an issue to discuss major changes before submitting a pull request.

## License
This project is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE), which allows personal and non-commercial use while prohibiting commercial exploitation without permission.
