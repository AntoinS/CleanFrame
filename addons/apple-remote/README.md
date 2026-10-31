# CleanFrame iPhone Remote · experimental 0.1.0

A separate Android app receives authenticated Apple Companion commands and forwards supported keys through the Philips JointSpace service on the same TV. The Mac is not part of the runtime path. All visible setup and device management are integrated into **CleanFrame → Settings → Add-ons → iPhone Remote**. Android owns keyboard/accessibility permissions and app installation dialogs.

**Experimental release:** secure pairing and reconnection, Home/YouTube navigation, volume, Play/Pause, and live Spotify typing/deletion/search were confirmed on the reference Philips and iPhone. This is not a general Apple TV implementation. Custom app keyboards, selection replacement, cross-app input, Netflix and the full lifecycle matrix remain unverified. Siri, microphone forwarding and AirPlay streaming are excluded.

## Build on the Mac

Requirements: Python 3.11+, Rust **1.90.0**, target
`armv7-linux-androideabi`, Android NDK **27.2.12479018**, SDK / Build Tools 35,
JDK 17 and Gradle 8.9. The current Philips candidate is **armeabi-v7a, API 31+**.
Other ABIs require their own build and device validation.

The existing `.local/editor/toolchain.json` supplies `javaHome`, `sdk`,
`androidUserHome`, `gradleUserHome`, `gradle` and `signerSha256`. Keep this file and
all keys local. `androidUserHome/debug.keystore` must already exist and must be
the host CleanFrame signing key: the Android host interface checks signatures.
The builder refuses a missing key and verifies the resulting certificate.

Once the toolchain is installed, run from the CleanFrame checkout:

```sh
python3 scripts/build-apple-remote.py
```

`--offline` requires cached Cargo and Gradle dependencies. `--output <new-folder>`
selects a new artifact directory; the script never overwrites an existing one.
The script does not install toolchains, start a receiver, connect to the TV,
install an APK, or publish a release.

The builder snapshots Rust and Android source, runs native tests, cross-compiles
the Rust bridge, runs Android unit tests and lint, and assembles the APK. Source,
Cargo target, Gradle output and project cache are staged in an external temporary
directory to avoid build artifacts inside iCloud-managed project folders.
Cargo's shared download cache is `.local/remote-cargo`; Gradle uses the existing
configured cache and signing home. Private keys are never copied into staging.

Successful output is under `.local/apple-remote/builds/<timestamp>/`:

- Signed `CleanFrame-Apple-Remote-<version>-experimental.apk` and native library.
- `manifest.json` with package, version, source hash, APK/library hashes, signing
  certificate, toolchain versions and commands that passed.
- `inputs.json`, Android reports and `build.log`.

The source tree's ignored `android/app/src/main/jniLibs/armeabi-v7a/` receives the
same freshly built library for subsequent local Android builds. Packaging checks
require the ARM library and expected JNI entrypoint; a Java-only scaffold cannot
pass. `deviceTestsPassed`, `approvedForCatalog` and `tvInstalled` stay `false`.

## Set up and test a candidate

**Device evidence:** Apple Companion pairing passed in build 8, single-press pause/resume in build 10, and live Spotify typing, umlauts, emoji, deletion, Return/Search and reopening the field in build 18. Reconnection after APK updates preserved pairing. The integrated settings release keeps the same protocol and storage. Full TV reboot, standby/wake, Netflix and package cancellation/uninstall checks remain separate gates for a regular release.

Back up the installed APK, settings and permission state before a TV update.
Install only the verified artifact, with the same signing key. Opening setup is
part of confirmed installation through CleanFrame. A first installation activates the receiver; updates retain its disabled/enabled choice. Direct APK sideloading still needs activation in setup.

1. Open **CleanFrame → Settings → Add-ons → iPhone Remote → Set up** and enable it.
2. For automatic setup, enable **CleanFrame · Automatic TV setup** once in Android Accessibility through the add-on settings. The service reads only this add-on’s correlated Philips pairing message for up to 30 seconds, sends the normal authenticated confirmation, and does not record screen text or inject input. Existing credentials are reused. The permission can be revoked after setup; normal remote control keeps working. Without the grant, enter the four-digit TV code in the small left-hand CleanFrame sheet.
3. After initial automatic TV pairing, the time-limited iPhone pairing window opens. You can also open a new window in setup. Select this TV in the
   iPhone's built-in Apple TV Remote and complete its PIN confirmation.
