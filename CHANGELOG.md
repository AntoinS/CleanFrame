# Changelog

## 0.9.0

- Show the installed CleanTV version in the settings footer and open a software-update detail page.
- Download verified same-package, same-signer APK updates from the public release channel, with Android confirmation and retained app data.
- Display available versions and blue update indicators; handle cancellation and offline errors without stale installation state.
- Preserve valid add-on state responses that contain a recoverable operation error.
- Add software-update simulation and German/English 720p, 1080p and 4K editor checks.


## 0.8.4 — 2026-10-31

- Rename the visible Android app and Mac editor to CleanTV while retaining package IDs, signing identities, preferences and pairing. Preserve the original CleanFrame entrypoint as an editor-launch compatibility wrapper.
- Publish the existing repository as CleanTV with German project and add-on documentation, upstream attribution, compatibility limits and public release checks.
- Include the reviewed iPhone Remote 0.1.2 branding update. Add-on lifecycle and update management from 0.8.3 remain included.

## 0.8.3 — 2026-10-31

- Separate installed native identity from catalog download verification. Status, settings and uninstall remain available when the catalog or cached descriptor refers to a different version; package and certificate checks remain mandatory.
- Compare installed versions numerically and use Android version codes for native updates. Older catalogs no longer offer downgrades. Catalog refresh runs in the background at most once per 15 minutes.
- Blue update indicators in Settings and add-on cards, available-version details, and Update all with a persistent sequential queue. Android still confirms every APK update; cancellation or failure stops remaining updates.
- Updates preserve native activation/pairing and widget activation. Unknown runtime status is shown explicitly instead of implying that an installed receiver is disabled.

## 0.8.2 — 2026-10-31

- iPhone setup now refers to the TV’s device name instead of a fixed CleanFrame TV label. The reviewed iPhone Remote 0.1.1 package follows the Android device name and preserves existing pairing when it changes.

## 0.8.1 — 2026-10-31

- Preserve the native receiver’s own activation setting during an add-on update. Automatic activation is restricted to the first confirmed installation; enabling or disabling from integrated setup remains authoritative.

## 0.8.0 — 2026-10-31

- Spotify QR sign-in and iPhone Remote setup now share CleanFrame Add-on settings. Includes pairing countdown, removable devices, keyboard setup and a host-owned Philips code sheet. Backend credentials remain native.
- Optional one-time Android accessibility permission enables automatic Philips code confirmation on the TV. Capture is limited to the current 30-second pairing message; no Mac, screen recording or input injection is used. Existing TV and iPhone credentials are retained.
- TV add-on manager now has Installed / Discover cards, package details and stable remote focus.
- Add-on API 2 preserves JSON packages and adds individually signed native APKs with Android confirmation, checksum, identity and certificate checks. API 1 catalog stays unchanged.
- Mac editor adds isolated package simulation, a virtual remote and a read-only service diagnostic view. Simulation is excluded from TV preference/design exports.
- iPhone Remote 0.1.0 is an independently built experimental Android service, reusing pinned atv-core and chromiacast sources. Pairing signatures, persistent grants, bounded input and lifecycle handling are hardened; there is no HTTP debug controller.

The iPhone Remote package remains an experimental 0.1.0 release. The owner confirmed pairing, reconnection, navigation, volume, Play/Pause and live Spotify typing/deletion/search. Full lifecycle and cross-app acceptance remain pending. Siri, microphone forwarding and AirPlay are not implemented. Spotify credentials, Home and startup configuration remain unchanged.

## 0.7.0 — 2026-10-30

- Preserve the Philips Android launcher, Spotify account controls and Mac visual editor developed from CleanFrame 0.6.1.
- Group Android settings into Display, Home screen, Add-ons, Language and Android TV. Background, typography and focus share Display; layout, app arrangement and clock share Home screen.
- Add direct TV catalog refresh, verified package download/update, activation and confirmed removal. Existing Spotify installations migrate automatically without losing the account or appearance preferences.
- Introduce declarative Add-on API 1: the reserved Spotify adapter and third-party clock/text widgets. Public catalog, package schema, examples and contributor review workflow are maintained separately.
- Keep private source, public add-ons and independently versioned packages separate. Android version names derive from package.json; versionCode remains monotonic.

Validation: source checks, Android build/lint/unit tests and browser navigation/layout checks. Native TV installation and download verification remain a separate deployment step. This first release is marked prerelease until physical-TV validation is complete.
