/* Development-only Android boundary; never included in the TV package. */
(() => {
  'use strict';
  const boot=window.CleanFrameEditorBoot;
  window.CleanFramePreview=true;
  localStorage.setItem('cleanframe.home.v1',JSON.stringify(boot.preferences));
  const apps=boot.tvSource?.apps||[['com.google.android.youtube.tv','YouTube'],['com.netflix.ninja','Netflix'],['com.amazon.amazonvideo.livingroom','Prime Video'],['com.spotify.tv.android','Spotify'],['com.apple.atve.androidtv.appletv','Apple TV'],['tv.twitch.android.app','Twitch'],['com.disney.disneyplus','Disney+']].map(([appId,name])=>({appId,name}));
  let media,scenario=boot.scenario;
  const calls=[];
  const publish=()=>window.dispatchEvent(new CustomEvent('cleanframe:spotify',{detail:{...media}}));
  const notice=text=>window.dispatchEvent(new CustomEvent('cleanframe:preview-notice',{detail:text}));
  function setScenario(next){
    scenario=next;
    media={permission:true,status:'playing',connection:'connected',webApi:'connected',webLinked:true,title:'A Walk',artist:'Tycho',duration:316000,position:74000,speed:1,deviceName:'MacBook',artColor:'#758e93',artLight:false,art:'/media/spotify-1.png',actions:{previous:true,toggle:true,next:true}};
    if(next==='paused')Object.assign(media,{status:'paused',speed:0});
    if(next==='long')Object.assign(media,{title:'Eine sehr lange Aufnahme mit vielen Worten und einem ausführlichen Titel',artist:'Ein langer Name · Ein weiterer Interpret · Live Session'});
    if(next==='missing')media.art='';
    if(next==='none')Object.assign(media,{status:'idle',title:'',speed:0});
    if(next==='unlinked')Object.assign(media,{webApi:'unlinked',webLinked:false,actions:{previous:false,toggle:false,next:false}});
    if(next==='denied')Object.assign(media,{permission:false,status:'unavailable',title:''});
    if(next==='offline')Object.assign(media,{connection:'unavailable',status:'unavailable',title:'',errorMessage:'Spotify ist in dieser Vorschau nicht erreichbar.'});
    publish();
  }
  setScenario(scenario);
  let link = {phase:"idle"};
  const ok=()=>'{"ok":true}';
  const version=boot.softwareVersion||'0.8.3',code=boot.softwareVersionCode||24;
  let software={current:{name:'CleanTV',version,versionName:version+'-android'+code,versionCode:code},available:null,checkedAt:0,busy:false,error:'',pending:{},simulated:true};
  const publishSoftware=()=>window.dispatchEvent(new CustomEvent('cleanframe:software',{detail:JSON.parse(JSON.stringify(software))}));
  function softwareRequest(input){
    const {action}=JSON.parse(input);
    if(action==='state')return JSON.stringify(software);
    if(software.busy)return JSON.stringify({error:'Simulation operation in progress'});
    software.busy=true;software.error='';publishSoftware();
    setTimeout(()=>{
      if(action==='refresh'){software.available={version:'9.0.0',versionCode:999,notes:'Simulation: Neue Funktionen und Verbesserungen.'};software.checkedAt=Date.now();software.updateAvailable=software.current.versionCode<999;}
      if(action==='install'){Object.assign(software.current,{version:'9.0.0',versionCode:999,versionName:'9.0.0-android999'});software.updateAvailable=false;software.result='installed';}
      software.busy=false;publishSoftware();
    },300);return ok();
  }
  window.CleanFrameSoftwareMock={state:()=>structuredClone(software),set(value){Object.assign(software,value);publishSoftware();}};
  window.CleanFrameNative={
    software:softwareRequest,
    apps:()=>JSON.stringify(apps),spotifyState:()=>JSON.stringify(media),spotifyObserve:ok,
    launchApp:id=>{calls.push(['launch',id]);notice('Vorschau: '+(apps.find(a=>a.appId===id)?.name||id)+' öffnen');return ok();},
    home:()=>{notice('Vorschau: ursprünglichen Startbildschirm öffnen');return ok();},settings:()=>{notice('Vorschau: Android Einstellungen öffnen');return ok();},
    spotifyAccess:()=>{notice('Vorschau: Android Medienzugriff öffnen');return ok();},
    spotifyControl:command=>{calls.push(['control',command]);if(command==='toggle')Object.assign(media,{status:media.status==='playing'?'paused':'playing',speed:media.status==='playing'?0:1});else{media.title=command==='next'?'Awake':'A Walk';media.position=0;}publish();return ok();},
    spotifyLink:op=>{if(op==='begin')link={phase:'pending',code:'PREVIEW',expiresAt:Date.now()+600000};if(op==='cancel')link={phase:'idle'};return JSON.stringify(link);},
    remotePermission:op=>{notice('Simulation: Android Tastatur '+op);return ok();},
    spotifySignOut:()=>{calls.push(['signout']);setTimeout(()=>setScenario('unlinked'),20);return ok();}
  };
  window.CleanFrameMock={setScenario,state:()=>({...media}),calls,scenario:()=>scenario};
})();
