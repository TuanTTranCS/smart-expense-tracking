# Android Local Extraction Spike

Last updated: 2026-06-28

## Status

In progress.

This spike now has a testable Kotlin extraction contract, prompts for multimodal and OCR-text paths, model-output parser, validation rules, extraction pipeline orchestration, model selection metadata, an Android-facing LiteRT-LM adapter bridge, model-output fixtures, and a verified Android module build in this workspace. The remaining Phase 1 device work is to run the pipeline through LiteRT-LM on Android with the selected Gemma 4 E2B `.litertlm` model and confirm whether direct receipt image input is practical for the chosen model/device.

## Documentation Review

Reviewed on 2026-06-27:

- Google LiteRT-LM Android documentation: `https://developers.google.com/edge/litert-lm/android`
- Google LiteRT-LM overview: `https://developers.google.com/edge/litert-lm`
- Google AI Edge API reference: `https://developers.google.com/edge/api`

Findings:

- LiteRT-LM exposes a Kotlin API for Android/JVM.
- The Android dependency is `com.google.ai.edge.litertlm:litertlm-android`.
- The API uses an `Engine` configured with a local `.litertlm` model path.
- Engine initialization can take meaningful time and must run off the UI thread.
- The API supports multimodal message content, including image file and image bytes, but only when the selected model supports multimodality.
- A vision backend must be configured for image input.
- Google points Android sample work at AI Edge Gallery, which remains useful as a reference app rather than an MVP runtime dependency.

## Selected Spike Direction

Use LiteRT-LM directly from the Android app for the Phase 1 extraction spike.

Selected model target for the first Android device run:

- Model family: `Gemma 4`
- Variant: `E2B-it`
- Hugging Face repo: `litert-community/gemma-4-E2B-it-litert-lm`
- Default Android/Desktop file: `gemma-4-E2B-it.litertlm`
- Web-specific file: `gemma-4-E2B-it-web.litertlm`

Primary path:

1. Configure `Engine` with the local `gemma-4-E2B-it.litertlm` multimodal model.
2. Configure `visionBackend`.
3. Send receipt image content plus `ReceiptExtractionPrompt.multimodal`.
4. Parse the returned JSON with `ReceiptExtractionParser`.
5. Treat malformed, failed, or low-confidence output as a manual review path.

Fallback path:

1. Use Android OCR/preprocessing to produce receipt text.
2. Send OCR text plus `ReceiptExtractionPrompt.text` to a text-capable LiteRT-LM model.
3. Parse and validate the same output contract.

## Current Kotlin Spike

Source:

- `src/main/kotlin/com/hugo/smartexpense/extraction/ReceiptExtractionPrompt.kt`
- `src/main/kotlin/com/hugo/smartexpense/extraction/ReceiptExtractionParser.kt`
- `src/main/kotlin/com/hugo/smartexpense/extraction/ReceiptExtractionPipeline.kt`
- `src/main/kotlin/com/hugo/smartexpense/extraction/ReceiptExtractionResult.kt`
- `src/main/kotlin/com/hugo/smartexpense/extraction/ReceiptImage.kt`
- `src/main/kotlin/com/hugo/smartexpense/extraction/GemmaModelSpec.kt`
- `src/main/kotlin/com/hugo/smartexpense/extraction/AndroidLiteRtLmReceiptModelClient.kt`
- `src/main/kotlin/com/hugo/smartexpense/extraction/ReceiptModelClient.kt`
- `src/main/kotlin/com/hugo/smartexpense/extraction/OcrClient.kt`

Tests:

- `src/test/kotlin/com/hugo/smartexpense/extraction/ReceiptExtractionParserTest.kt`
- `src/test/kotlin/com/hugo/smartexpense/extraction/ReceiptExtractionPipelineTest.kt`
- `src/test/kotlin/com/hugo/smartexpense/extraction/GemmaModelSpecTest.kt`
- `src/test/kotlin/com/hugo/smartexpense/extraction/AndroidLiteRtLmReceiptModelClientTest.kt`
- `scripts/test-extraction-contract.ps1`

Fixtures:

- `fixtures/receipt-text/example-shop-20260626.txt`
- `fixtures/receipt-text/photo-1-lotto-max-20260623.txt`
- `fixtures/extraction/valid/example-shop-model-output.json`
- `fixtures/extraction/valid/photo-1-lotto-max-model-output.json`
- `fixtures/extraction/invalid/missing-total-amount.json`
- `fixtures/extraction/invalid/free-form-output.txt`

## Output Contract

The model must return only one JSON object:

```json
{
  "receiptDate": "2026-06-26",
  "merchantName": "Example Shop",
  "totalAmount": 42.35,
  "currency": "CAD",
  "extractionStatus": "confirmed",
  "confidence": 0.94,
  "merchantLocation": "Vancouver BC"
}
```

Validation rules:

