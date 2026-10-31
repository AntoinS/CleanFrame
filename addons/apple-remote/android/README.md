# CleanFrame iPhone Remote · Android wrapper

This is the standalone `com.cleanframe.addon.appleremote` package, version 0.1.0.
Its service owns the native receiver independently of the launcher. No ADB shell,
Accessibility injection, downloaded WebView code or LAN debug server is included.

## Native boundary

Place the built library at
`app/src/main/jniLibs/armeabi-v7a/libcleanframe_remote.so` for the Philips test device.
`RemoteNative.request(String)` is the only native entrypoint. Responses are JSON;
pairing and network operations may complete asynchronously and are read with
`status` once per second. Operations are:

- `start`: `name`, local IPv4 `address`, decrypted `secrets` object, `backend:"host"`.
- `stop`, `status`, `applePairBegin`. The old Google transport remains in the Rust library for compatibility but is not selected by this Android wrapper.
- `unpair`: the exact paired-device `id`.

Rust must call static `RemoteNative.persistSecrets(String)` synchronously before
acknowledging a new identity, trust pin or pairing change. `false` means that
durability failed; the protocol must fail closed. This callback encrypts with an
Android Keystore AES-GCM key and commits through `AtomicFile`. Lost keys or corrupt
storage are errors, never a silent replacement identity. No secret export exists
in the public binder interface or diagnostics.

The expected UI state contains `running`, `android: {state, error}`,
`apple: {state, pin, expiresAt, devices: [{id, name}]}`, `keyboard: {ready}` and
bounded, redacted `events`. Omitted sections show an idle state. Pairing PIN is
visible only while the native pairing window is active. No field text belongs in
this status object.

## Host interface

`com.cleanframe.addonapi.IRemoteAddon.request(String)` is AIDL interface version 1.
Every request contains `op` and optionally `api: 1`. The signed host can request `status`, `setupStatus`, `enable`, `disable`, `philipsPairBegin`, `philipsPairFinish` (four ASCII digits in `code`), `applePairBegin`, and `unpair` (a paired device handle). Extra fields and unknown operations fail. Accepted means queued, not connected.

`status` is redacted. `setupStatus` returns `setupInterface: 1` plus the visible PIN/expiry and device handles, with no events, credentials or input text. CleanFrame renders every setup page. The separate app entry redirects to the host. The host's translucent code-entry sheet keeps Philips' system PIN visible.

The host declares `com.cleanframe.addon.permission.CONTROL`. Android checks its
signature permission; the service additionally checks the Binder caller's UID,
package `com.cleanframe.philips` and matching signing identity. The initial test
build must therefore be signed with the same certificate as its CleanFrame host.
Keys remain private and are not shipped in this repository. Other publisher
certificates require an explicit future trust-contract change.

`Service.dump` exposes only an allowlisted JSON diagnostic snapshot for the
existing shell/ADB diagnostic path. It does not start the receiver, accept
commands or expose PINs, text, credentials or raw packets. Installing or binding
the app does not itself enable the receiver. The setup screen activates it; an
enabled started service survives closing setup and is restarted after boot.

## Build checks

Use the existing Android SDK 35, Build Tools 35.0.0, JDK 17 and Gradle 8.9 with
`testDebugUnitTest lintDebug assembleDebug`. Local `sdk.dir` and generated native
libraries are ignored. A successful Java build without the native library is a
scaffold check only and is not an installation-ready receiver.

See [PHILIPS_BACKEND.md](PHILIPS_BACKEND.md) for the fixed-loopback OEM backend,
JNI host input and user-selected IME contract. No general shell or accessibility
input injection is included.
