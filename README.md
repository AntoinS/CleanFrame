# CleanTV

**Eine anpassbare Startoberfläche für Android TV – mit visuellem Mac-Editor und Add-ons.**

CleanTV ist eine **Weiterentwicklung von [CleanFrame von nthnclrk](https://github.com/nthnclrk/CleanFrame)**. Das Original entstand für Samsung The Frame mit Tizen. Diese Weiterentwicklung ergänzt einen Android-TV-Host, einen lokalen Editor und unabhängig aktualisierbare Erweiterungen. Git-Historie, MIT-Lizenz und ursprüngliche Urheber bleiben erhalten; dieses Repository wird bei GitHub als eigenständiges Repository geführt.

CleanTV ist eine App und optionale Home-Oberfläche, **keine Ersatz-Firmware**. Android, installierte Apps und die Gerätefunktionen bleiben bestehen.

[Releases](https://github.com/AntoinS/CleanTV/releases) · [Add-ons](https://github.com/AntoinS/cleanframe-addons) · [Android-Einrichtung](android/README.md) · [Editor](docs/editor.md)

## Was CleanTV kann

| Bereich | Funktionen |
| --- | --- |
| Startbildschirm | Listen- und Dock-Layout, App-Reihenfolge, Symbole und Beschriftungen, All apps |
| Darstellung | Foto-, helle und dunkle Hintergründe, Schrift, Fokusdarstellung, Rundungen und Transparenz |
| Alltag | Uhrzeit und optionales Datum, Deutsch und Englisch, gruppierte Einstellungen |
| Spotify | Kompakter und aufgeklappter Player, Cover, Fortschritt, Wiedergabesteuerung, QR-Anmeldung und Abmelden |
| iPhone Remote | Experimentelle Steuerung über die Apple-TV-Fernbedienung im Kontrollzentrum, einschließlich unterstützter Texteingabe |
| Add-ons | Installiert/Entdecken, Paketdetails, Einrichtung, Aktivierung und Android-bestätigte Installation/Deinstallation |
| Mac-Editor | Bearbeiten der tatsächlichen Oberfläche, Vorschau, lokale Zwischenstände und vorbereitete TV-Versionen |

Neu gegenüber dem ursprünglichen Projekt sind insbesondere der Android-Host, der Mac-Editor, Spotify Connect über die Spotify Web API, die iPhone-Erweiterung und die Paketverwaltung. Die ursprüngliche Tizen-Implementierung und ihre Dokumentation bleiben als Grundlage erhalten.

## Oberfläche ausprobieren

Voraussetzungen: Node.js 22.13+ aus der 22-LTS-Reihe oder Node.js 24+, Python 3.10+.

```sh
git clone https://github.com/AntoinS/CleanTV.git
cd CleanTV
npm ci
npm run dev:public
```

Öffne [localhost:4795/home/](http://localhost:4795/home/). Pfeiltasten, Enter und Escape bedienen die Vorschau. Gerätefunktionen werden dort nicht als echte TV-Aktionen ausgeführt. Enthalten sind die öffentlichen Schriftarten Manrope, Geist und Newsreader.

## Am Mac gestalten

Starte **CleanTV Editor.command** per Doppelklick oder `npm run editor`. Der bisherige Starthelfer `CleanFrame Editor.command` bleibt kompatibel.

- Elemente auswählen, verschieben und vergrößern; Position, Schrift, Farben, Abstände und Materialeigenschaften bearbeiten.
- Hilfslinien, Raster, Einrasten sowie Abstandsmessung und Padding/Margin verwenden.
- Bearbeiten und Ausprobieren trennen; Fernbedienungsnavigation direkt in der Vorschau testen.
- Rückgängig/Wiederholen, benannte Zwischenstände und automatisches lokales Speichern nutzen.
- Den aktuellen TV-Stand importieren, Verbindungsstatus lesen und zwischen Entwurf und Liveansicht wechseln.
- Add-ons simulieren und lesende TV-Diagnose öffnen. Eine lokal eingerichtete Codex-Anbindung unterstützt Änderungen im Chat.
- Einen festen Stand aus Quellcode, Design und geänderten Einstellungen für eine geprüfte APK vorbereiten. Es erfolgt keine automatische Übertragung auf den TV.

Vorschauen unterstützen 720p, 1080p und 4K. **4K-Vorschau bedeutet nicht automatisch 4K-Ausgabe auf dem Fernseher.** Der geprüfte Philips liefert eine 1080p-Systemoberfläche auf einem 4K-Panel. Fotos erhalten durch Hochskalierung keine zusätzlichen Details. Die Liveansicht hängt von TV-Aufnahme und Netzwerk ab; konstante 30 oder 60 fps sind nicht zugesichert.

## Auf Android TV verwenden

Die bisherige Geräteprüfung erfolgte auf einem Philips 58PUS7304/12 mit Android 12 und 32-Bit-ARM. Andere Geräte sind nicht pauschal freigegeben. Der Android-Host benötigt mindestens API 31; der native Remote-Build ist derzeit für armeabi-v7a vorgesehen.

Beginne mit der [Android-Anleitung](android/README.md). App-Installation und Einrichtung als Standard-Home sind getrennte Schritte. Sichere vorher APK, Einstellungen und die ursprüngliche Home-Konfiguration. Behalte für eigene Builds denselben Signierschlüssel, wenn spätere Updates vorhandene Daten übernehmen sollen.

Die Samsung-Anleitung beschreibt ausschließlich den ursprünglichen Tizen-Weg und ist keine Android-Installationsanleitung.

## Add-ons und Updates

Erweiterungen werden im separaten öffentlichen [CleanTV Add-on-Repository](https://github.com/AntoinS/cleanframe-addons) gepflegt. Die Adresse bleibt aus Kompatibilitätsgründen `cleanframe-addons`.

CleanTV 0.8.3 ergänzt blaue Update-Markierungen, getrennte installierte/verfügbare Versionsangaben und **Alle aktualisieren**. Updates werden nacheinander verarbeitet; Android bestätigt jedes APK-Update. Ein Abbruch oder Fehler stoppt die restliche Reihe. Ein neuer Katalogeintrag ändert weder den Aktivierungsstatus noch die Deinstallierbarkeit der installierten Version. Einstellungen und Kopplungen bleiben bei Updates erhalten; eine vollständige Deinstallation entfernt dagegen die Daten der jeweiligen nativen App.

Ab **0.9.0** zeigt die Versionsanzeige unten rechts in den Einstellungen die installierte CleanTV-Version. Sie öffnet eine Softwareupdate-Seite mit blauem Update-Punkt, Versionsvergleich und Download. Android bestätigt das Update; Einstellungen und Anmeldungen bleiben erhalten.

Spotify-Anmeldung und Web-API-Einrichtung werden in den Add-on-Einstellungen verwaltet. Für geräteübergreifende Steuerung gelten die Voraussetzungen der Spotify API und des verwendeten Kontos.

**iPhone Remote bleibt experimentell.** Kopplung, erneute Verbindung, Navigation, Lautstärke, Play/Pause sowie Spotify-Texteingabe einschließlich Umlauten, Emoji, Löschen und Suchen wurden am Referenzgerät bestätigt. Vollständige Standby-/Neustarttests, Netflix und weitere appübergreifende Eingabefälle sind noch offen. Siri, Mikrofonübertragung und AirPlay-Streaming gehören nicht dazu. Details stehen im [Remote-README](https://github.com/AntoinS/cleanframe-addons/blob/main/addons/apple-remote/README.md).

## Entwickeln und beitragen

Siehe [CONTRIBUTING.md](CONTRIBUTING.md), [AGENTS.md](AGENTS.md), [Entwicklung](docs/development.md), [Add-on-API](docs/addons.md) und [Versionen und Releases](docs/releases.md). Die TV-Oberfläche bleibt ES5-kompatibel. Änderungen müssen Fernbedienungsfokus, gespeicherte Einstellungen und bestehende Paketidentitäten erhalten.

```sh
npm run check
npm run check:tools
CLEANFRAME_PUBLIC_FONTS=1 npm run check:browser
npm run check:public
```

Android-Builds und Gerätetests sind zusätzliche Prüfungen. Private Schlüssel, Kontodaten, Gerätekonfiguration und Editor-Entwürfe gehören nicht ins Repository. Neue Releases erhalten unveränderliche Tags und Prüfsummen; vorherige Versionen bleiben als Rückweg verfügbar.

## Herkunft und Lizenz

Das ursprüngliche CleanFrame stammt von **nthnclrk / Nathan**. CleanTV führt dieses Projekt mit Android-, Editor- und Add-on-Erweiterungen fort. Originale Copyright-Hinweise bleiben in [LICENSE](LICENSE) erhalten. Verwendete Bibliotheken, Schriftarten und weitere Komponenten sind in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) und den jeweiligen Add-on-Hinweisen dokumentiert.
