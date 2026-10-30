/* Native sign-in layout approximation. Deliberately contains no live QR or account flow. */
(() => {
 const root=document.createElement('section');root.id='qrRoot';root.hidden=true;
 root.style.cssText='position:absolute;left:0;top:0;width:1920px;height:1080px;background:#181818;color:#f4f4f1;font-family:Manrope,sans-serif;z-index:20';
 root.innerHTML='<div id="qrCode" style="position:absolute;left:96px;top:284px;width:512px;height:512px;background:#272727;display:flex;align-items:center;justify-content:center;font-size:36px;line-height:40px;text-align:center;border-radius:8px">QR<br>Vorschau</div><h2 id="qrTitle" style="position:absolute;left:688px;top:300px;margin:0;font-size:60px;line-height:80px;font-weight:500"></h2><p id="qrDetail" style="position:absolute;left:688px;top:412px;width:1136px;margin:0;font-size:36px;line-height:48px"></p><p id="qrPairing" style="position:absolute;left:688px;top:556px;margin:0;font-size:40px;line-height:56px"></p><button id="qrBack" type="button" style="position:absolute;left:688px;top:660px;width:400px;height:96px;background:#313131;color:inherit;border:0;border-radius:8px;font:500 32px Manrope"></button>';
 document.getElementById('stage').append(root);
 let shown=false;
 function apply(){if(!shown)return;CleanFrameDesign.before();CleanFrameDesign.after({view:'qr',layout:ShoreQA.state().layout,state:'compact'});}
 function hide(){shown=false;CleanFrameDesign.before();root.hidden=true;ShoreQA.render();}
 function show(){shown=true;const de=ShoreQA.state().language==='de';root.hidden=false;root.querySelector('#qrTitle').textContent=de?'Spotify verbinden':'Connect Spotify';root.querySelector('#qrDetail').textContent=de?'Scanne den QR-Code mit deinem Handy und melde dich bei Spotify an.':'Scan the QR code with your phone and sign in to Spotify.';root.querySelector('#qrPairing').textContent=de?'Verbindungscode: VORSCHAU':'Pairing code: PREVIEW';root.querySelector('#qrBack').textContent=de?'Zurück':'Back';apply();}
 root.querySelector('#qrBack').onclick=hide;
 document.addEventListener('keydown',event=>{if(!shown)return;event.preventDefault();event.stopImmediatePropagation();if(event.key==='Escape'||event.key==='Enter')hide();},true);
 window.CleanFrameQR={show,hide,apply,visible:()=>shown};
})();
