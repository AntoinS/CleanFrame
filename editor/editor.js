/* Local visual editor. Production UI and its preference paths remain authoritative. */
import {pixelRatio,snapBox} from './geometry.js';
import {initWorkspace} from './workspace.js';
const $=id=>document.getElementById(id), clone=value=>JSON.parse(JSON.stringify(value));
const scenes={'appearance.display':'Anzeige','appearance.homescreen':'Startbildschirm','appearance.addons':'Add-ons',home:'Home',apps:'Alle Apps',settings:'Einstellungen',editor:'Apps auf Home',wallpaper:'Hintergrundauswahl','appearance.background':'Hintergrund','appearance.layout':'Layout','appearance.typography':'Schrift','appearance.focus':'Fokus','appearance.rotation':'Bildwechsel','appearance.clock':'Uhr und Datum','appearance.spotify':'Spotify','appearance.language':'Sprache','appearance.samsung':'Android TV','message.leave':'Home verlassen','message.signout':'Spotify abmelden','message.error':'Fehlermeldung',qr:'Spotify QR Anmeldung'};
const prefDefs={bg:['Hintergrund',{photo:'Foto',dark:'Dunkel',light:'Hell'}],wall:['Foto',Object.fromEntries(['summit','cove','forest','valley','peaks','alpine','ridge','amber','mint','sydney','goldcoast','bridge','night','golden','misty'].map(x=>[x,x]))],apps:['App Darstellung',{icons:'Symbole',text:'Text',both:'Symbole und Text'}],controls:['Home Bedienelemente',{icons:'Symbole',text:'Text',both:'Symbole und Text'}],controlsPosition:['Position der Bedienelemente',{auto:'Automatisch',top:'Oben rechts',bottom:'Unten rechts'}],font:['Schriftfamilie',{manrope:'Manrope',geist:'Geist',newsreader:'Newsreader'}],focus:['Auswahl',{glass:'Glas',type:'Schrift'}],material:['Material',{soft:'Weich',frost:'Matt',tint:'Klar'}],spotifySurface:['Playerfläche',{dark:'Dunkel',light:'Hell',cover:'Coverfarbe'}],spotifyTransparency:['Player Transparenz',null],dateVisible:['Datum anzeigen',true],spotifyEnabled:['Spotify Player anzeigen',true]};
let project,etag,token,source,dirty=false,saving=null,saveTimer,conflicted=false,ready=false,loading=false,entries=[],selected=null,dragging=false,lastList='',revision=0;
const iframe=$('preview'), win=()=>iframe.contentWindow, qa=()=>win()?.ShoreQA, design=()=>win()?.CleanFrameDesign;
function notice(message){$('notice').textContent=message;}
function status(message){$('saveState').textContent=message;}
async function request(path,body){
 const response=await fetch('/api/editor/'+path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json','X-CleanFrame-Token':token},body:JSON.stringify({etag,...body})});
 let result;try{result=await response.json();}catch{throw Error('Der lokale Editor ist nicht erreichbar. Starte ihn erneut.');}
 if(response.status===409){conflicted=true;$('conflict').hidden=false;$('conflict').querySelector('span').textContent=result.error;}
 if(!response.ok)throw Error(result.error||'Der Vorgang ist fehlgeschlagen.');
 return result;
}
function changed(){dirty=true;revision++;status('Änderungen werden gespeichert …');clearTimeout(saveTimer);saveTimer=setTimeout(()=>save().catch(report),450);}
async function save(){
 if(saving){await saving;if(dirty)return save();return;}
 if(!dirty||conflicted)return;
 const sent=revision,body=clone(project);
 saving=request('save',{project:body}).then(result=>{etag=result.etag;dirty=revision!==sent;status(dirty?'Weitere Änderungen …':'Auf diesem Mac gespeichert');}).finally(()=>{saving=null;});
 await saving;if(dirty)return save();
}
function report(error){status('Nicht gespeichert');notice(error.message);}
function content(){return {design:clone(project.design),preferences:clone(project.preferences),guides:clone(project.guides||[])};}
function remember(){project.undo??=[];project.undo.push(content());if(project.undo.length>50)project.undo.shift();project.redo=[];historyButtons();}
function historyButtons(){$('undo').disabled=!project?.undo?.length;$('redo').disabled=!project?.redo?.length;}
async function history(direction){
 const stack=project[direction];if(!stack.length)return;
 const target=stack.pop(),other=direction==='undo'?'redo':'undo';project[other].push(content());
 project.design=target.design;project.preferences=target.preferences;project.guides=target.guides||[];historyButtons();changed();await loadFrame();
}
function uiChange(key,value){project.ui[key]=value;changed();}
function syncControls(){
 $('scene').value=project.ui.view;$('layout').value=project.preferences.layout;$('language').value=project.preferences.language;$('resolution').value=project.ui.resolution;
 $('scenario').value=project.ui.scenario;$('playerState').value=project.ui.state;$('snap').checked=project.ui.snap;$('scope').value=project.ui.scope;
 $('editMode').classList.toggle('active',project.ui.mode==='edit');$('testMode').classList.toggle('active',project.ui.mode==='test');
 $('editMode').setAttribute('aria-pressed',project.ui.mode==='edit');$('testMode').setAttribute('aria-pressed',project.ui.mode==='test');
 $('overlay').hidden=project.ui.mode!=='edit';$('canvasHint').textContent=project.ui.mode==='edit'?'Element anklicken und ziehen. Alt für feine Schritte.':'Mit Pfeiltasten, Enter und Escape ausprobieren.';
 $('nativeBadge').hidden=project.ui.view!=='qr';$('viewTitle').textContent=scenes[project.ui.view]||'Vorschau';
 Object.entries(prefDefs).forEach(([key])=>{const el=$('pref-'+key);if(!el)return;if(el.type==='checkbox')el.checked=project.preferences[key];else el.value=project.preferences[key];});
 if(project.baseCapturedAt){const date=new Date(project.baseCapturedAt);$('baselineAge').textContent='Ausgangsstand: lokale Sicherung vom '+(Number.isNaN(date.getTime())?project.baseCapturedAt:date.toLocaleDateString('de-DE'))+'.';}historyButtons();resize();
}
function resize(){
 const area=$('canvas'),css=getComputedStyle(area),availableWidth=area.clientWidth-parseFloat(css.paddingLeft)-parseFloat(css.paddingRight),availableHeight=area.clientHeight-parseFloat(css.paddingTop)-parseFloat(css.paddingBottom)-$('canvasHint').offsetHeight-16-$('canvas').querySelector('.canvas-caption').offsetHeight-36;
 const res=Number(project?.ui.resolution||1080),frameWidth=res*16/9,zoom=$('zoom')?.value||'fit',nativeZoom=zoom!=='fit'&&!document.body.classList.contains('live-mode'),width=nativeZoom?frameWidth*Number(zoom):Math.max(160,Math.min(availableWidth,availableHeight*16/9));
 area.classList.toggle('native-zoom',nativeZoom);
 $('surface').style.width=width+'px';
 iframe.width=frameWidth;iframe.height=res;iframe.style.width=frameWidth+'px';iframe.style.height=res+'px';iframe.style.transform=`scale(${width/frameWidth})`;
 $('overlay').style.transform=`scale(${width/1920})`;$('dimensions').textContent=`${frameWidth} × ${res}`;
}
async function waitReady(){for(let i=0;i<150;i++){if(qa()?.ready()&&design()?.ready())return;await new Promise(r=>setTimeout(r,40));}throw Error('Die Vorschau konnte nicht vollständig geladen werden.');}
async function loadFrame(){
 if(loading)return;loading=true;ready=false;await save();status('Vorschau wird geladen …');
 const state=project.ui.view,focus=qa()?.state()?.focusAction;
 await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Die Vorschau antwortet nicht.')),15000);iframe.onload=()=>{clearTimeout(timer);resolve();};iframe.src='/home/?editor=1&v='+Date.now();});
 try{await waitReady();win().addEventListener('cleanframe:preview-notice',event=>notice(event.detail));
  win().document.addEventListener('keydown',event=>{if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==='z'){event.preventDefault();event.stopImmediatePropagation();history(event.shiftKey?'redo':'undo');}},true);
  design().set(clone(project.design));
  await showScene(state);if(project.ui.mode==='test'&&focus)qa().focus(focus);
  ready=true;syncControls();refresh(true);notice('Vorschau mit Beispieldaten. Dein TV bleibt unverändert.');
 }finally{loading=false;if(!dirty)status('Auf diesem Mac gespeichert');}
}
async function showScene(scene){
 await waitReady();
 if(scene==='qr'){qa().editorScene('home');win().CleanFrameQR.show();}
 else{win().CleanFrameQR?.hide();qa().editorScene(scene);if(scene==='home')qa().playerView(project.ui.state==='expanded',project.ui.mode==='edit');}
 await waitReady();syncControls();refresh(true);
}
function context(){return design().context();}
function currentRule(create=false){
 if(!selected)return null;
 const c=context(),target=project.ui.scope==='template'?'type:'+selected.type:selected.id;
 const state=selected.id.toLowerCase().includes('spotify')&&c.view==='home'?c.state:'*';
 const layout=c.view==='qr'?'*':c.layout;
 let rule=project.design.rules.find(r=>r.view===c.view&&r.layout===layout&&r.state===state&&r.target===target);
 if(!rule&&create){rule={view:c.view,layout,state,target,style:{}};project.design.rules.push(rule);}
 return rule;
}
function apply(){design().set(clone(project.design));if(project.ui.view==='qr')win().CleanFrameQR.apply();refresh(true);}
function patch(properties,{record=true}={}){
 if(!selected)return;
 if(selected.id.includes('spotify-surface')&&properties.opacity!==undefined){preference('spotifyTransparency',Math.round((1-properties.opacity)*100/5)*5);return;}if(record)remember();const rule=currentRule(true);
 Object.entries(properties).forEach(([key,value])=>{if(value===null)delete rule.style[key];else rule.style[key]=value;});
 project.design.rules=project.design.rules.filter(r=>Object.keys(r.style).length);apply();changed();
}
function select(id){project.ui.selection=id;selected=entries.find(e=>e.id===id)||null;changed();refresh(true);}
function refresh(force=false){
 if(!qa()||dragging&&!force)return;
 const actual=design().context().view;
 if(ready&&project.ui.mode==='test'&&actual!==project.ui.view){project.ui.view=actual;changed();syncControls();}
 entries=design().elements();selected=entries.find(e=>e.id===project.ui.selection)||null;
 const filter=$('filter').value.toLowerCase(),key=entries.map(e=>e.id+'|'+e.name).join(';')+'|'+filter+'|'+project.ui.selection;
 if(key!==lastList){lastList=key;const fragment=document.createDocumentFragment();
  entries.filter(e=>(e.name+' '+e.id).toLowerCase().includes(filter)).forEach(e=>{const button=document.createElement('button');button.className='layer'+(e.id===project.ui.selection?' selected':'');button.dataset.element=e.id;button.title=e.name;
   let depth=0,parent=e.parent;while(parent&&depth<3){depth++;parent=entries.find(x=>x.id===parent)?.parent;}
   button.style.paddingLeft=12+depth*8+'px';const glyph=document.createElement('span');glyph.className='glyph';glyph.textContent=e.action?'◇':e.type==='img'?'▧':'▫';const name=document.createElement('span');name.className='layer-name';name.textContent=friendly(e.name);button.append(glyph,name);button.onclick=()=>select(e.id);fragment.append(button);
  });$('layers').replaceChildren(fragment);$('layerCount').textContent=entries.length;}
 $('emptyInspector').hidden=!!selected;$('inspector').hidden=!selected;$('selection').hidden=!selected||project.ui.mode!=='edit';
 if(selected){const r=selected.rect;Object.assign($('selection').style,{left:r.x+'px',top:r.y+'px',width:r.width+'px',height:r.height+'px'});$('selectionName').textContent=friendly(selected.name);$('elementName').textContent=friendly(selected.name);if(!dragging)updateFields();}
 if(ready&&!loading){const prefs=qa().state().committed,changes={};Object.keys(project.preferences).forEach(k=>{if(prefs[k]!==undefined&&JSON.stringify(prefs[k])!==JSON.stringify(project.preferences[k]))changes[k]=clone(prefs[k]);});if(Object.keys(changes).length){remember();Object.assign(project.preferences,changes);changed();syncControls();}}
 document.dispatchEvent(new CustomEvent('editor:refresh'));
}
function friendly(name){const labels={'spotify-surface':'Playerfläche','spotify-surface-expanded':'Große Playerfläche','spotify-compact-art':'Coverrahmen','spotify-compact-copy':'Titel und Interpret','spotify-progress':'Fortschrittsbalken','u-content':'Inhalt','ico':'Symbol','mark':'App Symbol','mk':'App Symbol','sheet-veil':'Hintergrundabdunklung','setting-copy':'Beschriftung','setting-options':'Auswahlmöglichkeiten','setting-tick':'Häkchen','settings-group-copy':'Beschriftung','clock-date':'Datum','clock':'Uhrzeit'};if(labels[name])return labels[name];return String(name).replace(/^action:/,'').replace(/\bis-[\w-]+/g,'').trim().replace(/-/g,' ').replace(/^./,c=>c.toUpperCase());}
function colorHex(value){if(value.startsWith('#'))return value.slice(0,7);const rgb=value.match(/\d+(?:\.\d+)?/g);return rgb?'#'+rgb.slice(0,3).map(x=>Math.round(+x).toString(16).padStart(2,'0')).join(''):'#000000';}
const fields=[
 ['geometryFields','x','X',-1920,1920],['geometryFields','y','Y',-1080,1080],['geometryFields','width','Breite',8,1920],['geometryFields','height','Höhe',8,1080],
 ['typeFields','fontFamily','Schriftfamilie','font'],['typeFields','fontSize','Schriftgröße',8,144],['typeFields','fontWeight','Stärke',100,700,100],['typeFields','lineHeight','Zeilenhöhe',8,200],['typeFields','letterSpacing','Zeichenabstand',-10,40,.5],['typeFields','padding','Innenabstand',0,96],['typeFields','spacing','Außenabstand',0,96],
 ['surfaceFields','radius','Ecken',0,160],['surfaceFields','opacity','Deckkraft %',0,100],['surfaceFields','backgroundAlpha','Fläche transparent %',0,100],['surfaceFields','color','Schriftfarbe','color'],['surfaceFields','background','Flächenfarbe','color'],
 ...['Top','Right','Bottom','Left'].flatMap((side,i)=>[['boxFields','padding'+side,'Padding '+['oben','rechts','unten','links'][i],0,400],['boxFields','margin'+side,'Margin '+['oben','rechts','unten','links'][i],0,400]]),['boxFields','gap','Abstand der Kinder',0,400]
];
const dimensional=key=>!['fontFamily','fontWeight','opacity','backgroundAlpha','color','background'].includes(key);
function createFields(){for(const [group,key,label,min,max,step]of fields){const box=document.createElement('label');box.className='field';box.textContent=label;const input=document.createElement(min==='font'?'select':'input');if(min==='font'){input.add(new Option('Manrope','Manrope'));input.add(new Option('Geist','Study Geist'));input.add(new Option('Newsreader','Study Newsreader'));box.classList.add('full');}input.id='field-'+key;input.dataset.property=key;if(min!=='font')input.type=min==='color'?'color':'number';if(min!=='color'&&min!=='font'){input.min=min;input.max=max;input.step=step||1;}const reset=document.createElement('button');reset.type='button';reset.className='reset-prop';reset.textContent='↶';reset.title=label+' zurücksetzen';reset.onclick=()=>patch({[key==='x'?'offsetX':key==='y'?'offsetY':key==='backgroundAlpha'?'background':key]:null});input.onchange=()=>{if(!selected)return;let value=input.type==='color'||min==='font'?input.value:Number(input.value);if(input.type!=='color'&&min!=='font'&&(!Number.isFinite(value)||!input.checkValidity()||value<Number(input.min)||value>Number(input.max))){input.reportValidity();updateFields();return;}if(dimensional(key)&&typeof value==='number')value/=pixelRatio(project.ui.resolution);const rule=currentRule(),base=rule?.style||{},effective=selected.style;let prop=key;
 if(key==='backgroundAlpha'){if(selected.id.includes('spotify-surface')){preference('spotifyTransparency',Math.round(value/5)*5);return;}prop='background';value=colorHex(base.background||selected.computed.background)+Math.round((1-value/100)*255).toString(16).padStart(2,'0');}if(key==='x'||key==='y'){prop=key==='x'?'offsetX':'offsetY';value=(effective[prop]||0)+value-selected.rect[key];}if(key==='opacity')value/=100;if(key==='background'&&base.background?.length===9)value+=base.background.slice(7);patch({[prop]:value});};box.append(input,reset);$(group).append(box);}}
