# Philips backend integration contract

`PhilipsRemote` is an experimental, package-private Java backend. The receiver explicitly selects it with native `backend:"host"`. No automatic
pairing or keyboard enable/selection occurs. Setup exposes separate Philips and
iPhone pairing flows and Android keyboard settings.
Constructing it only opens its encrypted local vault. Call blocking operations
on the existing service worker, never the Android main thread.

- `setEnabled(true)` is an explicit host lifecycle decision; pairing never starts automatically.
- `beginPairing()` asks the local TV to show its four-digit PIN. If no pin exists,
  this explicit setup operation alone captures the valid local leaf certificate
  via `/6/system`, durably saves its SHA-256 fingerprint, then requests pairing.
  Existing certificate pins are never replaced, even after an authentication failure.
- `finishPairing(String)` accepts exactly four ASCII digits. Pending pairing expires
  after at most 120 seconds and permits at most three submissions. A successful
  grant is not reported as paired until its credentials are durably encrypted.
- `send(Key)` accepts only the fixed enum: arrows, OK, Back, Home, Play/Pause,
  volume up/down and mute. Concurrent sends fail busy; starts are limited to 20/s.
  There is no key queue, arbitrary URL, shell or general command endpoint.
- `state()` is cached/read-only and never connects. It exposes `backend`, `enabled`,
  `paired`, `certificatePinned`, `state`, `error`, `pinDigits`,
  `pairingSecondsRemaining` while pairing, and capability flags. It does not expose
  fingerprints, credentials, PINs, usernames, entered text or response bodies.
- `setEnabled(false)` disables future operations and clears pending pairing.
  `close()` immediately cancels active network I/O, disables the instance and clears
  pending state. Construct a fresh disabled instance after close.

The host owns its existing enable preference. This backend does not persist or
change that preference. Credentials survive disable/restart in the independent
`philips-secrets.bin` vault and `cleanframe.remote.philips.v1` Keystore alias;
`remote-secrets.bin` and the Rust identity/pairings remain untouched.

Every request goes to HTTPS `127.0.0.1:1926`, API6, with no proxy, redirects or
transport retry. Exact leaf certificate pin and validity replace hostname trust
for this fixed loopback destination. First-use trust requires explicit setup;
it is not a certificate bypass for arbitrary URLs. A Philips firmware certificate
may be shared among TVs; it is not a unique device identity. Digest credentials
provide the separate paired-client authorization. Only the Digest library creates
authorization headers, with one challenge response per request. No HTTP logger is
installed. Pairing calls expire after eight seconds; input-key calls after 2.5 seconds and decompressed response bodies are
limited to 64 KiB. Errors exposed by this class are fixed short codes.

Power/standby/wake and native text are deliberately unsupported by this backend.
No ambiguous power toggle is substituted for wake. The key names still require
per-key physical verification on the target firmware, beyond the proven Home and
CursorLeft OEM path. Keyboard support needs a separate user-selected IME.

Dependencies and the public wire reference are recorded in
`../PHILIPS_HTTP_PROVENANCE.json`; licenses/notices ship in the APK assets. Tests
include an isolated JVM TLS/Digest server bound to **the Mac loopback port 1926**;
they never connect to the physical TV. That local port must be free to run them.

## Native host input boundary

`RemoteNative.hostInput(String)` dispatches only `state`, allowlisted `key`, and
validated `edit` operations from Rust's ordered blocking actor. Java never calls
back into `RemoteNative.request` from this path. IME edits await main-thread
completion for at most 2.5 seconds, with a cancellation token and an activation
generation checked before each InputConnection mutation. A stop invalidates the
generation immediately; rapid re-enable cannot revive old queued edits.

The selected IME exposes a ready snapshot only for its visible, supported active
field. Full field text is restricted to this local JNI boundary and the paired
iPhone protocol. Passwords, custom/partial fields, oversized text, hidden fields
and stale generation/revision requests are rejected. Binder and dumpsys expose
only readiness, selection status and fixed reason strings. The system owns IME
enable/selection; Setup opens the official settings and picker only on user input.

## Play/Pause dispatch

The host dispatcher sends only the iPhone `play_pause` action through Android's
public `AudioManager.dispatchMediaKeyEvent`: one DOWN followed by one UP for
`KEYCODE_MEDIA_PLAY_PAUSE` (85), with the same down-time and no repeat. The other
actions continue to use the Philips backend. This requires no new permission,
reflection, accessibility injection or ADB. Stop prevents new pairs; an already
submitted DOWN is followed by its release. Android's API returns void, so success
means submission and must be verified against the real active player's playback.

The installed reference JointSpace firmware maps both `Play` and `PlayPause` to
MEDIA_PLAY (126), not a toggle. Its `Confirm` workaround depends on visible player
controls, so neither OEM command is used as the host's Play/Pause action. No
firmware-family lookup, synthetic playback state or repeated toggle is added.

Public API: https://developer.android.com/reference/android/media/AudioManager#dispatchMediaKeyEvent(android.view.KeyEvent)
