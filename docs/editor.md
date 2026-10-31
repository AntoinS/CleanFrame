# CleanFrame Editor

Open `CleanFrame Editor.command` with a double click. The editor runs at
http://127.0.0.1:4797/editor/ and uses the actual packaged UI. The draft uses
synthetic data; the separate Live view receives continuous video from the configured TV.
Nothing is automatically installed. Keep its terminal window open while using
it; closing that process stops the local server. A second launch reuses it.

Choose a view on the left, select a group or individual element, and drag or
resize it. The right panel has precise properties, per-element resets and shared
templates. Stored design coordinates use CleanFrame's 1920 × 1080 logical stage.
The preview renders at 3840 × 2160, 1920 × 1080 or 1280 × 720. Inspector values,
rulers, guides and distances use pixels of the selected output resolution, so
one 4K pixel equals 0.5 stored units. 100% zoom shows one output pixel per browser
CSS pixel; Fit scales the view to the available workspace. Text and vectors render
at the selected resolution; existing 1920px photos/material images do not gain
additional photographic detail. The TV's actual UI resolution is shown separately
from its panel resolution. Selecting 4K in the editor never changes Android's
system resolution. The maintainer's Philips currently reports a 3840 × 2160
panel with a 1920 × 1080 UI and screenshots.

Drag a line from either ruler or use the guide buttons. Guides are scoped to the
view and layout, persisted with the draft, editable numerically and undoable.
Dragging/resizing snaps to guides, other visible elements and the eight-pixel
grid; Alt releases snapping. Shift-click another element (or choose it in the
inspector) to measure horizontal and vertical edge distances. Negative distances
mean overlap. Editing a distance moves the selected element. Per-side Padding
and Margin, plus child Gap, are standard layout properties shared with the TV UI.
Native QR preview remains approximate; per-side padding/margin have native equivalents.
Guides and measurement marks are editor-only and never enter the APK.
Edit mode selects; Test mode uses the real navigation. Arrow keys, Enter and
Escape, or the displayed remote, operate the preview. Cmd-Z and Shift-Cmd-Z undo
and redo. Spotify and device actions use isolated development fixtures; the QR
preview contains no live account link. Native QR typography remains approximate
until checked with a native TV screenshot.

Drafts, persisted undo history, named versions, baseline settings and prepared
releases live in ignored `.local/editor/`. The initial settings come from the
local TV backup, not a fresh device query. Its age is displayed. Source edits by
an assistant reload the preview while retaining the draft. Concurrent draft
writes fail with a conflict; they never silently overwrite another writer.

## Live status and local Codex chat

The connection badge distinguishes connected/offline, installed version, current
Android UI/panel resolutions, and whether the prepared APK still matches the
source, design and preference changes. A changed draft or source marks the
candidate stale. Ready means an intact tested candidate and reachable TV, not
that a backup or current preference-conflict check has already happened.

Only `.local/editor/tv.json` configures the fixed ADB executable, serial, package,
label, scrcpy server path and matching server version. The current integration is
pinned to scrcpy 5's documented packet format. Install it locally with
`brew install scrcpy`. Capture requires a current browser with H.264 WebCodecs
support. No video executable or helper is included in the CleanFrame APK.

