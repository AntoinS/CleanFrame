"""A small client of the installed Codex app-server, using its existing login.

Only the two validated editor tools can write a draft. Shell, apps, MCP servers,
network, approvals and delegation are disabled for this integration.
"""
import copy
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import time
import tomllib
import uuid

INSTRUCTIONS = '''Du bist der Designassistent im lokalen CleanFrame Editor. Antworte knapp auf Deutsch.
Du gestaltest vorhandene CleanFrame-Elemente im ENTWURF mit editor_read und editor_apply.
Nutze immer stabile Elementkennungen aus dem gelieferten Register. Keine Kennungen erfinden.
Geometrie und Designwerte sind logische 1920×1080 Einheiten. Bei 4K sind sichtbare Pixelmaße
doppelt so groß, bei 720p zwei Drittel. Rechne Benutzereingaben entsprechend um.
editor_apply führt Änderungen sofort in der Vorschau aus, erhält Einstellungen, dokumentiert
Rückgängig und prüft Konflikte. Lies vor einer Änderung den Entwurf. Bei veraltetem Etag oder
fehlender Geometrie bitte den Benutzer, die gewünschte Ansicht zu öffnen. Nie Änderungen verlieren.
Ändere nur die angefragten Eigenschaften, nicht andere Regeln. style ergänzt eine passende Regel;
null entfernt eine einzelne Eigenschaft. Ein leeres style entfernt die gesamte passende Regel.
Du kannst Position, Größe, Typografie, Farbe, Rundung, Transparenz, Abstände und vorhandene
CleanFrame-Einstellungen ändern. Neue Funktionen oder Quellcodeänderungen gehören in den
Hauptchat mit Codex; sage das klar, wenn es erforderlich ist. Kein TV-Zugriff, keine Installation,
keine Anmeldung, keine Geheimnisse, keine anderen Werkzeuge, keine weiteren Agenten.
Für den aktuellen TV-Stand verweise auf den Button „TV-Stand holen“ oben im Editor.
Er übernimmt Design und Einstellungen als Entwurf und sichert den vorherigen Zwischenstand.
Behaupte eine Änderung nur nach erfolgreichem editor_apply. Ungeprüfte Ansichten sind ungeprüft.
'''


