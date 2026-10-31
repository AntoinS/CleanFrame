import assert from 'node:assert/strict';
import {mkdtemp,mkdir,rm,readFile} from 'node:fs/promises';
import os from 'node:os';
import {openEditor,ready,root} from './editor-browser.mjs';
const version=JSON.parse(await readFile(root+'package.json','utf8')).version;
const storage=await mkdtemp(os.tmpdir()+'/cleanframe-software-'),app=await openEditor(storage);
try {
 const page=await app.browser.newPage({viewport:{width:1720,height:1100}}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));await page.goto(app.url+'/editor/');await ready(page);
 await page.selectOption('#scene','settings');await ready(page);
 const win=fn=>page.evaluate(fn),act=action=>page.evaluate(action=>window.CleanFrameEditor.qa().activate(action),action);
 await page.waitForFunction(()=>window.CleanFrameEditor.win().CleanFrameSoftwareMock.state().updateAvailable);
 assert.ok((await win(()=>window.CleanFrameEditor.win().document.querySelector('.settings-version-button').textContent)).includes(version));
 assert.equal(await win(()=>window.CleanFrameEditor.win().document.querySelectorAll('.settings-version-button .addon-update-dot').length),1);
 await act('settings.group.software');assert.equal(await win(()=>window.CleanFrameEditor.win().document.querySelector('#appearanceTitle').textContent),'Softwareupdate');
 const preferences=await win(()=>window.CleanFrameEditor.win().localStorage.getItem('cleanframe.home.v1'));
 await win(()=>window.CleanFrameEditor.win().CleanFrameSoftwareMock.set({error:'cancelled',busy:false,pending:{}}));
 assert.match(await win(()=>window.CleanFrameEditor.win().document.querySelector('#appearanceStatus').textContent),/abgebrochen/);
 assert.notEqual(await win(()=>window.CleanFrameEditor.win().document.querySelector('[data-action="software.install"]').getAttribute('aria-disabled')),'true');
 await win(()=>window.CleanFrameEditor.win().CleanFrameSoftwareMock.set({error:'',addonsBusy:true}));
 assert.equal(await win(()=>window.CleanFrameEditor.win().document.querySelector('[data-action="software.install"]').getAttribute('aria-disabled')),'true');
 await win(()=>window.CleanFrameEditor.win().CleanFrameSoftwareMock.set({addonsBusy:false}));
 await act('software.install');await page.waitForFunction(()=>window.CleanFrameEditor.win().CleanFrameSoftwareMock.state().result==='installed');
 assert.equal(await win(()=>window.CleanFrameEditor.win().CleanFrameSoftware.state().current.versionCode),999);
 assert.equal(await win(()=>window.CleanFrameEditor.win().document.querySelectorAll('[data-action="software.install"]').length),0);
 assert.equal(await win(()=>window.CleanFrameEditor.win().localStorage.getItem('cleanframe.home.v1')),preferences);
 await mkdir(root+'.local/software/previews',{recursive:true});
 for(const language of ['de','en'])for(const resolution of [720,1080,2160]){
  await page.selectOption('#language',language);await page.selectOption('#resolution',String(resolution));await page.selectOption('#scene','appearance.software');await ready(page);
  await win(()=>window.CleanFrameEditor.win().CleanFrameSoftwareMock.set({current:{name:'CleanTV',version:'0.8.3',versionName:'0.8.3-android24',versionCode:24},updateAvailable:true,result:'',error:'',busy:false}));
  await page.locator('#preview').screenshot({path:root+`.local/software/previews/software-${language}-${resolution}.png`});
  const geometry=await win(()=>{const w=window.CleanFrameEditor.win(),el=w.document.getElementById('appearanceRows');return {width:el.clientWidth,scroll:el.scrollWidth,focus:el.querySelectorAll('.is-focus').length};});
  assert.ok(geometry.scroll<=geometry.width+2,JSON.stringify(geometry));assert.equal(geometry.focus,1);
 }
 assert.deepEqual(errors,[]);
 console.log('Software version footer, update notice, cancellation recovery, package-operation exclusion, preference retention and DE/EN 720p/1080p/4K passed.');
}finally{await app.close();await rm(storage,{recursive:true,force:true});}
