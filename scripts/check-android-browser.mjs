// Production UI, synthetic Android bridge only. This never connects to a TV.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { openPreview, root } from './browser.mjs';

const entries = [
  ['com.netflix.ninja', 'Netflix'], ['com.google.android.youtube.tv', 'YouTube'],
  ['com.amazon.amazonvideo.livingroom', 'Prime Video'], ['com.disney.disneyplus', 'Disney+'],
  ['com.apple.atve.androidtv.appletv', 'Apple TV'], ['com.spotify.tv.android', 'Spotify'],
  ['tv.twitch.android.app', 'Twitch'], ['com.example.family', 'Family <TV>']
].map(([appId, name]) => ({ appId, name, icon: '' }));
const output = root + '.local/qa/';
mkdirSync(output, { recursive: true });
const preview = await openPreview({ publicFonts: true });
const page = await preview.browser.newPage({ viewport: { width: 1920, height: 1080 } });
const errors = [], checks = [];
page.on('pageerror', error => errors.push(error.message));
function pass(name) { checks.push(name); console.log(name); }
try {
  await page.addInitScript(catalogue => {
    // Reproduce the real TV WebView: inner dimensions include stage overflow.
    Object.defineProperty(window, 'innerWidth', { get: () => document.documentElement.clientWidth * 2 });
    Object.defineProperty(window, 'innerHeight', { get: () => document.documentElement.clientHeight * 2 });
    window.__androidCalls = [];
    window.__denyLaunch = false;
    window.CleanFrameNative = {
      apps: () => JSON.stringify(catalogue),
      launchApp: appId => {
        window.__androidCalls.push({ method: 'launchApp', appId });
        return JSON.stringify(window.__denyLaunch ? { error: 'App was removed' } : { ok: true });
      },
      home: () => { window.__androidCalls.push({ method: 'home' }); return '{"ok":true}'; },
      settings: () => { window.__androidCalls.push({ method: 'settings' }); return '{"ok":true}'; }
    };
  }, entries);
  await page.goto(preview.baseURL + '/home/');
  await page.waitForFunction(() => window.ShoreQA?.ready());
  let state = await page.evaluate(() => window.ShoreQA.state());
  assert.deepEqual(state.home, ['netflix', 'youtube', 'primevideo', 'disneyplus', 'appletv', 'spotify', 'tv.twitch.android.app']);
  assert.equal(state.catalog.loaded, true);
  assert.equal(state.catalog.ids.length, entries.length);
  assert.equal(await page.evaluate(() => document.getElementById('stage').getBoundingClientRect().width), 1920);
  assert.equal((await page.evaluate(() => window.ShoreQA.actions())).some(a => a.includes('art')), false);
  pass('Real-package Android defaults initialise before the first frame; Samsung Art Mode is absent.');
  await page.screenshot({ path: output + 'android-preview.png' });

  await page.keyboard.press('Enter');
  assert.equal((await page.evaluate(() => window.__androidCalls)).at(-1).appId, 'com.netflix.ninja');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('Enter');
  assert.equal((await page.evaluate(() => window.__androidCalls)).at(-1).appId, 'com.google.android.youtube.tv');
  pass('Remote OK launches the exact Android package for two existing shortcuts.');

  await page.evaluate(() => window.ShoreQA.scene('settings'));
  assert.match(await page.locator('body').innerText(), /Android TV/);
  await page.evaluate(() => window.ShoreQA.activate('settings.group.samsung'));
  assert.match(await page.locator('body').innerText(), /Original Home/);
  await page.evaluate(() => window.ShoreQA.activate('samsung.home'));
  assert.equal((await page.evaluate(() => window.__androidCalls)).at(-1).method, 'home');
  await page.evaluate(() => window.ShoreQA.activate('samsung.settings'));
  assert.equal((await page.evaluate(() => window.__androidCalls)).at(-1).method, 'settings');
  pass('Original Home and TV settings use separate native bridge actions with Android labels.');

  await page.evaluate(() => window.ShoreQA.scene('home'));
  await page.evaluate(() => { window.__denyLaunch = true; });
  await page.keyboard.press('Enter');
  assert.match(await page.locator('body').innerText(), /App was removed/);
  await page.keyboard.press('Escape');
  assert.equal(await page.evaluate(() => window.ShoreQA.state().view), 'home');
  pass('Native launch failure remains visible; Back closes it and restores Home navigation.');

  await page.evaluate(() => window.ShoreQA.look({ layout: 'list', bg: 'dark' }));
  await page.waitForFunction(() => window.ShoreQA.ready());
  await page.reload();
  await page.waitForFunction(() => window.ShoreQA.ready());
  state = await page.evaluate(() => window.ShoreQA.state());
  assert.equal(state.layout, 'list');
  assert.equal(state.bg, 'dark');
  assert.deepEqual(state.home, ['netflix', 'youtube', 'primevideo', 'disneyplus', 'appletv', 'spotify', 'tv.twitch.android.app']);
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.waitForFunction(() => Math.abs(document.getElementById('stage').getBoundingClientRect().width - 1280) < 1);
  await page.screenshot({ path: output + 'android-list-720.png' });
  assert.equal((await page.evaluate(() => window.ShoreQA.where())).focusCount, 1);
  pass('Preferences and exact app choices survive reload; List navigation scales to 720p.');
  assert.deepEqual(errors, []);
  pass('No uncaught browser errors.');
  writeFileSync(output + 'android-checks.json', JSON.stringify({
    scope: 'Original UI with synthetic Android bridge; no TV contacted; native host still needs on-device checks.',
    checks, errors
  }, null, 2));
} finally {
  await page.close();
  await preview.close();
}
