import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {openEditor,ready,root} from './editor-browser.mjs';
const storage=mkdtempSync(join(tmpdir(),'cleanframe-import-'));
const app=await openEditor(storage);
const page=await app.browser.newPage({viewport:{width:1280,height:800}}),errors=[];
page.on('pageerror',e=>errors.push(e.message));
try{
 await page.goto(app.url+'/editor/');await ready(page);await page.evaluate(()=>CleanFrameEditor.save());
 const before=await page.evaluate(()=>CleanFrameEditor.project);
 let releaseRead,started;
 const reading=new Promise(resolve=>{started=resolve;});
 await page.route('**/api/editor/tv/import',async route=>{
  assert.equal(route.request().postDataJSON().etag,createHash('sha256').update(readFileSync(storage+'/project.json')).digest('hex'));
  started();await new Promise(resolve=>{releaseRead=resolve;});
  const imported=structuredClone(before);
  imported.preferences={...before.preferences,wall:'mint',language:'en',home:['spotify','youtube']};
  imported.basePrefs=structuredClone(imported.preferences);imported.baseCapturedAt='2026-10-30T21:00:00+0100';
  imported.tvSource={version:'0.7.0-android16',apps:[{appId:'com.spotify.tv.android',name:'Spotify'},{appId:'com.google.android.youtube.tv',name:'YouTube'}],addons:[{id:'spotify',name:'Spotify',kind:'spotify',version:'1.0.0',enabled:false}]};
  imported.design={version:1,rules:[{view:'home',layout:'dock',state:'*',target:'caption',style:{offsetX:16}}]};
  imported.ui={...imported.ui,view:'home',mode:'edit',selection:''};imported.guides=[];imported.undo=[];imported.redo=[];
  mkdirSync(storage+'/snapshots',{recursive:true});
  writeFileSync(storage+'/snapshots/'+ 'a'.repeat(32)+'.json',JSON.stringify({id:'a'.repeat(32),name:'Vor TV-Import',created:'2026-10-30',project:before}));
  writeFileSync(storage+'/project.json',JSON.stringify(imported));
  await route.fulfill({json:{version:imported.tvSource.version,sourceMatches:true,etag:createHash('sha256').update(readFileSync(storage+'/project.json')).digest('hex')}});
 });
 await page.click('#liveTab');assert.ok(await page.locator('body').evaluate(el=>el.classList.contains('live-mode')));
 await page.click('#importTv');await reading;
 assert.ok(await page.locator('#importTv').isDisabled());assert.ok(await page.locator('#dialog').isVisible());
 await page.keyboard.press('Escape');assert.ok(await page.locator('#dialog').isVisible());releaseRead();
 await page.waitForFunction(()=>CleanFrameEditor.project.tvSource&&!document.querySelector('#dialog').open);await ready(page);
 assert.equal(await page.locator('body').evaluate(el=>el.classList.contains('live-mode')),false);
 const imported=await page.evaluate(()=>CleanFrameEditor.project);
 assert.deepEqual(imported.preferences,imported.basePrefs);assert.equal(imported.preferences.wall,'mint');
 assert.equal(await page.inputValue('#language'),'en');assert.equal(await page.inputValue('#scene'),'home');
 assert.equal(await page.evaluate(()=>CleanFrameEditor.win().CleanFrameAddons.enabled('spotify')),false);
 assert.equal(await page.evaluate(()=>JSON.parse(CleanFrameEditor.win().CleanFrameNative.apps()).length),2);
 assert.equal(await page.evaluate(()=>CleanFrameEditor.design().get().rules[0].style.offsetX),16);
 await page.reload();await ready(page);assert.deepEqual(await page.evaluate(()=>CleanFrameEditor.project.tvSource),imported.tvSource);
 await page.click('#versions');await page.getByRole('button',{name:'Laden',exact:true}).click();await page.waitForFunction(()=>!document.querySelector('#dialog').open);await ready(page);
 assert.deepEqual(await page.evaluate(()=>CleanFrameEditor.project.basePrefs),before.basePrefs);
 assert.equal(await page.evaluate(()=>CleanFrameEditor.project.tvSource),undefined);
 await page.click('#undo');await ready(page);assert.deepEqual(await page.evaluate(()=>CleanFrameEditor.project.tvSource),imported.tvSource);
 await page.unroute('**/api/editor/tv/import');
 await page.route('**/api/editor/tv/import',route=>route.fulfill({status:400,json:{error:'TV ist nicht erreichbar.'}}));
 const saved=await page.evaluate(()=>CleanFrameEditor.project);await page.click('#importTv');
 await page.waitForFunction(()=>document.querySelector('#notice').textContent==='TV ist nicht erreichbar.');
 assert.deepEqual(await page.evaluate(()=>CleanFrameEditor.project),saved);assert.equal(await page.locator('#dialog').isVisible(),false);
 for(const width of [1280,1600]){
  await page.setViewportSize({width,height:900});
  const button=await page.locator('#importTv').boundingBox(),last=await page.locator('#toggleChat').boundingBox();
  assert.ok(button.x>=0&&button.y>=0&&last.x+last.width<=width);assert.equal((await page.locator('.top').boundingBox()).height,48);
 }
 mkdirSync(root+'.local/editor/qa',{recursive:true});await page.screenshot({path:root+'.local/editor/qa/tv-import-editor.png'});
 assert.deepEqual(errors,[]);console.log('TV import: loading, preview data, persistence, restore/undo, offline error and compact toolbar passed.');
}finally{await page.close();await app.close();}
