import assert from 'node:assert/strict';
import {mkdirSync} from 'node:fs';
import {openPreview,root} from './browser.mjs';
const preview=await openPreview({publicFonts:true});
const page=await preview.browser.newPage({viewport:{width:1920,height:1080}});
const errors=[];page.on('pageerror',e=>errors.push(e.message));
mkdirSync(root+'.local/qa',{recursive:true});
try {
 await page.addInitScript(()=>{
  const spotify={id:'spotify',name:'Spotify',version:'1.0.0',kind:'spotify',enabled:true};
  window.__addons={installed:{spotify},catalog:[],busy:false,error:''}; window.__addonCalls=[];
  window.CleanFrameNative={apps:()=>JSON.stringify([{appId:'com.google.android.youtube.tv',name:'YouTube'}]),spotifyState:()=>JSON.stringify({status:'playing',title:'Test song',artist:'Artist',duration:180000,position:0,permission:true}),spotifyObserve:()=>'{"ok":true}',addons:value=>{
   const req=JSON.parse(value);if(req.action==='state')return JSON.stringify(__addons);__addonCalls.push(req);__addons.busy=true;
   setTimeout(()=>{if(req.action==='refresh')__addons.catalog=[{...spotify,description:'Spotify playback'},{id:'note',name:'Note',version:'1.0.0',kind:'text',text:'<script>test</script>',description:'Example'}];
    if(req.action==='install')__addons.installed[req.id]={...__addons.catalog.find(p=>p.id===req.id),enabled:true};
    if(req.action==='remove')delete __addons.installed[req.id];
    if(req.action==='toggle')__addons.installed[req.id].enabled=!__addons.installed[req.id].enabled;
    __addons.busy=false;window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:structuredClone(__addons)}));},30);return '{"ok":true}';}};
  localStorage.setItem('cleanframe.home.v1',JSON.stringify({language:'de',bg:'photo',wall:'summit',home:['youtube']}));
 });
 await page.goto(preview.baseURL+'/home/');await page.waitForFunction(()=>ShoreQA.ready());
 const act=a=>page.evaluate(a=>ShoreQA.activate(a),a);
 const scene=s=>page.evaluate(s=>ShoreQA.scene(s),s);
 const ready=()=>page.waitForFunction(()=>!__addons.busy);
 await scene('settings');assert.equal(await page.locator('.settings-group').count(),5);
 await act('settings.group.display');await act('group.background');await page.keyboard.press('Escape');assert.equal(await page.locator('#appearanceTitle').textContent(),'Anzeige');
 await scene('settings');await act('settings.group.addons');await act('addon.refresh');await ready();
 await act('addon.remove.spotify');await page.keyboard.press('Escape');assert.ok(await page.evaluate(()=>__addons.installed.spotify));
 await act('addon.remove.spotify');await page.keyboard.press('ArrowRight');await page.keyboard.press('Enter');await ready();assert.equal(await page.evaluate(()=>!!__addons.installed.spotify),false);
 await scene('home');assert.equal(await page.locator('#spotifyPlayer').isVisible(),false);
 await scene('settings');await act('settings.group.addons');await act('addon.install.spotify');await ready();assert.ok(await page.evaluate(()=>__addons.installed.spotify));
 await act('addon.install.note');await ready();await scene('home');assert.equal(await page.locator('.addon-widget p').textContent(),'<script>test</script>');assert.equal(await page.locator('.addon-widget script').count(),0);
 await scene('settings');await act('settings.group.addons');await act('addon.toggle.note');await ready();await scene('home');assert.equal(await page.locator('.addon-widget').count(),0);
 for(const width of [1920,1280])for(const language of ['de','en']){
  await page.setViewportSize({width,height:width*9/16});await scene('settings');await act('settings.group.language');await act('choice.language.'+language);
  for(const group of ['display','homescreen','addons']){await scene('settings');await act('settings.group.'+group);await page.screenshot({path:root+`.local/qa/addons-${group}-${language}-${width}.png`,animations:'disabled'});assert.equal(await page.locator('#appearanceRows .is-focus').count(),1); assert.deepEqual(await page.locator('#appearanceRows .setting-copy').evaluateAll(items=>items.filter(e=>e.scrollWidth>e.clientWidth+2).map(e=>e.textContent)),[]);}
 }
 assert.deepEqual(errors,[]);console.log('Grouped settings, nested Back, catalog, uninstall confirmation, reinstall, enable/disable, escaped widget content, DE/EN at 1080p/720p passed. Native network/storage are covered separately.');
}finally{await page.close();await preview.close();}
