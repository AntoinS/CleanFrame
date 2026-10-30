/* Shared layout overrides. The editor and the packaged TV use this same resolver. */
(function () {
  'use strict';
  var loaded = false, data = { version: 1, rules: [] }, context = { view: 'home', layout: 'dock', state: 'compact' };
  var saved = [], entries = [], initialized = false, navigating = false, moved = null, bridge = null;
  var TYPES = ['setting-choice', 'setting-section', 'setting-name', 'setting-description', 'setting-label', 'settings-group', 'settings-group-name', 'settings-group-value', 'tile', 'ph-row', 'gt', 'ut', 'btn', 'label', 'mark', 'ico', 'ed-row', 'eo', 'spotify-button'];
  var PROPS = { width: 'width', height: 'height', fontFamily: 'font-family', fontSize: 'font-size', fontWeight: 'font-weight', lineHeight: 'line-height', letterSpacing: 'letter-spacing', radius: 'border-radius', padding: 'padding', spacing: 'margin', color: 'color', background: 'background-color', opacity: 'opacity' };
  ['paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft', 'marginTop', 'marginRight', 'marginBottom', 'marginLeft', 'gap'].forEach(function (name) { PROPS[name] = name.replace(/[A-Z]/g, function (letter) { return '-' + letter.toLowerCase(); }); });
  var LABELS = { sheet: 'Menühintergrund', sheetBg: 'Hintergrundbild des Menüs', spotifyCompactCover: 'Cover', spotifyCover: 'Cover', spotifyCompactProgressFill: 'Fortschrittsanzeige', spotifyProgressFill: 'Fortschrittsanzeige', caption: 'Uhr und Datum', dock: 'App Dock', phList: 'App Liste', utils: 'Alle Apps und Einstellungen', spotifyPlayer: 'Spotify Player', spotifyCompact: 'Kompakte Ansicht', spotifyExpanded: 'Große Ansicht', spotifyTitle: 'Titel', spotifyArtist: 'Interpret', spotifyCompactTitle: 'Titel', spotifyCompactArtist: 'Interpret', spotifyProgress: 'Fortschritt', appearance: 'Einstellungsseite', appearanceTitle: 'Überschrift', appearanceRows: 'Einstellungen', sheetView: 'Menü', qrRoot: 'QR Anmeldung', qrCode: 'QR Code', qrTitle: 'Überschrift', qrDetail: 'Anleitung', qrPairing: 'Verbindungscode', qrBack: 'Zurück' };
  function rect(el) {
    var target = el.id === 'spotifyPlayer' && context.state === 'compact' ? document.getElementById('spotifyCompact') : el;
    var r = target.getBoundingClientRect(), s = document.getElementById('stage').getBoundingClientRect(), scale = s.width / 1920;
    return { x: (r.left - s.left) / scale, y: (r.top - s.top) / scale, width: r.width / scale, height: r.height / scale };
  }
  function visible(el, structureOnly) {
    var s = window.getComputedStyle(el), p = el;
    if (!el.getClientRects().length || s.visibility === 'hidden' || s.display === 'none') return false;
    if (structureOnly && el.closest('#spotifyPlayer')) {
      if (context.state === 'compact' && el.closest('#spotifyExpanded,.spotify-surface-expanded')) return false;
      if (context.state === 'expanded' && el.closest('#spotifyCompact,.spotify-surface')) return false;
      while (p && p.id !== 'stage') { if (p.hidden) return false; p = p.parentElement; }
      return true;
    }
    while (p && p.id !== 'stage') { if (p.hidden || window.getComputedStyle(p).opacity === '0') return false; p = p.parentElement; }
    return true;
  }
  function type(el) {
    for (var i = 0; i < TYPES.length; i++) if (el.classList.contains(TYPES[i])) return TYPES[i];
    return (el.getAttribute('class') || '').split(/\s+/).filter(function (name) { return name && name.indexOf('is-') !== 0; })[0] || el.tagName.toLowerCase();
  }
  function key(el) {
    var action = el.getAttribute('data-action'), cls, n = 0, prev;
    if (action) return 'action:' + action;
    if (el.id) return el.id;
    if (!el.parentElement || el.id === 'stage') return 'stage';
    cls = el.getAttribute('data-slot') || (el.getAttribute('class') || '').split(/\s+/).filter(function (c) { return c && c.indexOf('is-') !== 0; })[0] || el.tagName.toLowerCase();
    prev = el.previousElementSibling;
    while (prev) { if (prev.tagName === el.tagName && (prev.getAttribute('class') || '').split(' ')[0] === (el.getAttribute('class') || '').split(' ')[0]) n++; prev = prev.previousElementSibling; }
    return key(el.parentElement) + '/' + cls + (n ? ':' + n : '');
  }
  function root() {
    if (context.view === 'qr') return document.getElementById('qrRoot');
    if (context.view.indexOf('appearance.') === 0 || context.view === 'wallpaper') return document.getElementById('appearance');
    if (context.view === 'home') return document.getElementById('home');
    return document.getElementById('sheetView');
  }
  function collect() {
    var base = root(); entries = [];
    if (!base) return;
    var back = document.getElementById('sheet'), decoration = context.view !== 'home' && context.view !== 'qr' && back && !back.hidden ? [back, document.getElementById('sheetBg'), back.querySelector('.sheet-veil')] : [];
    var candidates = decoration.concat([base]).concat(Array.prototype.slice.call(base.querySelectorAll('[id], [data-action], [data-slot], h2, h3, p, span, svg, img, button, section, .setting-options, .setting-slider, .spotify-track, .spotify-copy, .spotify-art, .spotify-times, .spotify-surface, .spotify-surface-expanded, .spotify-compact-art, .spotify-compact-copy')));
    candidates.forEach(function (el) {
      if (!visible(el, true) || /^(path|rect|circle|g)$/i.test(el.tagName) || el.closest('.ph-field,.mat,.ut-lens,#phPlate,#phPlateAlt')) return;
      if (['home', 'track', 'phRows', 'shelf', 'phScene', 'scene'].indexOf(el.id) >= 0) return;
      var id = key(el), action = el.getAttribute('data-action'), parent = el.parentElement, parentId = null;
      while (parent && parent !== base.parentElement) { if (parent.getAttribute('data-design-id')) { parentId = parent.getAttribute('data-design-id'); break; } parent = parent.parentElement; }
      el.setAttribute('data-design-id', id);
      entries.push({ id: id, type: type(el), name: LABELS[el.id] || el.getAttribute('aria-label') || (el.children.length ? '' : el.textContent.trim().slice(0, 48)) || action || el.getAttribute('class') || id, parent: parentId, el: el });
    });
  }
  function matches(rule, entry) {
    return (rule.view === '*' || rule.view === context.view) && (rule.layout === '*' || rule.layout === context.layout) && (rule.state === '*' || rule.state === context.state) && (rule.target === entry.id || rule.target === 'type:' + entry.type);
  }
  function resolve(entry) {
    var result = {}, rules = data.rules.filter(function (rule) { return matches(rule, entry); });
    rules.sort(function (a, b) {
      function weight(r) { return (r.target.indexOf('type:') === 0 ? 0 : 8) + (r.view === '*' ? 0 : 4) + (r.layout === '*' ? 0 : 2) + (r.state === '*' ? 0 : 1); }
      return weight(a) - weight(b);
    });
    rules.forEach(function (rule) { Object.keys(rule.style).forEach(function (name) { result[name] = rule.style[name]; }); });
    return result;
  }
  function remember(el, prop, value) {
    saved.push({ el: el, prop: prop, value: el.style.getPropertyValue(prop), priority: el.style.getPropertyPriority(prop) });
    el.style.setProperty(prop, value);
  }
  function clear() {
    for (var i = saved.length - 1; i >= 0; i--) {
      var s = saved[i];
      if (s.value) s.el.style.setProperty(s.prop, s.value, s.priority); else s.el.style.removeProperty(s.prop);
    }
    saved = [];
  }
  function applyStyle(entry) {
    var el = entry.el, style = resolve(entry), computed = window.getComputedStyle(el), transform = computed.transform;
    Object.keys(PROPS).forEach(function (prop) {
      if (style[prop] === undefined) return;
      if (computed.display === 'inline' && (prop === 'width' || prop === 'height')) remember(el, 'display', 'inline-block');
      remember(el, PROPS[prop], String(style[prop]) + (['color', 'background', 'opacity', 'fontWeight', 'fontFamily'].indexOf(prop) < 0 ? 'px' : ''));
    });
    if (style.offsetX !== undefined || style.offsetY !== undefined) {
      if ('translate' in el.style) remember(el, 'translate', (style.offsetX || 0) + 'px ' + (style.offsetY || 0) + 'px');
      else {
        if (el.id === 'dock') transform = document.getElementById('stage').classList.contains('spotify-focused') ? 'translateY(-216px)' : 'none';
        remember(el, 'transform', 'translate(' + (style.offsetX || 0) + 'px,' + (style.offsetY || 0) + 'px) ' + (transform === 'none' ? '' : transform));
      }
    }
    if (style.width !== undefined || style.height !== undefined) remember(el, 'box-sizing', 'border-box');
    if (style.width !== undefined) { remember(el, 'min-width', '0'); remember(el, 'max-width', 'none'); remember(el, 'flex', '0 0 auto'); }
    if (style.height !== undefined) { remember(el, 'min-height', '0'); remember(el, 'max-height', 'none'); }
    if (el.id === 'dock' && style.radius !== undefined) remember(el.querySelector('.mat'), 'border-radius', style.radius + 'px');
    if (el.id === 'dock' && style.width !== undefined) remember(document.getElementById('shelf'), 'width', Math.max(1, style.width - 32) + 'px');
    if (el.id === 'spotifyPlayer') {
      Array.prototype.forEach.call(el.querySelectorAll('.spotify-surface,.spotify-surface-expanded,.spotify-compact'), function (surface) {
        if (style.radius !== undefined) remember(surface, 'border-radius', style.radius + 'px');
        if (style.height !== undefined && context.state === 'compact' && !surface.classList.contains('spotify-surface-expanded')) remember(surface, 'height', style.height + 'px');
      });
    }
  }
  function place(el, r) {
    if (!el) return;
    var p = el.offsetParent || document.getElementById('stage'), pr = rect(p), sc = (p.getBoundingClientRect().width / (p.offsetWidth || 1)) / (document.getElementById('stage').getBoundingClientRect().width / 1920);
    remember(el, 'transform', 'none'); remember(el, 'left', ((r.x - pr.x) / sc + p.scrollLeft) + 'px'); remember(el, 'top', ((r.y - pr.y) / sc + p.scrollTop) + 'px');
    remember(el, 'width', (r.width / sc) + 'px'); remember(el, 'height', (r.height / sc) + 'px');
  }
  function releaseClipping() {
    // Keep ordinary offscreen rows hidden while allowing a deliberately moved item
    // to leave its original scrolling window. The existing scroll state is retained.
    var clips = [], base = root();
    entries.forEach(function (entry) {
      var style = resolve(entry), parent = entry.el.parentElement;
      if (!style.offsetX && !style.offsetY && style.width === undefined && style.height === undefined) return;
      while (parent && parent !== base) {
        if (parent.matches('#shelf,#phList,.grid-view,.ed-other-view') && clips.indexOf(parent) < 0) clips.push(parent);
        parent = parent.parentElement;
      }
    });
    clips.forEach(function (clip) {
      var cr = rect(clip), ancestor = clip, anchor = null;
      while (ancestor && !anchor) { anchor = entries.filter(function (entry) { return entry.el === ancestor; })[0]; if (!anchor) ancestor = ancestor.parentElement; }
      if (anchor) { var ar = rect(ancestor); cr.x -= ar.x - anchor.baseRect.x; cr.y -= ar.y - anchor.baseRect.y; }
      entries.forEach(function (entry) {
        if (!entry.el.hasAttribute('data-action') || !clip.contains(entry.el)) return;
        var r = entry.baseRect, own = resolve(entry);
        if (!own.offsetX && !own.offsetY && (r.x + r.width <= cr.x || r.x >= cr.x + cr.width || r.y + r.height <= cr.y || r.y >= cr.y + cr.height)) remember(entry.el, 'visibility', 'hidden');
      });
      remember(clip, 'overflow', 'visible'); remember(clip, 'mask-image', 'none'); remember(clip, '-webkit-mask-image', 'none');
    });
  }
  function alignMaterial() {
    if (!geometry()) return;
    var focused = root() && root().querySelector('.is-focus[data-action]'), r, plate;
    var entry = entries.filter(function (e) { return e.el === focused; })[0];
    var textChanged = focused && entries.some(function (e) { var st = resolve(e); return focused.contains(e.el) && (st.fontSize !== undefined || st.fontFamily !== undefined || st.fontWeight !== undefined || st.letterSpacing !== undefined || st.lineHeight !== undefined); });
    var changed = entry && (textChanged || Math.abs(rect(focused).x - entry.baseRect.x) > .1 || Math.abs(rect(focused).y - entry.baseRect.y) > .1 || Math.abs(rect(focused).width - entry.baseRect.width) > .1 || Math.abs(rect(focused).height - entry.baseRect.height) > .1);
    if (context.view === 'home' && focused && changed) {
      var actual = rect(focused), deltaX = actual.x - entry.baseRect.x, deltaY = actual.y - entry.baseRect.y;
      r = actual;
      if (focused.getAttribute('data-z') === 'app') {
        plate = document.getElementById(context.layout === 'dock' ? 'plate' : 'phPlate');
        if (context.layout === 'list') {
          plate = document.querySelector('.ph-plate.is-current') || plate;
          var old = rect(plate); r = { x: old.x + deltaX, y: old.y + deltaY, width: old.width + actual.width - entry.baseRect.width, height: old.height + actual.height - entry.baseRect.height };
          if (textChanged) Array.prototype.forEach.call(focused.querySelectorAll('.ph-name,.mark'), function (part) { if (visible(part)) { var pr = rect(part); r.width = Math.max(r.width, pr.x + pr.width - r.x + 16); r.height = Math.max(r.height, pr.y + pr.height - r.y + 8); } });
          place(document.getElementById('phPlate'), r); place(document.getElementById('phPlateAlt'), r);
        } else place(plate, r);
        var label = document.getElementById('appFocusName');
        if (visible(label)) { var labelRect = rect(label); labelRect.x += deltaX; labelRect.y += deltaY; place(label, labelRect); }
      } else if (focused.getAttribute('data-z') === 'util') {
        place(document.getElementById('utLens'), r); place(document.getElementById('utLensAlt'), r);
        var utilityLabel = document.getElementById('utilFocusLabel');
        if (visible(utilityLabel)) { var utilityRect = rect(utilityLabel); utilityRect.x += deltaX; utilityRect.y += deltaY; place(utilityLabel, utilityRect); }
      }
    }
    // Re-register existing offline material fields with their actual stage position.
    ['phField', 'phFieldAlt', 'utField', 'utFieldAlt'].forEach(function (id) {
      var field = document.getElementById(id); if (!field || !visible(field.parentElement)) return;
      var p = rect(field.parentElement);
      var transformed = window.getComputedStyle(field).transform, scale = transformed !== 'none' && transformed.indexOf('matrix(') === 0 ? parseFloat(transformed.substring(7)) : 1;
      remember(field, 'transform-origin', (p.x + p.width / 2) + 'px ' + (p.y + p.height / 2) + 'px');
      remember(field, 'transform', 'translate3d(' + (-p.x) + 'px,' + (-p.y) + 'px,0)' + (scale !== 1 ? ' scale(' + scale + ')' : ''));
    });
    var dock = document.getElementById('dock'), mat = document.getElementById('matImg');
    if (dock && mat && visible(dock)) { r = rect(dock); remember(mat, 'left', -r.x + 'px'); remember(mat, 'top', -r.y + 'px'); }
  }
  function geometry() {
    return data.rules.some(function (rule) { return (rule.view === '*' || rule.view === context.view) && (rule.layout === '*' || rule.layout === context.layout) && Object.keys(rule.style).some(function (p) { return ['offsetX', 'offsetY', 'width', 'height', 'fontSize', 'fontFamily', 'fontWeight', 'lineHeight', 'letterSpacing', 'padding', 'spacing', 'gap'].indexOf(p) >= 0 || /^(padding|margin)/.test(p); }); });
  }
  function apply(next) {
    if (next) context = next;
    if (!data.rules.length && !window.CleanFramePreview) return;
    collect(); entries.forEach(function (entry) { entry.baseRect = rect(entry.el); }); entries.forEach(applyStyle); releaseClipping(); alignMaterial();
    window.dispatchEvent(new window.CustomEvent('cleanframe:design-rendered'));
  }
  function set(value) { clear(); data = value; if (bridge) bridge.render(); else apply(); }
  function navigate(direction, from, focus) {
    if (!geometry() || !window.SpatialNavigation || !root()) return false;
    var candidates = Array.prototype.filter.call(root().querySelectorAll('[data-action]'), function (el) { return visible(el) && el.getAttribute('aria-disabled') !== 'true' && !el.disabled; });
    var source = candidates.filter(function (el) { return el.getAttribute('data-action') === from; })[0];
    if (!source) return false;
    if (!initialized) {
      window.SpatialNavigation.init(); window.SpatialNavigation.pause();
      window.SpatialNavigation.add('cleanframe', { selector: [], restrict: 'self-only' }); initialized = true;
      document.addEventListener('sn:willfocus', function (event) { if (navigating) { moved = event.target; event.preventDefault(); } });
    }
    window.SpatialNavigation.set('cleanframe', { selector: candidates });
    navigating = true; moved = null;
    window.SpatialNavigation.resume(); window.SpatialNavigation.move(direction, source); window.SpatialNavigation.pause();
    navigating = false;
    if (moved) { focus(moved); return true; }
    // A list may reveal its next virtual row through the existing scrolling path.
    return !source.closest('.ed-other-view');
  }
  window.CleanFrameDesign = {
    ready: function () { return loaded; }, before: clear, after: apply, set: set, get: function () { return JSON.parse(JSON.stringify(data)); }, context: function () { return context; },
    attach: function (api) { bridge = api; }, navigate: navigate, geometry: geometry,
    elements: function () { if (!entries.length || entries.some(function (e) { return !document.documentElement.contains(e.el); })) collect(); return entries.map(function (e) {
      var css = window.getComputedStyle(e.el);
      return { id: e.id, type: e.type, name: e.name, parent: e.parent, action: e.el.getAttribute('data-action'), visible: visible(e.el), rect: rect(e.el), style: resolve(e), computed: { fontFamily: css.fontFamily.split(',')[0].replace(/[\"']/g, ''), fontSize: parseFloat(css.fontSize), fontWeight: parseInt(css.fontWeight, 10) || 500, lineHeight: parseFloat(css.lineHeight) || parseFloat(css.fontSize) * 1.4, radius: parseFloat(css.borderRadius) || 0, paddingTop: parseFloat(css.paddingTop) || 0, paddingRight: parseFloat(css.paddingRight) || 0, paddingBottom: parseFloat(css.paddingBottom) || 0, paddingLeft: parseFloat(css.paddingLeft) || 0, marginTop: parseFloat(css.marginTop) || 0, marginRight: parseFloat(css.marginRight) || 0, marginBottom: parseFloat(css.marginBottom) || 0, marginLeft: parseFloat(css.marginLeft) || 0, gap: parseFloat(css.gap) || 0, padding: parseFloat(css.paddingTop) || 0, spacing: parseFloat(css.marginTop) || 0, letterSpacing: parseFloat(css.letterSpacing) || 0, opacity: parseFloat(css.opacity), color: css.color, background: css.backgroundColor } };
    }); },
    element: function (id) { return entries.filter(function (e) { return e.id === id; })[0]; }
  };
  var request = new window.XMLHttpRequest();
  request.open('GET', 'design.json', true);
  request.onload = function () { loaded = true; if (request.status === 200) { try { var value = JSON.parse(request.responseText); if (value.version === 1 && Array.isArray(value.rules)) set(value); } catch (ignore) { /* Keep the shipped default when the file is unavailable. */ } } };
  request.onerror = function () { loaded = true; };
  request.send();
  if (document.fonts && document.fonts.addEventListener) document.fonts.addEventListener('loadingdone', function () { if (data.rules.length && bridge) bridge.render(); });
}());
