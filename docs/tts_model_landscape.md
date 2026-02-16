# TTS Model Landscape (Document-Only)

This repo focuses on **offline, on-device TTS speed benchmarking** on physical Android devices.

Implemented engines in this app:
- `android.speech.tts.TextToSpeech` (system baseline)
- sherpa-onnx Offline TTS (ONNX Runtime, CPU) for a large public model zoo (VITS/Matcha/Kokoro/Kitten)
- NVIDIA NeMo FastPitch + HiFiGAN (ONNX Runtime Java, local-bundle import)

Non-goals for now:
- Subjective quality scoring (MOS)
- ASR loopback WER/intelligibility scoring
- Streaming TTS

## Recent / Notable Open Models (Not Implemented Yet)

These are useful references for future work, but are **not integrated** into the Android app today.
Reasons include model size, pipeline complexity, input conditioning requirements, performance on CPU-only mobile, and license constraints.

Policy:
- This repository is **Apache-2.0**.
- We do **not** integrate copyleft (GPL/AGPL) code into the app.
- Model weights are not redistributed by this repo; users download/push them themselves.

### Parler-TTS
- Repo: https://github.com/huggingface/parler-tts
- License: Apache-2.0 (per repo)
- Notes: Instruction-style conditioning and larger models; ONNX/mobile export paths vary.

### F5-TTS
- Repo: https://github.com/SWivid/F5-TTS
- License: Apache-2.0 (per repo)
- Notes: Diffusion-style generation and additional conditioning; likely heavy for CPU-only Android.

### Bark
- Repo: https://github.com/suno-ai/bark
- License: MIT (per repo)
- Notes: High compute and multi-stage pipeline; not a good fit for fast, repeatable CPU benchmarking.

### StyleTTS2
- Repo: https://github.com/yl4579/StyleTTS2
- License: MIT (per repo)
- Notes: Research-style pipeline; export/inference stack is non-trivial on Android.

### Chatterbox TTS
- Repo: https://github.com/resemble-ai/chatterbox
- License: MIT (per repo)
- Notes: Voice-cloning style models need more user-provided inputs and careful evaluation methodology.

### Qwen3-TTS
- Repo: https://github.com/QwenLM/Qwen3-TTS
- License: Apache-2.0 (per repo)
- Notes: LLM-ish / modern TTS pipeline; needs a well-defined mobile inference format and benchmark harness.

### ChatTTS
- Repo: https://github.com/2noise/ChatTTS
- License: AGPL-3.0 (per repo)
- Notes: Excluded from integration due to copyleft constraints for an Apache-2.0 Android app. (User-supplied bundles may still be benchmarked in a separate workflow if an engine is implemented without reusing AGPL code.)

