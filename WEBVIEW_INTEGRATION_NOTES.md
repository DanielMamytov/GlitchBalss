# GLITCH BALLS WebView Integration Notes

What was added:

- `np.sairwv.glitchballs.MainActivity` is now the launch resolver.
- The original game is preserved as `np.sairwv.glitchballs.ui.activity.GameActivity` and remains the fallback/native mode.
- Added WebView flow copied from the reference project:
  - AppsFlyer conversion/deeplink collection
  - remote config POST request
  - cached WebView URL with expiry
  - notification/deeplink one-time URL support
  - full-screen WebView with cookies, DOM storage, file chooser, camera upload, intent-scheme handling, and WebView state restore
  - connection error retry screen
  - custom notification permission prompt
  - FCM notification service skeleton
- Added Glitch Balls themed launch/prompt/error UI colors and resources.

Configured URLs/build fields:

- `CONFIG_ENDPOINT` defaults to the Glitch Balls config endpoint.
- `STORE_ID` defaults to `np.sairwv.glitchballs`.
- `DEBUG_WEB_URL` defaults to the provided AppsFlyer test URL.
- `APPSFLYER_DEV_KEY` is wired through BuildConfig and can be overridden without editing source.

You can override values in `local.properties`, environment variables, or Gradle properties:

```properties
GLITCH_CONFIG_ENDPOINT=https://gllitchballs.com/config.php
APPSFLYER_DEV_KEY=your_key_here
GLITCH_STORE_ID=np.sairwv.glitchballs
GLITCH_DEBUG_WEB_URL=https://your-test-url
GLITCH_AF_DEBUG=false
GLITCH_FORCE_WEB_DEBUG=false
```

For debug testing only, run with:

```bash
./gradlew :app:assembleDebug -PGLITCH_FORCE_WEB_DEBUG=true
```

Expected config response format:

```json
{
  "ok": true,
  "url": "https://example.com/web-target",
  "expires": 1780000000
}
```

Notes:

- If the config endpoint returns an error/non-JSON/`ok:false`, the app opens the native game fallback.
- FCM dependency and service are included, but live Firebase push token delivery requires adding a valid `google-services.json` for package `np.sairwv.glitchballs` and enabling the Google Services plugin.
- The Gradle wrapper could not be executed in this environment because the Gradle distribution download requires internet access. XML resources were validated statically.
