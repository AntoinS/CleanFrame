/* Android host adapter. Uses only the packaged WebView's native bridge. */
(function () {
  'use strict';
  var native = window.CleanFrameNative;
  if (!native) return;

  var BRANDS = {
    'com.netflix.ninja': 'netflix', 'com.google.android.youtube.tv': 'youtube',
    'com.amazon.amazonvideo.livingroom': 'primevideo', 'com.disney.disneyplus': 'disneyplus',
    'com.apple.atve.androidtv.appletv': 'appletv', 'com.spotify.tv.android': 'spotify'
  };
  var DEFAULTS = ['com.netflix.ninja', 'com.google.android.youtube.tv',
    'com.amazon.amazonvideo.livingroom', 'com.disney.disneyplus',
    'com.apple.atve.androidtv.appletv', 'com.spotify.tv.android', 'tv.twitch.android.app'];
  function invoke(method, args, stateKey) {
    var value = JSON.parse(args && args.length ? native[method](args[0]) : native[method]());
    if (!value || value.error && (!stateKey || !value[stateKey])) throw new Error(value && value.error || 'The TV returned no result.');
    return value;
  }
  function installed() {
    var entries = invoke('apps');
    if (!Array.isArray(entries)) throw new Error('The TV could not read installed apps.');
    return entries.filter(function (a) {
      return a && typeof a.appId === 'string' && /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(a.appId) && typeof a.name === 'string' && a.name.trim();
    }).map(function (a) {
      return { appId: a.appId, name: a.name, icon: a.icon || '', brand: BRANDS[a.appId] || '' };
    });
  }
  function action(method, args, done) {
    try {
      if (invoke(method, args).ok !== true) throw new Error('The TV did not confirm this action.');
      done(null);
    } catch (err) { done({ message: err.message || 'The TV could not open this screen.' }); }
  }
  window.FrameDevice = {
    platform: 'android', capabilities: { art: false, spotify: typeof native.spotifyState === 'function' },
    spotify: {
      state: function () { return invoke('spotifyState'); },
      observe: function (enabled, done) { action('spotifyObserve', [enabled], done); },
      control: function (command, done) { action('spotifyControl', [command], done); },
      access: function (done) { action('spotifyAccess', [], done); },
      link: function (operation) { return invoke('spotifyLink', [operation]); },
      signOut: function (done) { action('spotifySignOut', [], done); }
    },
    remotePermission: function (operation, done) { action('remotePermission', [operation], done); },
    addons: typeof native.addons === 'function' ? function (request) { return invoke('addons', [JSON.stringify(request)], request.action === 'state' ? 'installed' : null); } : null,
    software: typeof native.software === 'function' ? function (request) { return invoke('software', [JSON.stringify(request)], request.action === 'state' ? 'current' : null); } : null,
    defaultApps: function () {
      var entries;
      try { entries = installed(); } catch (err) { return []; }
      var preferred = DEFAULTS.map(function (id) {
        return entries.filter(function (a) { return a.appId === id; })[0];
      }).filter(function (a) { return !!a; });
      return (preferred.length ? preferred : entries.slice(0, 5)).map(function (a) {
        return { id: a.brand || a.appId, name: a.name, appId: a.appId, brand: a.brand, mark: !!a.brand, icon: a.icon };
      });
    },
    apps: function (done) {
      try { done(null, installed()); }
      catch (err) { done({ message: err.message || 'Could not read installed apps.' }); }
    },
    launch: function (brand, done) {
      var target = Object.keys(BRANDS).filter(function (id) { return BRANDS[id] === brand; })[0];
      if (!target) { done({ message: 'This app has no launch target.' }); return; }
      action('launchApp', [target], done);
    },
    launchApp: function (id, done) { action('launchApp', [id], done); },
    home: function (done) { action('home', [], done); },
    settings: function (done) { action('settings', [], done); },
    art: function (done) { done({ message: 'Samsung Art Mode is not available on this Philips TV.' }); }
  };
}());
