# Model assets (Phase 1)

Place the converted PP-OCRv5_mobile models here. These are NOT committed (binary, ~20MB).

```text
app/src/main/assets/models/
├── det.nb               # PP-OCRv5_mobile detection, quantized, converted
├── rec.nb               # PP-OCRv5_mobile recognition, quantized, converted
├── cls.nb               # text orientation classifier
└── ppocr_keys_v1.txt    # recognition dictionary (from PaddleOCR repo)
```

## Conversion

```bash
# 1. Get Paddle Lite opt tool + PP-OCRv5_mobile models:
#    PaddleOCR repo: deploy/lite/readme.md (official flow)
#    Models: PP-OCRv5_mobile_det / PP-OCRv5_mobile_rec / PP-OCRv5_mobile_cls
#            (paddle-model-ecology / PaddleOCR release assets)

paddle_lite_opt \
  --model_file=inference.pdmodel \
  --param_file=inference.pdiparams \
  --optimize_out=./det \
  --valid_targets=arm \
  --optimize_out_type=naive_buffer

# 2. Copy det.nb / rec.nb / cls.nb + ppocr_keys_v1.txt into this directory.

# 3. Verify in the app: debug builds use the demo engine (BuildConfig.USE_DEMO_OCR);
#    release builds require the native library (app/src/main/cpp/) + these assets.
```
