import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,statSync,utimesSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {openEditor,ready,root} from './editor-browser.mjs';
import {validate} from './editor-schema.mjs';
const storage=mkdtempSync(join(tmpdir(),'cleanframe-editor-check-'));
writeFileSync(join(storage,'project.json'),JSON.stringify({version:1,basePrefs:{bg:'photo',wall:'summit',layout:'dock',apps:'icons',font:'manrope',controls:'text',focus:'glass',material:'tint',home:['youtube','netflix','primevideo','spotify','appletv'],dateVisible:true,spotifyEnabled:true,spotifySurface:'cover',spotifyTransparency:60,controlsPosition:'top',language:'de',mode:'fixed'},preferences:{bg:'photo',wall:'summit',layout:'dock',apps:'icons',font:'manrope',controls:'text',focus:'glass',material:'tint',home:['youtube','netflix','primevideo','spotify','appletv'],dateVisible:true,spotifyEnabled:true,spotifySurface:'cover',spotifyTransparency:60,controlsPosition:'top',language:'de',mode:'fixed'},design:{version:1,rules:[]},ui:{view:'home',state:'compact',scenario:'playing',resolution:1080,selection:'',snap:true,mode:'edit',scope:'element'},undo:[],redo:[]}));
const app=await openEditor(storage),page=await app.browser.newPage({viewport:{width:1600,height:1000}}),errors=[],checks=[];
page.on('pageerror',e=>errors.push(e.message));const pass=s=>{checks.push(s);console.log(s);};
const frame=()=>page.frames().find(f=>f.url().includes('/home/'));
const state=()=>frame().evaluate(()=>ShoreQA.state());
const select=id=>page.evaluate(id=>CleanFrameEditor.select(id),id);
const waitSaved=()=>page.evaluate(()=>CleanFrameEditor.save());
const show=async name=>{await page.selectOption('#scene',name);await page.waitForTimeout(200);};
try{
 await page.goto(app.url+'/editor/');await ready(page);assert.ok(await page.locator('#layers button').count()>20);assert.equal((await state()).language,'de');assert.equal((await state()).spotify.status,'playing');
 const initial=await page.evaluate(()=>CleanFrameEditor.project);assert.ok(validate(initial));pass('The editor opens the real Android UI with isolated media fixtures and valid local settings.');
 await select('caption');const x=+await page.locator('#field-x').inputValue();await page.locator('#field-x').fill(String(x+80));await page.locator('#field-x').press('Tab');await waitSaved();assert.equal(Math.round(+await page.locator('#field-x').inputValue()),Math.round(x+80));
 assert.deepEqual(await frame().evaluate(()=>CleanFrameMock.calls),[]);await page.locator('#undo').click();await ready(page);assert.equal(Math.round(+await page.locator('#field-x').inputValue()),Math.round(x));await page.locator('#redo').click();await ready(page);assert.equal(Math.round(+await page.locator('#field-x').inputValue()),Math.round(x+80));
 await page.reload();await ready(page);assert.equal(Math.round(+await page.locator('#field-x').inputValue()),Math.round(x+80));pass('Numeric edits, passive selection, undo/redo and reopening preserve the draft without launching apps.');
 const box=await page.locator('#selection').boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+43,box.y+box.height/2+16,{steps:8});await page.mouse.up();await waitSaved();assert.ok(+await page.locator('#field-x').inputValue()>x+100);
 const beforeWidth=+await page.locator('#field-width').inputValue();const handle=await page.locator('#selection .br').boundingBox();await page.mouse.move(handle.x+handle.width/2,handle.y+handle.height/2);await page.mouse.down();await page.mouse.move(handle.x+handle.width/2+40,handle.y+handle.height/2+16,{steps:8});await page.mouse.up();await waitSaved();assert.ok(+await page.locator('#field-width').inputValue()>beforeWidth+10);pass('interact.js drag and resize update shared stage coordinates.');
 await page.locator('#snapshot').click();await page.locator('#dialog input').fill('Teststand');await page.locator('#dialogSave').click();await page.waitForSelector('#dialog',{state:'hidden'});
 await page.locator('#resetElement').click();await waitSaved();await page.locator('#versions').click();await page.getByRole('button',{name:'Laden',exact:true}).click();await page.waitForSelector('#dialog',{state:'hidden'});await ready(page);assert.ok((await page.evaluate(()=>CleanFrameEditor.project.design.rules)).length>0);pass('Named snapshots restore the design and settings.');
 await page.locator('#resetElement').click();await waitSaved();
 for(const width of [2160,1080,720])for(const language of ['de','en'])for(const layout of ['dock','list']){
  await page.selectOption('#resolution',String(width));await page.selectOption('#language',language);await page.selectOption('#layout',layout);
  for(const scene of ['home','apps','settings','editor','wallpaper','appearance.background','appearance.layout','appearance.typography','appearance.focus','appearance.rotation','appearance.clock','appearance.spotify','appearance.language','appearance.samsung','message.leave','message.signout','message.error','qr']){
   await show(scene);assert.ok(await page.locator('#layers button').count()>0,`${width} ${language} ${layout} ${scene}`);
  }
 }
 pass('Every owned view, including dialogs and the marked native QR preview, is editable in both layouts, languages and resolutions.');
 await show('home');await page.selectOption('#layout','dock');await page.selectOption('#resolution','1080');
 await frame().evaluate(()=>{
  CleanFrameDesign.set({version:1,rules:[{view:'home',layout:'dock',state:'*',target:'action:home.app.netflix',style:{offsetX:-300,offsetY:-240}},{view:'home',layout:'dock',state:'compact',target:'spotifyPlayer',style:{height:144,radius:32}}]});
  ShoreQA.focus('home.app.netflix');
 });
 const floating=await frame().evaluate(()=>{const e=CleanFrameDesign.element('action:home.app.netflix').el,r=e.getBoundingClientRect();return{hit:document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest('[data-action]')?.dataset.action,player:CleanFrameDesign.elements().find(e=>e.id==='spotifyPlayer').rect.height,radius:getComputedStyle(document.querySelector('.spotify-surface')).borderRadius};});
 assert.equal(floating.hit,'home.app.netflix');assert.equal(Math.round(floating.player),144);assert.equal(floating.radius,'32px');
 await frame().evaluate(()=>CleanFrameDesign.set({version:1,rules:[{view:'home',layout:'dock',state:'*',target:'caption',style:{opacity:0}}]}));
 assert.ok(await frame().evaluate(()=>CleanFrameDesign.elements().some(e=>e.id==='caption'&&!e.visible)));
 pass('Freely moved apps escape their original clip, player geometry follows its surface, and transparent elements remain editable.');
 await frame().evaluate(()=>{CleanFrameDesign.set({version:1,rules:[{view:'home',layout:'dock',state:'*',target:'dock',style:{offsetX:32}}]});ShoreQA.playerView(false,true);});await page.waitForTimeout(450);
 const dockY=await frame().evaluate(()=>document.getElementById('dock').getBoundingClientRect().top);
 await page.selectOption('#playerState','expanded');await page.waitForTimeout(500);
 assert.ok(await frame().evaluate(()=>CleanFrameDesign.elements().some(e=>e.action==='spotify.toggle')));
 const expandedY=await frame().evaluate(()=>document.getElementById('dock').getBoundingClientRect().top);assert.ok(Math.abs(dockY-expandedY-216)<2);
 await frame().evaluate(()=>CleanFrameMock.setScenario('long'));assert.equal((await state()).focusAction,'spotify.toggle');
 pass('Custom positioning preserves the Dock animation, exposes expanded controls and keeps media-update focus.');


 await page.selectOption('#resolution','1080');await page.selectOption('#layout','dock');await show('appearance.layout');
 const actions=await frame().evaluate(()=>{const q=s=>[...document.querySelectorAll('[data-action]')].find(e=>e.dataset.action===s);const a=q('choice.layout.list').getBoundingClientRect(),b=q('choice.layout.dock').getBoundingClientRect();return{dx:b.left-a.left};});
 await frame().evaluate(dx=>{CleanFrameDesign.set({version:1,rules:[{view:'appearance.layout',layout:'dock',state:'*',target:'action:choice.layout.list',style:{offsetX:dx}},{view:'appearance.layout',layout:'dock',state:'*',target:'action:choice.layout.dock',style:{offsetX:-dx}}]});ShoreQA.focus('choice.layout.dock');ShoreQA.nav('right');},actions.dx/(Number(await page.locator('#resolution').inputValue())/1080));
 assert.equal((await state()).focusAction,'choice.layout.list');assert.equal((await state()).layout,'dock');pass('Reordered choices follow visible geometry; focusing does not activate a choice.');
 await frame().evaluate(()=>CleanFrameDesign.set({version:1,rules:[{view:'appearance.layout',layout:'dock',state:'*',target:'action:choice.layout.list',style:{width:200}}]}));assert.equal(Math.round(await frame().evaluate(()=>CleanFrameDesign.elements().find(e=>e.id==='action:choice.layout.list').rect.width)),200);pass('A width override also resizes flexible menu choices.');
 await show('appearance.spotify');await frame().evaluate(()=>{CleanFrameDesign.set({version:1,rules:[{view:'appearance.spotify',layout:'dock',state:'*',target:'appearanceTitle',style:{offsetX:8}}]});ShoreQA.focus('spotify.transparency');ShoreQA.nav('right');});assert.equal((await state()).spotifyTransparency,65);pass('A slider retains left/right adjustment with spatial navigation enabled.');
 await show('message.signout');await frame().evaluate(()=>{CleanFrameDesign.set({version:1,rules:[{view:'message.signout',layout:'dock',state:'*',target:'action:message.signout',style:{offsetY:20}}]});ShoreQA.nav('right');});assert.match((await state()).focusAction,/^message\./);assert.deepEqual(await frame().evaluate(()=>CleanFrameMock.calls),[]);pass('Dialog focus stays trapped and simulated sign-out is not invoked by navigation.');
 await show('home');for(const scenario of ['playing','paused','long','missing','none','unlinked','denied','offline']){await page.selectOption('#scenario',scenario);await page.waitForTimeout(80);assert.equal(await frame().evaluate(()=>CleanFrameMock.scenario()),scenario);}await page.selectOption('#scenario','playing');pass('All Spotify fixtures update the production adapter without accessing an account.');
 await waitSaved();const config=await(await fetch(app.url+'/api/editor/state')).json();const post=(body,headers={})=>fetch(app.url+'/api/editor/save',{method:'POST',headers:{'Content-Type':'application/json','Origin':app.url,'X-CleanFrame-Token':config.token,...headers},body:JSON.stringify(body)});
 assert.equal((await post({etag:config.etag,project:config.project},{Origin:'https://example.com'})).status,403);
 const bad=structuredClone(config.project);bad.design.rules=[{view:'home',layout:'dock',state:'*',target:'caption',style:{background:'url(https://example.com)'}}];assert.equal((await post({etag:config.etag,project:bad})).status,400);
 assert.equal((await post({etag:'stale',project:config.project})).status,409);pass('Writes reject foreign origins, executable styles and stale revisions.');
 const css=join(root,'editor/editor.css'),stat=statSync(css);utimesSync(css,new Date(),new Date());await page.waitForTimeout(3400);utimesSync(css,stat.atime,stat.mtime);await page.waitForTimeout(3000);await ready(page);assert.equal((await page.evaluate(()=>CleanFrameEditor.project)).ui.scenario,'playing');pass('Source reload preserves the draft and selected scene.');
 await show('home');await select('spotifyPlayer');await waitSaved();mkdirSync(join(root,'.local/editor/qa'),{recursive:true});await page.screenshot({path:join(root,'.local/editor/qa/editor.png')});
 assert.deepEqual(errors,[]);writeFileSync(join(root,'.local/editor/qa/checks.json'),JSON.stringify({checks,errors,storage},null,2));
}finally{await page.close();await app.close();}