class Agent:
    def __init__(self, store):
        self.store = store
        self.lock = threading.RLock()
        self.write_lock = threading.Lock()
        self.pending = {}
        self.sequence = 0
        self.proc = None
        self.thread_id = None
        self.turn_id = None
        self.context = {}
        self.turn_etag = None
        self.active = False
        self.error = None
        self.activity = 'Bereit für deinen Entwurf'
        self.model = None
        self.messages = []
        self.path = store.path / 'chat.json'
        if self.path.exists():
            self.messages = json.loads(self.path.read_text()).get('messages', [])[-100:]
        self.auth = None

    def persist(self):
        from editor_server import atomic
        atomic(self.path, {'messages': self.messages[-100:]})

    def state(self):
        with self.lock:
            return dict(available=bool(shutil.which('codex')), active=self.active,
                        activity=self.activity, error=self.error, model=self.model,
                        messages=copy.deepcopy(self.messages), signedIn=self.auth)

    def send(self, value):
        with self.write_lock:
            if not self.proc or self.proc.poll() is not None:
                raise ValueError('Codex ist nicht erreichbar. Nachricht erneut senden.')
            self.proc.stdin.write(json.dumps(value, ensure_ascii=False) + '\n')
            self.proc.stdin.flush()

    def rpc(self, method, params, timeout=30):
        with self.lock:
            self.sequence += 1
            ident = self.sequence
            reply = queue.Queue(maxsize=1)
            self.pending[ident] = reply
        try:
            self.send(dict(id=ident, method=method, params=params))
            result = reply.get(timeout=timeout)
            if 'error' in result:
                raise ValueError(result['error'].get('message', 'Codex konnte die Anfrage nicht ausführen.'))
            return result.get('result', {})
        except queue.Empty as error:
            raise ValueError('Codex antwortet nicht. Bitte erneut versuchen.') from error
        finally:
            with self.lock:
                self.pending.pop(ident, None)

    def start(self):
        if self.proc and self.proc.poll() is None and self.thread_id:
            return
        if self.proc and self.proc.poll() is None:
            old, self.proc = self.proc, None
            old.terminate()
            try:
                old.wait(timeout=3)
            except subprocess.TimeoutExpired:
                old.kill()
        self.thread_id = None
        executable = shutil.which('codex')
        if not executable:
            raise ValueError('Codex CLI fehlt. Installiere Codex und melde dich dort an.')
        cmd = [executable, 'app-server', '--stdio', '-c', 'approval_policy="never"',
               '-c', 'sandbox_mode="read-only"', '-c', 'web_search="disabled"']
        for feature in ['apps', 'shell_tool', 'unified_exec', 'multi_agent', 'multi_agent_v2']:
            cmd += ['-c', 'features.' + feature + '=false']
        # Disable inherited integrations without exposing or copying their credentials.
        home = Path(os.environ.get('CODEX_HOME', str(Path.home() / '.codex')))
        config_path = home / 'config.toml'
        config = tomllib.loads(config_path.read_text()) if config_path.exists() else {}
        for group in ['mcp_servers', 'plugins']:
            for name in config.get(group, {}):
                if '.' in name:
                    raise ValueError('Diese Codex-Konfiguration enthält einen Integrationsnamen mit Punkt. Die lokale Chat-Anbindung kann ihn noch nicht sicher deaktivieren.')
                cmd += ['-c', group + '.' + name + '.enabled=false']
        log = open(self.store.path / 'codex.log', 'ab')
        os.chmod(self.store.path / 'codex.log', 0o600)
        self.proc = subprocess.Popen(cmd, cwd=self.store.path, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=log, text=True, bufsize=1)
        log.close()
        threading.Thread(target=self.read_loop, args=(self.proc,), daemon=True).start()
        self.rpc('initialize', {'clientInfo': {'name': 'cleanframe_editor', 'title': 'CleanFrame Editor', 'version': '1.1.0'},
                                'capabilities': {'experimentalApi': True}})
        self.send({'method': 'initialized'})
        account = self.rpc('account/read', {'refreshToken': False})
        self.auth = bool(account.get('account'))
        if not self.auth:
            raise ValueError('Codex ist noch nicht angemeldet. Im Terminal „codex login“ ausführen und dann erneut senden.')
        models = self.rpc('model/list', {}).get('data', [])
        available = [m for m in models if not m.get('hidden')]
        preferred = next((m for m in available if m['model'] == config.get('model')), None)
        chosen = preferred or next((m for m in available if m.get('isDefault')), None)
        if not chosen:
            raise ValueError('Codex meldet kein verfügbares Standardmodell für diesen Zugang.')
        tools = [
            dict(type='function', name='editor_read', description='Read current draft, Etag, view, stable element IDs, geometry and supported styles.',
                 inputSchema={'type': 'object', 'properties': {}, 'additionalProperties': False}),
            dict(type='function', name='editor_apply', description='Merge rules and preference changes into the draft only. Requires the exact Etag from editor_read. Undo is recorded. Empty style removes rule; null deletes property.',
                 inputSchema={'type': 'object', 'required': ['etag', 'rules', 'preferences'], 'additionalProperties': False,
                              'properties': {'etag': {'type': 'string'}, 'rules': {'type': 'array', 'items': {'type': 'object'}}, 'preferences': {'type': 'object'}}})]
        result = self.rpc('thread/start', {'cwd': str(self.store.path), 'sandbox': 'read-only', 'approvalPolicy': 'never',
                          'ephemeral': True, 'model': chosen['model'], 'config': {'model_reasoning_effort': chosen['defaultReasoningEffort']},
                          'developerInstructions': INSTRUCTIONS, 'dynamicTools': tools})
        self.thread_id = result['thread']['id']
        self.model = result.get('model')

    def submit(self, text, context):
        if not isinstance(text, str) or not text.strip() or len(text) > 12000:
            raise ValueError('Bitte eine Nachricht mit 1 bis 12000 Zeichen eingeben.')
        with self.lock:
            if self.active:
                raise ValueError('Codex arbeitet noch. Stoppen oder die Antwort abwarten.')
            _, current_etag = self.store.read()
            if context.get('geometryEtag') and context['geometryEtag'] != current_etag:
                raise ValueError('Die Vorschau wurde inzwischen geändert. Aktuellen Entwurf laden und erneut senden.')
            self.turn_etag = current_etag
            self.active = True
            self.error = None
            self.activity = 'Codex verbindet sich …'
            self.context = context
            self.messages.append({'id': uuid.uuid4().hex, 'role': 'user', 'text': text.strip()})
            self.persist()
        threading.Thread(target=self.run_turn, args=(text.strip(),), daemon=True).start()
        return self.state()

    def run_turn(self, text):
        try:
            fresh = not self.proc or self.proc.poll() is not None
            self.start()
            history = self.messages[-11:-1] if fresh else []
            prompt = ('Bisheriger lokaler Chat zur Orientierung: ' + json.dumps(history, ensure_ascii=False) + '\n\n') if history else ''
            prompt += text
            self.activity = 'Codex bearbeitet den Entwurf …'
            result = self.rpc('turn/start', {'threadId': self.thread_id, 'input': [{'type': 'text', 'text': prompt}],
                            'sandboxPolicy': {'type': 'readOnly', 'networkAccess': False}, 'approvalPolicy': 'never'})
            with self.lock:
                if self.active:
                    self.turn_id = result['turn']['id']
        except Exception as error:
            self.fail(str(error))

    def fail(self, message):
        with self.lock:
            self.active = False
            self.turn_id = None
            self.error = message
            self.activity = 'Codex konnte nicht fortfahren'
            self.persist()

    def stop(self):
        if self.active and self.turn_id:
            self.rpc('turn/interrupt', {'threadId': self.thread_id, 'turnId': self.turn_id})
        elif self.active and self.proc:
            self.proc.terminate()
            self.fail('Gestoppt. Bereits sichtbare Änderungen bleiben über Rückgängig erreichbar.')
        return self.state()

    def read_loop(self, proc):
        try:
            for line in proc.stdout:
                event = json.loads(line)
                ident = event.get('id')
                if 'method' not in event:
                    with self.lock:
                        pending = self.pending.get(ident)
                    if pending:
                        pending.put(event)
                    continue
                if ident is not None:
                    # Handle tools off the protocol reader so normal RPC replies keep flowing.
                    threading.Thread(target=self.server_request, args=(event,), daemon=True).start()
                else:
                    self.notification(event['method'], event.get('params', {}))
        except (ValueError, OSError):
            pass
        finally:
            if self.proc is proc and self.active:
                self.fail('Verbindung zu Codex beendet. Du kannst die Nachricht erneut senden.')

    def notification(self, method, params):
        with self.lock:
            if method == 'item/agentMessage/delta':
                ident = params['itemId']
                message = next((m for m in self.messages if m['id'] == ident), None)
                if not message:
                    message = dict(id=ident, role='assistant', text='')
                    self.messages.append(message)
                message['text'] += params.get('delta', '')
            elif method == 'item/completed' and params.get('item', {}).get('type') == 'agentMessage':
                item = params['item']
                message = next((m for m in self.messages if m['id'] == item['id']), None)
                if not message:
                    self.messages.append(dict(id=item['id'], role='assistant', text=item.get('text', '')))
                else:
                    message['text'] = item.get('text', message['text'])
                self.persist()
            elif method == 'turn/completed':
                self.active = False
                self.turn_id = None
                turn = params.get('turn', {})
                self.error = (turn.get('error') or {}).get('message')
                self.activity = 'Gestoppt' if turn.get('status') == 'interrupted' else 'Bereit für deinen Entwurf'
                self.persist()
            elif method == 'error' and not params.get('willRetry', False):
                self.fail((params.get('error') or {}).get('message', 'Codex meldet einen Fehler.'))

    def server_request(self, event):
        try:
            if event['method'] != 'item/tool/call':
                self.send({'id': event['id'], 'error': {'code': -32601, 'message': 'This editor permits only its draft tools. No approvals or other capabilities.'}})
                return
            params = event['params']
            if not self.active or params.get('threadId') != self.thread_id:
                raise ValueError('Kein aktiver Editorauftrag.')
            tool = params['tool']
            if tool == 'editor_read':
                project, etag = self.store.read()
                result = dict(etag=etag, design=project['design'], preferences=project['preferences'], ui=project['ui'], context=self.context)
                result['styles'] = 'offsetX offsetY width height fontFamily fontSize fontWeight lineHeight letterSpacing radius opacity color background padding spacing paddingTop paddingRight paddingBottom paddingLeft marginTop marginRight marginBottom marginLeft gap'
                result['geometryFresh'] = self.context.get('geometryEtag') == etag
                result['concurrentChange'] = etag != self.turn_etag
                self.activity = 'Codex liest die aktuelle Ansicht …'
            elif tool == 'editor_apply':
                result = self.apply(params['arguments'])
                self.activity = 'Entwurf aktualisiert'
            else:
                raise ValueError('Unbekanntes Editorwerkzeug.')
            response = dict(success=True, contentItems=[dict(type='inputText', text=json.dumps(result, ensure_ascii=False))])
        except Exception as error:
            response = dict(success=False, contentItems=[dict(type='inputText', text=str(error))])
        try:
            self.send({'id': event['id'], 'result': response})
        except (ValueError, OSError):
            pass

    def apply(self, args):
        from editor_server import atomic, validate
        with self.store.lock:
            project, etag = self.store.read()
            if args.get('etag') != etag or etag != self.turn_etag:
                raise ValueError('Entwurf gleichzeitig verändert. Nichts überschrieben. Erneut lesen und den Benutzer bei veralteter Geometrie informieren.')
            before = copy.deepcopy({k: project[k] for k in ['design', 'preferences', 'basePrefs']})
            for key in ['guides', 'baseCapturedAt', 'tvSource', 'addonLab']:
                if key in project: before[key] = copy.deepcopy(project[key])
            for incoming in args.get('rules', []):
                keys = ['view', 'layout', 'state', 'target']
                rule = next((r for r in project['design']['rules'] if all(r[k] == incoming[k] for k in keys)), None)
                if not rule:
                    rule = {k: incoming[k] for k in keys}
                    rule['style'] = {}
                    project['design']['rules'].append(rule)
                if not incoming['style']:
                    rule['style'] = {}
                else:
                    for key, value in incoming['style'].items():
                        if value is None:
                            rule['style'].pop(key, None)
                        else:
                            rule['style'][key] = value
            project['design']['rules'] = [r for r in project['design']['rules'] if r['style']]
            project['preferences'].update(args.get('preferences', {}))
            project['undo'] = (project.get('undo', []) + [before])[-50:]
            project['redo'] = []
            validate(project)
            atomic(self.store.path / 'project.json', project)
            _, updated = self.store.read()
            self.turn_etag = updated
            return dict(saved=True, etag=updated, note='Nur Entwurf aktualisiert. Vorschau lädt automatisch. Keine TV-Übertragung.')

    def close(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
