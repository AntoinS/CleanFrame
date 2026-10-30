"""OAuth callback checks with fake Spotify and fake ADB; no account or TV access."""
import contextlib
import http.client
import importlib.util
import io
import json
import re
import threading
import time
import unittest
import urllib.parse
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('connect_spotify', Path(__file__).with_name('connect-spotify.py'))
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)

class SetupTests(unittest.TestCase):
    def test_pkce_state_denial_and_private_provisioning(self):
        for approve in [False, True]:
            calls = []
            output = io.StringIO()
            token = {'scope': setup.SCOPES, 'refresh_token': 'TEST_REFRESH_NOT_REAL', 'access_token': 'TEST_ACCESS_NOT_REAL'}
            def run(*args, **kwargs):
                calls.append((args[0], kwargs))
            def get(path, host='127.0.0.1:8765'):
                conn = http.client.HTTPConnection('127.0.0.1', 8765, timeout=3)
                conn.request('GET', path, headers={'Host': host})
                response = conn.getresponse()
                body = response.read().decode()
                status = response.status
                conn.close()
                return status, body
            with patch('sys.argv', ['connect-spotify.py', '--client-id', 'a' * 32, '--serial', 'test-only']), \
                 patch.object(setup.subprocess, 'run', side_effect=run), \
                 patch.object(setup.urllib.request, 'urlopen', return_value=io.BytesIO(json.dumps(token).encode())) as exchange, \
                 contextlib.redirect_stdout(output):
                thread = threading.Thread(target=setup.main, daemon=True)
                thread.start()
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    try:
                        status, page = get('/')
                        break
                    except ConnectionRefusedError:
                        time.sleep(.02)
                self.assertEqual(status, 200)
                link = setup.html.unescape(re.search(r'href="([^"]+)"', page)[1])
                params = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)
                self.assertEqual(params['code_challenge_method'], ['S256'])
                self.assertEqual(len(params['code_challenge'][0]), 43)
                self.assertEqual(set(params['scope'][0].split()), set(setup.SCOPES.split()))
                self.assertNotIn('client_secret', params)
                self.assertEqual(get('/callback?state=wrong&code=TEST_CODE')[0], 400)
                self.assertEqual(get('/', 'attacker.invalid')[0], 400)
                self.assertEqual(exchange.call_count, 0)
                query = {'state': params['state'][0]}
                query['code' if approve else 'error'] = 'TEST_CODE' if approve else 'access_denied'
                self.assertEqual(get('/callback?' + urllib.parse.urlencode(query))[0], 200)
                thread.join(3)
                self.assertFalse(thread.is_alive())
                self.assertNotIn('TEST_REFRESH_NOT_REAL', output.getvalue())
                if approve:
                    self.assertEqual(exchange.call_count, 1)
                    request = exchange.call_args[0][0]
                    fields = urllib.parse.parse_qs(request.data.decode())
                    self.assertEqual(fields['redirect_uri'], [setup.REDIRECT])
                    challenge = setup.base64.urlsafe_b64encode(setup.hashlib.sha256(fields['code_verifier'][0].encode()).digest()).rstrip(b'=').decode()
                    self.assertEqual(challenge, params['code_challenge'][0])
                    provisioning = [call for call in calls if call[0][3:5] == ['shell', '-T']]
                    self.assertEqual(len(provisioning), 1)
                    self.assertNotIn('TEST_REFRESH_NOT_REAL', ' '.join(provisioning[0][0]))
                    self.assertEqual(json.loads(provisioning[0][1]['input'])['refresh_token'], 'TEST_REFRESH_NOT_REAL')
                else:
                    self.assertEqual(len(calls), 1)
                    self.assertEqual(exchange.call_count, 0)

if __name__ == '__main__':
    unittest.main()
