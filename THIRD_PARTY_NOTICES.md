# Third-party notices

The root MIT licence covers CleanFrame's original code and documentation. Third-party material retains the terms below. Keep these notices and the adjacent licence texts when sharing source or a build.

## Fonts

| Material | Terms and source record |
| --- | --- |
| Manrope | SIL Open Font License 1.1; `app/assets/manrope-OFL.txt` |
| Geist and Newsreader | SIL Open Font License 1.1; licences beside the files in `app/assets/fonts/`, with exact sources and hashes in `app/assets/fonts/SOURCES.json` |
| Optional PP Neue Montreal, PP Mori and PP Pangram Sans | Proprietary. Binaries stay in ignored `.local/` storage and are excluded from Git and public exports. The optional local overlay requires the user's own permission to use and embed those files. |

The public app uses Manrope, Geist and Newsreader. A personal widget containing proprietary fonts must stay private. See [Pangram's licensing information](https://pangrampangram.com/pages/eula).

## Icons and trademarks

| Material | Provenance and terms |
| --- | --- |
| Simple Icons service marks | CC0; exact revisions and adaptations in `app/assets/icons/SOURCES.json` and `ADDITIONAL-SOURCES.json`. The CC0 text is retained beside the icons. |
| Disney+, ABC and SBS marks | [Custom Brand Icons](https://github.com/elax46/custom-brand-icons), MIT. The retained licence is `app/assets/icons/CUSTOM-BRAND-ICONS-LICENSE.txt`. ABC and SBS sources were replaced during the public-source review. Exact revisions, source files and extraction/recolour notes are in `SOURCES.json`. |
| Art, apps, settings and home outlines | Original CleanFrame drawings, covered by the project's MIT licence. |

Brand marks identify services. Their owners retain trademark rights; this project claims no endorsement or affiliation. The older Australian TV icon archive's GPL text is retained for historical provenance only; no current icon uses that archive.

## Photographs and derived materials

The fifteen photo pages were individually checked on 20 October 2026. Each displayed its credited author and free use under the [Unsplash License](https://unsplash.com/license). The [asset source register](docs/asset-sources.md) links every page. Runtime paths are in `app/assets/wallpapers/SOURCES.json`.

Photographers retain their rights. Wallpapers, thumbnails, panel backgrounds and material fields derive from the credited photographs and retain their terms. The Unsplash licence permits copying, modification and distribution subject to its restrictions, including on selling unmodified images and building a competing image service.

## Development dependencies and exports

Development dependencies retain the licences in their npm packages. They are not copied into the TV JavaScript runtime.

`npm run export:public` excludes private fonts and includes this notice and the root MIT licence. Preserve the included font and icon licence texts. Review [public release preparation](docs/public-release.md) before publishing a repository or download.

## Android host libraries

The Android APK uses AndroidX WebKit 1.12.1 and Media3 Session 1.8.0. Their runtime dependencies include AndroidX Core, Media, Media3 Common/DataSource/Database, annotations, collection, concurrent futures, lifecycle, arch core, versionedparcelable and ExifInterface; Google Guava (including failureaccess/listenablefuture); Kotlin standard library; and JetBrains annotations. These components retain their Apache License 2.0 terms. The complete licence text is retained in `android/THIRD_PARTY_LICENSES.txt` and copied into the Android assets.

Sources: [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Media3](https://github.com/androidx/media), [Guava](https://github.com/google/guava), [Kotlin](https://github.com/JetBrains/kotlin), [JetBrains annotations](https://github.com/JetBrains/java-annotations).

ZXing Core 3.5.3 generates the QR code in the native sign-in dialog. It is licensed under Apache License 2.0; source: https://github.com/zxing/zxing. The complete Apache licence accompanies the Android assets.

JUnit and JSON-java are test-only dependencies and are not packaged in the APK. The Spotify integration calls the official Web API using the owner's Developer app and OAuth authorization. No Spotify APK, implementation code, credentials or client secret is included in CleanFrame's source or APK.

## Local editor and spatial navigation

The local visual editor uses interact.js (MIT) and Ajv (MIT), pinned in
package-lock.json. These development dependencies are not packaged on the TV.
Their license texts are included with the installed npm packages.

The TV's custom-layout navigation uses Luke Chang's js-spatial-navigation,
unmodified, under MPL-2.0. The pinned source revision is recorded in
app/vendor/spatial-navigation-source.json; source and license are distributed
in app/vendor/. CleanFrame's integration is implemented separately in design.js.
https://github.com/luke-chang/js-spatial-navigation
