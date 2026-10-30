# RemoteAI Responder

RemoteAI Responder turns an Android phone into a small, private LLM server. It runs Gemma 4 on the phone, on the Google Tensor TPU where it can, and answers requests from other machines on your network through an **Anthropic-compatible Messages API**.

Pick a model, tap **Start API**, copy the base URL and API key, and send requests from your computer. The app keeps serving in the background, with the screen off, until you stop it.

It started as Google's [LiteRT-LM Tensor TPU sample app](#credits-and-license); the on-device chat UI has been replaced with a control panel for running the phone as a server.

## Supported phones and models

The app picks a model for the phone it is running on, and downloads it from the [`litert-community`](https://huggingface.co/litert-community) models on Hugging Face the first time you start it.

| Phone | Model offered | Size | Runs on |
|---|---|---|---|
| Pixel 10 (Tensor G5) | Gemma 4 E2B, compiled for Tensor G5 | 3.1 GB | TPU (NPU) |
| Pixel 11 (Tensor G6) | Gemma 4 E2B, compiled for Tensor G6 | 3.3 GB | TPU (NPU) |
| Any other 64-bit Android phone | Gemma 4 E2B GPU build | 2.0 GB | GPU |
| Any other phone with 12 GB+ RAM | Gemma 4 E4B GPU build (also offered) | 3.0 GB | GPU |

Only the Pixel 10 and Pixel 11 have been tested. No TPU build exists for other chips (including Tensor G4), so those phones use the GPU builds, which haven't been tested on a phone yet. You can also load your own `.litertlm` model with **Upload New Model...** in the model menu.

## Install

Build and install with the [Android SDK](https://developer.android.com/studio) (JDK 21 required):

```shell
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`sample_app_tpu.apk` in this folder is Google's original chat sample, not RemoteAI Responder.

## Use

1. **Open the app.** On first launch it offers to download the model for your phone. Wi-Fi is recommended; the status line shows progress, speed and time left, and an interrupted download resumes where it stopped. If you skip it, the download stays available in the model menu.
2. **Tap Start API.** The app asks for permission to show notifications (for the persistent notification and its Stop button) and to be exempt from battery optimization (so it keeps answering with the screen off).
3. **Copy the base URL and API key** with the Copy buttons at the bottom of the screen.
4. **Send requests** from another machine on the same network. Each request and reply appears in the **Traffic** log, with timing and any errors.

To stop serving, tap **Stop API** or use **Stop API** in the notification. Stopping releases the model's memory. If the app is killed or updated while the API is on, the API starts again by itself.

## API

The app serves plain HTTP on port **8080**.

| Endpoint | Auth | Description |
|---|---|---|
| `GET /health` | No | Liveness check and the loaded model ID |
| `GET /v1/models` | Yes | The loaded model |
| `POST /v1/messages` | Yes | Generate a reply ([Anthropic Messages API](https://docs.anthropic.com/en/api/messages) format) |

Authenticate with either `x-api-key: <key>` or `Authorization: Bearer <key>`. `POST /v1/messages` also requires an `anthropic-version` header.

```shell
curl http://PHONE_IP:8080/v1/messages \
  -H "x-api-key: $API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{
    "model": "phone",
    "max_tokens": 200,
    "system": "Answer in one sentence.",
    "messages": [{"role": "user", "content": "What is a TPU?"}]
  }'
```

```json
{
  "type": "message",
  "role": "assistant",
  "content": [{"type": "text", "text": "A TPU (Tensor Processing Unit) is ..."}],
  "stop_reason": "end_turn",
  "usage": {"input_tokens": 7, "output_tokens": 51}
}
```

Multi-turn conversations work: send earlier `user` and `assistant` turns in `messages`, ending with a `user` turn. `system` can be a string or a list of text blocks.

### Current limitations

- **No streaming.** `"stream": true` is ignored and the full reply is returned at once, so clients that require streaming (including the Anthropic SDKs' streaming helpers) won't work yet.
- **Text only.** Image and audio content blocks are rejected.
- **Generation settings are ignored.** `max_tokens`, `temperature`, `top_p` and `stop_sequences` have no effect, and `model` can be any string.
- **Estimated usage.** Token counts in `usage` are approximate (characters ÷ 4).
- **One request at a time.** Requests are queued and served in order.
- **Plain HTTP.** The API key and traffic are not encrypted. Use it only on a network you trust, and don't expose port 8080 to the internet.

## How it works

```
app/src/main/java/io/github/somecomputerguy/remoteairesponder/
├── AnthropicServerService.kt  Foreground service: HTTP server, auth, request handling, status and traffic log
├── LiteRTLMManager.kt         LiteRT-LM engine: NPU → GPU → CPU fallback, one request at a time
├── ModelResolver.kt           Model list, choice of model for this phone, file locations
├── ModelDownloader.kt         Resumable model downloads with progress and speed
├── ModelDownloadService.kt    Keeps a download running with the screen off, with a progress notification
├── MainActivity.kt            Control panel: model picker, traffic log, connection details, start/stop
└── LogAdapter.kt              Traffic log list
```

- **The service owns the model.** Only `AnthropicServerService` loads the model; the screen just shows status. Stopping the API closes connections, lets in-flight work finish, then releases the model.
- **Staying up in the background.** The service runs as a foreground service with a persistent notification. It holds a Wi-Fi lock while serving and a wake lock for each request, and `START_STICKY` brings it back if Android kills the process.
- **Models.** Downloads run in a foreground service, so they continue with the screen off or the app in the background. Downloaded models live in the app's private storage (`files/models/`). Deleting a model from the menu removes every copy the app holds.

### TPU notes

To run on the Tensor TPU, the app ships the Tensor dispatch library (`app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so`), loads the vendor libraries `/vendor/lib64/libedgetpu_litert.so` and `libedgetpu_util.so` at service start, and passes the app's native library directory to the NPU backend. `useLegacyPackaging = true` in `app/build.gradle.kts` keeps the dispatch library uncompressed so it can be memory-mapped.

## Tests

```shell
./gradlew app:testDebugUnitTest
```

The unit tests cover `ModelResolver`, `ModelConfig` and model selection, using Robolectric.

## Credits and license

RemoteAI Responder is built on Google's [LiteRT-LM Multimodal Chatbot: Google Tensor TPU Reference Implementation](https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/google/sample_app_tpu) from [google-ai-edge/litert-samples](https://github.com/google-ai-edge/litert-samples), and uses [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). It is not affiliated with or endorsed by Google or Anthropic; "Anthropic-compatible" describes the request and response format only.

Licensed under the [Apache License 2.0](../../../../LICENSE). This version modifies the original sample as described above.
