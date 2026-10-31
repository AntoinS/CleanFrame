# Add-on API 2

The Android host downloads the reviewed public catalog from AntoinS/cleanframe-addons. See that repository's README, package.schema.json and examples for the public authoring contract. API 1 uses declarative JSON packages and remains supported. The new host reads catalog-v2.json; catalog.json is unchanged for older hosts. Downloaded JavaScript and HTML are never executed. API 2 adds reviewed, separately installed native APKs.

Spotify's native adapter remains part of the host API. Its installed package enables the widget; removing the package stops Home observation and removes the widget. Account credentials and appearance preferences are deliberately retained, as stated in the confirmation dialog. Use Spotify → Sign out before removal to clear CleanFrame's account grant. The native Spotify application is unaffected.

A first upgrade seeds the Spotify package from bundled assets. Later package state is kept in addons.json through Android AtomicFile, independently of Home preferences. Saved installations work offline. Failed downloads, validation or disk writes retain previous state. Explicit refresh fetches catalog changes; downloads are never automatic. A single executor serializes operations; the UI receives completion asynchronously.

Third-party text and clock widgets are rendered as textContent using host templates, with at most three active cards. Cards occupy a fixed Home region and are hidden while the Spotify player is expanded. Clock timezone is an optional valid IANA identifier. Long text is clipped to the card; authors should keep content concise. Arbitrary rendering, custom scripts, network data providers and additional native capabilities require a later host API revision.

Management is available at Settings → Add-ons on the TV. The Mac preview can show the layout; a normal browser has no native download/storage access and reports that honestly. Tests use an explicit synthetic adapter, never real account credentials.


## Native packages

The initial native package is `com.cleanframe.addon.appleremote`, interface 1, version 0.1.0. Catalog metadata must include API 2, native kind, exact package/version/code, minimum host, immutable release APK URL, SHA-256 and signing-certificate SHA-256. Download origins are restricted to the approved public repository and GitHub release-asset redirects. The host verifies the archive before creating an Android PackageInstaller session. Android owns confirmation; cancellation leaves the previous installation intact. Downloads are bounded and canceled when the owning manager closes. Pending committed operations are durable and reconciled with PackageManager after callbacks/resume.

Native status uses the small `IRemoteAddon.request` AIDL interface with an explicit interface version. Both endpoints verify package identity and the shared signer before binding or accepting host operations. The signed host owns the visible setup: `setupStatus`, activation, Philips pairing, iPhone pairing and device revocation. Ordinary `status` and ADB diagnostics remain redacted. `setupStatus` adds only the time-limited PIN, expiry and paired-device handles; keys and entered text never reach the WebView. Reading status cannot enable its receiver. Other developers can contribute native source/manifests for review; adding a new executable identity requires an explicit host allowlist update and a maintainer-signed release. Arbitrary APK URLs or self-signed third-party packages are not accepted.

Native services do not consume the three Home widget slots. The first receiver is experimental and omitted from the public catalog until the physical iPhone/TV acceptance gate passes. The protocol fork, source pins, build command and constraints are in [the add-on README](../addons/apple-remote/README.md).

## Editor

The Add-ons workspace keeps an `addonLab` simulation model separate from `tvSource`, normal preferences and design. Local installs, removal, activation and failures use a development-only adapter. Virtual commands operate the preview; simulated text is never sent to the TV or retained in the event log. Undo, snapshots, conflict detection and reload include this model, while a fresh TV import resets it. The package list, detail view and removal dialog are available in the normal visual editor.

TV diagnosis uses only fixed `dumpsys package` and `dumpsys activity service` commands over the existing configured ADB target. It does not start a missing service, install, enable, pair or send keys. Output contains bounded status/events, no pairing PIN, account credentials or entered text. Its polling runs only while the diagnosis workspace is visible.


## Unified setup and verified device scope

Spotify's PKCE exchange runs in `SpotifySignInSession`. The shared frontend renders the QR image, countdown, result and retry; it receives no verifier, refresh token or polling secret. Back, leaving CleanFrame and standby cancel the session. Existing account and player settings use the established storage paths.

The remote detail page opens the shared CleanFrame setup screen. It contains activation, TV and iPhone connection states, a two-minute pairing countdown, paired-device removal with confirmation, and links to Android's keyboard permission/selection dialogs. A small host-owned WebView displays the Philips code-entry sheet over the system pairing screen. The add-on app entry and notification return to CleanFrame. Android install/uninstall, keyboard and optional accessibility permission dialogs remain owned by Android. The optional Philips setup service reads only an actual visible OEM pairing message matching a fresh session marker, for up to 30 seconds; it never stores screen text or reads other apps. It completes the normal authenticated grant and retains existing credentials. Manual entry remains available. Confirmed first installation activates the remote receiver; updates preserve disabled state.

The Google remote listener rejects same-TV control before TLS on the reference firmware. The receiver therefore uses authenticated Philips JointSpace over pinned loopback TLS for navigation and Android's public media-key API for Play/Pause. Text input uses the user-selected CleanFrame input method; no system package or trust check is patched.

The owner confirmed iPhone pairing, reconnection, Home/YouTube navigation, volume, Play/Pause, and live Spotify input with umlauts, emoji, backward deletion, Return/Search and reopening the field in experimental build 18. Cross-app text changes, custom keyboards, arbitrary selection, Netflix, deep-sleep wake and the full reboot/uninstall matrix are not established. The remote release remains explicitly experimental. Private evidence and backups remain in ignored `.local/`.
