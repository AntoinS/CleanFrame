(function () {
  'use strict';
  var api = window.CleanFramePair, digits = [0, 0, 0, 0], submitted = false, language = 'de';
  try { language = JSON.parse(localStorage.getItem('cleanframe.home.v1') || '{}').language || 'de'; } catch (ignore) { /* Default language. */ }
  function tr(de, en) { return language === 'de' ? de : en; }
  function el(id) { return document.getElementById(id); }
  el('title').textContent = tr('Fernseher verbinden', 'Connect television');
  el('hint').textContent = tr('Gib den Philips Code ein: Links/Rechts wählt eine Stelle, Oben/Unten ändert die Ziffer.', 'Enter the Philips code: Left/Right selects a digit, Up/Down changes it.');
  el('cancel').textContent = tr('Zurück', 'Back'); el('confirm').textContent = tr('Bestätigen', 'Confirm');
  var buttons = Array.prototype.slice.call(document.querySelectorAll('button'));
  buttons[0].focus();
  buttons.forEach(function (button, i) {
    button.onclick = function () {
      if (i < 4) { if (!submitted) { digits[i] = (digits[i] + 1) % 10; button.textContent = digits[i]; } return; }
      if (!api) { el('status').textContent = tr('Vorschau · kein TV-Zugriff', 'Preview · no TV access'); return; }
      if (i === 4) { api.close(); return; }
      if (submitted) return;
      try { var result = JSON.parse(api.submit(digits.join(''))); if (result.error) throw new Error(); submitted = true; el('confirm').disabled = true; el('status').textContent = tr('Code wird geprüft …', 'Checking code …'); }
      catch (error) { el('status').textContent = tr('Anfrage fehlgeschlagen. Erneut versuchen.', 'Request failed. Please retry.'); }
    };
  });
  document.addEventListener('keydown', function (event) {
    var index = buttons.indexOf(document.activeElement), key = event.keyCode;
    if (key === 27 || key === 8) { event.preventDefault(); if (api) api.close(); return; }
    if (index < 4 && key >= 48 && key <= 57 && !submitted) { event.preventDefault(); digits[index] = key - 48; buttons[index].textContent = digits[index]; buttons[Math.min(5, index + 1)].focus(); return; }
    if (index < 4 && (key === 38 || key === 40)) { event.preventDefault(); if (!submitted) { digits[index] = (digits[index] + (key === 38 ? 1 : 9)) % 10; buttons[index].textContent = digits[index]; } return; }
    if (key === 37 || key === 39 || key === 38 || key === 40) { event.preventDefault(); buttons[Math.max(0, Math.min(5, index + (key === 37 || key === 38 ? -1 : 1)))].focus(); }
  });
  window.setInterval(function () {
    if (!api || document.hidden) return;
    try {
      var state = JSON.parse(api.state()), philips = state.philips || {};
      if (!submitted && philips.state === 'pairing' && philips.pairingSecondsRemaining > 0) el('status').textContent = tr('Code gültig: ', 'Code valid: ') + philips.pairingSecondsRemaining + ' s';
      if (submitted && philips.state === 'connected') { api.close(); return; }
      if (submitted && (philips.hasError || state.error)) { submitted = false; el('confirm').disabled = false; el('status').textContent = tr('Code nicht bestätigt. Prüfe den Code und versuche es erneut.', 'Code not confirmed. Check it and try again.'); }
      if (state.enabled === false || state.codeEntryAvailable === false && philips.state !== 'connecting') { el('confirm').disabled = true; el('status').textContent = tr('Kopplung beendet. Starte sie in den Einstellungen erneut.', 'Pairing ended. Restart it in settings.'); }
    } catch (ignore) { el('status').textContent = tr('Verbindung nicht erreichbar.', 'Connection unavailable.'); }
  }, 1000);
}());
