"""Freeze reviewed inputs, run checks, build and verify one personal Android candidate."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path,value):
    from editor_server import atomic
    atomic(path,value)
def status(release,state,message):write(release/'status.json',{'state':state,'message':message,'path':str(release),'updated':time.time()})
def freeze(release,project,storage):
    from editor_server import fingerprint
    before=fingerprint();release.mkdir(parents=True,exist_ok=False);source=release/'source';source.mkdir()
    status(release,'freezing','Quellcode und Entwurf werden gesichert.')
    for folder in ['app','editor','scripts','docs','android']:
        shutil.copytree(ROOT/folder,source/folder,ignore=shutil.ignore_patterns('build','.gradle','__pycache__','*.pyc','.DS_Store','local.properties','*.keystore','*.jks','private-fonts'))
    for path in ROOT.iterdir():
        if path.is_file() and (path.suffix in ['.json','.mjs','.md','.xml','.css'] or path.name in ['LICENSE','.gitignore']):shutil.copy2(path,source/path.name)
    if fingerprint()!=before:
        status(release,'failed','Der Quellcode hat sich während des Sicherns verändert. Bitte erneut vorbereiten.');raise ValueError('Quellcode gleichzeitig geändert. Bitte erneut vorbereiten.')
    write(source/'app/design.json',project['design']);write(release/'project.json',project)
    (release/'source-fingerprint.txt').write_text(before)
    # Editor-only fixtures are private, separate from the packaged assets.
    capture=source/'.local/editor';capture.mkdir(parents=True);write(capture/'project.json',project)
    patch={k:v for k,v in project['preferences'].items() if project['basePrefs'].get(k)!=v}
    write(release/'preferences.json',{'base':project['basePrefs'],'patch':patch,'capturedAt':project.get('baseCapturedAt')})
    metadata=json.loads((ROOT/'package.json').read_text());minimum=metadata['cleanframe']['androidVersionCode']
    counter=storage/'next-version.json';version=max(minimum,json.loads(counter.read_text())) if counter.exists() else minimum;write(counter,version+1);write(release/'version.json',{'code':version,'name':json.loads((ROOT/'package.json').read_text())['version']+'-android'+str(version)})
    config=storage/'toolchain.json'
    if config.exists():shutil.copy2(config,release/'toolchain.json')
    inputs={str(p.relative_to(source)):digest(p) for p in sorted(source.rglob('*')) if p.is_file() and '.local' not in p.relative_to(source).parts}
    write(release/'inputs.json',inputs)
    changes=['# CleanTV TV-Version '+str(version),'','Dieser Stand enthält '+str(len(project['design']['rules']))+' Designanpassungen.','']
    for rule in project['design']['rules']:changes.append('- '+rule['view']+' / '+rule['layout']+' / '+rule['target']+': '+', '.join(k+' = '+str(v) for k,v in rule['style'].items()))
    if patch:changes+=['','## Einstellungen','']+['- '+k+': '+str(v) for k,v in patch.items()]
    changes+=['','Die APK wurde noch nicht auf den TV übertragen. Der genaue Prüfumfang und die Prüfsumme stehen nach erfolgreichem Build in manifest.json.','']
    (release/'Änderungen.md').write_text('\n'.join(changes))
    status(release,'checking','Gesicherter Stand wird geprüft.')

def run(release):
    source=release/'source';checks=[]
    def command(args,env=None,timeout=240):
        print('RUN',args,flush=True);result=subprocess.run(args,cwd=source,env=env,timeout=timeout)
        if result.returncode:raise RuntimeError('Prüfung fehlgeschlagen: '+' '.join(args))
        checks.append(' '.join(args))
    try:
        # Dependencies are locked and shared locally; source inputs themselves are copied.
        os.symlink(ROOT/'node_modules',source/'node_modules',target_is_directory=True)
        env=os.environ.copy();env['CLEANFRAME_PUBLIC_FONTS']='1';env['CLEANFRAME_BASELINE_DESIGN']='1'
        command(['npm','run','check'],env)
        command(['npm','run','check:tools'],env)
        command(['python3','scripts/test-editor-tools.py'],env)
        command(['python3','scripts/test-editor-workspace.py'],env)
        command(['npm','run','check:browser'],env,360)
        for script in ['check-android-browser.mjs','check-spotify-browser.mjs','check-options-browser.mjs','check-editor.mjs','check-editor-workspace.mjs','check-editor-import.mjs','check-addons-browser.mjs','check-editor-addons.mjs','check-addon-setup.mjs','check-software-browser.mjs']:
            command(['node','scripts/'+script],env,360)
        custom=env.copy();custom.pop('CLEANFRAME_BASELINE_DESIGN',None)
        command(['node','scripts/capture-editor.mjs',str(release)],custom,240)
        config=json.loads((release/'toolchain.json').read_text()) if (release/'toolchain.json').exists() else None
        if not config:raise RuntimeError('Android Werkzeugpfade fehlen in .local/editor/toolchain.json.')
        version=json.loads((release/'version.json').read_text());status(release,'building','Prüfungen bestanden. Android wird gebaut und die Signatur geprüft.')
        env=os.environ.copy();env.update({'JAVA_HOME':config['javaHome'],'ANDROID_HOME':config['sdk'],'ANDROID_SDK_ROOT':config['sdk'],'ANDROID_USER_HOME':config['androidUserHome'],'GRADLE_USER_HOME':config['gradleUserHome']})
        build=release/'build';quoted=str(build).replace('\\','\\\\').replace("'","\\'")
        init=release/'build.init.gradle';init.write_text("gradle.beforeProject { p -> p.layout.buildDirectory.set(new File('"+quoted+"', p.name)) }\n")
        command([config['gradle'],'-p',str(source/'android'),'-I',str(init),'--offline','--no-daemon','-PcleanframeVersionCode='+str(version['code']),'testDebugUnitTest','lintDebug','assembleDebug'],env,600)
        apk=build/'app/outputs/apk/debug/app-debug.apk'
        verifier=Path(config['sdk'])/'build-tools/35.0.0/apksigner'
        verified=subprocess.run([str(verifier),'verify','--print-certs',str(apk)],env=env,capture_output=True,text=True,check=True)
        if config['signerSha256'].lower() not in verified.stdout.lower():raise RuntimeError('Die APK hat nicht den erwarteten Signierschlüssel. Keine TV-Version freigegeben.')
        out=release/('CleanTV-'+version['name']+'.apk');shutil.copy2(apk,out)
        inputs=json.loads((release/'inputs.json').read_text())
        if any(digest(source/path)!=sha for path,sha in inputs.items()):raise RuntimeError('Gesicherte Quelldateien wurden während der Prüfung verändert.')
        manifest={'version':version,'apk':out.name,'sha256':digest(out),'signerSha256':config['signerSha256'],'checks':checks,'sourceManifestSha256':digest(release/'inputs.json'),'designSha256':digest(source/'app/design.json'),'tvInstalled':False,'nativeQr':'Shared frontend with simulated QR state; real QR and native screenshots verified separately.'}
        write(release/'manifest.json',manifest)
        status(release,'ready','APK, Vorschauen und Änderungsliste sind fertig. Die Übertragung erfolgt anschließend gemeinsam im Chat.')
    except Exception as error:
        print(type(error).__name__+': '+str(error),flush=True);status(release,'failed',str(error)+' Details: build.log');return 1
    return 0
if __name__=='__main__':sys.exit(run(Path(sys.argv[1]).resolve()))
