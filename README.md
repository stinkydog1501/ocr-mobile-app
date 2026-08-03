# OCR Mobile

On-device OCR for ID cards and forms (mixed-script, handwritten + printed text) with
structured field extraction. Fully offline. Singapore-focused document templates
(NRIC first, driver's license, bank forms), extensible to any document type.

## Stack

| Layer | Choice |
|---|---|
| OCR engine | **PP-OCRv5_mobile** (Baidu PaddleOCR 3.x) via Paddle Lite — det + cls + rec, quantized, arm64 |
| Language | Kotlin 2.0, Jetpack Compose (Material 3, dynamic color) |
| Architecture | Multi-module (`:core` pure JVM + `:app` Android), MVVM + Repository, Hilt DI, Flow/StateFlow |
| Camera | CameraX (single capture, flash, gallery picker) |
| Serialization | kotlinx.serialization |
| Min / target | Android 10 (API 29) / API 35, arm64 only |

## Module layout

```
:core   Pure Kotlin (JVM) — OCR domain models, engine interface, demo engine,
        document schemas, field extraction pipeline. Fully unit-tested on JVM.
:app    Android — UI (Compose), CameraX capture, Hilt wiring, Paddle Lite JNI backend.
```

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

## Status

Implemented now:
- Full scaffold: Compose UI (capture → processing → review), CameraX capture + gallery,
  Hilt DI, MVVM, navigation
- `:core` extraction pipeline: NRIC / driver's license / bank form / generic schemas,
  regex + keyword matching, date/amount validation, confidence scoring, manual-entry
  flags, document-type detection — **10 unit tests**
- Demo OCR engine: realistic NRIC scan so the whole app flow runs without the native lib
- Paddle Lite JNI scaffold (`app/src/main/cpp/paddle_ocr_jni.cpp`) + JSON contract parser

Next (Phase 1, needs a dev machine — see below):
- Build Paddle Lite native library + port DB/CRNN post-processing into the JNI glue
- Convert PP-OCRv5_mobile models with `paddle_lite_opt`, drop `.nb` files into
  `app/src/main/assets/models/`
- Measure real accuracy/latency on actual documents; tune preprocessing (deskew/contrast)

## Building

Requirements: JDK 17+, Android SDK (platform 35), Android Studio Ladybug+ (or CLI).

```bash
./gradlew :core:test          # run the parsing pipeline tests (no Android SDK needed)
./gradlew :app:assembleDebug  # debug build uses the demo OCR engine — runs immediately
./gradlew :app:assembleRelease  # requires native lib + models (Phase 1)
```

Debug builds set `BuildConfig.USE_DEMO_OCR=true`: capture anything, and the demo
engine returns a simulated NRIC so the full UI flow is exercisable. Release builds
require the Paddle Lite `.so` and model assets.

## Architecture decisions

- **`:core` is pure JVM** so the OCR/parsing logic is testable without an emulator —
  images cross the module boundary as `OcrImage` (RGB888 + dimensions), not Bitmaps.
- **Structured output is a first-class contract**: `FieldSchema` per document type;
  extraction is regex → keyword (inline or label-on-previous-line) → manual flag.
  Fields below 0.55 confidence or failing type validation are flagged for the user.
- **Engine behind an interface** (`OcrEngine`): swap demo ↔ Paddle Lite via DI/BuildConfig.
- **Single capture, not live scanning** — the pipeline is one photo → one parse, which
  keeps latency and power budgets simple and matches ID/form use.
- Known limitation (Phase 4 tuning): composite label rows like "RACE / DIALECT" are
  treated as inline values rather than labels; production templates will carry explicit
  label lists per document version.
