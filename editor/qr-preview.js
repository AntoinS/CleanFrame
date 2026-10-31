/* Compatibility route for saved editor projects; uses the actual shared sign-in UI. */
(() => {
 let shown=false;
 function show(){shown=true;ShoreQA.editorScene('appearance.spotify-link');CleanFrameAddonSetup.signIn();}
 function hide(){if(shown)CleanFrameAddonSetup.close();shown=false;}
 window.CleanFrameQR={show,hide,apply(){},visible:()=>shown};
})();
