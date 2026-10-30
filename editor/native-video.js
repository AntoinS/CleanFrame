// The browser's native WebCodecs decoder receives the original scrcpy H.264 packets.
// No JPEG conversion, host-side video decode, resizing or synthetic frame clock.
async function readerFor(response){
 if(!response.ok){const error=await response.json().catch(()=>({}));throw Error(error.error||'Der Videokanal ist nicht erreichbar.');}
 if(!response.body)throw Error('Der Videokanal ist nicht erreichbar.');
 const reader=response.body.getReader();let chunk=new Uint8Array(0),offset=0;
 return {cancel:()=>reader.cancel(),async read(length){const out=new Uint8Array(length);let filled=0;
  while(filled<length){if(offset===chunk.length){const next=await reader.read();if(next.done)throw Error('Der TV hat den Videokanal beendet.');chunk=next.value;offset=0;}
   const size=Math.min(length-filled,chunk.length-offset);out.set(chunk.subarray(offset,offset+size),filled);offset+=size;filled+=size;}
  return out;
 }};
}
function codecFrom(data){
 for(let i=0;i<data.length-7;i++)if(data[i]===0&&data[i+1]===0){let n=i+2;if(data[n]===0)n++;if(data[n]===1&&(data[n+1]&31)===7)return 'avc1.'+Array.from(data.subarray(n+2,n+5)).map(v=>v.toString(16).padStart(2,'0')).join('');}
 throw Error('Der TV hat keine gültige H.264-Konfiguration geliefert.');
}
export class NativeVideo {
 constructor(canvas,onFrame,onError){this.canvas=canvas;this.onFrame=onFrame;this.onError=onError;this.controller=null;this.decoder=null;this.reader=null;this.frames=0;this.times=[];}
 stop(){this.controller?.abort();this.controller=null;this.reader?.cancel().catch(()=>{});this.reader=null;if(this.decoder?.state!=='closed')this.decoder?.close();this.decoder=null;}
 stats(){const now=performance.now(),times=this.times.filter(t=>t>now-3000);return{active:!!this.controller&&!this.controller.signal.aborted,frames:this.frames,fps:times.length>1?(times.length-1)*1000/(times.at(-1)-times[0]):0};}
 start(url){this.stop();const controller=new AbortController();this.controller=controller;this.frames=0;this.times=[];this.run(url,controller).catch(error=>{if(!controller.signal.aborted){this.stop();this.onError(error);}});}
 async run(url,controller){
  if(!window.VideoDecoder||!window.EncodedVideoChunk)throw Error('Dieser Browser unterstützt den direkten Videodecoder nicht. Bitte einen aktuellen Chrome oder Safari verwenden.');
  const current=()=>this.controller===controller&&!controller.signal.aborted;
  const stream=await readerFor(await fetch(url,{signal:controller.signal,cache:'no-store'}));if(!current()){await stream.cancel();return;}this.reader=stream;
  if(new TextDecoder().decode(await stream.read(4))!=='h264')throw Error('Der TV liefert kein H.264-Video.');
  let width=0,height=0,configData=null,codec=null,waitingForKey=true;
  const context=this.canvas.getContext('2d',{alpha:false,desynchronized:true});
  const configure=async()=>{
   if(!codec)return;
   if(this.decoder?.state!=='closed')this.decoder?.close();
   const config={codec,codedWidth:width,codedHeight:height,optimizeForLatency:true};
   if(!(await VideoDecoder.isConfigSupported(config)).supported)throw Error('Der Browser kann das H.264-Profil des TV nicht direkt dekodieren.');
   if(!current())return;
   this.decoder=new VideoDecoder({output:frame=>{try{if(!current())return;
    if(this.canvas.width!==frame.displayWidth||this.canvas.height!==frame.displayHeight){this.canvas.width=frame.displayWidth;this.canvas.height=frame.displayHeight;}
    context.drawImage(frame,0,0,this.canvas.width,this.canvas.height);this.frames++;this.times.push(performance.now());if(this.times.length>180)this.times.shift();this.onFrame(this.canvas.width,this.canvas.height,this.stats());
   }finally{frame.close();}},error:error=>{if(current()){this.stop();this.onError(error);}}});
   this.decoder.configure(config);waitingForKey=true;
  };
  while(current()){
   const header=await stream.read(12),view=new DataView(header.buffer),flags=header[0];
   if(flags&0x80){width=view.getUint32(4);height=view.getUint32(8);configData=null;codec=null;if(this.decoder?.state!=='closed')this.decoder?.close();this.decoder=null;continue;}
   const length=view.getUint32(8);if(length>16_000_000)throw Error('Ungültiges Videopaket.');if(length===0)continue;const data=await stream.read(length);
   if(!current())return;
   if(flags&0x40){configData=data;codec=codecFrom(data);await configure();continue;}
   if(!this.decoder||this.decoder.state!=='configured')continue;
   const key=!!(flags&0x20);
   if(this.decoder.decodeQueueSize>8){await configure();}
   if(waitingForKey&&!key)continue;
   let bytes=data;
   if(key&&configData){bytes=new Uint8Array(configData.length+data.length);bytes.set(configData);bytes.set(data,configData.length);}
   waitingForKey=false;
   const timestamp=Number(view.getBigUint64(0)&((1n<<61n)-1n));
   this.decoder.decode(new EncodedVideoChunk({type:key?'key':'delta',timestamp,data:bytes}));
  }
 }
}
