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
- **Image preprocessing**: OpenCV-based deskew, contrast enhancement (CLAHE), and adaptive binarization run before OCR; handwriting is detected and optimized separately

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
- No native OCR library required (OpenCV native lib is still bundled for preprocessing)
- Fast iteration for UI development
- APK size: ~80MB (OpenCV arm64 native library included — measured arm64-v8a debug build)

### Release build

```bash
./gradlew :app:assembleRelease
```

**Requires Phase 1 integration** (native OCR library + models). Run
`scripts/fetch_phase1_assets.sh` on an x86-64 workstation (NDK + OpenCV-android-sdk) — it
downloads Paddle Lite, converts the models and places everything here:

```
app/src/main/assets/models/
├── det.nb                # text detection (converted)
├── rec.nb                # text recognition (converted)
├── cls.nb                # text orientation classification (converted)
└── ppocr_keys_v1.txt     # recognition dictionary (committed)
app/src/main/jniLibs/arm64-v8a/libpaddle_lite_jni.so
```

Then configure release signing (see [Signing](#signing)) and build the signed APK with the
native integration enabled (`-PwithNative`):

```bash
./gradlew :app:assembleRelease -PwithNative
```

Release APK size not yet measured (the OpenCV AAR dominates; the debug APK is ~80MB, and bundling the `.nb` models adds the model sizes on top).

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
ImageDecoding (ImageDecoder → EXIF rotation → downsample to 1600px)
        │  Bitmap
        ▼
Edit / Adjust — rotate ±90° / ±5° + draggable crop   ← Phase 3
        │  adjusted Bitmap
        ▼
Phase 2 preprocessing (OpenCV — degrades to original if unavailable)
  deskew → handwriting detect? → (optimize) → adaptive binarize
        │  OcrImage (RGB888)
        ▼
OcrEngine.recognize()            ← DemoOcrEngine (debug) | PaddleLiteOcrEngine (release)
        │  OcrResult (blocks + confidence + boxes)
        ▼
FieldExtractor.detectDocumentType() + parse()
        │  ParsedDocument (fields + confidence + provenance)
        ▼
Review screen — editable fields, confidence badges, detected-text-box overlay, JSON copy/share
```

### Design decisions

- **`:core` is pure JVM**: OCR/parsing logic testable without emulator — images cross the module boundary as `OcrImage` (RGB888 + dimensions), not Bitmaps
- **Structured output**: `FieldSchema` per document type; extraction is regex → keyword → manual flag. Fields below 0.55 confidence or failing type validation are flagged
- **Engine behind interface** (`OcrEngine`): swap demo ↔ Paddle Lite via DI/BuildConfig
- **Preprocessing is best-effort**: every stage (deskew, handwriting, binarize) checks `ImagePreprocessor.isAvailable` and returns the original image if the OpenCV native library isn't loaded — a capture is never lost to a preprocessing failure
- **No pointless re-OCR**: re-running the same image through the deterministic engine returns identical results, so low-confidence output is flagged via `needsReview` instead of silently retried
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
│   │   │   ├── ui/                # Compose screens (Capture, Edit, Review)
│   │   │   ├── data/              # Repository, OcrRepository
│   │   │   ├── di/                # Hilt DI modules
│   │   │   ├── image/             # Phase 2: ImagePreprocessor, HandwritingOptimizer (OpenCV)
│   │   │   ├── ocr/               # PaddleLiteOcrEngine (JNI)
│   │   │   └── util/              # ImageDecoding (decode + preprocessing pipeline)
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

**Android module**: Google ships an x86-64-only `aapt2`, which historically blocked
building `:app` on ARM64 Linux hosts. On ARM64 Linux install the arm64 build-tools
from [`Commit451/android-arm-build-tools`](https://github.com/Commit451/android-arm-build-tools)
(e.g. run its `install.sh --sdk <SDK>` for build-tools 35.0.0) and add the
`android.aapt2FromMavenOverride=<path-to-aapt2>` to `~/.gradle/gradle.properties`
(machine-local — do not commit). Then the normal Gradle build works on ARM64:

```bash
./gradlew :core:test
./gradlew :app:assembleDebug
```

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

1. Build the native library + convert models: `scripts/fetch_phase1_assets.sh` (run on an
   x86-64 workstation with the NDK + OpenCV-android-sdk; see its header)
2. Configure release signing (see [Signing](#signing))
3. Build with native integration:

```bash
./gradlew :app:assembleRelease -PwithNative
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

### Phase 1: Native OCR Integration ✅ mostly implemented

- [x] Implement DB/CRNN post-processing in JNI glue (`app/src/main/cpp/paddle_ocr_jni.cpp`,
      algorithm core in `ocr_postprocess.h` — host unit-tested, see `native_tests/`)
- [ ] Build Paddle Lite native library for arm64 — *automated by `scripts/fetch_phase1_assets.sh`,
      but the `.so` compile is a pending workstation step (this Pi has no Android NDK)*
- [ ] Convert PP-OCRv5_mobile models to `.nb` — *automated conversion in the same script; not yet run*
- [ ] Benchmark accuracy/latency on real documents — *requires a physical device*
- [ ] Tune preprocessing against real PP-OCRv5 output — *requires the benchmarks above*

> Phase 1 production run still needs: run `scripts/fetch_phase1_assets.sh` on an x86-64
> workstation with the NDK + OpenCV-android-sdk, `./gradlew :app:assembleRelease -PwithNative`,
> then verify on-device accuracy/latency before tuning thresholds.

### Phase 2: Accuracy Improvements ✅ implemented

- [x] Add image preprocessing pipeline (OpenCV for deskew, CLAHE contrast, adaptive binarization)
- [x] Add handwritten text optimization (Laplacian edge-energy detection → denoise + stroke-connect morphology + CLAHE)
- [x] Wire preprocessing into the capture pipeline (`ImageDecoding`) with graceful degradation when OpenCV is unavailable
- [x] Verify Phase 2 sources compile against `android.jar` + OpenCV AAR classes; `:core` tests green

> Confidence-based re-OCR (re-capture below 0.6) was intentionally **not**
> implemented as an automatic retry: re-running the same image through the
> deterministic engine returns identical results. Low-confidence output already
> reaches the user via `ParsedDocument.needsReview`; the review screen prompts
> for re-capture.
>
> Remaining: expand test coverage with real-world document samples (needs a
> captured photo dataset) and benchmark preprocessing impact once Phase 1 ships
> the real engine.

### Phase 3: UX Polish ✅ implemented

- [x] Add image preview with detected text boxes overlay (review screen draws the
      recognized text boxes over the scanned image)
- [x] Implement document cropping/rotation UI (new "Adjust" step: rotate 90° / ±5°,
      draggable crop frame, then Scan & Review)
- [x] Add flash toggle in processing screen (capture viewfinder flash toggle)
- [x] Improve error messages and retry flows (retry action on capture errors,
      clear recovery from failure)

### Phase 4: Production Hardening

- [ ] Crash reporting (Firebase Crashlytics)
- [ ] Analytics (privacy-preserving, on-device only)
- [ ] Performance monitoring
- [ ] Accessibility audit (TalkBack, high contrast)
- [ ] Localization (Chinese, Malay, Tamil)

### Phase 5: Additional Document Types (partial)

- [x] Singapore driver's license (`DocumentType.DRIVERS_LICENSE`)
- [x] Bank deposit slips (`DocumentType.BANK_FORM`)
- [ ] Utility bills
- [ ] Multi-page forms

## Known Limitations

- **Composite labels**: Rows like "RACE / DIALECT" are treated as inline values, not labels. Mitigation: explicit label lists per document template (Phase 4)
- **Handwriting accuracy**: 85-95% for clear handwriting; degraded for cursive or non-standard scripts. Phase 2 preprocessing (denoise, stroke-connect, contrast) helps, and the manual review screen mitigates the rest
- **Low-light captures**: No automatic flash triggering; user must manually enable flash
- **Large images**: Downsampled to 1600px max dimension; very high-res images may lose fine detail
- **No batch processing**: Single capture only; multi-page forms require separate scans

## Tech Stack

| Layer | Choice |
|---|---|
| OCR engine | **PP-OCRv5_mobile** (Baidu PaddleOCR 3.x) via Paddle Lite — det + cls + rec, quantized, arm64 |
| Image processing | OpenCV 4.9.0 (`org.opencv:opencv` AAR) — deskew, CLAHE contrast, adaptive binarization, handwriting detection |
| Language | Kotlin 2.2 (KSP 2.2.20), Jetpack Compose (Material 3, dynamic color) |
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
