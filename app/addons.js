/* Declarative add-on API. Native storage/downloads; no downloaded executable code. */
(function () {
  'use strict';
  var state = { installed: { spotify: { id: 'spotify', name: 'Spotify', kind: 'spotify', version: '1.0.0', enabled: true } }, catalog: [], busy: false, error: '' };
  function read() { if (FrameDevice.addons) { try { state = FrameDevice.addons({ action: 'state' }); } catch (error) { state.error = error.message; } } return state; }
  window.CleanFrameAddons = {
    state: read,
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
      Object.keys(state.installed).filter(function (id) { var p = state.installed[id]; return p.enabled !== false && p.kind !== 'spotify'; }).slice(0, 3).forEach(function (id) {
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
