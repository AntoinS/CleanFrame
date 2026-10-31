# CleanFrame Android Remote fork additions

Chromiacast base: `65a398c7fe1b0b65c5fe9e3455f047eae5b50631`, MIT.

IME Protobuf field names/numbers in `src/android_tv/proto.rs` are adapted from
`tronikos/androidtvremote2` revision `e664a8e1f14ee458b14c646bfb3ea46f10367051`,
`src/androidtvremote2/remotemessage.proto` (Apache License 2.0). The accompanying
license is in `ANDROID_TV_IME_LICENSE.txt`. Original repository:
https://github.com/tronikos/androidtvremote2

The additions reuse prost, rustls, sha2 and the existing bounded Remote Service
actor. No second TLS, Protobuf, or cryptographic implementation is introduced.

## API and persistence

`AndroidTvPairingSession::finish` returns the SHA-256 fingerprint of the exact
server certificate from the successful code-confirmed pairing connection. Store
this fingerprint with the client identity in private application storage. Both
established `AndroidTvRemote` connect methods require it and verify it during
the TLS handshake, before any remote protocol commands are sent. A changed
certificate requires deliberate new pairing; do not silently replace the pin.
Handshake signatures remain checked by rustls/ring.

`ime_state()` and `ImeChanged` provide an in-memory field snapshot. Supply that
snapshot to `replace_text`, `append_text`, `delete_backward`, or
`submit_text_field`; re-read it after each sent edit. The actor rejects tickets
from an older session, field, revision or counters. Debug output omits field
text and label, and wire diagnostics show only byte count and top-level tags.
Keep actual text out of external logs and diagnostic responses.

## Experimental interoperability limits

Synthetic tests validate framing, counters, stale-ticket rejection and UTF-16
handling, not Android application acceptance. Full-field updates use
`RemoteEditInfo.insert=0` plus the resulting value and selection. The upstream
Python client uses a different insertion convention. Real-device verification
is mandatory before a regular release. Field status and batch counters must
agree before a writable snapshot is exposed; devices with differing counter
semantics need bounded, metadata-only observation before adaptation.

Backspace removes a Unicode scalar or the active selection without splitting a
UTF-16 surrogate pair. It does not implement grapheme-cluster deletion.
Submit sends Android Enter to the active field; whether that performs search
is controlled by the focused app. No voice, microphone, arbitrary shell, or
fallback text injection is implemented. Apps that do not expose Remote IME
remain unavailable for remote text input.
