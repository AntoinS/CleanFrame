"""Read-only, fixed-target ADB status and native screenshots for the Mac editor."""
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import threading
import time


class Television:
    def __init__(self, store):
        self.store = store
        self.lock = threading.Lock()
        self.cached = None
        self.captured = 0
        self.png = None
        self.frame = None

    def config(self):
        path = self.store.path / 'tv.json'
        return json.loads(path.read_text()) if path.exists() else None

    def adb(self, args, timeout=8, binary=False):
        config = self.config()
        if not config or not Path(config['adb']).is_file():
            raise ValueError('TV-Verbindung ist auf diesem Mac noch nicht eingerichtet.')
        result = subprocess.run([config['adb'], '-s', config['serial'], *args], capture_output=True, timeout=timeout)
        if result.returncode:
            raise ValueError(result.stderr.decode(errors='replace').strip()[:300] or 'ADB antwortet nicht.')
        return result.stdout if binary else result.stdout.decode(errors='replace')

    def connect(self):
        config = self.config()
        if not config:
            raise ValueError('TV-Verbindung ist noch nicht eingerichtet.')
        # The browser cannot supply a host, command or path.
        subprocess.run([config['adb'], 'connect', config['serial']], capture_output=True, timeout=10, check=True)
        self.cached = None
        return self.status()

    def read_draft(self):
        """Capture the running UI without launching it or reading account storage."""
        from editor_server import ROOT
        config = self.config()
        if not config:
            raise ValueError('TV-Verbindung ist auf diesem Mac noch nicht eingerichtet.')
        pid = self.adb(['shell', 'pidof', config['package']]).strip().split()
        if not pid or not pid[0].isdigit():
            raise ValueError('Bitte CleanFrame über die Home-Taste am Fernseher öffnen und erneut versuchen.')
        package = self.adb(['shell', 'dumpsys', 'package', config['package']])
        version = re.search(r'versionName=(\S+)', package)
        if not version:
            raise ValueError('Die installierte CleanFrame-Version konnte nicht gelesen werden.')
        port = self.adb(['forward', 'tcp:0', 'localabstract:webview_devtools_remote_' + pid[0]]).strip()
        if not port.isdigit():
            raise ValueError('Der Lesekanal zum Fernseher konnte nicht geöffnet werden.')
        try:
            result = subprocess.run(['node', str(ROOT / 'scripts/editor-tv-read.mjs'), port],
                                    capture_output=True, timeout=25, cwd=ROOT)
            if result.returncode:
                raise ValueError('Der TV-Stand konnte nicht gelesen werden. Bitte CleanFrame am TV öffnen und erneut versuchen.')
            snapshot = json.loads(result.stdout)
            snapshot['version'] = version.group(1)
            return snapshot
        finally:
            self.adb(['forward', '--remove', 'tcp:' + port])

    def addon_diagnostics(self):
        """Fixed read-only query; never start/bind an add-on to obtain diagnostics."""
        package = 'com.cleanframe.addon.appleremote'
        result = dict(package=package, checkedAt=time.time(), installed=False, status=None)
        try:
            info = self.adb(['shell', 'dumpsys', 'package', package])
            version = re.search(r'versionName=(\S+)', info)
            if not version:
                result['message'] = 'Das experimentelle Fernbedienungs-Add-on ist nicht installiert.'
                return result
            result.update(installed=True, version=version.group(1))
            dump = self.adb(['shell', 'dumpsys', 'activity', 'service', package + '/.RemoteService'])
            if len(dump) > 100000:
                raise ValueError('Diagnoseausgabe überschreitet die erlaubte Größe.')
            # Service.dump produces exactly one bounded redacted JSON line.
            for line in dump.splitlines():
                if not line.strip().startswith('{'):
                    continue
                value = json.loads(line)
                if value.get('package') != package or value.get('api') != 1:
                    continue
                allowed = ['api', 'package', 'version', 'enabled', 'running']
                safe = {key: value[key] for key in allowed if key in value}
                if isinstance(value.get('automaticPairingAccess'), bool): safe['automaticPairingAccess'] = value['automaticPairingAccess']
                if type(value.get('automaticPairings')) is int and 0 <= value['automaticPairings'] <= 100000: safe['automaticPairings'] = value['automaticPairings']
                if value.get('backend') in ['philips', 'google']:
                    safe['backend'] = value['backend']
                for section in ['android', 'apple', 'keyboard', 'philips']:
                    safe[section] = {k: v for k, v in value.get(section, {}).items()
                                     if k in ['state', 'ready', 'connected', 'hasError', 'pairedDevices', 'enabled', 'selected', 'paired', 'certificatePinned', 'reason']
                                     and isinstance(v, (str, bool, int)) and len(str(v)) <= 100}
                safe['events'] = [{k: v for k, v in event.items()
                                  if k in ['seq', 'at', 'stage', 'action', 'result', 'type', 'length', 'id', 'time', 'kind']
                                  and isinstance(v, (str, bool, int, float)) and len(str(v)) <= 100}
                                 for event in value.get('events', [])[-200:] if isinstance(event, dict)]
                result['status'] = safe
                break
            if result['status'] is None:
                result['message'] = 'Dienst ist nicht gestartet. Die Diagnose startet ihn nicht automatisch.'
        except (ValueError, OSError, subprocess.SubprocessError) as error:
            result['message'] = str(error)[:300]
        return result

    def status(self):
        with self.lock:
            if self.cached and time.time() - self.cached['checkedAt'] < 10:
                return self.with_release(self.cached.copy())
            config = self.config()
            result = dict(connected=False, configured=bool(config), checkedAt=time.time(), label=(config or {}).get('label', 'Fernseher'))
            try:
                if self.adb(['get-state']).strip() != 'device':
                    raise ValueError('Der TV ist noch nicht für ADB freigegeben.')
                result['connected'] = True
                result['address'] = config['serial']
                package = self.adb(['shell', 'dumpsys', 'package', config['package']])
                version = re.search(r'versionName=(\S+)', package)
                result['version'] = version.group(1) if version else None
                result['installed'] = bool(version)
                size = self.adb(['shell', 'wm', 'size'])
                sizes = re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)', size)
                result['uiResolution'] = [int(x) for x in sizes[-1]] if sizes else None
                display = self.adb(['shell', 'dumpsys', 'display'])
                panel = re.search(r'DisplayDeviceInfo\{[^\n]*?, (\d+) x (\d+),', display)
                result['panelResolution'] = [int(x) for x in panel.groups()] if panel else None
                result['message'] = 'Verbunden' if version else 'Verbunden, CleanFrame wurde nicht gefunden.'
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                result['connected'] = False
                result['message'] = str(error)
            self.cached = result
            return self.with_release(result.copy())

    def with_release(self, result):
        result.update(ready=False, releaseState='missing', releaseMessage='Zuerst den Entwurf für TV vorbereiten.')
        release = self.store.path / 'releases' / str(self.store.job)
        if not (release / 'status.json').exists():
            return result
        status = json.loads((release / 'status.json').read_text())
        result['releaseState'] = status['state']
        if status['state'] != 'ready':
            result['releaseMessage'] = status.get('message', 'Vorbereitung läuft.')
            return result
        try:
            manifest = json.loads((release / 'manifest.json').read_text())
            frozen = json.loads((release / 'project.json').read_text())
            current, _ = self.store.read()
            from editor_server import fingerprint
            fresh = (release / 'source-fingerprint.txt').exists() and (release / 'source-fingerprint.txt').read_text() == fingerprint()
            fresh = fresh and all(current[k] == frozen[k] for k in ['design', 'preferences', 'basePrefs'])
            result['preparedVersion'] = manifest['version']['name']
            if not fresh:
                result.update(releaseState='stale', releaseMessage='Entwurf oder Quellcode geändert. TV-Version erneut vorbereiten.')
            else:
                apk = release / manifest['apk']
                valid = apk.parent == release and hashlib.sha256(apk.read_bytes()).hexdigest() == manifest['sha256']
                result['ready'] = bool(valid and result.get('connected') and result.get('installed'))
                result['releaseMessage'] = 'Geprüfte APK bereit. Sicherung und Einstellungsabgleich erfolgen vor der Übertragung.' if valid else 'APK-Prüfsumme stimmt nicht. Erneut vorbereiten.'
        except (OSError, KeyError, ValueError):
            result['releaseMessage'] = 'Vorbereitete Version ist unvollständig. Erneut vorbereiten.'
        return result

    def screenshot(self):
        with self.lock:
            if self.png and time.time() - self.captured < 2.5:
                return self.png, self.frame
            raw = self.adb(['exec-out', 'screencap', '-p'], timeout=12, binary=True)
            if len(raw) < 24 or not raw.startswith(b'\x89PNG\r\n\x1a\n'):
                raise ValueError('Der TV hat keine gültige Bildschirmaufnahme geliefert.')
            width, height = struct.unpack('>II', raw[16:24])
            self.png, self.captured = raw, time.time()
            self.frame = dict(width=width, height=height, capturedAt=self.captured)
            return raw, self.frame
