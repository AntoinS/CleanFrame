# CleanFrame Companion fork

Upstream: corvofeng/atv-core, commit `8a4ada29bb7cad31e0315c3387b277eb9ab8c988`.
This fork is experimental until validated with the actual iPhone and Android TV.
Do not install the upstream application or enable its debug HTTP server.

## Host contract

- Supply a persistent random Ed25519 seed in `AtvConfig.private_key` and an
  encrypted, durable `PairingStore` callback. Persistence completes before M6.
- `PairingStore::new` returns `Result`; load only previously accepted client keys.
  Open a PIN window explicitly (at most 120 seconds); five attempts/minute maximum.
  Successful pairing closes the window. Revocation denies the next control frame
  and closes idle authenticated connections on the 250 ms service tick.
- `AtvConfig.ui_port` must be `None`; startup refuses any debug web endpoint.
- `KeyboardState.session_id` is a UUID identifying the current non-password input
  field. Return `None` when no supported field is focused. Check the UUID again
  when committing queued `KeyboardEdit` operations to Android, not only on enqueue.
  Preserve this UUID for text changes; rotate it for field/application changes.
- Serialize rapid text edits and reconcile pending text with Android IME updates.
  Do not log `KeyboardState.text`, `KeyboardEdit.text`, keys, PINs or raw frames.
- Stop/drop the server on service deactivation or network replacement. No external
  shell or accessibility injection is provided by this crate.

## Protocol hardening

Pair-Setup M5 verifies the Ed25519 client signature over the SRP-derived controller
transcript before persisting its key. Pair-Verify M3 decrypts the authenticated
payload, looks up the previously paired key and verifies the session transcript.
Only then is encrypted control enabled. X25519 secrets are fresh per verification;
non-contributory public keys are rejected. SRP proof comparison is constant-time.
The upstream deterministic private-key derivation and fixed session keys were removed.

Frames are limited to 64 KiB, OPACK depth 32 / 4096 nodes / 256 KiB decoded, pairing TLV 4 KiB,
four Companion connections, 300 frames/second/connection, and 5-second payload/write
timeouts. Unauthenticated connections expire after 30 seconds. Discovery stub
connections have separate bounded concurrency and 10-second lifetimes.

## RTI keyboard scope

The `plist` library decodes binary keyed archives without instantiating classes.
Its stream is checked before building a value: depth 16, 2048 events, 64 KiB decoded
scalar data, 256 archived objects and 8 UID indirections. UUID checks reject stale
field events. Clear/assert, append, backward deletion and standalone return are
implemented for a collapsed caret at the end of the Android field. Native tvOS
runtime probes establish deletionCount as extended grapheme clusters; the
unicode-segmentation library performs that split. The actual iPhone's observed
inputSourceState and multilingualLanguages metadata is accepted only after
native class/type checks and confers no editing authority. Metadata-only input
produces no IME write. Selection, forward deletion and unknown editing operations
fail closed, as does Return combined with a text mutation. A Return invokes only
the host editor action. Full live iPhone keyboard compatibility remains subject
to device acceptance; see the synthetic fixture provenance for runtime evidence.

Wire references (not authentication implementations):
- https://github.com/postlund/pyatv/blob/master/pyatv/protocols/companion/plist_payloads/rti_text_operations.py
- https://github.com/postlund/pyatv/blob/master/tests/fake_device/companion.py

The pinned plist 1.8 minor line enables its event-stream feature specifically to
bound container expansion before allocation. Cryptography is delegated to
ed25519-dalek, x25519-dalek, chacha20poly1305, hkdf and sha2.
