# Screen Translate

A floating button that sits over every app. Press and hold it to see all the Korean text on screen in English (or any language you pick). Let go and the screen goes back to Korean. A short tap keeps the translation on until you tap again.

- No screenshots. The app reads the text through Android's Accessibility Service, so nothing is saved to the gallery.
- Translation runs on the phone with Google ML Kit. It works offline once the language model (about 30 MB) is downloaded.

## Build
1. Open this folder in Android Studio (Hedgehog or newer) and let Gradle sync.
2. Plug in the phone with USB debugging on, then press Run. Or use Build → Build APK.

## First-time setup on Samsung / Android 13+
1. Open the app, choose a language, and wait for "✓ Ready".
2. Tap "Open Accessibility settings" → Installed apps → **Screen Translate button** → On.
3. If the switch is greyed out ("Restricted setting"): go to Settings → Apps → Screen Translate → ⋮ (top right) → **Allow restricted settings**, then turn it on again.
4. Recommended: Settings → Apps → Screen Translate → Battery → **Unrestricted**, so Samsung doesn't stop the service.

## Limitations (v1)
- Text inside images, videos, games and some custom views isn't readable through accessibility. That needs an OCR fallback (ML Kit Korean text recognition + MediaProjection), which is the next step.
- Some apps (banking, Netflix, etc.) block accessibility reading.
- ML Kit translation quality is fine but not as good as Papago or DeepL. You can swap in a cloud API in `TranslatorManager`.
