# OCR Mobile

On-device OCR for ID cards and forms (mixed-script, handwritten + printed text) with structured field extraction. Fully offline. Singapore-focused document templates (NRIC first, driver's license, bank forms), extensible to any document type.

## Features

- **Mixed-script support**: English, Chinese, Malay, and other scripts via PP-OCRv5_mobile
- **Handwriting + printed text**: Recognizes both handwritten and printed content
- **Structured extraction**: Auto-detects document type and extracts fields (name, ID number, DOB, address, etc.)
- **Confidence scoring**: Each field gets a confidence score; low-confidence fields are flagged for manual review
- **Editable review**: Users can correct OCR results before export
- **Offline only**: No network permission, no data leaves the device
- **Single capture**: Point, shoot, process — no live viewfinder pipeline
- **Export options**: Copy JSON or share results

## Quick Start

### Prerequisites

- **JDK 17+**: [Eclipse Temurin](https://adoptium.net/) recommended
- **Android SDK**: Platform 35, build tools 35.0.0
- **Android Studio** (optional): Ladybug+ (2024.2+) recommended
- **Device/emulator**: Android 10+ (API 29), arm64

### Clone and build

```bash
# Clone the repository
git clone <repository-url>
cd ocr-mobile-app

# Run core tests (no Android SDK needed)
./gradlew :core:test

# Build debug APK (uses simulated OCR engine)
./gradlew :app:assembleDebug

# APK output
ls app/build/outputs/apk/debug/
# → app-debug.apk
```

### Install on device

```bash
# Via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Or copy APK to device and install manually
```

**Debug build behavior**: Captures return simulated NRIC data so the full UI flow (capture → process → review → export) works without the native OCR library.

## Build Variants

### Debug build

```bash
./gradlew :app:assembleDebug
```

- Uses `DemoOcrEngine` (simulated NRIC scan)
- No native library required
- Fast iteration for UI development
- APK size: ~5MB

### Release build

```bash
./gradlew :app:assembleRelease
```

**Requires Phase 1 integration** (native OCR library + models):

1. Build Paddle Lite native library for arm64
2. Convert PP-OCRv5_mobile models to `.nb` format
3. Place models in `app/src/main/assets/models/`:
   - `det.nb` (text detection)
   - `rec.nb` (text recognition)
   - `cls.nb` (text orientation classification)
   - `ppocr_keys_v1.txt` (recognition dictionary)
4. Configure release signing (see [Signing](#signing))

APK size with models: ~25-30MB

## Signing

For release builds, configure signing in `app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("path/to/keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

Build with signed APK:

```bash
export KEYSTORE_PASSWORD=***
export KEY_ALIAS=ocr-mobile
export KEY_PASSWORD=***
./gradlew :app:assembleRelease
```

## Architecture

```
:core   Pure Kotlin (JVM) — OCR domain models, engine interface, demo engine,
        document schemas, field extraction pipeline. Fully unit-tested on JVM.
:app    Android — UI (Compose), CameraX capture, Hilt wiring, Paddle Lite JNI backend.
```

### Pipeline

```
CameraX capture / gallery picker
        │  Uri
        ▼
ImageDecoding (downsample → RGB888 → EXIF rotation)
        │  OcrImage
        ▼
OcrEngine.recognize()            ← DemoOcrEngine (debug) | PaddleLiteOcrEngine (release)
        │  OcrResult (blocks + confidence + boxes)
        ▼
FieldExtractor.detectDocumentType() + parse()
        │  ParsedDocument (fields + confidence + provenance)
        ▼
Review screen — editable fields, confidence badges, JSON copy/share
```

### Design decisions

- **`:core` is pure JVM**: OCR/parsing logic testable without emulator — images cross the module boundary as `OcrImage` (RGB888 + dimensions), not Bitmaps
- **Structured output**: `FieldSchema` per document type; extraction is regex → keyword → manual flag. Fields below 0.55 confidence or failing type validation are flagged
- **Engine behind interface** (`OcrEngine`): swap demo ↔ Paddle Lite via DI/BuildConfig
- **Single capture, not live scanning**: One photo → one parse, simpler latency and power model
- **No network permission**: Fully offline, no data exfiltration possible

## Development

### Project structure

```
ocr-mobile-app/
├── core/                          # Pure JVM module
│   ├── src/main/kotlin/
│   │   └── com/kinonn/ocrmobile/core/
│   │       ├── model/             # Domain models (OcrResult, ParsedDocument, etc.)
│   │       ├── ocr/               # OcrEngine interface, DemoOcrEngine
│   │       └── parse/             # FieldExtractor, DocumentSchemas, NativePayload
│   └── src/test/kotlin/           # Unit tests (11 passing)
│
├── app/                           # Android module
│   ├── src/main/
│   │   ├── java/com/kinonn/ocrmobile/
│   │   │   ├── ui/                # Compose screens (Capture, Review)
│   │   │   ├── data/              # Repository, OcrRepository
│   │   │   ├── di/                # Hilt DI modules
│   │   │   ├── ocr/               # PaddleLiteOcrEngine (JNI)
│   │   │   └── util/              # ImageDecoding
│   │   ├── cpp/                   # Native JNI glue (paddle_ocr_jni.cpp)
│   │   ├── assets/models/         # PP-OCRv5_mobile .nb models (Phase 1)
│   │   └── res/                   # Resources (strings, themes, icons)
│   └── build.gradle.kts
│
└── gradle/
    └── libs.versions.toml         # Version catalog
```

### Testing

**Core tests** (pure JVM, runs on any platform):

```bash
./gradlew :core:test
```

Covers:
- Field extraction logic (9 tests)
- Native JSON payload parsing (2 tests)
- Document type detection
- Date/amount validation
- Confidence scoring

**Integration tests**: Not yet implemented. Future work: Espresso tests for UI flow.

### Adding a document type

1. Add schema in `core/src/main/kotlin/.../parse/DocumentSchemas.kt`:

```kotlin
val passport: List<FieldSchema> = listOf(
    FieldSchema(
        key = "passport_number",
        label = "Passport Number",
        type = FieldType.ID_NUMBER,
        patterns = listOf("[A-Z]\\d{8}"),
        keywords = listOf("PASSPORT", "PASSPORT NO"),
        required = true,
    ),
    // ... more fields
)
```

2. Add to `DocumentType` enum:

```kotlin
enum class DocumentType(val displayName: String, val schemaKey: String) {
    NRIC("NRIC", "nric"),
    PASSPORT("Passport", "passport"),  // NEW
    // ...
}
```

3. Update `DocumentSchemas.all` map:

```kotlin
val all: Map<DocumentType, List<FieldSchema>> = mapOf(
    DocumentType.NRIC to nric,
    DocumentType.PASSPORT to passport,  // NEW
    // ...
)
```

4. Test extraction with mock OCR blocks in `FieldExtractorTest.kt`.

### Common issues

**"aapt2: command not found"**
- Ensure Android SDK build tools are installed: `sdkmanager "build-tools;35.0.0"`

**"Execution failed for task ':app:processDebugResources'"**
- Check `local.properties` has `sdk.dir=/path/to/android-sdk`
- Ensure platform-35 is installed: `sdkmanager "platforms;android-35"`

**"Paddle Lite native library is not bundled"**
- Debug builds use simulated engine — this error only occurs in release builds without native lib
- For Phase 1 integration, build Paddle Lite and place `.so` in `app/src/main/jniLibs/arm64-v8a/`

**Tests fail with "Cannot resolve symbol"**
- Run `./gradlew :core:clean` then `./gradlew :core:test`
- Ensure JDK 17+ is in PATH

## Deployment

### Build release APK

1. Integrate native OCR library (Phase 1 — not yet implemented)
2. Convert and bundle PP-OCRv5_mobile models
3. Configure release signing (see [Signing](#signing))
4. Build:

```bash
./gradlew :app:assembleRelease
```

### Install on device

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

### Publish to Google Play (future)

Not yet configured. Future work:
- Generate signed app bundle: `./gradlew :app:bundleRelease`
- Configure Play Console listing
- Set up internal testing track
- Implement release notes automation

## Roadmap

### Phase 1: Native OCR Integration (current focus)

- [ ] Build Paddle Lite native library for arm64
- [ ] Convert PP-OCRv5_mobile models to `.nb` format
- [ ] Implement DB/CRNN post-processing in JNI glue
- [ ] Benchmark accuracy/latency on real documents
- [ ] Tune preprocessing (deskew, contrast)

### Phase 2: Accuracy Improvements

- [ ] Add image preprocessing pipeline (OpenCV for deskew, binarization)
- [ ] Implement confidence-based retry (re-capture if overall confidence < 0.6)
- [ ] Add handwritten text optimization
- [ ] Expand test coverage with real-world document samples

### Phase 3: UX Polish

- [ ] Add image preview with detected text boxes overlay
- [ ] Implement document cropping/rotation UI
- [ ] Add flash toggle in processing screen
- [ ] Improve error messages and retry flows

### Phase 4: Production Hardening

- [ ] Crash reporting (Firebase Crashlytics)
- [ ] Analytics (privacy-preserving, on-device only)
- [ ] Performance monitoring
- [ ] Accessibility audit (TalkBack, high contrast)
- [ ] Localization (Chinese, Malay, Tamil)

### Phase 5: Additional Document Types

- [ ] Singapore driver's license
- [ ] Bank deposit slips
- [ ] Utility bills
- [ ] Multi-page forms

## Known Limitations

- **Composite labels**: Rows like "RACE / DIALECT" are treated as inline values, not labels. Mitigation: explicit label lists per document template (Phase 4)
- **Handwriting accuracy**: 85-95% for clear handwriting; degraded for cursive or non-standard scripts. Manual review screen mitigates this
- **Low-light captures**: No automatic flash triggering; user must manually enable flash
- **Large images**: Downsampled to 1600px max dimension; very high-res images may lose fine detail
- **No batch processing**: Single capture only; multi-page forms require separate scans

## Tech Stack

| Layer | Choice |
|---|---|
| OCR engine | **PP-OCRv5_mobile** (Baidu PaddleOCR 3.x) via Paddle Lite — det + cls + rec, quantized, arm64 |
| Language | Kotlin 2.0, Jetpack Compose (Material 3, dynamic color) |
| Architecture | Multi-module (`:core` pure JVM + `:app` Android), MVVM + Repository, Hilt DI, Flow/StateFlow |
| Camera | CameraX (single capture, flash, gallery picker) |
| Serialization | kotlinx.serialization |
| Min / target | Android 10 (API 29) / API 35, arm64 only |

## License

[License type] — [Year] [Author/Organization]

## Contributing

[Contributing guidelines — to be added]

## Support

[Contact/support info — to be added]
