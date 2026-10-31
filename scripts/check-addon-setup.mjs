import assert from 'node:assert/strict';
import {mkdtemp,rm,mkdir} from 'node:fs/promises';
import os from 'node:os';
import {openEditor,ready,root} from './editor-browser.mjs';
const storage=await mkdtemp(os.tmpdir()+'/cleanframe-setup-'),app=await openEditor(storage);
try {
 const page=await app.browser.newPage({viewport:{width:1720,height:1100}}), errors=[];
 page.on('pageerror',e=>errors.push(e.message));await page.goto(app.url+'/editor/');await ready(page);
 await page.evaluate(()=>window.CleanFrameEditor.win().CleanFrameNative.addons(JSON.stringify({action:'install',id:'apple-remote'})));
 await page.waitForFunction(()=>window.CleanFrameEditor.win().CleanFrameAddonMock.state().installed['apple-remote']);
 const act=a=>page.evaluate(a=>window.CleanFrameEditor.qa().activate(a),a);
 await page.selectOption('#scene','appearance.remote');await ready(page);
 await page.waitForFunction(()=>window.CleanFrameEditor.win().CleanFrameAddonSetup.remote().setupInterface===1);
 await act('remote.pairing-access');await page.waitForFunction(()=>window.CleanFrameEditor.win().CleanFrameAddonSetup.remote().automaticPairingAccess===true);
 await act('remote.applePairBegin');await page.waitForFunction(()=>window.CleanFrameEditor.win().document.querySelector('#remotePairPin')?.textContent==='1234');
 assert.match(await page.evaluate(()=>window.CleanFrameEditor.win().document.querySelector('#remotePairTime').textContent),/^[12]:[0-5][0-9]$/);
 assert.equal(await page.evaluate(()=>window.CleanFrameEditor.win().document.querySelector('#appearanceRows .is-focus').getAttribute('data-action')),'remote.applePairBegin');
 await act('remote.forget.0');await act('message.back');
 assert.equal(await page.evaluate(()=>window.CleanFrameEditor.win().CleanFrameAddonMock.state().runtime['apple-remote'].apple.pairedDevices),1);
 await mkdir(root+'.local/integration/previews',{recursive:true});
 for(const language of ['de','en'])for(const resolution of [720,1080,2160]){
  await page.selectOption('#language',language);await page.selectOption('#resolution',String(resolution));await ready(page);
  for(const scene of ['appearance.remote','appearance.spotify','appearance.spotify-link']){
   await page.selectOption('#scene',scene);await ready(page);await page.waitForTimeout(1100);
   const geometry=await page.evaluate(()=>{const d=window.CleanFrameEditor.win().document,p=d.getElementById('appearanceRows'),r=p.getBoundingClientRect();return {width:p.clientWidth,scroll:p.scrollWidth,right:r.right,viewport:d.documentElement.clientWidth,focused:p.querySelectorAll('.is-focus').length};});
   assert.ok(geometry.scroll<=geometry.width+2,JSON.stringify(geometry));assert.ok(geometry.right<=geometry.viewport+2,JSON.stringify(geometry));assert.equal(geometry.focused,1);
   await page.locator('#preview').screenshot({path:root+`.local/integration/previews/${scene}-${language}-${resolution}.png`});
  }
 }
 await page.selectOption('#scene','appearance.remote');await ready(page);await page.waitForTimeout(1100);await act('remote.forget.0');await act('message.remoteForget');
 await page.waitForFunction(()=>window.CleanFrameEditor.win().CleanFrameAddonMock.state().runtime['apple-remote'].apple.pairedDevices===0);
 await page.selectOption('#scene','appearance.spotify');await ready(page);await act('spotify.signin');
 await page.waitForFunction(()=>window.CleanFrameEditor.win().CleanFrameAddonSetup.link().phase==='pending');
 await page.evaluate(()=>window.CleanFrameEditor.qa().nav('back'));
 assert.equal(await page.evaluate(()=>JSON.parse(window.CleanFrameEditor.win().CleanFrameNative.spotifyLink('state')).phase),'idle');
 assert.deepEqual(errors,[]);console.log('Integrated add-on setup: pairing/countdown, focus preservation, removal confirmation, QR cancellation, DE/EN and 720p/1080p/4K passed.');
}finally{await app.close();await rm(storage,{recursive:true,force:true});}
