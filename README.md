<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Whisalt Logo">
</p>

# Whisalt

Push-to-talk dictation for Android.

Whisalt lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Local on-device transcription** with sherpa-onnx
- **Cloud transcription** with OpenAI Whisper
- **Optional cleanup** with OpenAI to fix punctuation and grammar

Whisalt is a fork of [Phone Whisper](https://github.com/kafkasl/phone-whisper) by Pol Alvarez.


## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/saltchang/whisalt-android/releases).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires a JDK (17-27) and the Android SDK (platform 37, build-tools 36).

```bash
git clone https://github.com/saltchang/whisalt-android.git && cd whisalt-android
make build
```

`make build` first runs `make deps`, which downloads the official [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) AAR (Kotlin API + native libraries) into `app/libs/` and verifies its SHA-256.

`make` uses `ANDROID_HOME` from your environment, defaulting to `~/Android/Sdk` on Linux and `~/Library/Android/sdk` on macOS.

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard

## Setup

### First-time setup

1. Open **Whisalt**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: paste your OpenAI API key

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Whisalt uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Whisalt supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to OpenAI's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to OpenAI's chat API

I don't run a backend for this app. In cloud mode, requests go straight from your phone to OpenAI using your own API key.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.saltchang.whisalt/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| SenseVoice | 163 MB | Chinese (with English mixed in), plus Japanese, Korean, Cantonese |
| Parakeet 110M | 100 MB | Best default for English |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives.

Chinese transcripts are converted to Traditional Chinese with Taiwan phrasing (软件 → 軟體, 网络 → 網路), using the same OpenCC `s2twp` conversion as [OpenWhispr](https://github.com/OpenWhispr/openwhispr). The dictionaries in `app/src/main/assets/opencc/` come from [opencc-js](https://github.com/nk2028/opencc-js) 1.4.1 (MIT) and [OpenCC](https://github.com/BYVoid/OpenCC) (Apache License 2.0).

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

Whisalt works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Whisalt falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Whisalt in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own OpenAI API key

## License

Licensed under the [Apache License 2.0](LICENSE). Based on [Phone Whisper](https://github.com/kafkasl/phone-whisper) by Pol Alvarez; uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache License 2.0).
