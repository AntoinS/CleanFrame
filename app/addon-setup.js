/* Ephemeral setup state. No credentials, pairing data or QR session are persisted. */
(function () {
  'use strict';
  var remote = {}, link = {}, issue = '';
  function tr(language, de, en) { return language === 'de' ? de : en; }
  function row(id, title, items, reason) { return { id: id, title: title, horizontal: true, items: items, reason: reason || '' }; }
  function action(op, label, disabled) { return { action: 'remote.' + op, label: label, disabled: !!disabled }; }
  function remaining(expires) { var seconds = Math.max(0, Math.ceil(expires - Date.now() / 1000)); return Math.floor(seconds / 60) + ':' + ('0' + seconds % 60).slice(-2); }
  window.CleanFrameAddonSetup = {
    remote: function () { return remote; }, link: function () { return link; }, error: function () { return issue; }, remaining: remaining,
    poll: function (group) {
      try {
        if (group === 'remote') remote = FrameDevice.addons({ action: 'remoteState' });
        if (group === 'spotify-link') link = FrameDevice.spotify.link('state');
        issue = '';
      } catch (error) { issue = group === 'remote' ? 'remote' : 'spotify'; }
    },
    signIn: function () { link = { phase: 'preparing' }; issue = ''; try { FrameDevice.spotify.link('begin'); } catch (error) { issue = 'spotify'; } },
    close: function () { try { if (FrameDevice.spotify.link) FrameDevice.spotify.link('cancel'); } catch (ignore) { /* No native session in offline preview. */ } link = {}; },
    command: function (op, id) {
      var command = { api: 1, op: op }; if (id) command.id = id;
      try { FrameDevice.addons({ action: 'remote', id: 'apple-remote', command: command }); issue = ''; }
      catch (error) { issue = 'remote'; }
    },
    sections: function (group, language) {
      function t(de, en) { return tr(language, de, en); }
      if (group === 'spotify-link') return [row('spotify-link', t('Spotify verbinden', 'Connect Spotify'), [{ action: 'spotify.retry-link', label: t('Neuen QR-Code erstellen', 'Create a new QR code') }])];
      var enabled = remote.enabled === true, philips = remote.philips || {}, keyboard = remote.keyboard || {}, apple = remote.apple || {}, devices = apple.devices || [];
      return [
        row('receiver', t('Fernbedienung', 'Remote control'), [action(enabled ? 'disable' : 'enable', t(enabled ? 'Deaktivieren' : 'Aktivieren', enabled ? 'Disable' : 'Enable'), remote.setupInterface !== 1)], t('Bediene den TV mit der Apple TV Fernbedienung im Kontrollzentrum.', 'Control your TV with Apple TV Remote in Control Center.')),
        row('automatic', t('Automatische Einrichtung', 'Automatic setup'), [action('pairing-access', t(remote.automaticPairingAccess ? 'Freigabe verwalten' : 'Einmalige Freigabe erteilen', remote.automaticPairingAccess ? 'Manage permission' : 'Grant permission once'))], t(remote.automaticPairingAccess ? 'Der Philips-Code wird bei der Einrichtung automatisch bestätigt. Bestehende Kopplungen bleiben erhalten.' : 'Aktiviere in Android „CleanFrame · Automatische TV-Einrichtung“. Nur der Philips-Kopplungscode wird kurz gelesen; keine Aufzeichnung.', remote.automaticPairingAccess ? 'The Philips code is confirmed automatically during setup. Existing pairings are retained.' : 'Enable CleanFrame automatic TV setup in Android. Only the Philips pairing code is read briefly; nothing is recorded.')),
        row('philips', t('1 · Fernseher', '1 · Television'), [action('philipsPairBegin', t(philips.paired ? 'Erneut verbinden' : 'Fernseher verbinden', philips.paired ? 'Reconnect television' : 'Connect television'), !enabled)], t(remote.automaticPairingAccess ? 'Die Verbindung wird automatisch hergestellt. Du brauchst keinen Code abzutippen.' : 'Manuelle Alternative: Der Philips-Code ist 30 Sekunden gültig. Die Eingabe erscheint links daneben.', remote.automaticPairingAccess ? 'The connection is established automatically. No code needs to be typed.' : 'Manual alternative: the Philips code is valid for 30 seconds. Input appears on its left.')),
        row('iphone', t('2 · iPhone koppeln', '2 · Pair iPhone'), [action('applePairBegin', t('Neuen iPhone-Code erzeugen', 'Generate iPhone code'), !enabled)], t('Wähle „CleanFrame TV“ in der iPhone Fernbedienung und gib den Code rechts ein.', 'Select “CleanFrame TV” in the iPhone remote and enter the code on the right.')),
        row('keyboard', t('3 · Texteingabe', '3 · Text input'), [action('keyboard-enable', t('Tastatur freigeben', 'Enable keyboard')), action('keyboard-select', t('Tastatur auswählen', 'Select keyboard'))], t(keyboard.selected ? 'CleanFrame Tastatur ausgewählt. Passwörter und App-eigene Tastaturen sind ausgenommen.' : 'Gib „CleanFrame iPhone Keyboard“ in Android frei und wähle sie aus.', keyboard.selected ? 'CleanFrame keyboard selected. Passwords and custom app keyboards are excluded.' : 'Enable “CleanFrame iPhone Keyboard” in Android and select it.')),
        row('devices', t('Gekoppelte Geräte', 'Paired devices'), devices.map(function (device, i) { return action('forget.' + i, (device.name || 'iPhone').slice(0, 80) + ' · ' + t('Entfernen', 'Remove')); }), devices.length ? '' : t('Noch kein iPhone gekoppelt.', 'No paired iPhone yet.'))
      ];
    },
    copy: function (language) {
      function t(de, en) { return tr(language, de, en); }
      var apple = remote.apple || {}, philips = remote.philips || {}, keyboard = remote.keyboard || {}, active = remote.enabled && /^[0-9]{4}$/.test(apple.pin || '') && apple.expiresAt > Date.now() / 1000;
      return {
        code: active ? apple.pin : '—', time: active ? remaining(apple.expiresAt) : t(apple.expiresAt ? 'Code abgelaufen' : 'Kein Code aktiv', apple.expiresAt ? 'Code expired' : 'No active code'),
        television: t(philips.state === 'connected' ? 'Verbunden' : remote.enabled && philips.paired && !philips.hasError ? 'Bereit' : 'Nicht verbunden', philips.state === 'connected' ? 'Connected' : remote.enabled && philips.paired && !philips.hasError ? 'Ready' : 'Not connected'),
        iphone: t(apple.connected || apple.state === 'connected' ? 'Verbunden' : apple.pairedDevices ? 'Gekoppelt' : 'Nicht gekoppelt', apple.connected || apple.state === 'connected' ? 'Connected' : apple.pairedDevices ? 'Paired' : 'Not paired'),
        keyboard: t(keyboard.selected ? 'Ausgewählt' : 'Nicht ausgewählt', keyboard.selected ? 'Selected' : 'Not selected'),
        error: issue ? t('Verbindung nicht erreichbar. Bitte erneut versuchen.', 'Connection unavailable. Please retry.') : (remote.serviceError ? t('Empfänger konnte nicht starten. Bitte das Add-on aktualisieren und erneut aktivieren.', 'Receiver could not start. Update the add-on and enable it again.') : '') || (philips.hasError ? t('TV-Verbindung fehlgeschlagen. Kopplung erneut starten.', 'TV connection failed. Start pairing again.') : ''),
        link: t(({ preparing: 'Anmeldung wird vorbereitet …', idle: 'Anmeldung erneut starten.', pending: 'Scanne den QR-Code mit deinem Handy und melde dich bei Spotify an.', connected: 'Spotify ist verbunden. Du kannst zu den Einstellungen zurückkehren.', expired: 'QR-Code abgelaufen. Erstelle einen neuen Code.', cancelled: 'Anmeldung abgebrochen. Du kannst erneut beginnen.', failed: 'Anmeldung fehlgeschlagen. Bitte erneut versuchen.', unreachable: 'Anmeldeseite nicht erreichbar. Bitte erneut versuchen.' })[link.phase] || 'Anmeldung wird vorbereitet …', ({ preparing: 'Preparing sign in …', idle: 'Please restart sign in.', pending: 'Scan the QR code with your phone and sign in to Spotify.', connected: 'Spotify is connected. You can return to settings.', expired: 'QR code expired. Create a new code.', cancelled: 'Sign in cancelled. You can try again.', failed: 'Sign in failed. Please try again.', unreachable: 'Sign in unavailable. Please try again.' })[link.phase] || 'Preparing sign in …')
      };
    }
  };
}());
