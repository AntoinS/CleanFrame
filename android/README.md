# CleanFrame for Philips Android TV

This independent Android host reuses the original CleanFrame web interface and
bundled public assets. Android, the original Google TV launcher, installed apps,
DRM, Ambilight and firmware remain installed. It is an app, not replacement
firmware. The Samsung `.wgt` installer and native Home helper are not used.

## Build

Use JDK 17, Gradle 8.9 and Android SDK platform 35. The project uses Android Gradle
Plugin 8.7.3, targets API 35 and requires Android 12 / API 31. Direct libraries are AndroidX WebKit 1.12.1 and Media3 Session 1.8.0.

```sh
gradle --version
gradle :app:assembleDebug
```

Set the SDK location through `ANDROID_HOME` or an ignored `local.properties` file.
The included Gradle wrapper can also be used: `./gradlew :app:assembleDebug`.
The build copies `../app/` plus the source licence and third-party notices into
generated assets. It excludes Tizen's `config.xml` and private-font binaries.
The source UI is never rewritten by the Android build. Debug output is
`app/build/outputs/apk/debug/app-debug.apk`; a release needs the owner's own local
signing setup before installation.

## Device stages

Installation and Home selection are separate actions. Prepare a device-state
baseline and obtain the owner's stage approval before running device commands.
For an authorised device selected through ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cleanframe.philips/.MainActivity
```

The application is visible as **CleanFrame** in the TV app list. Its optional
`com.cleanframe.philips/.HomeActivity` alias is disabled in the manifest, so
installing the APK does not make it a Home candidate or change the Home default.
Do not enable/select that alias until the original Home baseline, app behaviour
and exact rollback procedure have been checked and the owner has approved that
separate stage. Home activation uses the app's own
`CleanFrameNative.setHomeEnabled(true)` bridge method, then Android's Home
selection. The shell on the tested Philips firmware cannot toggle individual
components; the app uses the standard PackageManager API for its own alias.

On this Philips, the Google launcher's HOME filter has priority 2. Android caps
ordinary third-party filters at 0, so assigning the HOME role alone still leaves
Google as the effective resolver. Disabling the Google launcher for user 0 was a
separately approved step. Its APK and data remain installed. Always verify the
effective resolver instead of relying on `set-home-activity` reporting success.

```sh
adb shell cmd package set-home-activity --user 0 com.cleanframe.philips/.HomeActivity
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

After Home activation, open CleanFrame through Home or `.HomeActivity`. The
ordinary app entry remains available for initial testing.

### Original Home recovery

These values were verified on the owner's Philips 58PUS7304/12. Reverify them for
other devices before making changes:

```sh
adb shell pm default-state --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity
adb shell pm disable-user --user 0 com.cleanframe.philips
adb shell am start -W -n com.google.android.tvlauncher/.MainActivity
```

Enable the original launcher first. Disabling CleanFrame as a whole package also
stops its boot receiver and preserves its preferences. `pm default-state --user 0
com.cleanframe.philips` can re-enable it later. The standalone macOS recovery
bundle contains its own official ADB binary and this tested procedure.

### Power-on behaviour

The tested Philips firmware explicitly opens Live TV during boot, even with
CleanFrame selected as Home. Neither its General Settings menu nor Android's
Power & Energy menu offers a Home/last-input choice.

Version `0.6.1-android7` includes a small `BOOT_COMPLETED` receiver and the normal
`RECEIVE_BOOT_COMPLETED` permission. It requests the Home activity once, only if
CleanFrame both holds the HOME role and resolves as the effective Home alias.
Selecting another Home or disabling CleanFrame stops this behaviour. It does not
change defaults, enable/disable other packages, poll, start a service, require
overlay/accessibility access or handle ordinary standby wake-ups. This update
must be approved and verified with a real reboot before claiming automatic
startup works. Android 12 exempts the selected Home app from background activity
start restrictions; manufacturer behaviour still needs testing.

The Home activity is explicitly `directBootAware`; its alias inherits that
property. During early boot it shows only a native starting label and waits for
`UserManager.isUserUnlocked()` / `ACTION_USER_UNLOCKED` before creating WebView.
Preferences remain in their existing credential-encrypted storage. This lets
Android recognise the actual Home process during early boot; without it, the
system's FallbackHome process caused the later boot launch to be blocked on this
firmware. The pre-reboot check uses `MATCH_DIRECT_BOOT_AWARE` to verify the
effective Home alias really has this property. A global application attribute
alone did not make the activity Direct Boot aware on the tested TV.

