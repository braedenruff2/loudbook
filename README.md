# Loudbook for Android

The Chrome extension's reader, as a phone app. It has a built-in browser for the fiction sites, a
player underneath, and Kokoro running on the phone itself (through sherpa-onnx), so it works
offline once a chapter has loaded. It keeps reading with the screen off, and turns the page when a
chapter ends.

## Getting it on your phone (once)

1. On the PC, double-click **Turn on phone updates.bat** and follow its two steps. It has you make an
   empty public repository called `loudbook` on GitHub, then uploads this folder there. GitHub builds
   the app in about 5 minutes.
2. On the phone, open `https://github.com/YOUR-NAME/loudbook/releases/latest` and tap
   **Loudbook.apk**. Allow your browser to install apps when asked, then tap **Install**. If Play
   Protect doesn't recognise the developer, choose **Install anyway**: the app was built for you and
   isn't in the Play Store.
3. Open Loudbook. The first time, it downloads the voice (354 MB, once), so use Wi-Fi.

## Updates

Nothing to do. When this folder changes, the "Loudbook publish" scheduled task sends the change to
GitHub within 5 minutes, and GitHub builds a new release. The app checks for new releases when you open
it and downloads them in the background. It installs an update when you leave the app while nothing is
playing, or straight away if you tap the "Update … is ready" bar.

The first update asks you once to let Loudbook install apps (Settings › Install unknown apps ›
Loudbook). After that, updates install without asking on Android 12 and later. Older Android shows a
one-tap "Update" prompt. ⚙ › **Check for updates** checks right away.

To pause publishing, turn off "Loudbook publish" in Windows Task Scheduler.

## Using it

| | |
|---|---|
| **Open a chapter** | tap a site on the start page, type or paste a link, or in Chrome tap **Share → Loudbook** |
| **▶** | read from where you are; it carries on with the screen off |
| **⏪ ⏩** | back / forward a sentence (also on the lock screen, the notification, and headset buttons) |
| **⏮ ⏭** | previous / next chapter |
| **1.0×** | speed; tap to step through, or use the slider in ⚙ |
| **Long-press a paragraph** | read from there |
| **⚙** | voice (25, best first, with "Hear this voice"), speed, pause between sentences, roll into the next chapter, sleep timer, pronunciation fixes, voice commands |
| **Voice commands** | ⚙ › Voice commands. Say *play*, *pause*, *back*, *forward*, *beginning* or *end* while Loudbook is open or reading. Setup records each word twice so only your voice counts: the TV, other people and the story being read are ignored. Everything stays on the phone. |
| **Back button** | goes back a page; on the start page it puts Loudbook in the background (still reading) instead of closing |

The same sites as the extension: Royal Road, AO3, FanFiction.net / FictionPress, Wattpad, WebNovel,
Wuxiaworld, Scribble Hub, SpaceBattles / Sufficient Velocity / QQ, plus other article-style pages.

## The best voice: Google Gemini (online, optional)

Gemini's text-to-speech is one of the best-rated voices available. To use it, get a free API key
at [aistudio.google.com](https://aistudio.google.com) (**Get API key**), then paste it in ⚙ › **Best
voice**. Pick a voice ("Sulafat" is warm; "Charon" is a clear storyteller) and tap **Save and hear
it**.

- **What it sends:** the chapter text goes to Google a paragraph at a time.
- **Free tier:** has daily limits, and Google may use what's sent to improve its products.
- **Paid tier:** Google charges roughly $0.81 per hour of listening.
- **Fallback:** when the limit is hit or there's no connection, the PC voice or the phone's voice
  takes over by itself.

## Voice on your PC (saves battery)

The PC can make the speech instead of the phone. The phone only plays it, which uses much less
battery. When the PC is off or you're away from home, the phone reads by itself as usual, and it
switches back to the PC on its own.

1. On the PC, double-click **Set up PC voice.bat**. It installs everything into
   `%LOCALAPPDATA%\Loudbook` (nothing system-wide), starts the server, sets it to start when you sign
   in, and asks Windows once to let the phone in on **home (private) networks only**. Then it shows a
   pairing code.
2. On the phone: ⚙ › **Pair with my PC**. It finds the PC by itself; type the code.

How it stays private:

- **Encrypted:** everything between the phone and the PC goes over HTTPS. The phone remembers the
  PC's exact certificate fingerprint, so no other machine can pose as it.
- **Pairing:** pairing needs the one-time code the PC shows. It is 12 characters, works for 10
  minutes, and allows 5 wrong tries. The PC proves it knows the code before the phone sends
  anything.
- **Per-phone keys:** each paired phone gets its own 256-bit key. The PC stores only a hash of it.
  **Unpair all phones.bat** removes every phone's access.
- **Home network only:** the server answers only private-network addresses (and Tailscale's, if
  you ever use that to reach home). It limits request sizes and rates, and never logs or keeps the
  text it reads.
- **Code:** `pc-server/loudbook_server.py`, about 400 lines of Python.

## How it's built

There's no Android Studio or Gradle. `build.sh` calls Google's build tools directly: aapt2 for
resources, javac, d8 for dex, zipalign and apksigner. GitHub Actions runs it on every push
(`.github/workflows/build.yml`). `tools/ci-parts.sh` fetches sherpa-onnx's Android libraries and the
Kokoro model. The model goes up once as the `voice-1` release, and each build becomes release
`v<number>` with `Loudbook.apk` and `version.json`.

| | |
|---|---|
| `app/src/com/loudbook/app/` | `MainActivity` (browser + player screen), `ReaderService` (background playback, notification, lock-screen controls), `Voice` (Kokoro via sherpa-onnx, plus the one-time voice download), `Updater` (self-updates from GitHub releases), `Net`, `Chapter` |
| `app/src/com/k2fsa/sherpa/onnx/` | sherpa-onnx's Java API (v1.13.8), with an Android library loader and the asset-loading entry point |
| `web/bridge.js` | the page script's Android side: finds the chapter, splits it into sentences, turns numbers into words, highlights |
| `tools/bundle_web.py` | builds `app/assets/web/loudbook.js` from the extension's `sites.js`, `lib/chunk.js` and `lib/say.js` plus `web/bridge.js`, so the phone and the extension read pages identically (the built file is committed) |
| `keystore/loudbook.jks` | the key the app is signed with. Updates must be signed with the same key, so don't lose it. It's in the public repo, which is fine for a personal app. To keep it private, add it base64-encoded as the repository secret `KEYSTORE_B64` and remove it from the repo. |
