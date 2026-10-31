import {pixelRatio,distance,setDistance} from './geometry.js';
import {NativeVideo} from './native-video.js';
const $=id=>document.getElementById(id);
const round=value=>Math.round(value*100)/100;
const text=(tag,value)=>{const node=document.createElement(tag);node.textContent=value;return node;};

export function initWorkspace(api){
 let live=false,chat=false,tv=null,tvBusy=false,frameTimer,frameEpoch=0,videoStarted=0,lastFrame=null,chatKey='',reference='',guideDragging=false,lastGuides='',lastTargets='',chatBusy=false;
 const ratio=()=>pixelRatio(api.liveProject?.ui.resolution),currentGuides=()=>((api.liveProject?.guides)||[]).filter(g=>g.view===api.liveProject.ui.view&&g.layout===api.liveProject.preferences.layout);
 const visiblePixel=value=>round(value*ratio());
 const video=new NativeVideo($('liveFrame'),(width,height)=>{if(!live)return;lastFrame={width,height};$('liveEmpty').hidden=true;$('retryLive').hidden=true;$('dimensions').textContent=`${width} × ${height} · Live`;},error=>{if(!live)return;$('liveEmpty').hidden=false;$('liveEmpty').textContent=error.message;$('liveCaption').textContent='Live-Verbindung unterbrochen';$('retryLive').hidden=false;});
 try{$('liveProfile').value=localStorage.getItem('cleanframe.liveProfile')==='native'?'native':'smooth';}catch{$('liveProfile').value='smooth';}
 const guarded=fn=>(...args)=>Promise.resolve().then(()=>fn(...args)).catch(api.report);

 function source(value){
  live=value;document.body.classList.toggle('live-mode',live);
  $('draftTab').classList.toggle('active',!live);$('liveTab').classList.toggle('active',live);
  $('draftTab').setAttribute('aria-pressed',!live);$('liveTab').setAttribute('aria-pressed',live);
  $('liveCaption').hidden=!live;$('liveControls').hidden=!live;$('retryLive').hidden=!live;stopVideo();api.resize();
  if(live){$('viewTitle').textContent='Produktiv am Fernseher';api.notice('Live vom Fernseher · kontinuierlicher Videostream');startVideo();}
  else{$('liveFrame').hidden=true;$('liveEmpty').hidden=true;api.refresh(true);$('viewTitle').textContent=$('scene').selectedOptions[0]?.textContent||'Entwurf';api.notice('Entwurf mit Beispieldaten. Dein TV bleibt unverändert.');}
 }
 async function status(){
  if(tvBusy||document.hidden)return;tvBusy=true;
  try{tv=await api.request('tv/status');const label=tv.ready?'Bereit zur Übertragung':tv.connected?'TV verbunden':'TV nicht verbunden';
   $('tvStatus').className='connection '+(tv.ready?'ready':tv.connected?'connected':'offline');$('tvStatus').querySelector('span').textContent=label;
   const dl=document.createElement('dl');const pair=(key,value)=>{dl.append(text('dt',key),text('dd',value||'–'));};
   pair('Fernseher',tv.label);pair('Verbindung',tv.connected?tv.address:tv.message);pair('Installiert',tv.version);
   pair('Android UI',tv.uiResolution?.join(' × '));pair('TV Panel',tv.panelResolution?.join(' × '));
   pair('Vorbereitet',tv.preparedVersion);pair('Übertragung',tv.releaseMessage);pair('Geprüft',new Date(tv.checkedAt*1000).toLocaleTimeString('de-DE'));
   $('tvDetails').replaceChildren(dl);$('connectTv').disabled=!tv.configured;
  }catch(error){$('tvStatus').querySelector('span').textContent='TV Status nicht erreichbar';$('tvStatus').className='connection offline';$('tvDetails').textContent=error.message;}
  finally{tvBusy=false;}
 }
 function stopVideo(){clearTimeout(frameTimer);frameEpoch++;video.stop();}
 function startVideo(){
  if(!live||document.hidden)return;
  stopVideo();videoStarted=Date.now();lastFrame=null;$('liveEmpty').hidden=false;$('liveEmpty').textContent='Live-Video wird verbunden …';
  $('liveCaption').textContent='Videostream startet';$('retryLive').hidden=true;$('liveFrame').hidden=false;
  video.start('/api/editor/tv/live?profile='+$('liveProfile').value+'&v='+Date.now());checkVideo(frameEpoch);
 }
 async function checkVideo(epoch){
  if(!live||document.hidden||epoch!==frameEpoch)return;
  try{const state=await api.request('tv/live/status');if(epoch!==frameEpoch)return;
   if(!video.stats().active)return;
   const active=state.state==='streaming';
   if(active&&video.stats().frames){lastFrame={width:$('liveFrame').width,height:$('liveFrame').height};$('liveEmpty').hidden=true;$('retryLive').hidden=true;
    $('dimensions').textContent=`${lastFrame.width} × ${lastFrame.height} · Live`;
    const detail=state.profile==='smooth'?'Bis 30 fps bei Bewegung · reduzierte Auflösung':'Native Auflösung';
    $('liveCaption').textContent=`${detail} · TV ${Math.round(state.fps)} / Anzeige ${Math.round(video.stats().fps)} Bilder/s${state.age>=2?' · Standbild':''}`;
   }else if(state.state==='error'||(state.state==='stopped'&&Date.now()-videoStarted>5000)){$('liveEmpty').hidden=false;$('liveEmpty').textContent=state.error||'Der Videostream wurde unterbrochen.';$('liveCaption').textContent='Live-Verbindung unterbrochen';$('retryLive').hidden=false;}
   else{$('liveEmpty').textContent='Live-Video wird verbunden …';}
  }catch(error){if(epoch!==frameEpoch)return;$('liveEmpty').hidden=false;$('liveEmpty').textContent=error.message;$('retryLive').hidden=false;}
  finally{if(live&&epoch===frameEpoch&&!document.hidden)frameTimer=setTimeout(()=>checkVideo(epoch),500);}
 }
 $('retryLive').onclick=startVideo;
 $('liveProfile').onchange=()=>{try{localStorage.setItem('cleanframe.liveProfile',$('liveProfile').value);}catch{}if(live)startVideo();};
 function openChat(value){chat=value;document.body.classList.toggle('chat-open',chat);document.body.classList.remove('panels-hidden');$('focusWorkspace').textContent='Mehr Platz';
  $('propertiesPanel').hidden=chat;$('chatPanel').hidden=!chat;$('propertiesTab').classList.toggle('active',!chat);$('chatTab').classList.toggle('active',chat);$('toggleChat').setAttribute('aria-pressed',chat);api.resize();if(chat)pollChat();
 }
 function chatState(state){
  const key=JSON.stringify(state.messages);if(key!==chatKey){chatKey=key;const log=$('chatMessages'),bottom=log.scrollHeight-log.scrollTop-log.clientHeight<64;const fragment=document.createDocumentFragment();
   for(const item of state.messages){const message=document.createElement('article');message.className='chat-message '+item.role;message.append(text('small',item.role==='user'?'Du':'Codex'),document.createTextNode(item.text));fragment.append(message);}log.replaceChildren(fragment);if(bottom)log.scrollTop=log.scrollHeight;
  }
  $('chatActivity').textContent=state.error||(!state.available?'Codex CLI fehlt. Bitte Codex installieren und anmelden.':state.activity+(state.model?' · '+state.model:''));
  $('chatActivity').classList.toggle('error',!!state.error||!state.available);$('sendAgent').disabled=state.active||!state.available;$('stopAgent').hidden=!state.active;
 }
 async function pollChat(){if(chatBusy||document.hidden)return;chatBusy=true;try{chatState(await api.request('agent/state'));}catch(error){$('chatActivity').textContent=error.message;$('chatActivity').classList.add('error');}finally{chatBusy=false;}}
 $('chatForm').onsubmit=guarded(async event=>{event.preventDefault();const value=$('chatInput').value.trim();if(!value)return;if(live)source(false);await api.save();if(!$('conflict').hidden)throw Error('Bitte zuerst den Entwurfskonflikt auflösen.');
  const context={view:api.liveProject.ui.view,layout:api.liveProject.preferences.layout,state:api.liveProject.ui.state,resolution:api.liveProject.ui.resolution,selection:api.liveProject.ui.selection,geometryEtag:api.etag,elements:api.entries};
  $('sendAgent').disabled=true;try{const state=await api.request('agent/send',{text:value,context});$('chatInput').value='';chatState(state);}finally{pollChat();}
 });
 // Prevent browser navigation immediately, before the asynchronous save begins.
 $('chatForm').addEventListener('submit',event=>event.preventDefault());
 $('chatInput').onkeydown=event=>{if(event.key==='Enter'&&(event.metaKey||event.ctrlKey)){event.preventDefault();$('chatForm').requestSubmit();}};
 $('stopAgent').onclick=guarded(async()=>chatState(await api.request('agent/stop',{})));
 $('toggleChat').onclick=()=>openChat(!chat);$('chatTab').onclick=()=>openChat(true);$('propertiesTab').onclick=()=>openChat(false);
 $('importTv').onclick=guarded(async()=>{
  const result=await api.importTv();if(!result)return;
  source(false);$('tvPanel').hidden=true;
  api.notice('TV '+result.version+' als Entwurf übernommen. Spotify zeigt Beispieldaten. Vorheriger Entwurf unter „Gespeicherte Zwischenstände“.'+(result.sourceMatches?'':' Hinweis: Die Vorschau verwendet eine andere lokale CleanFrame-Version.'));
 });
 $('draftTab').onclick=()=>source(false);$('liveTab').onclick=()=>source(true);
 $('tvStatus').onclick=()=>{$('tvPanel').hidden=!$('tvPanel').hidden;$('tvStatus').setAttribute('aria-expanded',!$('tvPanel').hidden);if(!$('tvPanel').hidden)status();};
 $('closeTv').onclick=()=>{$('tvPanel').hidden=true;$('tvStatus').setAttribute('aria-expanded','false');};
 $('connectTv').onclick=guarded(async()=>{$('connectTv').disabled=true;try{await api.request('tv/connect',{});await status();}finally{$('connectTv').disabled=false;}});
 function togglePanels(){const hide=document.body.classList.toggle('panels-hidden');$('focusWorkspace').textContent=hide?'Leisten zeigen':'Mehr Platz';api.resize();}
 $('focusWorkspace').onclick=togglePanels;$('zoom').onchange=()=>{api.resize();api.refresh(true);};
 document.addEventListener('keydown',event=>{if(event.key==='Tab'&&event.target===document.body){event.preventDefault();togglePanels();}});

 function addGuide(axis,position){const p=api.liveProject;if(!p)return null;api.remember();p.guides??=[];if(p.guides.length>=100){api.notice('Maximal 100 Hilfslinien pro Entwurf.');return null;}const guide={id:crypto.randomUUID(),axis,position:Math.max(0,Math.min(axis==='x'?1920:1080,position)),view:p.ui.view,layout:p.preferences.layout};p.guides.push(guide);api.changed();renderGuides();return guide;}
 $('addGuideX').onclick=()=>addGuide('x',api.selected?.rect.x??960);$('addGuideY').onclick=()=>addGuide('y',api.selected?.rect.y??540);
 function renderGuides(){if(!api.liveProject||guideDragging)return;const guides=currentGuides(),key=JSON.stringify(guides)+ratio();if(key===lastGuides)return;lastGuides=key;
  const fragment=document.createDocumentFragment(),list=document.createDocumentFragment();
  for(const g of guides){const line=document.createElement('div');line.className='user-guide';line.dataset.id=g.id;line.dataset.axis=g.axis;line.style[g.axis==='x'?'left':'top']=g.position+'px';line.append(text('span',visiblePixel(g.position)+' px'));fragment.append(line);
   const row=document.createElement('div');row.className='guide-row';const label=text('label',g.axis==='x'?'X · senkrecht':'Y · waagerecht'),input=document.createElement('input');input.type='number';input.min=0;input.max=(g.axis==='x'?1920:1080)*ratio();input.step='any';input.value=visiblePixel(g.position);input.setAttribute('aria-label','Hilfslinie '+(g.axis==='x'?'X':'Y'));input.onchange=()=>{if(!input.checkValidity()||!Number.isFinite(input.valueAsNumber))return;api.remember();g.position=input.valueAsNumber/ratio();api.changed();renderGuides();};label.append(input);
   const remove=text('button','×');remove.title='Hilfslinie löschen';remove.setAttribute('aria-label','Hilfslinie löschen');remove.onclick=()=>{api.remember();api.liveProject.guides=api.liveProject.guides.filter(item=>item.id!==g.id);api.changed();renderGuides();};row.append(label,remove);list.append(row);
  }
  $('userGuides').replaceChildren(fragment);$('guideList').replaceChildren(list);
 }
 let movingGuide=null;
 function moveGuide(event){if(!movingGuide)return;const bounds=$('surface').getBoundingClientRect(),scale=bounds.width/1920,axis=movingGuide.axis;let value=(axis==='x'?event.clientX-bounds.left:event.clientY-bounds.top)/scale;
  if(api.liveProject.ui.snap&&!event.altKey){const step=8/ratio();value=Math.round(value/step)*step;}
  movingGuide.position=Math.max(0,Math.min(axis==='x'?1920:1080,value));const line=$('userGuides').querySelector(`[data-id="${movingGuide.id}"]`);if(line){line.style[axis==='x'?'left':'top']=movingGuide.position+'px';line.firstChild.textContent=visiblePixel(movingGuide.position)+' px';}
 }
 function endGuide(){movingGuide=null;guideDragging=false;lastGuides='';api.changed();renderGuides();}
 window.interact('.user-guide').draggable({listeners:{start:event=>{movingGuide=api.liveProject.guides.find(g=>g.id===event.target.dataset.id);api.remember();guideDragging=true;},move:moveGuide,end:endGuide}});
 for(const [id,axis]of [['rulerX','x'],['rulerY','y']])window.interact('#'+id).draggable({listeners:{start:event=>{movingGuide=addGuide(axis,0);guideDragging=true;moveGuide(event);},move:moveGuide,end:endGuide}});
 function rulers(){if(!api.liveProject)return;for(const [id,limit]of [['rulerX',1920],['rulerY',1080]]){const key=limit+':'+ratio();if($(id).dataset.key===key)continue;$(id).dataset.key=key;const marks=[];const step=ratio()===2?400:ratio()<1?160:200;for(let value=0;value<limit*ratio();value+=step){const mark=text('span',value);mark.style[id==='rulerX'?'left':'top']=value/(limit*ratio())*100+'%';marks.push(mark);}$(id).replaceChildren(...marks);}}

 function pickReference(event){if(!event.shiftKey||live)return;event.preventDefault();const bounds=$('surface').getBoundingClientRect(),factor=api.liveProject.ui.resolution*16/9/bounds.width;
  const node=api.win().document.elementsFromPoint((event.clientX-bounds.left)*factor,(event.clientY-bounds.top)*factor).find(n=>n.hasAttribute('data-design-id'));
  if(node&&node.dataset.designId!==api.selected?.id){reference=node.dataset.designId;renderMeasurements();}
 }
 $('overlay').addEventListener('pointerdown',pickReference,true);
 $('measureTarget').onchange=()=>{reference=$('measureTarget').value;renderMeasurements();};
 function renderMeasurements(){if(!api.liveProject)return;const selected=api.selected,items=api.entries,key=items.map(e=>e.id).join('|')+'|'+selected?.id;
  if(key!==lastTargets){lastTargets=key;$('measureTarget').replaceChildren(new Option('Element wählen …',''),...items.filter(e=>e.id!==selected?.id).map(e=>new Option(e.name,e.id)));}
  $('measureTarget').value=reference;const other=items.find(e=>e.id===reference);$('measurements').replaceChildren();
  if(!selected||!other||selected.id===other.id){$('distanceX').disabled=true;$('distanceY').disabled=true;return;}
  const svg=(name,attrs)=>{const n=document.createElementNS('http://www.w3.org/2000/svg',name);for(const[k,v]of Object.entries(attrs))n.setAttribute(k,v);$('measurements').append(n);return n;};
  svg('rect',{x:other.rect.x,y:other.rect.y,width:other.rect.width,height:other.rect.height});
  for(const axis of ['x','y']){const d=distance(selected.rect,other.rect,axis),input=$(axis==='x'?'distanceX':'distanceY');input.disabled=false;if(document.activeElement!==input)input.value=visiblePixel(d.gap);
   const horizontal=axis==='x',x1=horizontal?d.start:d.cross,x2=horizontal?d.end:d.cross,y1=horizontal?d.cross:d.start,y2=horizontal?d.cross:d.end;
   svg('line',{x1,y1,x2,y2});for(const [x,y]of [[x1,y1],[x2,y2]])svg('line',{x1:x-(horizontal?0:8),y1:y-(horizontal?8:0),x2:x+(horizontal?0:8),y2:y+(horizontal?8:0)});
   svg('text',{x:(x1+x2)/2+8,y:(y1+y2)/2-8}).textContent=visiblePixel(d.gap)+' px';
  }
 }
 for(const [id,axis]of [['distanceX','x'],['distanceY','y']])$(id).onchange=()=>{const selected=api.selected,other=api.entries.find(e=>e.id===reference),gap=$(id).valueAsNumber;if(!selected||!other||!Number.isFinite(gap))return;const value=setDistance(selected.rect,other.rect,axis,gap/ratio()),key=axis==='x'?'offsetX':'offsetY';const offset=(selected.style[key]||0)+value-selected.rect[axis];if(Math.abs(offset)>(axis==='x'?1920:1080)){api.notice('Dieser Abstand liegt außerhalb der bearbeitbaren Fläche.');return;}api.patch({[key]:offset});renderMeasurements();};
 document.addEventListener('editor:refresh',()=>{renderGuides();rulers();renderMeasurements();if(live){$('viewTitle').textContent='Produktiv am Fernseher';if(lastFrame)$('dimensions').textContent=`${lastFrame.width} × ${lastFrame.height} · Live`;}});
 document.addEventListener('visibilitychange',()=>{if(document.hidden){if(live)stopVideo();}else{status();if(live)startVideo();if(chat)pollChat();}});
 window.addEventListener('pagehide',stopVideo);
 setInterval(status,12000);setInterval(()=>{if(chat)pollChat();},1000);status();
 api.workspace={video,source,openChat,addGuide,renderMeasurements,get reference(){return reference;},setReference:id=>{reference=id;renderMeasurements();},status};
}
