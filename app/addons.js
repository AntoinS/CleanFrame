/* Declarative add-on API. Native storage/downloads; no downloaded executable code. */
(function () {
  'use strict';
  var state = { installed: { spotify: { id: 'spotify', name: 'Spotify', kind: 'spotify', version: '1.0.0', enabled: true } }, catalog: [], busy: false, error: '' };
  function read() { if (FrameDevice.addons) { try { state = FrameDevice.addons({ action: 'state' }); } catch (error) { state.error = error.message; } } return state; }
  var de = {
    'Update all': 'Alle aktualisieren', 'Checking status…': 'Status wird geprüft…', 'Status unavailable': 'Status nicht verfügbar', 'Activation': 'Aktivierung', 'Available version': 'Verfügbare Version', 'Updates completed': 'Updates abgeschlossen', 'Updating add-ons': 'Add-ons werden aktualisiert', 'Settings and pairings are retained. Android confirms each app update.': 'Einstellungen und Kopplungen bleiben erhalten. Android bestätigt jedes App Update.',
    'Enabled': 'Aktiviert', 'Disabled': 'Deaktiviert', 'Ready': 'Bereit', 'Not connected': 'Nicht verbunden',
    'Update available': 'Update verfügbar', 'Available': 'Verfügbar', 'Set up': 'Einrichten',
    'No installed add-ons': 'Noch keine Add-ons installiert', 'Find extensions in Discover.': 'Erweiterungen findest du unter Entdecken.',
    'No catalog available': 'Kein Katalog verfügbar', 'Refresh the catalog to see approved extensions.': 'Aktualisiere den Katalog, um freigegebene Erweiterungen zu sehen.',
    'Local network': 'Lokales Netzwerk', 'Foreground service': 'Hintergrunddienst',
    'Version': 'Version', 'Provider': 'Anbieter', 'License': 'Lizenz', 'Permissions': 'Freigaben',
    'Not provided': 'Nicht angegeben', 'Confirm the operation in Android.': 'Bitte den Vorgang in Android bestätigen.', 'No additional permissions': 'Keine zusätzlichen Freigaben',
    'Installation': 'Installation', 'Android connection': 'Android Verbindung', 'iPhone pairing': 'iPhone Kopplung',
    'Connected': 'Verbunden', 'Paired': 'Gekoppelt', 'Not paired': 'Nicht gekoppelt', 'Not installed': 'Nicht installiert',
    'Extension unavailable': 'Erweiterung nicht verfügbar', 'The package is no longer in the catalog.': 'Das Paket ist nicht mehr im Katalog.',
    'Playback, account and widget appearance': 'Wiedergabe, Konto und Widget Darstellung',
    'Native service': 'Nativer Dienst', 'Home widget': 'Widget auf dem Startbildschirm',
    'Choose an extension': 'Erweiterung auswählen', 'Manage extension': 'Erweiterung verwalten',
    'Android will ask you to confirm removal. Pairings stored by this add-on are removed.': 'Android fragt vor dem Entfernen nach. Gespeicherte Kopplungen dieser Erweiterung werden gelöscht.'
  };
  function installedVersion(item, runtime) {
    var result = {}, actual = runtime && runtime[item.id];
    Object.keys(item).forEach(function (key) { result[key] = item[key]; });
    if (item.kind === 'native' && actual && actual.installedVersion) {
      result.version = actual.installedVersion;
      result.versionCode = actual.installedVersionCode;
    }
    return result;
  }
  function newer(candidate, current) {
    if (!candidate || !current || candidate.id !== current.id || candidate.kind !== current.kind) return false;
    if (candidate.kind === 'native' && candidate.versionCode && current.versionCode) return candidate.versionCode > current.versionCode;
    if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(candidate.version) || !/^[0-9]+\.[0-9]+\.[0-9]+$/.test(current.version)) return false;
    var a = candidate.version.split('.'), b = current.version.split('.');
    for (var i = 0; i < 3; i++) { if (+a[i] !== +b[i]) return +a[i] > +b[i]; }
    return false;
  }
  function updates(value) {
    return value.catalog.filter(function (item) { var current = value.installed[item.id]; return current && newer(item, installedVersion(current, value.runtime)); });
  }
  window.CleanFrameAddons = {
    text: function (language, value) { return language === 'de' && de[value] ? de[value] : window.CleanFrameI18n.text(language, value); },
    state: read, newer: newer, installedVersion: installedVersion, updates: function (value) { return updates(value || state); },
    enabled: function (id) { return !!(state.installed[id] && state.installed[id].enabled !== false); },
    request: function (action, id) {
      if (!FrameDevice.addons) { state.error = 'Downloads are available on Android TV.'; return; }
      try { FrameDevice.addons({ action: action, id: id || '' }); read(); } catch (error) { state.error = error.message; }
    },
    render: function (visible, language) {
      var root = document.getElementById('addonWidgets');
      if (!root) { root = document.createElement('div'); root.id = 'addonWidgets'; document.getElementById('home').appendChild(root); }
      root.hidden = !visible;
      if (!visible) return;
      while (root.firstChild) root.removeChild(root.firstChild);
      Object.keys(state.installed).filter(function (id) { var p = state.installed[id]; return p.enabled !== false && p.kind !== 'spotify' && p.kind !== 'native'; }).slice(0, 3).forEach(function (id) {
        var item = state.installed[id], card = document.createElement('section'), title = document.createElement('h3'), body = document.createElement('p');
        card.className = 'addon-widget'; card.setAttribute('data-addon', id); title.textContent = item.name;
        body.textContent = item.kind === 'clock' ? new Date().toLocaleTimeString(language, { hour: '2-digit', minute: '2-digit', timeZone: item.timeZone || undefined }) : item.text || '';
        card.appendChild(title); card.appendChild(body); root.appendChild(card);
      });
    }
  };
  read();
  window.addEventListener('cleanframe:addons', function (event) { state = event.detail; });
}());
