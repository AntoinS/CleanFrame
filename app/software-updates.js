/* Software status stays separate from user preferences and add-on configuration. */
(function () {
  'use strict';
  var state = { current: { name: 'CleanTV', version: '—' }, busy: false, error: '', checkedAt: 0 }, lastCheck = 0;
  var de = {
    'Software update': 'Softwareupdate', 'Installed version': 'Installierte Version', 'Available version': 'Verfügbare Version',
    'Check for updates': 'Nach Updates suchen', 'Download and install': 'Herunterladen und installieren', 'Build': 'Build',
    'Up to date': 'Auf dem neuesten Stand', 'Update available': 'Update verfügbar', 'Not checked yet': 'Noch nicht geprüft',
    'Checking or downloading…': 'Wird geprüft oder heruntergeladen…', 'Confirm in Android': 'In Android bestätigen',
    'Update installed': 'Update installiert', 'Update cancelled. Your existing version and settings are retained.': 'Update abgebrochen. Deine bisherige Version und Einstellungen bleiben erhalten.',
    'Could not check for updates. Try again.': 'Updates konnten nicht geprüft werden. Bitte erneut versuchen.',
    'The download channel has not been published yet.': 'Der Downloadkanal wurde noch nicht veröffentlicht.',
    'The update could not be installed. Your existing version is retained.': 'Das Update konnte nicht installiert werden. Deine bisherige Version bleibt erhalten.',
    'The installed update could not be verified.': 'Das installierte Update konnte nicht bestätigt werden.',
    'Allow installation in Android, then try again.': 'Erlaube die Installation in Android und versuche es erneut.',
    'Finish the add-on operation first.': 'Schließe zuerst den Vorgang bei den Add-ons ab.',
    'This update requires a newer Android version.': 'Dieses Update benötigt eine neuere Android Version.',
    'Settings, apps, sign-ins and the selected Home remain unchanged. Android confirms the installation.': 'Einstellungen, Apps, Anmeldungen und der gewählte Startbildschirm bleiben erhalten. Android bestätigt die Installation.',
    'After installation, press Home to open the updated interface.': 'Nach der Installation öffnet die Home Taste die aktualisierte Oberfläche.',
    'Simulation only. No TV download or installation.': 'Nur Simulation. Kein Download und keine Installation auf dem TV.'
  };
  function text(language, value) { return language === 'de' && de[value] || value; }
  function read() {
    if (FrameDevice.software) try { var result = FrameDevice.software({ action: 'state' }); if (result.current) state = result; else state.error = 'state'; } catch (failure) { state.error = 'state'; }
    return state;
  }
  function pending() { var status = (state.pending || {}).status; return status === 'awaiting-confirmation' || status === 'checking'; }
  function request(action) {
    if (!FrameDevice.software) return;
    try { FrameDevice.software({ action: action }); read(); } catch (failure) { state.error = 'check'; }
  }
  window.CleanFrameSoftware = {
    state: read, text: text, request: request,
    check: function () { read(); if (!state.busy && !pending() && Date.now() - lastCheck > 900000) { lastCheck = Date.now(); request('refresh'); } },
    footer: function () { read(); return state.current.name + ' ' + state.current.version; },
    sections: function (language) {
      read(); var blocked = state.busy || pending(), items = [{ action: 'software.refresh', label: text(language, 'Check for updates'), disabled: blocked || !FrameDevice.software }];
      if (state.updateAvailable) items.push({ action: 'software.install', label: text(language, 'Download and install'), disabled: blocked || state.addonsBusy || state.compatible === false });
      return [{ id: 'software-actions', horizontal: true, items: items }];
    },
    status: function (language) {
      var error = { 'not-published': 'The download channel has not been published yet.', cancelled: 'Update cancelled. Your existing version and settings are retained.', failed: 'The update could not be installed. Your existing version is retained.', install: 'The update could not be installed. Your existing version is retained.', verify: 'The installed update could not be verified.' };
      if (state.error) return text(language, error[state.error] || 'Could not check for updates. Try again.');
      if ((state.pending || {}).status === 'permission-required') return text(language, 'Allow installation in Android, then try again.');
      if (pending()) return text(language, 'Confirm in Android');
      if (state.busy) return text(language, 'Checking or downloading…');
      if (state.result === 'installed' && !state.updateAvailable) return text(language, 'Update installed');
      return text(language, state.updateAvailable ? 'Update available' : state.checkedAt ? 'Up to date' : 'Not checked yet');
    }
  };
  window.addEventListener('cleanframe:software', function (event) { if (event.detail && event.detail.current) state = event.detail; });
}());
