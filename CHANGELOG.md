# Changelog

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