4. Verify navigation outside CleanFrame, reconnect, Unicode text, field changes,
   volume, Home, service restart and TV restart. Check standby/wake separately.
5. Remove one pairing and verify that the old client can no longer control it.
   Deactivate the add-on and verify that reception and announcements stop.

Record failures as failures. Do not replace authentication or unsupported IME
behavior with shell commands, open debug HTTP endpoints or unverified TLS.
Editor simulation does not establish device compatibility; TV diagnostics are
read-only and omit PINs, keys, entered text and raw packets.

RTI supports clear/assert, append, backward deletion by extended grapheme cluster,
and standalone Return while the Android caret is collapsed at the end of the
field. The observed keyboard/language metadata is validated and ignored.
Selection, forward deletion and unknown editing operations fail closed. A Return
combined with a text mutation is rejected because the host submit operation does
not atomically replace text. Native runtime fixtures verify deletion units and
Unicode handling. Spotify full-field updates passed on the reference device.
Apps without a compatible Android input field are unsupported. Siri, microphone
audio and AirPlay streaming are outside this version.

Synthetic tvOS 27 runtime checks verify that the outgoing text-field archive is
decoded as Apple's `RTIDocumentState` and `TIDocumentState`; classless archives
were rejected by that runtime. See the synthetic fixtures and
[their provenance](vendor/atv-core/tests/fixtures/RTI-PROVENANCE.md). This verifies
archive compatibility; the later build 18 device checks below establish the narrower live Spotify behavior.

Experimental build 18 passed the maintainer's live Spotify text test on
2026-10-31: typing, umlauts, emoji and backward deletion were confirmed on the
TV, with ten native `text_edit` deliveries and no input rejection in that test.
The owner also confirmed the search action, reopening the Spotify search field
and entering a new character there, followed by normal remote navigation after
closing the keyboard. Cross-app field switching has not been verified.
The versioned RTI envelope and both `NSArray`/`NSMutableArray` language lists
are required by the tested iPhone. This limited acceptance does not establish
selection replacement, other apps' custom keyboards, or the remaining lifecycle
and package-management release checks. Keep the package experimental until those
checks pass.

## Source provenance and licenses

Pinned sources are recorded in [PROVENANCE.json](PROVENANCE.json):

- **atv-core:** `corvofeng/atv-core` at
  `8a4ada29bb7cad31e0315c3387b277eb9ab8c988`. Its workspace declares MIT. That pinned
  tree contains no separate LICENSE file; this absence is recorded rather than
  inventing an upstream copyright notice. The CleanFrame fork adds
  signature checks, persistent authorization, limits, RTI and lifecycle cleanup.
- **chromiacast:** `pronkproject/chromiacast` at
  `65a398c7fe1b0b65c5fe9e3455f047eae5b50631`, MIT; original notice retained in
  `vendor/chromiacast/LICENSE`. Its three public Cast CA certificate files are
  named `.crt` in this fork (with `include_bytes!` paths updated) so the private
  PEM/key exclusion stays intact; certificate bytes are unchanged.
- **Android IME schema:** field definitions from `tronikos/androidtvremote2` at
  `e664a8e1f14ee458b14c646bfb3ea46f10367051`, Apache-2.0. Attribution and the full
  license are retained in `vendor/chromiacast/ANDROID_TV_IME_NOTICE.md` and
  `ANDROID_TV_IME_LICENSE.txt`.
- **pyatv:** Companion RTI client/fake-device behavior is a wire reference, not an
  authentication implementation or installed dependency. Rust uses `plist` for
  bounded archive decoding and existing cryptographic libraries.

Preserve these notices and dependency licenses when preparing a distributable
package. See [the Companion fork contract](vendor/atv-core/CLEANFRAME-FORK.md),
[Android protocol notes](vendor/chromiacast/ANDROID_TV_IME_NOTICE.md), and
[the Android wrapper interface](android/README.md) for implementation limits.
