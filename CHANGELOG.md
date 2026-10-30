# Changelog

## 0.7.0 — 2026-10-30

- Preserve the Philips Android launcher, Spotify account controls and Mac visual editor developed from CleanFrame 0.6.1.
- Group Android settings into Display, Home screen, Add-ons, Language and Android TV. Background, typography and focus share Display; layout, app arrangement and clock share Home screen.
- Add direct TV catalog refresh, verified package download/update, activation and confirmed removal. Existing Spotify installations migrate automatically without losing the account or appearance preferences.
- Introduce declarative Add-on API 1: the reserved Spotify adapter and third-party clock/text widgets. Public catalog, package schema, examples and contributor review workflow are maintained separately.
- Keep private source, public add-ons and independently versioned packages separate. Android version names derive from package.json; versionCode remains monotonic.

Validation: source checks, Android build/lint/unit tests and browser navigation/layout checks. Native TV installation and download verification remain a separate deployment step. This first release is marked prerelease until physical-TV validation is complete.
