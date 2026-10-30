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

Download the APK from the [latest release](https://github.com/SomeComputerGuy/litert-samples/releases/latest) and install it (allow installing from your browser or file manager when Android asks). Releases are signed with this certificate; check it if you got the APK from somewhere else:

```
SHA-256: e9:3d:ab:54:11:f7:b0:e6:61:91:f3:28:09:b9:06:1a:03:9f:55:d1:2f:82:c3:3d:b9:91:49:c2:71:05:d0:ec
```

`apksigner verify --print-certs RemoteAI-Responder-*.apk` shows it.

### Build it yourself

With the [Android SDK](https://developer.android.com/studio) (JDK 21 required):

```shell
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug build can't update a release install (or the other way round) because they're signed differently; uninstall first, which deletes the app's models and pairings.

`./gradlew :app:assembleRelease` signs with the key described in `~/.config/remoteai-release/signing.properties` (or the file named by `REMOTEAI_SIGNING_PROPERTIES`), with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without that file the release APK is unsigned.

## Use

1. **Open the app.** On first launch it offers to download the model for your phone. Wi-Fi is recommended; the status line shows progress, speed and time left, and an interrupted download resumes where it stopped. If you skip it, the download stays available in the model menu.
2. **Tap Start API.** The app asks for permission to show notifications (for the persistent notification, its Stop button and pairing requests), to be exempt from battery optimization (so it keeps answering with the screen off), and on Android 14+ to show full-screen notifications (so pairing requests wake the phone while it's locked).
3. **Pair a coordinator** from another machine on the same network with [`coordinator/remoteai.py`](#coordinator). The phone shows the request with a 6-digit code; check it matches and approve with your fingerprint or screen lock. Paired coordinators are listed under **Paired coordinators**, where you can remove them.
4. **Send requests.** Each request and reply appears in the **Traffic** log, with timing and any errors.

Instead of pairing, a client can use the **API key** shown in the app, over HTTPS. That skips the phone's approval, and the client has to trust the phone's self-signed certificate on its own (`curl -k` doesn't check it at all), so pairing is the safer choice.

To stop serving, tap **Stop API** or use **Stop API** in the notification. Stopping releases the model's memory. If the app is killed or updated while the API is on, the API starts again by itself.

## API

The app serves **HTTPS only**, on port **2424** ("AIAI"), with TLS 1.2 or 1.3. There is no plain-HTTP port. While the API is on, the phone advertises itself on the local network over mDNS as `_remoteai._tcp`, named "RemoteAI on <phone model>", with TXT records `v`, `model` and `fp` (the certificate's SHA-256 fingerprint; advisory only, since anyone on the network can advertise).

| Endpoint | Auth | Description |
|---|---|---|
| `GET /health` | No | Liveness check and the loaded model ID |
| `GET /v1/models` | Yes | The loaded model |
| `POST /v1/messages` | Yes | Generate a reply ([Anthropic Messages API](https://docs.anthropic.com/en/api/messages) format) |
| `POST /v1/pair/start`, `/v1/pair/finish` | No | Pairing (see [Pairing protocol](#pairing-protocol)) |

Authenticate with a paired coordinator's token or the app's API key, as either `x-api-key: <token>` or `Authorization: Bearer <token>`. `POST /v1/messages` also requires an `anthropic-version` header.

```shell
curl -k https://PHONE_IP:2424/v1/messages \
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
- **Local network only.** Don't expose port 2424 to the internet.

## Coordinator

[`coordinator/remoteai.py`](coordinator/remoteai.py) finds phones, pairs with them and sends them requests. It needs Python 3.9+, plus `pip install zeroconf` for discovery.

```shell
python3 coordinator/remoteai.py discover          # phones on the network, and whether they're paired
python3 coordinator/remoteai.py pair "Pixel 10"   # prints a code; approve it on the phone
python3 coordinator/remoteai.py ask "Pixel 10" "What is a TPU?"
python3 coordinator/remoteai.py proxy "Pixel 10"  # http://127.0.0.1:8787 for Anthropic SDKs
```

Pairings are saved in `~/.config/remoteai/devices.json` (owner-only; it holds tokens). Each request looks the phone up over mDNS by its certificate fingerprint, so a changed IP address doesn't matter, and refuses to send anything if the phone presents a different certificate than the one it paired with. If you remove a coordinator in the app, it's cut off at once. Its next `ask` says the phone removed it and asks "Request pairing again?"; only on yes does the phone get a new pairing request, which you still have to approve. Re-pairing refuses a phone whose certificate has changed. Without a terminal to ask on (scripts, `proxy`), it just reports that the pairing was removed.

`proxy` serves a plain local endpoint on `127.0.0.1` only, so SDKs that expect a normal base URL work unchanged: use `base_url="http://127.0.0.1:8787"` and any API key.

### Pairing protocol

The phone's TLS key is an EC P-256 key generated in the Android Keystore; it never leaves the phone's secure hardware. Its self-signed certificate is pinned by the coordinator at pairing:

1. The coordinator connects over TLS, accepting any certificate but recording its SHA-256 fingerprint `fp`. It sends `POST /v1/pair/start` with its name and `SHA-256(client_nonce)`, and gets back a pairing ID and `server_nonce`.
2. It sends `POST /v1/pair/finish` with `client_nonce`. Both sides compute the code `SHA-256(fp ‖ client_nonce ‖ server_nonce)`, first 4 bytes, mod 1,000,000, each using the certificate it actually sees. An interceptor would present a different certificate, and because the coordinator committed to its nonce before seeing the server's, the interceptor can't choose values that make the codes match.
3. The phone shows the code and asks for a fingerprint, face or screen lock. On approval, `finish` returns a random per-coordinator token; the phone stores only its hash. The request waits up to 2 minutes, then fails with 408; a denial returns 403.

## How it works

```
app/src/main/java/io/github/somecomputerguy/remoteairesponder/
├── AnthropicServerService.kt  Foreground service: HTTPS server, mDNS, auth, request handling, status and traffic log
├── ServerIdentity.kt          TLS key in the Android Keystore and its self-signed certificate
├── Pairing.kt                 Pairing protocol, comparison code, paired coordinators and their tokens
├── PairingActivity.kt         Pairing approval screen, with fingerprint or screen-lock check
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