Live uses the official [scrcpy packet interface](https://github.com/Genymobile/scrcpy/blob/v5.0.1/doc/develop.md).
A uniquely named temporary server is pushed to `/data/local/tmp`, started through
ADB with audio/control/clipboard/power/wake options disabled, and connected through
one session-specific local ADB forward. The TV's original H.264 configuration,
frame data and presentation timestamps pass unchanged to the browser's built-in
`VideoDecoder`. Canvas displays the decoded frames at their native dimensions.
There is no JPEG conversion, FFmpeg transcoding or host-side scaling. Actual TV-source and browser-display
frame rates are shown separately. The browser's decode queue is bounded; a slow
client restarts at a current keyframe instead of accumulating latency.

Live offers two capture modes. **Flüssig · bis 30 fps** is the initial choice:
the TV captures at 848×464 with a 30 fps target and 33 ms idle repeats. **Nativ ·
Full HD** retains the TV's 1920×1080 UI pixels, has no capture-rate limit and
repeats an unchanged surface only after 200 ms. Both use 4 Mbps; the smaller mode
changes only the recording size, never Android's display resolution. The choice
is saved in the Mac browser, outside the design and TV preferences. Both the
reduced resolution and the measured frame rate remain visible.

On this Philips, the earlier 100 ms idle repeats back up the capture pipeline on
Home. Shorter GOPs and a nominal codec rate of 30 make fresh keyframes available
more often. A new subscriber waits for the next keyframe; cached screen history
is never replayed on entry. Switching modes closes the previous stream first.
Concurrent tabs can share one mode; a conflicting request waits briefly for a
pending disconnect, then reports the conflict instead of replacing another tab's
capture or starting a second encoder.

The latency investigation measured approximately 550 ms from the TV presentation
timestamp to receipt with the earlier defaults. With the new configuration, the
quiet Home screen measured about 190 ms; navigation still measured about 380 ms.
Browser decoding added only a few milliseconds. These are observations, not
latency guarantees: the clock estimate has roughly half the ADB round-trip time
of uncertainty and excludes physical panel presentation. Alternative software AVC
and hardware VP8 encoders did not improve the navigation result. Local detailed
measurements are kept in the ignored `.local/editor/qa/` directory.

Sustained motion tests showed about 48 fps in the TV WebView, but only 5–6 fps
through native-size capture. A recording made locally on the TV contained 46
frames in 7.8 seconds, confirming the bottleneck exists before Mac transport.
Explicit AVC profiles/operating-rate hints, direct WebView screencasting and
minicap did not provide native-size 30 fps. The tested 720p and 540p modes achieved
about 13 and 23 fps. Although 854×480 reached about 30 fps, it produced corrupt
color planes on this Philips. The final mode requires 16 px alignment and uses
848×464; both source and browser measured about 30 fps with clean images. Static
content can produce fewer frames (about 11 fps on a quiet Home during testing):
Android repeats idle surface buffers a limited number of times. No frames are
interpolated or invented in the browser. The experimental helpers were removed from the TV.

After the first encoded frame the video socket has no idle-read timeout. A quiet
screen can legitimately have no changes for minutes. The local HTTP stream sends
a zero-length 12-byte keepalive packet once a second while waiting; the browser
skips it without decoding or counting a frame. This also detects a departed viewer
while the picture is static. Idle pictures are labelled `Standbild`, rather than
treated as failed connections. EOF and encoder errors still end the stream.

The panel refresh rate is distinct from Android's capture cadence. scrcpy capture
is intrinsically variable, driven by changed screen content. The maintainer's
Philips firmware declares its AVC encoder up to 1920×1088, 30 fps, with a measured
1920×1080 performance range of 21 fps. This is not evidence that a static Home
screen must deliver 21 or 30 distinct frames each second, or that 60 fps capture
is available. Android currently renders its UI in 1920×1080 on a 4K/60Hz panel.

A further isolation test on 2026-10-30 found that the native capture limit is
specifically in the MediaTek codec's graphics-surface input path, not a general
6 fps limit of the H.264 encoder. An off-screen Android MediaCodec probe fed the
same sequence of 120 changing grey frames through two inputs at 1920×1080:
direct NV12 ByteBuffers reached 39.41 fps, whereas EGL Surface input reached
6.59 fps. Both produced 120 frames and 1,094,885 coded bytes. A preceding run
measured 39.39 versus 6.66 fps; Surface input at 720p reached 14.58 fps. These
are unpaced synthetic throughput tests, not a claim of working 30 fps capture.

The vendor codec log confirms ARGB8888 native-window buffers. Inspection of the
installed `libMtkOmxVenc.so` shows that this branch copies RGBA pixels and calls
`ConvertABGRToNV12`, a scalar CPU pixel loop with double-precision conversions.
This is strong evidence for costly vendor color conversion in the slow Surface
path. Android denied CPU profiling of the codec service, so time spent in
conversion versus buffer locking/copying has not been separately measured.
The diagnostic logs, probe source, binary hash and focused disassembly are kept
in ignored `.local/editor/qa/codec-probe/diagnosis.json` and adjacent files;
the temporary helper was removed from the TV. No driver or TV settings changed.

A targeted next experiment is to bypass that Surface conversion and feed NV12
using an existing optimized converter such as
[libyuv](https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/README.md).
Screen readback, conversion and encoding must be measured together before
promising or shipping native 30 fps. Increasing the nominal fps setting alone
does not remove this bottleneck.

Capture begins only when Live is selected in a visible browser tab. Leaving Live,
`visibilitychange` to hidden, or `pagehide` aborts the fetch and closes the decoder.
Multiple visible clients share a single TV capture and a bounded keyframe buffer.
The last client closes the video socket, stops the temporary process, and removes
only that session's ADB forward and helper. Startup waits for the Android socket
rather than a guessed delay. Errors offer explicit retry and never fall back to
periodic screenshots. Native PNG capture remains a separate verification endpoint.
Status queries are cached for ten seconds and never start video capture.

There is no endpoint for arbitrary commands, addresses, installation, input events,
resolution changes or credential reads. Reconnect uses the fixed configured serial;
it does not wake or launch an app. Original APK, Home assignment and Spotify login
remain unchanged by capture.

The Codex panel uses the installed `codex app-server` and its existing ChatGPT
login. No API key is copied into the editor. It checks `model/list` and uses the
configured model if available, otherwise the returned default, displayed in the
chat status. Protocol reference: [official App Server documentation](https://learn.chatgpt.com/docs/app-server).
Messages and current draft/element data are sent to OpenAI when the user sends
a request. TV pictures and Spotify credentials are not included. Messages persist
locally in `.local/editor/chat.json`; a restarted process receives the recent
conversation as context. The two dynamic tools read the current draft and merge
validated design/preference changes, recording normal undo history. Concurrent
changes invalidate the turn's write revision even if the agent rereads it.

This chat arranges existing elements and settings. New source-code features
remain work for the main Codex chat. Shell, inherited MCP integrations, app tools,
web search and delegation are disabled; the sandbox is read-only with no network
and no approval escalation. Only the local host's validated draft tool can write.
Stop interrupts the turn; already-applied edits remain undoable. Authentication,
model, quota and connection errors are displayed, never replaced by fake replies.

The 48px header, compact controls, collapsible property groups, optional chat
panel and “Mehr Platz” button maximize preview space.

## Shared interfaces

`app/design.json` is the shipped document: `version: 1` plus `rules`. Each rule
has `view`, `layout`, `state`, `target` and a restricted `style`. The runtime
resolver is `CleanFrameDesign` in `app/design.js`. Element IDs use actual action
or app IDs and stable view structure. `type:` targets are templates. Specific
elements override templates; within each class, specific view/layout/state
values override `*`. Missing rules retain the existing renderer. Ordinary
preferences remain in the existing CleanFrame store; they are not duplicated as
design settings. The local schema in `scripts/editor-schema.mjs` rejects unknown
properties, arbitrary selectors, URLs and executable style values.

`ShoreQA.focus(action)` selects without activation; `ShoreQA.preferences(patch)`
uses the normal preference validation and save path. Native devices retain their
existing actions. `ShoreQA.editorScene` and `playerView` exist only behind the
preview boundary. The editor-only mock and interface are outside `app/` and never
enter the Android APK. Changed geometry uses the vendored spatial-navigation
library with the actual visible rectangles. Existing list scroll, slider and
Back behavior is retained. The native QR screen reads the same packaged design
file using `DesignOverrides`; its rules have `view: qr` and `layout: *`.

## Preparing and transferring a TV version

**TV-Stand holen** reads the running CleanFrame WebView through the configured
ADB connection and imports its committed display preferences, home app order,
design rules, app names/IDs and installed declarative add-ons into the draft.
Open CleanFrame on the TV first. This does not launch, install or modify anything
on the TV. A short-lived WebView forward is removed after each read, including
failure. No Spotify credentials, notifications or general app storage are read.

The previous complete draft is saved under **Gespeicherte Zwischenstände** before
replacement. Failed reads, invalid data and concurrent edits leave the draft
untouched. Imported preferences become the fresh baseline for later transfer;
old guides and undo history remain in the saved snapshot. Restoring a snapshot
also restores its preference baseline and imported app/add-on context.

The editable preview uses the local CleanFrame source, with Spotify sample data.
The import reports a version mismatch explicitly; it does not copy APK code or
Android/system screens. The imported TV version and capture time appear below
the element list. The editor chat directs TV import requests to this button.

**Für TV vorbereiten** copies source, design and preference changes into an
immutable input snapshot under `.local/editor/releases/<id>/`. It runs baseline
regression checks against the unchanged layout, editor checks and a matrix of
the actual frozen design. It then builds Android, runs unit tests/lint, checks
the expected signing certificate and verifies the snapshot hashes. The release
contains its APK, manifest, change list, preview images, layout report and log.
A failed preparation never produces a ready manifest or replaces a previous
release. Version codes increase per preparation. The local Android tool paths
and expected signing fingerprint are in `.local/editor/toolchain.json`.

Transfer is a separate assistant task after the owner requests the prepared
version. The editor has only the fixed read-only ADB functions above and no
installation or credential-import endpoint. The assistant must:

1. Verify `manifest.json`, APK hash and signer against the selected release.
2. Announce the TV update; back up the installed APK, current CleanFrame
   preferences and current permission/Home state in a new recovery directory.
   Keep Spotify credentials in Android; never export them into editor files.
3. Three-way merge fresh TV preferences using `scripts/editor_preferences.py`
   with the release's `preferences.json`. Resolve reported conflicts with the
   owner. Preserve TV-side edits to all untouched fields, recent apps and state.
4. Install the frozen APK as an in-place update with the same identity/key.
   Apply only its merged preference changes through CleanFrame's own WebView.
   Do not change Home, boot receivers, original launcher or media permissions.
5. Inspect native screenshots, remote navigation and the changed native QR
   screen when applicable. Record the installed APK hash and owner's visual
   confirmation in the release. Keep a recovery file with the exact previous
   APK and settings; use an in-place debug downgrade if restoring that version.
   Do not uninstall the app as a rollback shortcut, because that deletes account
   credentials and app data.

## Verification

`npm run check:editor` runs isolated browser tests with a temporary draft store.
`python3 scripts/test-editor-tools.py` checks preference conflicts and merging.
`python3 scripts/test-editor-workspace.py` checks draft tool validation, concurrent
edits, TV status, native capture metadata, native packet framing and shared capture cleanup. Browser
workspace tests cover 4K pixel conversion, 1:1 viewing, guides, distances, per-side
spacing, the draft/live switch and chat context. These tests use a fake TV and
mocked chat response; the installed Codex integration is separately smoke-tested
with a real isolated draft. No account inference or TV access occurs in builds.
The normal `npm run check`, `npm run check:tools`, browser/Android/Spotify/options
suites and Android tests/lint remain part of a release. `capture-editor.mjs`
checks all owned views in both layouts, languages and resolutions, captures
representative previews, and rejects offscreen or unreachable menu actions.
Native TV inspection is intentionally recorded as pending until a transfer.


## Add-on workspace (0.8.0)

Choose **Add-ons** in the top navigation. **Simulation** exposes package details in the actual TV UI, simulated install/remove/activation actions and failure scenarios. The right-hand virtual remote operates only the preview. The text field checks local routing/rejection and Unicode handling; it is not an emulation of Apple's wire protocol or proof of TV compatibility.

Choose **TV-Diagnose** to read the installed native package version, service, Android/iPhone connection, keyboard readiness and a bounded event log. This view has no device control actions and does not launch a stopped service. Switch back to Entwurf or Live am TV to leave it. Package cards, details and removal dialogs can also be selected through the normal scene picker and edited in either language at 720p, 1080p and 4K.

`addonLab` is saved with local undo and snapshots, separate from the immutable imported `tvSource`. It is not included in APK assets, design rules or preference changes. TV import clears it while retaining the previous complete draft as a named snapshot. Tests: `npm run check:editor:addons` and the diagnostic cases in `scripts/test-editor-workspace.py`.

The visual scene picker includes **iPhone Remote Einrichtung** and **Spotify Anmeldung** using the actual packaged frontend. Pairing state is simulated locally; simulation cannot grant a TV permission or pair a phone.
