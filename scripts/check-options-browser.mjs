// Exercise settings through the real remote and storage paths. No TV/account is contacted.
import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
import {openPreview,root} from './browser.mjs';
const preview=await openPreview({publicFonts:true});
const page=await preview.browser.newPage({viewport:{width:1920,height:1080}});
const out=root+'.local/qa/';mkdirSync(out,{recursive:true});
const checks=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
const state=()=>page.evaluate(()=>ShoreQA.state());
const scene=name=>page.evaluate(name=>ShoreQA.scene(name),name);
const act=action=>page.evaluate(action=>ShoreQA.activate(action),action);
const ready=()=>page.waitForFunction(()=>ShoreQA.ready());
const key=name=>page.keyboard.press(name);
async function group(name){await scene('settings');const parent={background:'display',typography:'display',focus:'display',layout:'homescreen',clock:'homescreen',spotify:'addons'}[name];await act('settings.group.'+(parent||name));if(name==='spotify')await act('addon.details.spotify');if(parent)await act('group.'+name);}
const pass=message=>{checks.push(message);console.log(message);};
try{
 await page.addInitScript(()=>{
  window.__calls=[];window.__media={permission:true,status:'playing',connection:'connected',webApi:'connected',webLinked:true,title:'Settings',artist:'Original artist',duration:180000,position:1000,speed:1,artColor:'#ebc789',artLight:true,actions:{previous:true,toggle:true,next:true}};
  window.__publish=patch=>{Object.assign(__media,patch);window.dispatchEvent(new CustomEvent('cleanframe:spotify',{detail:{...__media}}));};
  window.CleanFrameNative={apps:()=>JSON.stringify([{appId:'com.google.android.youtube.tv',name:'YouTube'},{appId:'com.netflix.ninja',name:'Netflix'},{appId:'com.spotify.tv.android',name:'Spotify'}]),launchApp:id=>{__calls.push(['launch',id]);return '{"ok":true}';},home:()=>'{"ok":true}',settings:()=>'{"ok":true}',spotifyState:()=>JSON.stringify(__media),spotifyObserve:()=>'{"ok":true}',spotifyControl:command=>{__calls.push(['control',command]);return '{"ok":true}';},spotifyAccess:()=>'{"ok":true}',spotifyLink:op=>{if(op==='begin')__calls.push(['signin']);return JSON.stringify(op==='state'?{phase:'pending',expiresAt:Date.now()+600000}:{ok:true});},spotifySignOut:()=>{__calls.push(['signout']);setTimeout(()=>__publish({webApi:'unlinked',webLinked:false,actions:{previous:false,toggle:false,next:false}}),50);return '{"ok":true}';}};
  if(!localStorage.getItem('cleanframe.home.v1'))localStorage.setItem('cleanframe.home.v1',JSON.stringify({language:'en',layout:'dock',bg:'photo',wall:'summit',home:['youtube','netflix','spotify'],controls:'both',spotifySurface:'cover'}));
 });
 await page.goto(preview.baseURL+'/home/');await ready();
 assert.equal((await state()).spotifyTransparency,60);assert.equal((await state()).controlsPosition,'auto');
 const baseline=(await state()).home;
 await group('spotify');await act('spotify.transparency');
 assert.equal((await state()).focusAction,'spotify.transparency');
 assert.equal((await page.evaluate(()=>ShoreQA.where())).focusCount,1);
 await key('ArrowRight');assert.equal((await state()).spotifyTransparency,65);
 await key('ArrowLeft');assert.equal((await state()).spotifyTransparency,60);
 for(let n=0;n<22;n++)await key('ArrowLeft');assert.equal((await state()).spotifyTransparency,0);
 await scene('home');await page.waitForTimeout(350);
 assert.equal(await page.locator('.spotify-surface').evaluate(e=>getComputedStyle(e).opacity),'1');
 await group('spotify');await act('spotify.transparency');for(let n=0;n<22;n++)await key('ArrowRight');
 assert.equal((await state()).spotifyTransparency,100);
 await scene('home');await page.waitForTimeout(350);
 assert.equal(await page.locator('.spotify-surface').evaluate(e=>getComputedStyle(e).opacity),'0');
 assert.equal(await page.locator('#spotifyCompactTitle').isVisible(),true);
 await group('spotify');await act('spotify.transparency');for(let n=0;n<5;n++)await key('ArrowLeft');
 await page.evaluate(()=>{window.__save=Storage.prototype.setItem;Storage.prototype.setItem=()=>{throw Error('Full');};});
 await key('ArrowRight');assert.equal((await state()).spotifyTransparency,75);assert.equal((await state()).storageOk,false);
 await page.evaluate(()=>{Storage.prototype.setItem=__save;});await act('settings.retry');assert.equal((await state()).spotifyTransparency,80);
 pass('Transparency uses 5% steps, clamps at 0/100, changes only the surface, and retains its committed value on storage failure.');

 await group('language');await act('choice.language.de');
 assert.equal(await page.locator('html').getAttribute('lang'),'de');
 assert.equal(await page.locator('#appearanceTitle').textContent(),'Sprache');
 await scene('home');assert.equal(await page.locator('[data-action="util.settings"]').getAttribute('aria-label'),'Einstellungen');
 assert.equal(await page.locator('#spotifyCompactTitle').textContent(),'Settings');
 await group('layout');await act('choice.controlsPosition.top');
 await page.evaluate(()=>ShoreQA.look({layout:'list'}));await ready();
 await scene('editor');await act('editor.remove.netflix');await act('editor.add.netflix');
 await page.reload();await ready();
 const saved=await state();assert.deepEqual(saved.home,baseline);assert.equal(saved.language,'de');assert.equal(saved.controlsPosition,'top');assert.equal(saved.spotifyTransparency,80);assert.equal(saved.spotifySurface,'cover');
 pass('Language, transparency and controls position survive app editing, layout changes and reload without changing app order.');

 await group('spotify');await act('spotify.signout');
 assert.equal(await page.locator('.msg-title').textContent(),'Von Spotify abmelden?');
 await key('Escape');assert.equal(await page.evaluate(()=>__calls.filter(c=>c[0]==='signout').length),0);
 await act('spotify.signout');await act('message.signout');
 await page.waitForFunction(()=>ShoreQA.state().spotify.webApi==='unlinked');
 assert.equal(await page.evaluate(()=>__calls.filter(c=>c[0]==='signout').length),1);
 assert.equal((await state()).focusAction,'spotify.signin');
 assert.equal(await page.locator('[data-action="spotify.signout"]').getAttribute('aria-disabled'),'true');
 await act('spotify.signin');assert.deepEqual(await page.evaluate(()=>__calls.at(-1)),['signin']);
 assert.equal(await page.evaluate(()=>__calls.filter(c=>c[0]==='control'||c[0]==='launch').length),0);
 await page.evaluate(()=>__publish({webLinked:true,webApi:'connected',actions:{previous:true,toggle:true,next:true}}));
 pass('Sign out requires confirmation, cancels safely, handles the asynchronous disconnected state, and never launches Spotify or changes playback. QR stays in the localized frontend.');

 for(const width of [1920,1280])for(const lang of ['de','en']){
  await page.setViewportSize({width,height:width*9/16});await page.waitForFunction(w=>Math.abs(document.getElementById('stage').getBoundingClientRect().width-w)<1,width);
  await group('language');await act('choice.language.'+lang);
  for(const name of ['layout','spotify','clock','focus','background','samsung']){
   await group(name);
   // Headless Linux may finish loading the UI font after the scene is mounted.
   await page.evaluate(async()=>{await document.fonts.ready;await new Promise(requestAnimationFrame);});
   const bad=await page.locator('#appearanceRows .setting-choice').evaluateAll(els=>els.filter(el=>{const b=el.getBoundingClientRect(),copy=el.querySelector('.setting-copy');return b.bottom>document.getElementById('appearanceDesc').getBoundingClientRect().top||(copy&&copy.scrollWidth>copy.clientWidth+2);}).map(el=>({text:el.textContent,bounds:el.getBoundingClientRect().toJSON(),copyWidth:el.querySelector('.setting-copy')?.clientWidth,copyScroll:el.querySelector('.setting-copy')?.scrollWidth,footer:document.getElementById('appearanceDesc').getBoundingClientRect().top})));
   assert.deepEqual(bad,[],`${width} ${lang} ${name}`);
   if(name==='spotify')await page.screenshot({path:out+`options-spotify-${lang}-${width}.png`,animations:'disabled'});
  }
  await scene('settings');assert.ok(await page.locator('.settings-group').last().evaluate(el=>el.getBoundingClientRect().bottom<document.querySelector('.settings-overview-hint').getBoundingClientRect().top));
  for(const layout of ['list','dock'])for(const position of ['top','bottom'])for(const controls of ['both','icons','text']){
   await group('layout');await act('choice.controlsPosition.'+position);
   await page.evaluate(look=>ShoreQA.look(look),{layout,controls});await ready();await scene('home');
   const box=await page.evaluate(()=>{const rect=id=>{const r=document.getElementById(id).getBoundingClientRect();return{left:r.left,right:r.right,top:r.top,bottom:r.bottom};};return{utils:rect('utils'),clock:rect('caption'),player:rect('spotifyCompact')};});
   assert.ok(position==='top'?box.utils.bottom<width*9/32:box.utils.top>width*9/32,JSON.stringify({layout,position,box}));
   if(layout==='list'&&position==='top')assert.ok(box.utils.top>box.clock.bottom);
   await key(layout==='dock'?'ArrowUp':'ArrowRight');
   assert.match((await state()).focusAction,/^util\./);
   if((await state()).focusAction==='util.settings')await key('ArrowUp');
   if(controls==='icons')assert.ok(await page.evaluate(()=>{const a=document.querySelector('#utils .is-focus').getBoundingClientRect(),b=document.getElementById('utilFocusLabel').getBoundingClientRect();return b.right<a.left&&Math.abs((a.top+a.bottom-b.top-b.bottom)/2)<4;}));
   await key('ArrowDown');assert.equal((await state()).focusAction,'util.settings');
   if(position==='bottom')await key('ArrowUp');
   await key(position==='top'?'ArrowDown':'ArrowUp');assert.equal((await state()).focusAction,'spotify.toggle');
   await key('Escape');assert.match((await state()).focusAction,/^home.app\./);
   if(controls==='both')await page.screenshot({path:out+`options-home-${layout}-${position}-${lang}-${width}.png`,animations:'disabled'});
  }
 }
 pass('Both languages and utility positions fit both layouts at 1080p/720p in all display modes; labels stay aligned and directional navigation reaches the player.');
 await scene('home');await group('language');await act('choice.language.en');await scene('home');
 assert.match(await page.locator('.clock-date').textContent(),/^(Mon|Tue|Wed|Thu|Fri|Sat|Sun),? \d/);
 assert.equal(await page.locator('[data-action="util.settings"]').getAttribute('aria-label'),'Settings');
 assert.deepEqual(errors,[]);
 writeFileSync(out+'options-checks.json',JSON.stringify({checks,errors,scope:'Real UI with synthetic Android account boundary; no real account signed out.'},null,2));
}finally{await page.close();await preview.close();}
