// Check the frozen design itself, separately from the baseline regression suite.
import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {openEditor,ready,root} from './editor-browser.mjs';
const release=process.argv[2];if(!release)throw Error('Release path required');
const out=join(release,'previews');mkdirSync(out,{recursive:true});
const app=await openEditor(join(root,'.local/editor'));
const page=await app.browser.newPage({viewport:{width:1600,height:1000}}),errors=[],reports=[];
page.on('pageerror',error=>errors.push(error.message));
const views=['appearance.software','home','settings','apps','editor','wallpaper','appearance.background','appearance.layout','appearance.typography','appearance.focus','appearance.rotation','appearance.clock','appearance.spotify','appearance.language','appearance.samsung','message.leave','message.signout','message.error','qr'];
try{
 await page.goto(app.url+'/editor/');await ready(page);await page.selectOption('#scenario','playing');
 for(const resolution of [2160,1080,720])for(const language of ['de','en'])for(const layout of ['dock','list']){
  await page.selectOption('#resolution',String(resolution));await page.selectOption('#language',language);await page.selectOption('#layout',layout);
  for(const view of views){
   await page.selectOption('#scene',view);await page.waitForTimeout(120);
   const frame=page.frames().find(f=>f.url().includes('/home/'));
   await frame.addStyleTag({content:'*,*::before,*::after{animation-duration:0s!important;transition-duration:0s!important}'});
   const report=await frame.evaluate(()=>{
    const context=CleanFrameDesign.context(),items=CleanFrameDesign.elements(),issues=[];
    const visibleActions=items.filter(e=>e.visible&&(e.action||e.id==='qrBack'));
    for(const item of visibleActions){const node=CleanFrameDesign.element(item.id)?.el;if(!node||node.getAttribute('aria-disabled')==='true')continue;
     const r=item.rect;let parent=node,customized=false;while(parent){const entry=items.find(e=>e.id===parent.getAttribute('data-design-id'));if(entry&&Object.keys(entry.style).some(k=>['offsetX','offsetY','width','height'].includes(k)))customized=true;parent=parent.parentElement;}const scroll=!customized&&node.closest('#phList,#shelf,.grid-view,.ed-other-view');
     if(!scroll&&(r.x< -2||r.y< -2||r.x+r.width>1922||r.y+r.height>1082))issues.push(item.id+': outside stage');
     if(r.width<8||r.height<8)issues.push(item.id+': too small');
    }
    // Explore directional edges of altered menus without activating any action.
    if(CleanFrameDesign.geometry()&&context.view!=='qr'&&context.view!=='home'){
     const actions=visibleActions.filter(e=>{const n=CleanFrameDesign.element(e.id).el;return n.getAttribute('aria-disabled')!=='true'&&e.rect.x>=0&&e.rect.y>=0&&e.rect.x+e.rect.width<=1922&&e.rect.y+e.rect.height<=1082;}).map(e=>e.action);
     const start=ShoreQA.state().focusAction,edges={};
     for(const action of actions){edges[action]=[];for(const key of ['left','right','up','down']){if(!ShoreQA.focus(action))continue;ShoreQA.nav(key);const next=ShoreQA.state().focusAction;if(next&&next!==action)edges[action].push(next);}}
     const reached=new Set([start]),pending=[start];while(pending.length){const current=pending.shift();for(const next of edges[current]||[])if(!reached.has(next)){reached.add(next);pending.push(next);}}
     for(const action of actions)if(!reached.has(action))issues.push(action+': not reachable with directional keys');
     if(start)ShoreQA.focus(start);
    }
    if(CleanFrameDesign.geometry()&&context.view==='home'){
     const available=()=>CleanFrameDesign.elements().filter(e=>e.visible&&e.action&&CleanFrameDesign.element(e.id).el.getAttribute('aria-disabled')!=='true').map(e=>e.action);
     const start=ShoreQA.state().focusAction,all=new Set(available());ShoreQA.playerView(true,true);available().forEach(a=>all.add(a));all.delete('spotify.expand');
     for(const required of ['util.apps','util.settings'])if(!all.has(required))issues.push(required+': hidden');
     const edges={};for(const action of all){edges[action]=[];for(const key of ['left','right','up','down']){ShoreQA.playerView(action.startsWith('spotify.'),true);if(!ShoreQA.focus(action))continue;ShoreQA.nav(key);edges[action].push(ShoreQA.state().focusAction);}}
     const reached=new Set([start]),pending=[start];while(pending.length){for(const next of edges[pending.shift()]||[])if(next&&!reached.has(next)){reached.add(next);pending.push(next);}}
     for(const action of all)if(!reached.has(action))issues.push(action+': not reachable with directional keys');
     ShoreQA.playerView(false,true);if(start)ShoreQA.focus(start);
    }
    return {context,elementCount:items.length,issues};
   });
   reports.push({resolution,language,layout,view,...report});
   if(['home','settings','appearance.spotify','qr','message.signout'].includes(view))await page.locator('#surface').screenshot({path:join(out,`${layout}-${language}-${resolution}-${view}.png`)});
   if(view==='home'){
    await page.selectOption('#playerState','expanded');await page.waitForTimeout(120);await page.locator('#surface').screenshot({path:join(out,`${layout}-${language}-${resolution}-player-expanded.png`)});await page.selectOption('#playerState','compact');
   }
  }
 }
 writeFileSync(join(release,'layout-report.json'),JSON.stringify({reports,errors,nativeQr:'Approximation only; native TV inspection pending.'},null,2));
 assert.deepEqual(errors,[]);
 const failed=reports.filter(r=>r.issues.length);assert.deepEqual(failed.map(r=>({view:r.view,layout:r.layout,language:r.language,resolution:r.resolution,issues:r.issues})),[],'The draft contains unreachable or clipped controls.');
 console.log(`${reports.length} frozen-design view checks passed; previews saved.`);
}finally{await page.close();await app.close();}