function updateFields(){if(!selected)return;for(const [,key]of fields){const input=$('field-'+key);if(document.activeElement===input)continue;let value=key==='x'||key==='y'||key==='width'||key==='height'?selected.rect[key]:selected.style[key]??selected.computed[key];if(key==='backgroundAlpha'){const bg=selected.style.background||selected.computed.background;const parts=bg?.match(/[\d.]+/g);value=selected.id.includes('spotify-surface')?project.preferences.spotifyTransparency:Math.round(100*(1-(bg?.startsWith('#')?(bg.length===9?parseInt(bg.slice(7),16)/255:1):parts?.length===4?Number(parts[3]):1)));}else if(key==='fontFamily')value=value||'Manrope';else if(/^(padding|margin)/.test(key)||key==='gap')value=parseFloat(win().getComputedStyle(design().element(selected.id).el)[key==='padding'?'paddingTop':key])||0;else if(input.type==='color')value=colorHex(value||'#000000');else value=key==='opacity'?Math.round(value*1000)/10:Number(value);if(dimensional(key)){const def=fields.find(f=>f[1]===key),ratio=pixelRatio(project.ui.resolution);value=Math.round(value*ratio*100)/100;input.min=def[3]*ratio;input.max=def[4]*ratio;input.step='any';}input.value=value??'';input.closest('label').querySelector('.reset-prop').hidden=currentRule()?.style[key==='x'?'offsetX':key==='y'?'offsetY':key==='backgroundAlpha'?'background':key]===undefined;}}
function makePreferences(){Object.entries(prefDefs).forEach(([key,[title,values]])=>{const label=document.createElement('label');label.textContent=title;let input;if(values===true){input=document.createElement('input');input.type='checkbox';label.className='check';}else if(values){input=document.createElement('select');for(const[value,name]of Object.entries(values)){const option=new Option(name,value);input.add(option);}}else{input=document.createElement('input');input.type='range';input.min=0;input.max=100;input.step=5;}input.id='pref-'+key;input.onchange=()=>preference(key,input.type==='checkbox'?input.checked:input.type==='range'?Number(input.value):input.value);label.append(input);$('preferences').append(label);});}
function preference(key,value){if(!ready)return;remember();qa().preferences({[key]:value});project.preferences[key]=value;changed();syncControls();refresh(true);}
function setMode(mode){if(project.ui.mode===mode)return;uiChange('mode',mode);syncControls();if(project.ui.view==='home')qa().playerView(project.ui.state==='expanded',mode==='edit');refresh(true);if(mode==='test')iframe.focus();}
function remote(key){if(!ready||document.body.classList.contains('live-mode'))return;setMode('test');if(win().CleanFrameQR?.visible()){if(key==='back'||key==='ok'){win().CleanFrameQR.hide();qa().editorScene('appearance.spotify');}refresh(true);return;}qa().nav(key);refresh(true);}
function inspectAt(event){if(event.target.closest('.user-guide,#rulerX,#rulerY')||event.shiftKey||event.target.closest('#selection'))return;const area=$('surface').getBoundingClientRect(),factor=Number(project.ui.resolution)*16/9/area.width;const nodes=win().document.elementsFromPoint((event.clientX-area.left)*factor,(event.clientY-area.top)*factor);let el=nodes.find(n=>n.hasAttribute('data-design-id'));if(!el)el=nodes[0]?.closest('[data-design-id]');if(el)select(el.getAttribute('data-design-id'));}
let gesture;
function beginGesture(){if(!selected)return;remember();gesture={id:selected.id,rect:{...selected.rect},style:{...selected.style},dx:0,dy:0,width:selected.rect.width,height:selected.rect.height};dragging=true;}
function finishGesture(){dragging=false;gesture=null;$('guideX').hidden=true;$('guideY').hidden=true;refresh(true);changed();}
function snapping(box,event){
 if(!project.ui.snap||event.altKey)return {...box,guideX:null,guideY:null};
 const related=entry=>{let parent=entry.parent;while(parent){if(parent===selected.id)return true;parent=entries.find(e=>e.id===parent)?.parent;}let own=selected.parent;while(own){if(own===entry.id)return true;own=entries.find(e=>e.id===own)?.parent;}return false;};
 const boxes=entries.filter(e=>e.visible&&e.id!==selected.id&&!related(e)).map(e=>e.rect);
 const guides=(project.guides||[]).filter(g=>g.view===project.ui.view&&g.layout===project.preferences.layout);
 return snapBox(box,guides,boxes,6/($('surface').clientWidth/1920),8/pixelRatio(project.ui.resolution),window.interact);
}
function showSnap(result){for(const [key,id,prop]of [['guideX','guideX','left'],['guideY','guideY','top']]){$(id).hidden=result[key]===null;if(result[key]!==null)$(id).style[prop]=result[key]+'px';}}
window.interact('#selection').draggable({ignoreFrom:'.handle',listeners:{start:beginGesture,move(event){if(!gesture)return;const scale=$('surface').clientWidth/1920;gesture.dx+=event.dx/scale;gesture.dy+=event.dy/scale;const result=snapping({...gesture.rect,x:gesture.rect.x+gesture.dx,y:gesture.rect.y+gesture.dy},event);showSnap(result);patch({offsetX:(gesture.style.offsetX||0)+result.x-gesture.rect.x,offsetY:(gesture.style.offsetY||0)+result.y-gesture.rect.y},{record:false});},end:finishGesture}}).resizable({edges:{left:'.tl,.bl',right:'.tr,.br',top:'.tl,.tr',bottom:'.bl,.br'},modifiers:[window.interact.modifiers.restrictSize({min:{width:8,height:8}})],listeners:{start:beginGesture,move(event){if(!gesture)return;const scale=$('surface').clientWidth/1920;gesture.width+=event.deltaRect.width/scale;gesture.height+=event.deltaRect.height/scale;gesture.dx+=event.deltaRect.left/scale;gesture.dy+=event.deltaRect.top/scale;let width=gesture.width,height=gesture.height;let x=gesture.rect.x+gesture.dx,y=gesture.rect.y+gesture.dy;const right=x+width,bottom=y+height;
 const corner=snapping({x:event.edges.left?x:right,y:event.edges.top?y:bottom,width:0,height:0},event);showSnap(corner);
 if(event.edges.left){width=right-corner.x;x=corner.x;}else width=corner.x-x;if(event.edges.top){height=bottom-corner.y;y=corner.y;}else height=corner.y-y;
 patch({width:Math.min(1920,Math.max(8,width)),height:Math.min(1080,Math.max(8,height)),offsetX:(gesture.style.offsetX||0)+x-gesture.rect.x,offsetY:(gesture.style.offsetY||0)+y-gesture.rect.y},{record:false});},end:finishGesture}});