## Bridge contract

`window.CleanFrameNative` exposes these annotated methods:

- `apps()` → JSON array of `{appId, name, icon}`. It discovers enabled, exported
  `MAIN` / `LEANBACK_LAUNCHER` activities, deduplicates by package and excludes
  CleanFrame. Icons use `/icons/<package>.png`. Failure returns `{error: ...}`.
- `launchApp(packageName)`, `home()`, `settings()` → `{ok:true}` or `{error: ...}`.
  Launches must resolve to a currently installed, enabled, exported TV app.
- `art()` → a truthful unsupported-feature error; Samsung Art Mode is absent.
- `setHomeEnabled(boolean)` → `{ok:true}` or `{error: ...}`. Changes only the
  app's own Home alias; it never selects a default or changes another package.

`home()` explicitly starts the verified original component
`com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity` when
enabled. If it is disabled, the action opens that package's Android App Info
page, where this TV offers **Aktivieren**. This native route also works from the
fallback screen when WebView cannot render.
It does not resolve the current Home default and cannot loop back to CleanFrame.
This component must be reverified when adapting to different hardware.

The WebView loads only packaged assets from its local AssetLoader origin.
External requests/navigation, file access and content-provider access are
blocked. Android8 requests `INTERNET` for its native Spotify Web API client;
WebView network loads remain explicitly blocked. It does not request
`QUERY_ALL_PACKAGES`. App icons are
generated from Android drawables into the app's private cache. WebView inspection
is enabled only for debuggable builds. Back maps to Escape and D-pad Center to
Enter in CleanFrame's existing DOM input handlers; arrow events stay native.

Bridge actions execute directly on WebView's bridge thread and call
`getApplicationContext().startActivity(... FLAG_ACTIVITY_NEW_TASK)`. They never
post work to the UI thread and then synchronously wait. A returned `ok` means
Android accepted the launch request, not that the destination has visibly drawn.
The TV banner reuses the original icon over a black 320 × 180 XML drawable.

## Platform evidence

- Android's [local-content guide](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)
  documents AssetLoader's local HTTP(S) URLs without internet access.