- `receiptDate` must be `yyyy-MM-dd`.
- `merchantName` must not be blank.
- `totalAmount` must be numeric and non-negative.
- `currency` must match `^[A-Z]{3}$`.
- `extractionStatus` must be `confirmed`, `manual`, `low_confidence`, or `failed`.
- `confidence`, when present, must be numeric between `0` and `1`.

## Android Adapter Sketch

The Android-specific adapter should be added after the app module exists:

```kotlin
val engineConfig = EngineConfig(
    modelPath = modelPath, // e.g. gemma-4-E2B-it.litertlm
    backend = Backend.GPU(),
    visionBackend = Backend.GPU(),
    cacheDir = context.cacheDir.path,
)

Engine(engineConfig).use { engine ->
    engine.initialize()
    engine.createConversation().use { conversation ->
        val response = conversation.sendMessage(
            Contents.of(
                Content.ImageFile(receiptImagePath),
                Content.Text(ReceiptExtractionPrompt.multimodal),
            )
        )
        val parsed = ReceiptExtractionParser().parse(response.text)
    }
}
```

Exact imports and model/runtime options should be verified against the LiteRT-LM package version selected during the Android app setup.

## Current Runtime Boundary

The repo now contains a dependency-free extraction pipeline boundary for the Android module to implement:

- `ReceiptModelClient.supportsDirectImageInput()` gates multimodal receipt-image attempts.
- `ReceiptModelClient.extractFromReceiptImage(...)` carries the direct-image path.
- `OcrClient.extractText(...)` provides an OCR fallback input when direct multimodal extraction is unavailable or returns invalid structured output.
- `ReceiptExtractionPipeline.extract(...)` tries direct image first, validates the response, and falls back to OCR text on the same parser contract when needed.

This keeps the parser and validation rules identical across both candidate paths, which directly supports REQ-M-003, REQ-M-004, and REQ-M-007.

The repo also now pins the selected Phase 1 model target in `GemmaModels.gemma4E2BItLiteRtLm` so the Android adapter can consume one explicit repo/file selection rather than duplicating filenames in app code.

The `AndroidLiteRtLmReceiptModelClient` bridge now encapsulates the Android-facing session configuration needed by LiteRT-LM:

- resolves the pinned `Gemma 4 E2B` model path
- uses GPU/GPU as the default text and vision backends for the first device spike
- stages receipt image bytes to a temporary file path for multimodal requests
- sends OCR fallback through the same prompt/parser contract

It is intentionally dependency-free in this repo so its behavior can be unit-tested before the real LiteRT-LM library is wired into an Android module.

That real wiring now exists under `android-extraction/`:

- `android-extraction/build.gradle.kts`
- `android-extraction/src/main/kotlin/com/hugo/smartexpense/androidextraction/AndroidLiteRtLmReceiptModelClientFactory.kt`
- `android-extraction/src/main/kotlin/com/hugo/smartexpense/androidextraction/LiteRtLmAndroidSessionFactory.kt`
- `android-extraction/src/main/kotlin/com/hugo/smartexpense/androidextraction/AndroidGemmaModelPathResolver.kt`
- `android-extraction/src/main/kotlin/com/hugo/smartexpense/androidextraction/AndroidTemporaryReceiptImageStore.kt`

Workspace verification:

- `local.properties` now points to the installed Android SDK with `sdk.dir=...`.
- The required Android platform/build-tools for this module are installed in the SDK.
- The project Kotlin plugins were upgraded to `2.2.21` to match the Kotlin metadata used by `com.google.ai.edge.litertlm:litertlm-android:0.13.1`.
- `:android-extraction:assembleDebug` now succeeds in this workspace.

## Requirements Check

- REQ-A-003: Parser requires receipt date, merchant/shop/service name, and total amount.
- REQ-A-004: `low_confidence` and `failed` statuses support user confirmation/review.
- REQ-A-005: Parsed results are separated from handoff export so the UI can correct fields before export.
- REQ-M-001: Selected path is on-device LiteRT-LM by default.
- REQ-M-002: Prompt and parser require structured JSON.
- REQ-M-003: Parser validates model output before export.
- REQ-M-004: Invalid, failed, or low-confidence output can route to manual entry; invalid direct-image output can also route through OCR fallback before manual review.
- REQ-M-005: Selected path uses Google AI Edge LiteRT-LM directly.
- REQ-M-006: AI Edge Gallery remains a reference app only.
- REQ-M-007: Direct image input is plausible with multimodal Gemma 4 E2B support, and the code now defines OCR fallback behavior; Android hardware still needs to prove which path is required.
- NFR-001: No cloud AI is required by the designed path.
- NFR-002: `rawModelOutput` is captured in memory/model only and should not be logged unless debug logging is enabled.
- NFR-003: On-device extraction supports offline capture/review once model files are available locally.

## Remaining Work

- Download `gemma-4-E2B-it.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm` for receipt testing.
- Run direct image extraction on an Android device.
- Measure rough latency and failure behavior.
- Decide whether OCR is required for the MVP path.
