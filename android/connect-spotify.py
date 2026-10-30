#!/usr/bin/env python3
"""Personal Spotify PKCE setup on the Mac; credentials go straight to TV-private storage."""
import argparse
import hashlib
import base64
import hmac
import html
import json
import secrets
import shlex
import ssl
import certifi
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

REDIRECT = 'http://127.0.0.1:8765/callback'
SCOPES = 'user-read-playback-state user-modify-playback-state'
PACKAGE = 'com.cleanframe.philips'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-id', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    if len(args.client_id) != 32 or any(c not in '0123456789abcdefABCDEF' for c in args.client_id):
        parser.error('Spotify client ID must contain 32 hexadecimal characters.')
    adb = [args.adb, '-s', args.serial]
    subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'id'], check=True, capture_output=True)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
    state = secrets.token_urlsafe(32)
    authorize = 'https://accounts.spotify.com/authorize?' + urllib.parse.urlencode({
        'response_type': 'code', 'client_id': args.client_id, 'redirect_uri': REDIRECT,
        'scope': SCOPES, 'state': state, 'code_challenge_method': 'S256', 'code_challenge': challenge,
    })
    complete = False
    deadline = time.monotonic() + 900

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # OAuth codes and query strings must never be logged.

        def page(self, code, content):
            body = ('<!doctype html><html lang="de"><meta charset="utf-8"><title>CleanFrame · Spotify</title>'
                    '<style>body{font:20px system-ui;max-width:620px;margin:15vh auto;padding:32px;'
                    'background:#171717;color:#eee;line-height:1.6}a{color:#1ed760}</style>'
                    '<h1>CleanFrame · Spotify</h1>' + content).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            nonlocal complete
            if self.headers.get('Host') != '127.0.0.1:8765':
                self.page(400, '<p>Ungültige Anfrage.</p>')
                return
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path == '/':
                self.page(200, '<p>Verbinde dein Spotify-Konto, um die Wiedergabe auf deinem aktiven Gerät '
                          'mit dem Fernseher zu steuern.</p><p><a href="' + html.escape(authorize, quote=True) + '">Mit Spotify verbinden</a></p>')
                return
            values = urllib.parse.parse_qs(parsed.query)
            if parsed.path != '/callback' or not hmac.compare_digest(values.get('state', [''])[0], state):
                self.page(400, '<p>Ungültige Anmeldung. Bitte das Setup neu starten.</p>')
                return
            if 'error' in values:
                self.page(200, '<p>Die Freigabe wurde nicht erteilt. Es wurde nichts gespeichert.</p>')
                complete = True
                return
            code = values.get('code', [''])[0]
            if not code or len(code) > 8192:
                self.page(400, '<p>Ungültige Antwort von Spotify.</p>')
                return
            phase = 'token exchange'
            try:
                body = urllib.parse.urlencode({'grant_type': 'authorization_code', 'client_id': args.client_id,
                         'redirect_uri': REDIRECT, 'code_verifier': verifier, 'code': code}).encode()
                request = urllib.request.Request('https://accounts.spotify.com/api/token', data=body,
                          headers={'Content-Type': 'application/x-www-form-urlencoded'}, method='POST')
                with urllib.request.urlopen(request, context=ssl.create_default_context(cafile=certifi.where()), timeout=15) as response:
                    token = json.loads(response.read(65536))
                if not set(SCOPES.split()).issubset(set(token.get('scope', '').split())):
                    raise ValueError('Required scopes not granted')
                refresh = token.get('refresh_token', '')
                if not refresh or len(refresh) > 8192:
                    raise ValueError('Missing refresh token')
                phase = 'private TV provisioning'
                credentials = json.dumps({'client_id': args.client_id, 'refresh_token': refresh}).encode()
                # ADB stdin avoids command-line tokens, shell history and persistent Mac token files.
                subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files'], check=True, capture_output=True)
                subprocess.run(adb + ['shell', '-T', 'run-as', PACKAGE, 'sh', '-c',
                               shlex.quote('umask 077; cat > files/spotify-oauth-import.tmp && mv files/spotify-oauth-import.tmp files/spotify-oauth-import.json')], input=credentials, check=True, capture_output=True)
                subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'test', '-s', 'files/spotify-oauth-import.json'], check=True, capture_output=True)
                phase = 'CleanFrame restart'
                subprocess.run(adb + ['shell', 'am', 'force-stop', PACKAGE], check=True, capture_output=True)
                subprocess.run(adb + ['shell', 'am', 'start', '-n', PACKAGE + '/.HomeActivity'], check=True, capture_output=True)
                self.page(200, '<p>Die Spotify-Freigabe wurde an CleanFrame übergeben. CleanFrame wurde auf dem Fernseher geöffnet.</p>'
                          '<p>Du kannst dieses Fenster jetzt schließen.</p>')
                print('Spotify credentials delivered privately. CleanFrame restarted for encrypted import.', flush=True)
                complete = True
            except Exception as failure:
                # Do not expose OAuth responses or subprocess input in browser/logs.
                self.page(502, '<p>Die Einrichtung konnte nicht abgeschlossen werden. Bitte die TV-Verbindung prüfen und das Setup erneut starten.</p>')
                print('Spotify setup failed during ' + phase + ' (' + type(failure).__name__ + '). No credentials were printed.', flush=True)
                complete = True

    server = HTTPServer(('127.0.0.1', 8765), Handler)
    server.timeout = 1
    print('Open http://127.0.0.1:8765/ to connect Spotify. Waiting up to 15 minutes.', flush=True)
    try:
        while not complete and time.monotonic() < deadline:
            server.handle_request()
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