- [WebSettings](https://developer.android.com/reference/android/webkit/WebSettings#setBlockNetworkLoads(boolean))
  defaults to blocked network loads without the INTERNET permission. Chromium's
  [network helpers](https://chromium.googlesource.com/chromium/src/+/main/android_webview/browser/network_service/net_helpers.cc)
  apply cache-only network flags; the
  [intercepting loader](https://chromium.googlesource.com/chromium/src/+/main/android_webview/browser/network_service/aw_proxying_url_loader_factory.cc)
  still serves a supplied local response before network fallback.
- Chromium's [Java Bridge threading guide](https://chromium.googlesource.com/chromium/src/+/main/android_webview/docs/java-bridge.md#Threading)
  explains synchronous bridge calls and their dedicated thread. Android 12's
  [ContextImpl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/app/ContextImpl.java)
  and [Instrumentation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/app/Instrumentation.java)
  route application-context launches to ActivityTaskManager Binder without an
  Activity target or waiting for a UI-thread callback.
- Android's [intent-filter documentation](https://developer.android.com/guide/topics/manifest/intent-filter-element)
  explains the priority cap for nonprivileged activities. Android 12's
  [ActivityStarter](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/wm/ActivityStarter.java)
  permits background activity starts from the selected Home application.
- Android's [Direct Boot guide](https://developer.android.com/privacy-and-security/direct-boot)
  documents encryption-aware components, user-unlock notification and the
  restriction on accessing credential-encrypted storage before unlock.

## Verification boundary

Build success does not establish physical TV compatibility. Check the actual
WebView version, rendering, keyboard repeats, visibility/rotation, stored choices,
real app starts, TV settings and the explicit original-Home route. Home-button
and power-on behaviour need separate tests only after Home activation is approved.
The native fallback screen offers the explicit original-Home action if WebView
cannot render.

The independent build was verified on a Philips 58PUS7304/12 running Android 12:
full-screen rendering, 31 actual TV app entries, native Netflix/YouTube launches,
Philips settings and the original-launcher App Info escape. The owner confirmed
physical remote navigation and the Home button. Version android7 completed an
actual Android reboot without operator keys or app launches; its boot receiver
opened Home and focus remained stable for over 45 seconds. Preferences matched
the pre-reboot values exactly. On this firmware the transition takes roughly
two and a half minutes, with Live TV displayed in between. A three-second
Android Sleep/Wakeup cycle also returned to CleanFrame. These tests do not
establish behaviour after a firmware update, a mains power cut or long standby.

For visual verification use an Android `screencap` or the actual panel. On this
TV, DevTools `Page.captureScreenshot` produced narrow icon artefacts although
the direct TV screenshot rendered the installed SVG masks correctly. Temporary
image/mask experiments were reverted; no rendering workaround was required.

## Date and Spotify (android8)

Clock settings include **Show date**, enabled by default. The date uses German `Intl.DateTimeFormat` formatting and the current TV timezone. Spotify settings include the Home player toggle and translucent **Dark**, **Light**, or **Cover colour** backgrounds. The compact player sits at the bottom centre; selecting it expands the controls. Up from the controls reaches **Open Spotify app**; Back returns to the selected Home app. All apps and Settings remain stacked at the right, with Settings aligned to the player bottom.

Android notification access permits reading Spotify's MediaSession only. The listener processes no notification contents. The native Media3 service connection is held only while Home or the Spotify settings preview is visible, and is released on leaving/standby. Artwork is scaled and averaged on a worker and served locally. The WebView remains network-blocked.

On the tested Spotify TV version, the native session displayed remote playback but ignored pause/previous/next commands. The official Spotify Web API therefore handles transport. It requests current playback immediately before every command and explicitly targets that active device ID. Missing, inactive or restricted devices and Spotify's disallow flags produce no transport command. No transfer endpoint is implemented. Native session updates continue to supply the widget's metadata and artwork.

### Personal Spotify setup on the Mac

1. In [Spotify Developer Dashboard](https://developer.spotify.com/dashboard), create an app named **CleanFrame TV** with Web API access. Use redirect URI **`http://127.0.0.1:8765/callback`**. The owner must review Spotify's Developer terms. Development mode requires Spotify Premium and an allowed user.
2. Install the android8 APK with the existing package identity and signer. Run:

```sh
python3 -m pip install certifi
python3 android/connect-spotify.py --client-id YOUR_CLIENT_ID --adb /path/to/adb --serial YOUR_TV_ADB_SERIAL
```

3. Open `http://127.0.0.1:8765/` on that Mac and grant **read playback state** and **modify playback state**. The helper waits up to 15 minutes. It uses PKCE S256 and a checked state value; no client secret is needed.
4. After approval, the helper sends the refresh token directly through ADB stdin into app-private storage and restarts CleanFrame. The native host imports it into an AES-GCM file protected by an Android Keystore key and deletes the temporary import. No token is written to Mac files, source, localStorage, JavaScript events or logs. The encrypted file lives in Android's no-backup directory.

The grant persists across APK updates and refreshes automatically. Re-run setup if access is revoked. To revoke it, remove **CleanFrame TV** from [Spotify account apps](https://www.spotify.com/account/apps/). The developer Client ID is public; keep refresh/access tokens private. The setup helper requires the personal debug build's `run-as` access and is not a general consumer-TV sign-in flow.

### Validation and recovery

```sh
npm run check
node scripts/check-android-browser.mjs
node scripts/check-spotify-browser.mjs
CLEANFRAME_PUBLIC_FONTS=1 npm run check:browser
python3 android/test_connect_spotify.py
gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Browser and unit tests use fake media/OAuth data. They cannot establish whether real Spotify commands succeeded; check pause/resume and skips against the actual active device after account setup.

The personal android8 build was also checked on the Philips with Spotify playing
on the owner's Mac. TV remote commands paused and resumed the Mac, skipped to the
next track and returned to the previous track. The Mac's device picker still
showed **This computer** after the commands. The expanded player's Spotify
button opened the native TV app; Home returned to CleanFrame. A short standby
cycle released the media connection and reconnected after waking.

Keep the saved version7 APK and app preferences. To undo only this extension, revoke CleanFrame's notification listener (leave all other listeners intact) and reinstall the saved APK with `adb install -r -d`. Restore the private preference backup only if required. This update does not write Home selection or boot configuration. Revoke the separate Spotify account grant if it is no longer wanted.
