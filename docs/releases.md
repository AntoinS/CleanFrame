# Versions and releases

The public source repository is https://github.com/AntoinS/CleanTV. `upstream` retains the original MIT project; keep its license, copyright and third-party notices. The public extension repository is https://github.com/AntoinS/cleanframe-addons and contains only reviewed catalog data, widget packages and contributor documentation.

Use Semantic Versioning for the host (`package.json`, package-lock.json and app/config.xml). Android derives its human-readable name from package.json and adds its monotonically increasing build number. Increase the default versionCode for every shipped APK; the editor's local counter must also exceed the installed TV version. Never reuse a code or replace an existing Git tag or release asset.

For each release:

1. Update versions and CHANGELOG.md. Run source, browser, editor and Android checks. Include new API tests and German/English 1080p/720p visual inspection.
2. Commit reviewed sources, create an annotated `vMAJOR.MINOR.PATCH` tag and push it to the origin. Use the Prepare versioned release workflow to create a draft from an existing tag, or the GitHub CLI with the exact tag and changelog.
3. Build locally with the existing private signing identity. Verify the signer against the saved local fingerprint. Attach the APK and SHA256SUMS to the release. Do not publish credentials, .local data, design drafts, proprietary fonts or signing material.
4. Mark the release prerelease until native TV checks pass. Installing remains a separate deployment: back up APK/preferences, merge editor preference changes and preserve Home/boot/account state.
5. Publish only the approved add-on packages and their metadata in the public catalog repository. Packages use independent semantic versions and immutable paths; catalog revisions have their own tags/releases. Document the minimum host API. Unknown kinds/APIs fail closed.

GitHub source archives contain code; they are not signed TV packages. CI cannot reproduce this owner's signing identity without additional explicit setup. No signing secrets are stored in Actions.

## Softwareupdates direkt am TV (ab 0.9.0)

Die Versionsanzeige unten rechts in den Einstellungen öffnet die Software-Paketseite.
Sie zeigt die tatsächlich installierte Android-Paketversion und den verfügbaren Stand.
Ein blauer Punkt kennzeichnet ein Update. Download und Installation erfolgen nur auf
Anforderung; Android bestätigt die Installation. Das ist ein Update der CleanTV-App,
kein Android-Firmwareupdate. App-Daten, Anmeldungen, Add-on-Konfiguration und die
bestehende Home-Zuordnung werden nicht zurückgesetzt.

Der feste öffentliche Kanal ist `updates/stable.json` in diesem Repository. Er verweist
auf eine unveränderliche APK unter `AntoinS/CleanTV/releases/download/`. CleanTV prüft
SHA-256, eigene Paketkennung, eigenen Signierer, genaue Versionskennung und eine
höhere Android-Buildnummer. Andere Signierer, ältere Builds und fremde Downloadorte
werden abgelehnt. Selbst signierte Builds benötigen einen eigenen Releasekanal; ein
anderes Zertifikat wird niemals durch ein offizielles Update ersetzt.

Veröffentlichung:

1. Paketversion und monotone `cleanframe.androidVersionCode` erhöhen. Den festen Stand
   mit `scripts/editor_release.py` prüfen und mit dem vorhandenen Schlüssel bauen.
2. Die verifizierte APK samt Prüfsumme an den passenden Release-Tag anhängen.
3. Öffentliche Metadaten erzeugen, erst nach erfolgreichem APK-Upload veröffentlichen:

   ```sh
   python3 scripts/software-release.py PATH_TO_FROZEN_RELEASE \
     --notes PATH_TO_PUBLIC_NOTES --output updates/stable.json
   ```

4. Die Metadaten zusätzlich dem Release beilegen. Vor stabiler Freigabe Download,
   Abbruch und Aktualisierung auf dem Referenzgerät prüfen. APK, Daten einschließlich
   `no_backup` sowie Berechtigungen vorher lokal sichern.

Download- oder Installationsfehler behalten die vorhandene Version. Abbruch löst die
Paketoperation, sodass erneut geprüft oder installiert werden kann. Host- und Add-on-
Installationen laufen nicht gleichzeitig. Nach einem erfolgreichen Selbstupdate kann
Android die bisherige Activity schließen; Home öffnet die neue Oberfläche.
