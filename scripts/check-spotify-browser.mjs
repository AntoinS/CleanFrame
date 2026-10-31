// Production UI with an Android media boundary; never contacts a TV or Spotify.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { openPreview, root } from './browser.mjs';

const preview = await openPreview({ publicFonts: true });
const page = await preview.browser.newPage({ viewport: { width: 1920, height: 1080 } });
const checks = [], errors = [];
const out = root + '.local/qa/';
mkdirSync(out, { recursive: true });
const pass = message => { checks.push(message); console.log(message); };
const state = () => page.evaluate(() => ShoreQA.state());
const scene = name => page.evaluate(name => ShoreQA.scene(name), name);
const act = action => page.evaluate(action => ShoreQA.activate(action), action);
const publish = patch => page.evaluate(patch => window.__publish(patch), patch);
async function group(name) { await scene('settings'); const parent={background:'display',typography:'display',focus:'display',layout:'homescreen',clock:'homescreen',spotify:'addons'}[name]; await act('settings.group.'+(parent||name)); if(name==='spotify')await act('addon.details.spotify'); if(parent) await act('group.'+name); }
page.on('pageerror', error => errors.push(error.message));
const timezone = await page.context().newCDPSession(page);
await timezone.send('Emulation.setTimezoneOverride', { timezoneId: 'Europe/Berlin' });
try {
  await page.clock.install({ time: new Date('2026-10-30T22:59:30Z') });
  await page.addInitScript(() => {
    const apps = [['com.google.android.youtube.tv', 'YouTube'], ['com.netflix.ninja', 'Netflix'], ['com.spotify.tv.android', 'Spotify'], ['com.example.long', 'A very long family application name that would overlap a player']].map(([appId, name]) => ({ appId, name, icon: '' }));
    window.__calls = [];
    window.__media = { permission: true, status: 'playing', connection: 'connected', title: 'Track <title> & music', artist: 'Artist', duration: 240000, position: 10000, speed: 1, actions: { previous: true, toggle: true, next: true } };
    window.__publish = patch => { Object.assign(window.__media, patch); window.dispatchEvent(new CustomEvent('cleanframe:spotify', { detail: { ...window.__media } })); };
    window.CleanFrameNative = {
      apps: () => JSON.stringify(apps),
      launchApp: id => { window.__calls.push(['launch', id]); return '{"ok":true}'; },
      home: () => '{"ok":true}', settings: () => '{"ok":true}',
      spotifyState: () => JSON.stringify(window.__media),
      spotifyObserve: value => { window.__calls.push(['observe', value]); return '{"ok":true}'; },
      spotifyControl: value => { window.__calls.push(['control', value]); return '{"ok":true}'; },
      spotifyAccess: () => { window.__calls.push(['access']); return '{"ok":true}'; },
      spotifyLink: op => { if(op==='begin')window.__calls.push(['signin']); return JSON.stringify(op==='state'?{phase:'pending',expiresAt:Date.now()+600000}:{ok:true}); }
    };
    if (!localStorage.getItem('cleanframe.home.v1')) localStorage.setItem('cleanframe.home.v1', JSON.stringify({ layout: 'list', bg: 'photo', wallpaper: 'alpine', language: 'de', apps: 'icons', controls: 'both', home: ['youtube', 'netflix', 'spotify'] }));
  });
  await page.goto(preview.baseURL + '/home/');
  await page.waitForFunction(() => ShoreQA.ready());
  assert.equal(await page.locator('.clock-date').textContent(), 'Fr. 30. Oktober');
  await page.clock.fastForward(31000);
  assert.equal(await page.locator('.clock-date').textContent(), 'Sa. 31. Oktober');
  await page.clock.setSystemTime(new Date('2026-10-31T22:59:30Z'));
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.clock.fastForward(31000);
  assert.equal(await page.locator('.clock-date').textContent(), 'So. 1. November');
  await timezone.send('Emulation.setTimezoneOverride', { timezoneId: 'America/Los_Angeles' });
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  assert.equal(await page.locator('.clock-date').textContent(), 'Sa. 31. Oktober');
  await timezone.send('Emulation.setTimezoneOverride', { timezoneId: 'Europe/Berlin' });
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  assert.equal(await page.locator('.clock-date').textContent(), 'So. 1. November');
  await group('language'); await act('choice.language.en'); await scene('home');
  pass('German date follows local midnight, month boundaries and timezone changes.');

  await group('clock'); await act('choice.dateVisible.false');
  assert.equal((await state()).dateVisible, false);
  await page.evaluate(() => ShoreQA.look({ layout: 'dock', bg: 'dark' }));
  await page.waitForFunction(() => ShoreQA.ready());
  await scene('editor'); await act('editor.remove.netflix'); await act('editor.add.netflix');
  assert.equal((await state()).dateVisible, false);
  await page.reload(); await page.waitForFunction(() => ShoreQA.ready());
  assert.equal((await state()).dateVisible, false);
  assert.equal(await page.locator('.clock-date').count(), 0);
  await group('clock'); await act('choice.dateVisible.true'); await scene('home');
  pass('Date preference survives appearance changes, app editing and reload.');

  await page.keyboard.press('ArrowDown');
  assert.equal((await state()).focusAction, 'spotify.toggle');
  await page.evaluate(() => { window.__button = document.querySelector('[data-action="spotify.toggle"]'); });
  await page.clock.pauseAt(await page.evaluate(() => Date.now() + 100));
  await page.keyboard.press('Enter');
  assert.deepEqual(await page.evaluate(() => __calls.at(-1)), ['control', 'toggle']);
  await publish({ status: 'paused', title: 'Changed <img onerror=alert(1)>', position: 34000, deviceName: 'MacBook Pro' });
  assert.equal(await page.locator('#spotifySource').textContent(), 'MacBook Pro');
  await page.clock.fastForward(2000);
  assert.equal((await state()).focusAction, 'spotify.toggle');
  await publish({ title: 'Changed <img onerror=alert(1)>' });
  await page.clock.fastForward(499);
  assert.equal((await state()).focusAction, 'spotify.toggle');
  await page.clock.fastForward(1);
  assert.equal((await state()).focusAction, 'spotify.expand');
  const idleCalls = await page.evaluate(() => __calls.length);
  await page.keyboard.press('Enter');
  assert.equal((await state()).focusAction, 'spotify.toggle');
  assert.equal(await page.evaluate(() => __calls.length), idleCalls);
  await page.clock.fastForward(2000);
  await page.keyboard.press('ArrowLeft');
  await page.clock.fastForward(2000);
  assert.equal((await state()).focusAction, 'spotify.previous');
  await page.keyboard.press('ArrowRight');
  await page.clock.fastForward(61000);
  assert.equal((await state()).focusAction, 'spotify.expand');
  await page.clock.resume();
  assert.equal(await page.evaluate(() => __button === document.querySelector('[data-action="spotify.toggle"]')), true);
  assert.equal(await page.locator('#spotifyPosition').textContent(), '0:34');
  assert.equal(await page.locator('#spotifyTitle').textContent(), 'Changed <img onerror=alert(1)>');
  assert.equal(await page.locator('#spotifyTitle img').count(), 0);
  await page.keyboard.press('ArrowRight'); await page.keyboard.press('Enter');
  assert.deepEqual(await page.evaluate(() => __calls.at(-1)), ['control', 'next']);
  await page.keyboard.press('Escape');
  assert.match((await state()).focusAction, /^home.app\./);
  await act('spotify.expand');
  await page.keyboard.press('ArrowUp');
  assert.equal((await state()).focusAction, 'spotify.open');
  await page.keyboard.press('Enter');
  assert.deepEqual(await page.evaluate(() => __calls.at(-1)), ['launch', 'com.spotify.tv.android']);
  await page.keyboard.press('ArrowDown');
  assert.equal((await state()).focusAction, 'spotify.toggle');
  await page.keyboard.press('Escape');
  pass('Controls collapse after 2.5 seconds of input inactivity; metadata does not reset the timer; OK reveals without playing; navigation retains the selected control.');
  pass('Dock controls target Spotify; title/clock changes preserve button identity; pause freezes progress; only the device name labels playback.');

  await page.evaluate(() => ShoreQA.look({ layout: 'list', bg: 'photo', wall: 'alpine' }));
  await page.waitForFunction(() => ShoreQA.ready());
  await page.keyboard.press('ArrowRight');
  assert.match((await state()).focusAction, /^util\./);
  await page.keyboard.press('ArrowDown');
  assert.equal((await state()).focusAction, 'util.settings');
  await page.keyboard.press('ArrowUp');
  assert.equal((await state()).focusAction, 'util.apps');
  await page.keyboard.press('ArrowUp');
  assert.equal((await state()).focusAction, 'spotify.toggle');
  await publish({ status: 'playing', position: 0, actions: { previous: false, toggle: true, next: false } });
  await page.keyboard.press('ArrowRight');
  assert.equal((await state()).focusAction, 'spotify.toggle');
  assert.equal(await page.locator('[data-action="spotify.next"]').getAttribute('aria-disabled'), 'true');
  await page.clock.fastForward(3000);
  assert.equal(await page.locator('#spotifyPosition').textContent(), '0:03');
  await publish({ status: 'idle', title: '' });
  assert.equal(await page.locator('#spotifyPlayer').isVisible(), false);
  assert.match((await state()).focusAction, /^home.app\./);
  pass('List navigation skips unsupported commands; destroyed session hides the card and restores app focus.');

  await publish({ status: 'playing', title: 'Remote music', position: 10000 });
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  assert.deepEqual(await page.evaluate(() => __calls.at(-1)), ['observe', false]);
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  assert.deepEqual(await page.evaluate(() => __calls.at(-1)), ['observe', true]);
  await group('spotify'); await act('choice.spotifyEnabled.false');
  await page.evaluate(() => ShoreQA.look({ material: 'frost' }));
  await page.waitForFunction(() => ShoreQA.ready());
  await scene('home');
  assert.equal(await page.locator('#spotifyPlayer').isVisible(), false);
  assert.equal((await state()).spotifyEnabled, false);
  await page.reload(); await page.waitForFunction(() => ShoreQA.ready());
  assert.equal((await state()).spotifyEnabled, false);
  await group('spotify'); await act('choice.spotifyEnabled.true');
  await publish({ permission: false, status: 'permission' });
  assert.match(await page.locator('#appearanceRows').innerText(), /Allow media access/);
  await act('spotify.access');
  assert.deepEqual(await page.evaluate(() => __calls.at(-1)), ['access']);
  await publish({ permission: true, status: 'unavailable', connection: 'unavailable' });
  assert.match(await page.locator('#appearanceRows').innerText(), /could not connect/);
  await act('spotify.open');
  assert.deepEqual(await page.evaluate(() => __calls.at(-1)), ['launch', 'com.spotify.tv.android']);
  pass('Visibility and the saved off switch release observation; access and connection failures offer explicit recovery.');

  await group('spotify'); await act('choice.spotifySurface.cover');
  assert.equal((await state()).spotifySurface, 'cover');
  await page.evaluate(() => ShoreQA.look({ layout: 'dock' }));
  await page.waitForFunction(() => ShoreQA.ready());
  await scene('editor'); await act('editor.remove.netflix'); await act('editor.add.netflix');
  await page.reload(); await page.waitForFunction(() => ShoreQA.ready());
  assert.equal((await state()).spotifySurface, 'cover');
  await publish({ artColor: '#dae1e9', artLight: true });
  assert.equal(await page.locator('#spotifyPlayer').evaluate(e => e.style.getPropertyValue('--spotify-surface')), '#dae1e9');
  assert.equal(await page.locator('#spotifyPlayer').evaluate(e => e.classList.contains('is-light')), false);
  await publish({ artColor: '#322044', artLight: false });
  assert.equal(await page.locator('#spotifyPlayer').evaluate(e => e.style.getPropertyValue('--spotify-surface')), '#322044');
  for (const [value, expected] of [['light', '#f4f4f1'], ['dark', '#131209']]) {
    await group('spotify'); await act('choice.spotifySurface.' + value); await scene('home');
    assert.equal(await page.locator('#spotifyPlayer').evaluate(e => e.style.getPropertyValue('--spotify-surface')), expected);
  }
  await group('spotify');
  await page.screenshot({ path: out + 'spotify-settings-qr.png', animations: 'disabled' });
  await act('spotify.signin');
  assert.equal(await page.evaluate(() => __calls.filter(c=>c[0]==='signin').length), 1);
  assert.equal(await page.locator('#spotifyPairQr').count(),0);
  await page.keyboard.press('Escape');
  assert.ok(await page.locator('#appearanceRows [data-action="spotify.open"]').evaluate(e => e.getBoundingClientRect().bottom < document.getElementById('appearanceDesc').getBoundingClientRect().top));
  assert.equal(await page.locator('#spotifyCompact').isVisible(), true);
  pass('Translucent dark, light and cover backgrounds update safely; the saved choice survives layout, app edits and reload.');

  await scene('home');
  await publish({ status: 'playing', connection: 'connected', title: 'A long track title '.repeat(12), artist: 'An artist name '.repeat(10), art: '/media/spotify-99.png', actions: { previous: true, toggle: true, next: true } });
  await page.waitForFunction(() => document.getElementById('spotifyFallback').hidden === false);
  for (const width of [1920, 1280]) for (const layout of ['list', 'dock']) for (const bg of ['photo', 'light', 'dark']) {
    await page.setViewportSize({ width, height: width * 9 / 16 });
    await page.clock.runFor(50);
    await page.waitForFunction(width => Math.abs(document.getElementById('stage').getBoundingClientRect().width - width) < 1, width);
    await page.evaluate(look => ShoreQA.look(look), { layout, bg });
    await page.waitForFunction(() => ShoreQA.ready());
    await page.clock.runFor(450);
    await page.screenshot({ path: out + 'spotify-layout-check.png', animations: 'disabled' });
    const rects = await page.evaluate(() => {
      const r = el => { const b = el.getBoundingClientRect(); return { left: b.left, right: b.right, top: b.top, bottom: b.bottom }; };
      return { player: r(document.getElementById('spotifyCompact')), clock: r(document.getElementById('caption')), controls: r(document.getElementById('utils')), dock: r(document.getElementById('dock')) };
    });
    assert.ok(rects.player.right <= width && rects.player.top > rects.clock.bottom, JSON.stringify({ width, layout, bg, rects }));
    assert.ok(Math.abs((rects.player.left + rects.player.right) / 2 - width / 2) < 1);
    assert.ok(await page.locator('#utils .ico').evaluateAll(icons => Math.abs(icons[icons.length - 1].getBoundingClientRect().bottom - document.getElementById('spotifyCompact').getBoundingClientRect().bottom) < 1 && icons[0].getBoundingClientRect().bottom < icons[icons.length - 1].getBoundingClientRect().top));
    assert.ok(layout === 'list' ? rects.player.right < rects.controls.left : rects.player.top > rects.dock.bottom);
    assert.equal(await page.locator('#spotifyExpanded').evaluate(e => getComputedStyle(e).visibility), 'hidden');
    assert.deepEqual(await page.locator('.spotify-surface').evaluate(e => ({ transform: getComputedStyle(e).transform, radius: getComputedStyle(e).borderRadius, opacity: getComputedStyle(e).opacity })), { transform: 'none', radius: '24px', opacity: '0.4' });
    assert.equal((await page.evaluate(() => ShoreQA.where())).focusCount, 1);
    await page.screenshot({ path: out + `spotify-${layout}-${bg}-${width}.png`, animations: 'disabled' });
    await act('spotify.expand');
    await page.clock.runFor(450);
    await page.screenshot({ path: out + `spotify-expanded-${layout}-${bg}-${width}.png`, animations: 'disabled' });
    assert.equal((await state()).focusAction, 'spotify.toggle');
    assert.equal(await page.locator('#spotifyCompact').evaluate(e => getComputedStyle(e).visibility), 'hidden');
    assert.equal(await page.locator('#spotifyExpanded').evaluate(e => getComputedStyle(e).visibility), 'visible');
    assert.equal(await page.locator('.spotify-surface').evaluate(e => getComputedStyle(e).boxShadow), 'none');
    await publish({ errorMessage: 'Spotify TV did not apply this control.' });
    assert.equal((await state()).focusAction, 'spotify.toggle');
    assert.equal(await page.locator('#spotifyError').isVisible(), true);
    assert.ok(await page.locator('#spotifyExpanded').evaluate(e => e.getBoundingClientRect().top >= document.getElementById('spotifyPlayer').getBoundingClientRect().top - 1));
    await publish({ errorMessage: '' });
    if (layout === 'dock') assert.ok(await page.evaluate(() => document.getElementById('spotifyPlayer').getBoundingClientRect().top > document.getElementById('dock').getBoundingClientRect().bottom));
    await page.keyboard.press('Escape');
  }
  for (const layout of ['list', 'dock']) {
    await page.evaluate(layout => ShoreQA.look({ layout, controls: 'icons' }), layout);
    await page.waitForFunction(() => ShoreQA.ready());
    await scene('home');
    await page.keyboard.press(layout === 'list' ? 'ArrowRight' : 'ArrowUp');
    if ((await state()).focusAction === 'util.settings') await page.keyboard.press('ArrowUp');
    for (let i = 0; i < 2; i++) {
      assert.ok(await page.evaluate(() => {
        const button = document.querySelector('#utils .is-focus').getBoundingClientRect();
        const label = document.getElementById('utilFocusLabel').getBoundingClientRect();
        return label.right < button.left && Math.abs(label.top + label.height / 2 - button.top - button.height / 2) < 4;
      }));
      await page.keyboard.press('ArrowDown');
    }
  }
  pass('Compact and expanded player stay bottom centred in both layouts, all backgrounds, 1080p/720p; controls appear only on focus and the Dock moves clear.');
  await scene('settings');
  assert.equal(await page.locator('.settings-group').count(), 5);
  assert.ok(await page.locator('.settings-group').last().evaluate(e => e.getBoundingClientRect().bottom < document.querySelector('.settings-overview-hint').getBoundingClientRect().top));
  assert.deepEqual(errors, []);
  pass('Five settings categories fit the screen; no uncaught browser errors.');
  writeFileSync(out + 'spotify-checks.json', JSON.stringify({ scope: 'Synthetic Android boundary only; real MediaSession controls need TV verification.', checks, errors }, null, 2));
} finally { await page.close(); await preview.close(); }
