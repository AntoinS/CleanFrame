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
   const req=JSON.parse(value);if(req.action==='remoteState')return JSON.stringify(__addons.runtime?.['apple-remote']||{});if(req.action==='state')return JSON.stringify(__addons);__addonCalls.push(req);__addons.busy=true;
   setTimeout(()=>{if(req.action==='refresh')__addons.catalog=[{...spotify,description:'Spotify playback'},{id:'note',name:'Note',version:'1.0.0',kind:'text',text:'<script>test</script>',description:'Example'}];
    if(req.action==='install'){if(window.__cancelNext){__addons.error='Package operation cancelled.';window.__cancelNext=false;}else __addons.installed[req.id]={...__addons.catalog.find(p=>p.id===req.id),enabled:true};}
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
 await act('addon.details.spotify');await act('addon.remove.spotify');await page.keyboard.press('Escape');assert.ok(await page.evaluate(()=>__addons.installed.spotify));
 await act('addon.remove.spotify');await page.keyboard.press('ArrowRight');await page.keyboard.press('Enter');await ready();assert.equal(await page.evaluate(()=>!!__addons.installed.spotify),false);
 await scene('home');assert.equal(await page.locator('#spotifyPlayer').isVisible(),false);
 await scene('settings');await act('settings.group.addons');await act('addon.tab.discover');await act('addon.details.spotify');await act('addon.install.spotify');await ready();assert.ok(await page.evaluate(()=>__addons.installed.spotify));
 await page.keyboard.press('Escape');await act('addon.details.note');await act('addon.install.note');await ready();await scene('home');assert.equal(await page.locator('.addon-widget p').textContent(),'<script>test</script>');assert.equal(await page.locator('.addon-widget script').count(),0);
 await scene('settings');await act('settings.group.addons');await act('addon.details.note');await act('addon.toggle.note');await ready();await scene('home');assert.equal(await page.locator('.addon-widget').count(),0);
 // Native packages remain distinct from Home widgets; an aborted Android dialog is not installation.
 await page.evaluate(()=>{const remote={id:'apple-remote',name:'iPhone Fernbedienung',version:'0.1.0',kind:'native',packageName:'com.cleanframe.addon.appleremote',author:'CleanFrame',license:'MIT',permissions:['Local network','Foreground service'],description:'Navigation und Texteingabe mit der iPhone Fernbedienung'};__addons.catalog.push(remote);window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:structuredClone(__addons)}));});
 await scene('settings');await act('settings.group.addons');await act('addon.tab.discover');await act('addon.details.apple-remote');
 await page.evaluate(()=>window.__cancelNext=true);await act('addon.install.apple-remote');await ready();assert.equal(await page.evaluate(()=>!!__addons.installed['apple-remote']),false);assert.match(await page.locator('#appearanceStatus').textContent(),/cancelled/);
 await act('addon.install.apple-remote');await ready();await act('addon.setup.apple-remote');assert.equal(await page.locator('#appearanceTitle').textContent(),'iPhone Remote');await page.keyboard.press('Escape');
 await page.evaluate(()=>{__addons.runtime={'apple-remote':{enabled:true,running:true,android:{state:'connected'},apple:{state:'listening',devices:[]}}};__addons.error='';window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:structuredClone(__addons)}));});
 assert.match(await page.locator('#addonConnections').textContent(),/Installiert/);assert.match(await page.locator('#addonConnections').textContent(),/Nicht gekoppelt/);
 assert.equal(await page.locator('#appearanceRows .is-focus').getAttribute('data-action'),'addon.setup.apple-remote');
 await page.keyboard.press('Escape');assert.equal(await page.locator('#appearanceRows .is-focus').getAttribute('data-action'),'addon.details.apple-remote');
 await scene('home');assert.equal(await page.locator('.addon-widget[data-addon="apple-remote"]').count(),0);
 // Status events keep the selected package and scroll position, even in a longer catalog.
 await page.evaluate(()=>{for(let i=0;i<12;i++)__addons.catalog.push({id:'test-'+i,name:'Test extension '+i,version:'1.0.0',kind:'text',description:'A long descriptive title for an extension that must remain inside its card.'});window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:structuredClone(__addons)}));});
 await scene('settings');await act('settings.group.addons');await act('addon.tab.discover');await act('addon.details.test-11');await page.keyboard.press('Escape');
 const scroll=await page.locator('#appearanceRows').evaluate(e=>e.scrollTop);assert.ok(scroll>0);
 await page.evaluate(()=>window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:structuredClone(__addons)})));
 assert.equal(await page.locator('#appearanceRows .is-focus').getAttribute('data-action'),'addon.details.test-11');assert.equal(await page.locator('#appearanceRows').evaluate(e=>e.scrollTop),scroll);
 await page.evaluate(()=>{__addons.catalog=__addons.catalog.filter(p=>!p.id.startsWith('test-'));window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:structuredClone(__addons)}));});
 for(const width of [1920,1280,3840])for(const language of ['de','en']){
  await page.setViewportSize({width,height:width*9/16});await scene('settings');await act('settings.group.language');await act('choice.language.'+language);
  for(const group of ['display','homescreen','addons','addon-detail','native-detail']){await scene('settings');if(group==='addon-detail'||group==='native-detail'){await act('settings.group.addons');await act('addon.details.'+(group==='native-detail'?'apple-remote':'spotify'));}else await act('settings.group.'+group);await page.screenshot({path:root+`.local/qa/addons-${group}-${language}-${width}.png`,animations:'disabled'});assert.equal(await page.locator('#appearanceRows .is-focus').count(),1); assert.deepEqual(await page.locator('#appearanceRows .setting-copy').evaluateAll(items=>items.filter(e=>e.scrollWidth>e.clientWidth+2).map(e=>e.textContent)),[]);}
 }
 await page.evaluate(()=>{__addons.catalog=[];__addons.installed={};__addons.error='Offline';window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:structuredClone(__addons)}));});await scene('settings');await act('settings.group.addons');assert.equal(await page.locator('#addonEmpty').count(),1);await act('addon.tab.discover');assert.equal(await page.locator('#addonEmpty').count(),1);
 assert.deepEqual(errors,[]);console.log('Package tabs/cards/details, native setup/cancellation/runtime, async focus/scroll, empty/offline catalog, uninstall/reinstall and escaped widgets passed; DE/EN at 720p/1080p/4K. Native network/storage are covered separately.');
}finally{await page.close();await preview.close();}
