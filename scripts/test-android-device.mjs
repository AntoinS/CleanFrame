import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../app/android-device.js', import.meta.url), 'utf8');
const tizenSource = readFileSync(new URL('../app/device.js', import.meta.url), 'utf8');
function harness(entries, overrides = {}) {
  const launched = [];
  const native = {
    apps: () => JSON.stringify(entries),
    launchApp: id => { launched.push(id); return '{"ok":true}'; },
    home: () => '{"ok":true}', settings: () => '{"ok":true}', ...overrides
  };
  const window = { CleanFrameNative: native };
  vm.runInNewContext(source, { window });
  vm.runInNewContext(tizenSource, { window });
  return { device: window.FrameDevice, launched };
}

test('Android startup uses only installed packages, and the Tizen adapter preserves it', () => {
  const h = harness([
    { appId: 'com.netflix.ninja', name: 'Netflix' },
    { appId: 'com.example.realapp', name: 'My app', icon: '/icons/com.example.realapp.png' }
  ]);
  assert.equal(h.device.platform, 'android');
  assert.deepEqual(JSON.parse(JSON.stringify(h.device.defaultApps())), [
    { id: 'netflix', name: 'Netflix', appId: 'com.netflix.ninja', brand: 'netflix', mark: true, icon: '' }
  ]);
  h.device.launchApp(h.device.defaultApps()[0].appId, error => assert.equal(error, null));
  assert.deepEqual(h.launched, ['com.netflix.ninja']);
});

test('without reference streaming apps, real installed apps are valid defaults', () => {
  const h = harness([{ appId: 'com.example.realapp', name: 'My app', icon: '/icons/com.example.realapp.png' }]);
  assert.equal(h.device.defaultApps()[0].appId, 'com.example.realapp');
  assert.equal(h.device.defaultApps()[0].icon, '/icons/com.example.realapp.png');
});

test('catalogue failures remain errors and do not fabricate shortcuts', () => {
  const h = harness({ error: 'Package manager unavailable' });
  assert.equal(h.device.defaultApps().length, 0);
  h.device.apps(error => assert.equal(error.message, 'Package manager unavailable'));
});

test('denied native launches are reported and never treated as success', () => {
  const h = harness([], { launchApp: () => '{"error":"App no longer installed"}' });
  h.device.launchApp('com.example.removed', error => assert.equal(error.message, 'App no longer installed'));
});

test('a malformed bridge result is reported as an error', () => {
  const h = harness([], { home: () => '{}' });
  h.device.home(error => assert.match(error.message, /did not confirm/));
});

test('Android excludes Samsung Art Mode and web previews gain no native access', () => {
  const h = harness([]);
  assert.equal(h.device.capabilities.art, false);
  h.device.art(error => assert.match(error.message, /not available/));
  const window = {};
  vm.runInNewContext(source, { window });
  assert.equal(window.FrameDevice, undefined);
});
