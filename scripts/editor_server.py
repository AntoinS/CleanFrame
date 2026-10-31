"""Loopback-only visual editor. Draft writes are CAS, atomic and path allowlisted."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import threading
import time
import uuid
from urllib.parse import urlsplit, parse_qs
from preview import Preview, PreviewServer, ROOT

PREF_KEYS = 'bg wall layout apps font controls focus material mode dateVisible spotifyEnabled spotifySurface spotifyTransparency controlsPosition language home'.split()
DEFAULT_PREFS = dict(bg='photo',wall='summit',layout='dock',apps='icons',font='manrope',controls='text',focus='glass',material='tint',mode='fixed',dateVisible=True,spotifyEnabled=True,spotifySurface='cover',spotifyTransparency=60,controlsPosition='top',language='de',home=['youtube','netflix','primevideo','spotify','appletv'])

def encode(value): return json.dumps(value,ensure_ascii=False,indent=2).encode('utf-8')
def atomic(path, value):
    path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
    fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as out: out.write(encode(value)); out.flush(); os.fsync(out.fileno())
    os.replace(tmp,path)
def initial(storage):
    prefs=DEFAULT_PREFS.copy(); baseline=storage/'baseline.json'
    if baseline.exists(): prefs.update({k:v for k,v in json.loads(baseline.read_text()).items() if k in PREF_KEYS})
    captured=json.loads((storage/'baseline-metadata.json').read_text()).get('capturedAt') if (storage/'baseline-metadata.json').exists() else time.strftime('%Y-%m-%dT%H:%M:%S%z')
    return dict(version=1,basePrefs=prefs.copy(),preferences=prefs,baseCapturedAt=captured,design=json.loads((ROOT/'app/design.json').read_text()),ui=dict(view='home',state='compact',scenario='playing',resolution=1080,selection='',snap=True,mode='edit',scope='element'),undo=[],redo=[])
def fingerprint():
    h=hashlib.sha256()
    for folder in ['app','editor','scripts','android/app/src']:
        for path in sorted((ROOT/folder).rglob('*')):
            if path.is_file() and '__pycache__' not in str(path) and path.name != '.DS_Store':
                stat=path.stat();h.update(str(path.relative_to(ROOT)).encode());h.update(str((stat.st_mtime_ns,stat.st_size)).encode())
    for name in ['package.json','package-lock.json','eslint.config.mjs','android/build.gradle','android/settings.gradle','android/gradle.properties','android/app/build.gradle']:
        path=ROOT/name
        if path.exists():
            stat=path.stat();h.update(name.encode());h.update(str((stat.st_mtime_ns,stat.st_size)).encode())
    return h.hexdigest()
def validate(project):
    result=subprocess.run(['node',str(ROOT/'scripts/editor-schema.mjs'),'--stdin'],input=encode(project),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,cwd=ROOT)
    if result.returncode: raise ValueError('Der Entwurf enthält ungültige Werte. '+result.stdout.decode()[:1400])

class Store:
    def __init__(self,path):
        self.path=path; self.lock=threading.Lock(); self.token=secrets.token_urlsafe(32); self.job=None
        previous=sorted((path/'releases').glob('*/status.json'),key=lambda p:p.stat().st_mtime)
        if previous:self.job=previous[-1].parent.name
        path.mkdir(parents=True,exist_ok=True)
        token_file=path/'session-token.json'
        if token_file.exists():self.token=json.loads(token_file.read_text())
        else:atomic(token_file,self.token)
        if not (path/'project.json').exists(): atomic(path/'project.json',initial(path))
        from editor_tv import Television
        from editor_agent import Agent
        from editor_live import LiveVideo
        self.tv=Television(self);self.agent=Agent(self);self.live=LiveVideo(self.tv)
    def read(self):
        raw=(self.path/'project.json').read_bytes()
        return json.loads(raw),hashlib.sha256(raw).hexdigest()

    def import_tv(self, expected):
        # Do not hold the draft lock during device I/O. Recheck before committing.
        with self.lock:
            if self.read()[1] != expected: raise DraftConflict('Der Entwurf wurde inzwischen geändert. Bitte aktuellen Stand laden.')
        captured = self.tv.read_draft()
        with self.lock:
            project, etag = self.read()
            if etag != expected: raise DraftConflict('Der Entwurf wurde während des TV-Abrufs geändert. Nichts überschrieben; bitte erneut versuchen.')
            stamp = time.strftime('%Y-%m-%dT%H:%M:%S%z')
            updated = copy.deepcopy(project)
            prefs = {key: captured['preferences'][key] for key in PREF_KEYS}
            updated.update(design=captured['design'], preferences=prefs, basePrefs=copy.deepcopy(prefs),
                           baseCapturedAt=stamp, guides=[], undo=[], redo=[],
                           tvSource=dict(version=captured['version'], apps=captured['apps'], addons=captured['addons']))
            updated.pop('addonLab', None)
            updated['ui'].update(view='home', state='compact', mode='edit', selection='')
            validate(updated)
            ident=uuid.uuid4().hex
            atomic(self.path/'snapshots'/f'{ident}.json', dict(id=ident, name='Vor TV-Import · '+stamp,
                   created=stamp, project=project))
            atomic(self.path/'project.json', updated)
            local=json.loads((ROOT/'package.json').read_text())
            expected_version=local['version']+'-android'+str(local['cleanframe']['androidVersionCode'])
            return dict(etag=self.read()[1], version=captured['version'], snapshot=ident,
                        sourceMatches=captured['version']==expected_version)


class DraftConflict(ValueError): pass

class Editor(Preview):
    def log_message(self,*args): pass
    def safe_host(self): return self.headers.get('Host') == '127.0.0.1:'+str(self.server.server_port)
    def json(self,value,code=200):
        raw=encode(value); self.send_response(code); self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    def file(self,path,kind=None):
        if not path.is_file(): self.send_error(404);return
        raw=path.read_bytes();self.send_response(200);self.send_header('Content-Type',kind or self.guess_type(str(path)));self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    def end_headers(self):
        self.send_header('X-Content-Type-Options','nosniff');self.send_header('Referrer-Policy','same-origin');self.send_header('X-Frame-Options','SAMEORIGIN')
        self.send_header('Cache-Control','no-store')
        super().end_headers()
    def do_GET(self):
        if not self.safe_host() or self.headers.get('Sec-Fetch-Site')=='cross-site': self.send_error(403);return
        route=urlsplit(self.path).path;store=self.server.store
        if route in ['/editor','/editor/']:
            return self.file(ROOT/'editor/index.html','text/html; charset=utf-8')
        if route.startswith('/editor/'):
            name=route[len('/editor/'):]
            allowed={'editor.js','editor.css','mock.js','qr-preview.js','workspace.js','geometry.js','native-video.js','addon-lab.js','addon-mock.js'}
            if name not in allowed:self.send_error(404);return
            return self.file(ROOT/'editor'/name)
        if route=='/media/spotify-1.png': return self.file(ROOT/'app/assets/wallpapers/cove-thumb.jpg','image/jpeg')
        if route=='/editor-vendor/interact.js': return self.file(ROOT/'node_modules/interactjs/dist/interact.min.js','text/javascript')
        if route=='/api/editor/state':
            project,etag=store.read();return self.json(dict(project=project,etag=etag,token=store.token,source=fingerprint()))
        if route=='/api/editor/watch':
            _,etag=store.read();return self.json(dict(etag=etag,source=fingerprint(),token=store.token))
        if route=='/api/editor/addons/diagnostics':return self.json(store.tv.addon_diagnostics())
        if route=='/api/editor/tv/status':return self.json(store.tv.status())
        if route=='/api/editor/agent/state':return self.json(store.agent.state())
        if route=='/api/editor/tv/live/status':return self.json(store.live.status())
        if route=='/api/editor/tv/live':
            profile=parse_qs(urlsplit(self.path).query).get('profile',['native'])[0]
            frames=store.live.frames(profile)
            try:first=next(frames)
            except ValueError as error:return self.json({'error':str(error)},409)
            except StopIteration:return self.json({'error':'Der Videokanal konnte nicht gestartet werden.'},503)
            try:
                self.connection.settimeout(5)
                self.send_response(200);self.send_header('Content-Type','application/octet-stream');self.send_header('Connection','close');self.end_headers()
                self.wfile.write(first);self.wfile.flush()
                for packet in frames:
                    self.wfile.write(packet);self.wfile.flush()
            except (BrokenPipeError,ConnectionError,TimeoutError,OSError):pass
            finally:frames.close()
            return
        if route=='/api/editor/tv/frame':
            try:
                raw,meta=store.tv.screenshot()
                self.send_response(200);self.send_header('Content-Type','image/png');self.send_header('Content-Length',str(len(raw)))
                self.send_header('X-CleanFrame-Frame',json.dumps(meta));self.end_headers();self.wfile.write(raw)
            except Exception as error:self.json({'error':str(error)},503)
            return
        if route=='/api/editor/snapshots':
            values=[]
            for p in sorted((store.path/'snapshots').glob('*.json'),reverse=True):
                value=json.loads(p.read_text());values.append({k:value[k] for k in ['id','name','created']})
            return self.json(values)
        if route=='/api/editor/job':
            path=store.path/'releases'/str(store.job)/'status.json'
            return self.json(json.loads(path.read_text()) if store.job and path.exists() else {'state':'idle'})
        if route=='/home/' and parse_qs(urlsplit(self.path).query).get('editor')==['1']:
            project,_=store.read();boot=json.dumps({'preferences':project['preferences'],'scenario':project['ui'].get('scenario','playing'),'tvSource':project.get('tvSource'),'addonLab':project.get('addonLab')},ensure_ascii=False).replace('<','\\u003c')
            raw=(ROOT/'app/index.html').read_text().replace('<script src="android-device.js">','<script>window.CleanFrameEditorBoot='+boot+';</script><script src="/editor/mock.js"></script><script src="/editor/addon-mock.js"></script><script src="android-device.js">').replace('</body>','<script src="/editor/qr-preview.js"></script></body>').encode()
            self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw);return
        if route.startswith('/api/') or not (route.startswith('/home/') or route.startswith('/build/') or route=='/'):
            self.send_error(404);return
        return super().do_GET()
    def do_POST(self):
        origin='http://127.0.0.1:'+str(self.server.server_port);store=self.server.store
        if not self.safe_host() or self.headers.get('Origin')!=origin or not secrets.compare_digest(self.headers.get('X-CleanFrame-Token',''),store.token):self.send_error(403);return
        if self.headers.get('Content-Type','').split(';')[0]!='application/json':self.send_error(415);return
        try:
            size=int(self.headers.get('Content-Length','0'))
            if size<2 or size>4_000_000:self.send_error(413);return
            value=json.loads(self.rfile.read(size));route=urlsplit(self.path).path
            if route=='/api/editor/tv/connect':return self.json(store.tv.connect())
            if route=='/api/editor/tv/import':return self.json(store.import_tv(value.get('etag')))
            if route=='/api/editor/agent/send':return self.json(store.agent.submit(value.get('text'),value.get('context',{})))
            if route=='/api/editor/agent/stop':return self.json(store.agent.stop())
            with store.lock:
                project,etag=store.read()
                if value.get('etag')!=etag:return self.json({'error':'Der Entwurf wurde an anderer Stelle geändert. Lade den aktuellen Stand oder sichere deine Änderungen separat.'},409)
                if route=='/api/editor/save':
                    validate(value['project']);atomic(store.path/'project.json',value['project']);project,etag=store.read();return self.json({'etag':etag})
                if route=='/api/editor/snapshot':
                    name=value.get('name','')
                    if not isinstance(name,str) or not name.strip() or len(name)>80:raise ValueError('Bitte einen Namen mit höchstens 80 Zeichen verwenden.')
                    ident=uuid.uuid4().hex;atomic(store.path/'snapshots'/f'{ident}.json',dict(id=ident,name=name.strip(),created=time.strftime('%Y-%m-%d %H:%M:%S'),project=project));return self.json({'id':ident})
                if route=='/api/editor/restore':
                    ident=value.get('id','')
                    if len(ident)!=32 or any(c not in '0123456789abcdef' for c in ident):raise ValueError('Unbekannter Zwischenstand.')
                    restored=json.loads((store.path/'snapshots'/f'{ident}.json').read_text())['project'];validate(restored);return self.json({'project':restored})
                if route=='/api/editor/prepare':
                    if store.job:
                        status=json.loads((store.path/'releases'/store.job/'status.json').read_text())
                        if status['state'] in ['freezing','checking','building']:return self.json({'error':'Eine TV-Version wird bereits vorbereitet.'},409)
                    validate(project)
                    from editor_release import freeze
                    ident=uuid.uuid4().hex[:12];release=store.path/'releases'/ident
                    freeze(release,project,store.path)
                    store.job=ident
                    log=open(release/'build.log','ab')
                    subprocess.Popen([sys.executable,str(ROOT/'scripts/editor_release.py'),str(release)],cwd=ROOT,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);log.close()
                    return self.json({'id':ident})
                self.send_error(404)
        except DraftConflict as error:self.json({'error':str(error)},409)
        except (ValueError,KeyError,FileNotFoundError,json.JSONDecodeError) as error:self.json({'error':str(error)},400)
        except Exception as error:self.json({'error':'Der Vorgang ist fehlgeschlagen: '+str(error)},500)

def run():
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=4797);parser.add_argument('--storage',type=Path,default=ROOT/'.local/editor');parser.add_argument('--open',action='store_true');parser.add_argument('--public-fonts',action='store_true');args=parser.parse_args()
    server=PreviewServer(('127.0.0.1',args.port),Editor);server.include_private_fonts=not args.public_fonts;server.store=Store(args.storage.resolve())
    url='http://127.0.0.1:'+str(server.server_port)+'/editor/'
    print('CleanFrame editor: '+url,flush=True)
    if args.open:
        import webbrowser
        webbrowser.open(url)
    import signal
    def stop(*_):
        server.store.live.close();server.store.agent.close()
        worker=server.store.live.worker
        if worker:worker.join(timeout=10)
        raise SystemExit(0)
    signal.signal(signal.SIGTERM,stop)
    signal.signal(signal.SIGINT,stop)
    server.serve_forever()
if __name__=='__main__':run()
