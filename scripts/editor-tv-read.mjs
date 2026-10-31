// Read only the design and display data from the running CleanFrame WebView.
// ADB forwarding and target selection are owned by editor_tv.py, never the browser.
import {chromium} from 'playwright';
import {prefProperties} from './editor-schema.mjs';

const port=Number(process.argv[2]);
if(!Number.isInteger(port)||port<1||port>65535)throw Error('Invalid local port');
const browser=await chromium.connectOverCDP('http://127.0.0.1:'+port,{timeout:10000});
try{
 const page=browser.contexts().flatMap(context=>context.pages()).find(page=>page.url()==='https://appassets.androidplatform.net/assets/home/index.html');
 if(!page)throw Error('Bitte CleanFrame über die Home-Taste am Fernseher öffnen und erneut versuchen.');
 const snapshot=await page.evaluate(keys=>{
  if(!window.ShoreQA?.ready()||!window.CleanFrameDesign?.ready())throw Error('CleanFrame ist noch nicht bereit. Bitte erneut versuchen.');
  const state=window.ShoreQA.state(),preferences={};
  keys.forEach(key=>{if(state.committed[key]!==undefined)preferences[key]=state.committed[key];});
  const apps=JSON.parse(window.CleanFrameNative.apps()).map(({appId,name})=>({appId,name}));
  const installed=window.CleanFrameAddons?.state().installed||{};
  const addons=Object.keys(installed).map(id=>{
   const item=installed[id],safe={};
   ['id','name','kind','version','enabled','text','timeZone'].forEach(key=>{if(item[key]!==undefined)safe[key]=item[key];});
   return safe;
  });
  return {preferences,design:window.CleanFrameDesign.get(),apps,addons};
 },Object.keys(prefProperties));
 process.stdout.write(JSON.stringify(snapshot));
}finally{await browser.close();}
