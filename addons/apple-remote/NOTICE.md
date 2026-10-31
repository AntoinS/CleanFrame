# CleanFrame iPhone Remote notices

CleanFrame integration code is offered under MIT; see LICENSE. Pinned upstream sources and their declared licenses are recorded in PROVENANCE.json. Retain the vendored notices when redistributing source or binaries.

- atv-core by corvofeng and contributors declares MIT at the recorded upstream revision; no separate upstream license file was present. This fact is preserved in PROVENANCE.json and vendor/atv-core/CLEANFRAME-FORK.md.
- chromiacast: MIT, copyright and terms in vendor/chromiacast/LICENSE. See its THIRD_PARTY_NOTICES.md for Cast trust anchors.
- Android TV IME protocol adaptation: Apache-2.0, terms and provenance in vendor/chromiacast/ANDROID_TV_IME_LICENSE.txt and ANDROID_TV_IME_NOTICE.md.
- Rust dependency versions are locked in Cargo.lock. Direct dependencies include Tokio, mdns-sd, ed25519-dalek, x25519-dalek, chacha20poly1305, sha2, hkdf, num-bigint, num-traits, rand, uuid, hex, thiserror, tracing, plist, subtle and unicode-segmentation. Their original license declarations are preserved in the Cargo packages.
- Android dependencies: OkHttp 4.12.0 and okhttp-digest 3.1.1 (Apache-2.0), with their Kotlin/Okio dependencies.

Apple, iPhone, Apple TV, Philips and Spotify are third-party trademarks. This independent experimental interoperability add-on is not endorsed by those companies.
