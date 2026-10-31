/* Local simulation only. This file is never packaged into the TV APK. */
(() => {
  const boot=window.CleanFrameEditorBoot,copy=value=>JSON.parse(JSON.stringify(value));
  const catalog=[
    {id:'spotify',name:'Spotify',kind:'spotify',version:'1.0.0',description:'Wiedergabe und Player auf dem Startbildschirm',author:'CleanFrame',license:'MIT'},
    {id:'apple-remote',name:'iPhone Remote',kind:'native',version:'0.1.0',description:'Experimentelle iPhone-Fernbedienung · Simulationspaket',author:'CleanFrame',license:'MIT',packageName:'com.cleanframe.addon.appleremote',serviceInterface:1,permissions:['Local network','Foreground service']}
  ];
  let model=copy(boot.addonLab||{installed:(boot.tvSource?.addons||[catalog[0]]).map(p=>({id:p.id,version:p.version,enabled:p.enabled!==false})),scenario:'connected',selected:'apple-remote'});
  let setupPin=null;
  let busy=false,error='',pending=null,events=[],seq=0;
  const imported=Object.fromEntries((boot.tvSource?.addons||[]).map(p=>[p.id,p]));
  const descriptor=id=>catalog.find(p=>p.id===id)||imported[id];
  const installed=()=>Object.fromEntries(model.installed.filter(p=>descriptor(p.id)).map(p=>[p.id,{...descriptor(p.id),...p}]));
  const log=(action,result='ok',length)=>{events.push({seq:++seq,stage:'simulation',action,result,...(length===undefined?{}:{length})});events=events.slice(-100);};
  function state(){
    const packages=installed(),remote=packages['apple-remote'],active=remote?.enabled!==false;
    const connected=active&&!['unpaired','disconnected','input-unavailable'].includes(model.scenario);
    const runtime=remote?{'apple-remote':{enabled:active,running:active,setupInterface:1,automaticPairingAccess:model.automaticPairingAccess===true,philips:{state:connected?'connected':'disconnected'},android:{state:connected?'connected':'disconnected'},apple:{state:model.scenario==='unpaired'?'unpaired':connected?'connected':'idle',pairedDevices:model.scenario==='unpaired'?0:1,devices:model.scenario==='unpaired'?[]:[{id:'preview-iphone',name:'iPhone (Simulation)'}],...(setupPin||{})},keyboard:{ready:connected,selected:connected},events:copy(events)}}:{};
    return {api:2,installed:packages,catalog:catalog.map(p=>({...p,version:model.scenario==='update'&&p.id==='apple-remote'?'0.2.0':p.version})),runtime,busy,error,pending};
  }
  function publish(changed=false){window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:state()}));window.dispatchEvent(new CustomEvent('cleanframe:addon-simulation',{detail:{model:copy(model),changed,events:copy(events)}}));}
  function request(input){
    const {action,id,command}=JSON.parse(input);
    if(action==='remoteState')return JSON.stringify(state().runtime['apple-remote']||{});
    if(action==='remote'){if(command.op==='philipsPairBegin')model.scenario='connected';if(command.op==='applePairBegin')setupPin={pin:'1234',expiresAt:Date.now()/1000+120};if(command.op==='unpair')model.scenario='unpaired';if(['enable','disable'].includes(command.op)){const p=model.installed.find(p=>p.id==='apple-remote');if(p)p.enabled=command.op==='enable';}log(command.op,'simulation');publish(true);return JSON.stringify({ok:true});}
    if(action==='state')return JSON.stringify(state());
    if(busy)return JSON.stringify(state());
    error='';
    if(action==='setup'){log('setup','preview');window.dispatchEvent(new CustomEvent('cleanframe:preview-notice',{detail:'Simulation: Einrichtung der iPhone-Fernbedienung. Kein TV-Zugriff.'}));publish();return JSON.stringify(state());}
    if(action==='refresh'){publish();return JSON.stringify(state());}
    if(!descriptor(id))return JSON.stringify(state());
    busy=true;pending={action,id,status:'simulation'};publish();
    setTimeout(()=>{
      if(model.scenario==='download-error'&&action==='install'){error='Simulation: Download fehlgeschlagen. Vorherige Version bleibt erhalten.';log(action,'download-error');}
      else if(model.scenario==='cancelled'&&['install','remove'].includes(action)){log(action,'cancelled');}
      else{
        const prior=model.installed.find(p=>p.id===id);
        if(action==='install'){model.installed=model.installed.filter(p=>p.id!==id);model.installed.push({id,enabled:prior?.enabled!==false,version:state().catalog.find(p=>p.id===id)?.version||descriptor(id).version});}
        if(action==='remove')model.installed=model.installed.filter(p=>p.id!==id);
        if(action==='toggle'&&prior)prior.enabled=!prior.enabled;
        log(action);
      }
      pending=null;busy=false;publish(true);
    },300);
    return JSON.stringify(state());
  }
  window.CleanFrameNative.remotePermission=op=>{if(op==='pairing'){model.automaticPairingAccess=true;log('pairing-access','simulation');publish(true);}return JSON.stringify({ok:true});};
  window.CleanFrameNative.addons=request;
  window.CleanFrameAddonMock={state,model:()=>copy(model),events:()=>copy(events),catalog:copy(catalog),
    scenario(value){model.scenario=value;error='';log('scenario',value);publish(true);},
    select(id){model.selected=id;publish(true);},
    command(action){const ready=state().runtime['apple-remote']?.android.state==='connected';log(action,ready?'translated':'not-connected');if(ready){if(action==='home')window.ShoreQA.editorScene('home');else window.ShoreQA.nav(action);}publish();return ready;},
    text(value,action='replace'){const ready=state().runtime['apple-remote']?.keyboard.ready===true;log(action,ready?'simulated-ime':'field-unavailable',Array.from(value).length);publish();return ready;}
  };
})();
