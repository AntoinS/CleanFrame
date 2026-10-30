import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {chromium} from 'playwright';
import {fileURLToPath} from 'node:url';
export const root=fileURLToPath(new URL('../',import.meta.url));
export async function openEditor(storage){
 const args=['scripts/editor_server.py','--port','0','--public-fonts'];if(storage)args.push('--storage',storage);
 const server=spawn('python3',args,{cwd:root,stdio:['ignore','pipe','pipe']});let browser,errors='';server.stderr.on('data',data=>errors+=data);
 try{const url=await new Promise((resolve,reject)=>{let out='';const timer=setTimeout(()=>reject(Error('Editor startup timed out '+errors)),10000);server.stdout.on('data',data=>{out+=data;const match=out.match(/http:\/\/127\.0\.0\.1:\d+/);if(match){clearTimeout(timer);resolve(match[0]);}});server.once('error',reject);server.once('exit',()=>{clearTimeout(timer);reject(Error(errors));});});browser=await chromium.launch();return{browser,url,close:async()=>{await browser.close();server.kill();await once(server,'exit');}};}catch(error){server.kill();if(browser)await browser.close();throw error;}
}
export async function ready(page){await page.waitForFunction(()=>window.CleanFrameEditor?.ready&&document.querySelector('#layers button')&&document.querySelector('#saveState').textContent==='Auf diesem Mac gespeichert');}