function layoutIssues(){const issues=[];const all=design().elements();for(const e of all){if(!e.action||!e.visible)continue;const r=e.rect;if(r.x< -2||r.y< -2||r.x+r.width>1922||r.y+r.height>1082){const el=design().element(e.id)?.el;if(el?.closest('.grid-view,.ed-other-view,#phList,#shelf'))continue;issues.push(friendly(e.name)+': liegt teilweise außerhalb des Bildes.');}const el=design().element(e.id)?.el;if(el&&el.scrollWidth>el.clientWidth+4&&!el.querySelector('.mark'))issues.push(friendly(e.name)+': Inhalt braucht mehr Breite.');}return [...new Set(issues)];}
function showIssues(){const issues=layoutIssues();$('issues').hidden=false;$('issues').replaceChildren();const title=document.createElement('strong');title.textContent=issues.length?issues.length+' Hinweise für diese Ansicht':'Diese Ansicht liegt innerhalb des Bildes.';$('issues').append(title);if(issues.length){const list=document.createElement('ul');issues.forEach(text=>{const li=document.createElement('li');li.textContent=text;list.append(li);});$('issues').append(list);}return issues;}
function dialog(title,body,onSubmit){$('dialogTitle').textContent=title;$('dialogBody').replaceChildren(body);$('dialogSave').hidden=!onSubmit;$('dialogForm').onsubmit=event=>{event.preventDefault();Promise.resolve(onSubmit?.()).then(()=>$('dialog').close()).catch(report);};$('dialog').showModal();}
async function snapshot(){await save();const label=document.createElement('label');label.textContent='Name des Zwischenstands';const input=document.createElement('input');input.maxLength=80;input.required=true;input.placeholder='Zum Beispiel: kleiner Spotify Player';label.append(input);dialog('Zwischenstand speichern',label,async()=>{await request('snapshot',{name:input.value});notice('Zwischenstand gespeichert.');});input.focus();}
async function versions(){await save();const list=await request('snapshots'),body=document.createElement('div');if(!list.length)body.textContent='Noch keine Zwischenstände gespeichert.';for(const version of list){const row=document.createElement('div');row.className='version';const text=document.createElement('div');text.textContent=version.name;const date=document.createElement('small');date.textContent=version.created;text.append(date);const button=document.createElement('button');button.type='button';button.textContent='Laden';button.onclick=async()=>{try{const result=await request('restore',{id:version.id});remember();project.design=result.project.design;project.preferences=result.project.preferences;project.ui=result.project.ui;project.guides=result.project.guides||[];changed();$('dialog').close();await loadFrame();}catch(error){report(error);}};row.append(text,button);body.append(row);}dialog('Zwischenstände',body);}
let jobTimer;
async function pollJob(){try{const job=await request('job');if(job.state==='idle')return;const labels={freezing:'Stand sichern',checking:'Oberfläche und Bedienung prüfen',building:'Android App bauen',ready:'TV-Version ist vorbereitet',failed:'Vorbereitung fehlgeschlagen'};const root=$('release');root.hidden=false;root.replaceChildren();const title=document.createElement('strong');title.textContent=labels[job.state]||job.state;root.append(title);const p=document.createElement('p');p.textContent=job.message||'';root.append(p);if(job.path){const details=document.createElement('details'),summary=document.createElement('summary'),path=document.createElement('code');summary.textContent='Gespeicherten Stand anzeigen';path.textContent=job.path;details.append(summary,path);root.append(details);}if(['ready','failed'].includes(job.state)){$('prepare').disabled=false;if(job.state==='ready')notice('TV-Version gespeichert. Der Verbindungsstatus prüft, ob sie noch zum Entwurf passt.');}else jobTimer=setTimeout(pollJob,2000);}catch(error){report(error);$('prepare').disabled=false;}}
async function prepare(){await save();if(conflicted)return;showIssues();$('prepare').disabled=true;try{await request('prepare',{});clearTimeout(jobTimer);await pollJob();}catch(error){$('prepare').disabled=false;report(error);}}
function download(){const blob=new Blob([JSON.stringify(project,null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='CleanFrame-Entwurf.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),2000);}
async function boot(){const state=await request('state');project=state.project;etag=state.etag;token=state.token;source=state.source;project.undo??=[];project.redo??=[];syncControls();await loadFrame();status('Auf diesem Mac gespeichert');await pollJob();}
for(const[value,text]of Object.entries(scenes))$('scene').add(new Option(text,value));createFields();makePreferences();
$('scene').onchange=async()=>{uiChange('view',$('scene').value);await showScene(project.ui.view);};$('layout').onchange=()=>preference('layout',$('layout').value);$('language').onchange=()=>preference('language',$('language').value);
$('resolution').onchange=()=>{uiChange('resolution',Number($('resolution').value));resize();setTimeout(()=>refresh(true),200);};$('scenario').onchange=()=>{uiChange('scenario',$('scenario').value);win().CleanFrameMock.setScenario(project.ui.scenario);refresh(true);};
$('playerState').onchange=()=>{uiChange('state',$('playerState').value);qa().playerView(project.ui.state==='expanded',project.ui.mode==='edit');project.ui.view='home';syncControls();refresh(true);};$('snap').onchange=()=>uiChange('snap',$('snap').checked);$('scope').onchange=()=>{uiChange('scope',$('scope').value);updateFields();};$('filter').oninput=()=>refresh();
$('editMode').onclick=()=>setMode('edit');$('testMode').onclick=()=>setMode('test');$('overlay').addEventListener('pointerdown',inspectAt);$('undo').onclick=()=>history('undo');$('redo').onclick=()=>history('redo');$('snapshot').onclick=()=>snapshot().catch(report);$('versions').onclick=()=>versions().catch(report);$('dialogCancel').onclick=()=>$('dialog').close();$('checks').onclick=showIssues;$('prepare').onclick=()=>prepare().catch(report);$('downloadDraft').onclick=download;
$('loadCurrent').onclick=async()=>{conflicted=false;dirty=false;$('conflict').hidden=true;await boot();};$('resetElement').onclick=()=>{remember();const rule=currentRule();if(rule)project.design.rules.splice(project.design.rules.indexOf(rule),1);apply();changed();};
for(const button of document.querySelectorAll('[data-nav]'))button.onclick=()=>remote(button.dataset.nav);
for(const button of document.querySelectorAll('[data-align]'))button.onclick=()=>{if(!selected)return;const x=button.dataset.align==='left'?0:button.dataset.align==='right'?1920-selected.rect.width:(1920-selected.rect.width)/2;patch({offsetX:(selected.style.offsetX||0)+x-selected.rect.x});};
window.addEventListener('resize',resize);new ResizeObserver(resize).observe($('canvas'));
window.addEventListener('keydown',event=>{if(document.body.classList.contains('live-mode'))return;if(event.target.matches('input,select,textarea')||$('dialog').open)return;if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==='z'){event.preventDefault();history(event.shiftKey?'redo':'undo');return;}if(project?.ui.mode==='test'){const key={ArrowLeft:'left',ArrowRight:'right',ArrowUp:'up',ArrowDown:'down',Enter:'ok',Escape:'back'}[event.key];if(key){event.preventDefault();remote(key);}}else if(selected&&event.key.startsWith('Arrow')){event.preventDefault();const step=(event.altKey?1:project.ui.snap?8:1)/pixelRatio(project.ui.resolution),dx=event.key==='ArrowLeft'?-step:event.key==='ArrowRight'?step:0,dy=event.key==='ArrowUp'?-step:event.key==='ArrowDown'?step:0;patch({offsetX:(selected.style.offsetX||0)+dx,offsetY:(selected.style.offsetY||0)+dy});}});
window.addEventListener('beforeunload',event=>{if(dirty){event.preventDefault();event.returnValue='';}});
setInterval(()=>{if(ready&&!loading&&!dragging)refresh();},350);
setInterval(async()=>{if(!ready||loading||dragging||saving)return;try{const state=await request('watch');token=state.token||token;if(state.etag!==etag){if(dirty){conflicted=true;$('conflict').hidden=false;$('conflict').querySelector('span').textContent='Der Entwurf wurde gleichzeitig an anderer Stelle geändert.';}else await boot();}else if(state.source!==source){source=state.source;await loadFrame();notice('Quellcode aktualisiert. Dein Entwurf bleibt erhalten.');}}catch(error){notice(error.message);}},2500);
window.CleanFrameEditor={get ready(){return ready&&!loading;},get project(){return clone(project);},save,select,patch,layoutIssues,refresh,loadFrame,request,get entries(){return clone(entries);},get selected(){return selected?clone(selected):null;},get liveProject(){return project;},get etag(){return etag;},remember,changed,resize,notice,boot,qa,design,win,report};
initWorkspace(window.CleanFrameEditor);
boot().catch(report);
