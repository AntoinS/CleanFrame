import copy
import json
from pathlib import Path
import struct
import tempfile
from itertools import islice
from concurrent.futures import ThreadPoolExecutor, TimeoutError
import unittest
from unittest.mock import patch, Mock
from editor_server import Store, Editor, atomic, DraftConflict


class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name))

    def tearDown(self):
        self.store.live.close()
        self.store.agent.close()
        self.temp.cleanup()

    def agent_patch(self, **values):
        project, etag = self.store.read()
        self.store.agent.turn_etag = etag
        args = dict(etag=etag, rules=[dict(view='home', layout='dock', state='*', target='caption', style={'offsetX': -8})], preferences={})
        args.update(values)
        return self.store.agent.apply(args)

    def test_agent_preserves_preferences_and_records_undo(self):
        before, _ = self.store.read()
        self.agent_patch()
        after, _ = self.store.read()
        self.assertEqual(after['preferences'], before['preferences'])
        self.assertEqual(after['undo'][-1]['design'], before['design'])
        caption = next(r for r in after['design']['rules'] if r['target'] == 'caption')
        self.assertEqual(caption['style']['offsetX'], -8)

    def test_agent_cannot_overwrite_concurrent_edits_even_after_reading_new_etag(self):
        project, etag = self.store.read()
        self.store.agent.turn_etag = etag
        project['preferences']['language'] = 'en'
        atomic(self.store.path / 'project.json', project)
        _, new_etag = self.store.read()
        with self.assertRaises(ValueError):
            self.store.agent.apply(dict(etag=new_etag, rules=[], preferences={'language': 'de'}))
        self.assertEqual(self.store.read()[0]['preferences']['language'], 'en')

    def test_agent_rejects_unknown_preferences_and_executable_styles(self):
        before = self.store.read()
        with self.assertRaises(ValueError):
            self.agent_patch(preferences={'spotifyToken': 'forbidden'})
        self.assertEqual(self.store.read(), before)
        with self.assertRaises(ValueError):
            self.agent_patch(rules=[dict(view='home', layout='dock', state='*', target='caption', style={'background': 'url(https://example.com)'})])
        self.assertEqual(self.store.read(), before)

    def test_real_pixels_and_guides_are_validated(self):
        from editor_server import validate
        project, _ = self.store.read()
        project['ui']['resolution'] = 2160
        project['guides'] = [dict(id='g', axis='x', position=400, view='home', layout='dock')]
        project['design']['rules'] = [dict(view='home',layout='dock',state='*',target='caption',style={'paddingLeft':24,'marginTop':8,'gap':12})]
        validate(project)
        project['guides'][0]['position'] = 9999
        with self.assertRaises(ValueError):
            validate(project)

    def test_missing_tv_never_reports_ready(self):
        status = self.store.tv.status()
        self.assertFalse(status['connected'])
        self.assertFalse(status['ready'])

    def tv_capture(self):
        project, _ = self.store.read()
        return dict(preferences={**project['preferences'], 'wall': 'mint', 'language': 'en'},
                    design={'version': 1, 'rules': []}, version='0.7.0-android16',
                    apps=[{'appId': 'com.spotify.tv.android', 'name': 'Spotify'}],
                    addons=[dict(id='spotify', name='Spotify', kind='spotify', version='1.0.0', enabled=False)])

    def test_import_saves_exact_old_draft_and_rebases_preferences(self):
        before, etag = self.store.read()
        capture = self.tv_capture()
        capture['preferences']['spotifyToken'] = 'must-not-import'
        with patch.object(self.store.tv, 'read_draft', return_value=capture):
            result = self.store.import_tv(etag)
        project, _ = self.store.read()
        self.assertEqual(project['preferences']['wall'], 'mint')
        self.assertEqual(project['preferences'], project['basePrefs'])
        self.assertEqual(project['design'], capture['design'])
        self.assertFalse(project['tvSource']['addons'][0]['enabled'])
        self.assertNotIn('spotifyToken', project['preferences'])
        backup = json.loads((self.store.path/'snapshots'/f"{result['snapshot']}.json").read_text())
        self.assertEqual(backup['project'], before)
        self.assertNotEqual(result['etag'], etag)

    def test_import_rejects_stale_request_without_contacting_tv(self):
        with patch.object(self.store.tv, 'read_draft') as capture:
            with self.assertRaises(DraftConflict): self.store.import_tv('stale')
            capture.assert_not_called()

    def test_import_does_not_overwrite_edits_during_device_read(self):
        before, etag = self.store.read()
        def capture():
            before['preferences']['language'] = 'en'
            atomic(self.store.path/'project.json', before)
            return self.tv_capture()
        with patch.object(self.store.tv, 'read_draft', side_effect=capture):
            with self.assertRaises(DraftConflict): self.store.import_tv(etag)
        self.assertEqual(self.store.read()[0], before)
        self.assertFalse((self.store.path/'snapshots').exists())

    def test_failed_or_invalid_capture_preserves_draft(self):
        before, etag = self.store.read()
        with patch.object(self.store.tv, 'read_draft', side_effect=ValueError('TV offline')):
            with self.assertRaises(ValueError): self.store.import_tv(etag)
        invalid = self.tv_capture()
        invalid['design']['rules'] = [dict(view='home', layout='dock', state='*', target='caption', style={'background':'url(https://example.com)'})]
        with patch.object(self.store.tv, 'read_draft', return_value=invalid):
            with self.assertRaises(ValueError): self.store.import_tv(etag)
        self.assertEqual(self.store.read(), (before, etag))
        self.assertFalse((self.store.path/'snapshots').exists())

    def test_capture_always_removes_only_its_own_forward(self):
        with patch.object(self.store.tv, 'config', return_value={'package':'test.package'}), \
             patch.object(self.store.tv, 'adb', side_effect=['123', 'versionName=0.7.0-android16', '4567', '']) as adb, \
             patch('editor_tv.subprocess.run', side_effect=ValueError('WebView unavailable')):
            with self.assertRaises(ValueError): self.store.tv.read_draft()
            self.assertEqual(adb.call_args.args[0], ['forward', '--remove', 'tcp:4567'])

    def test_native_screenshot_has_dimensions_and_cache(self):
        raw = b'\x89PNG\r\n\x1a\n' + b'\x00\x00\x00\rIHDR' + struct.pack('>II',1920,1080)
        with patch.object(self.store.tv,'adb',return_value=raw) as adb:
            image,meta = self.store.tv.screenshot()
            self.assertEqual((meta['width'],meta['height']),(1920,1080))
            self.assertEqual(self.store.tv.screenshot()[0],image)
            adb.assert_called_once_with(['exec-out','screencap','-p'],timeout=12,binary=True)

    def test_capture_failure_does_not_mark_old_frame_fresh(self):
        self.store.tv.captured = 10
        with patch.object(self.store.tv,'adb',return_value=b'error'):
            with self.assertRaises(ValueError):
                self.store.tv.screenshot()
        self.assertEqual(self.store.tv.captured,10)

    def test_connected_is_separate_from_prepared(self):
        atomic(self.store.path/'tv.json',dict(adb='/fake',serial='test-device',package='test.package',label='Test TV'))
        replies=['device','versionName=0.6.1-android9','Physical size: 1920x1080','DisplayDeviceInfo{"TV": id, 3840 x 2160, modeId 1}']
        with patch.object(self.store.tv,'adb',side_effect=replies):
            status=self.store.tv.status()
        self.assertTrue(status['connected']);self.assertFalse(status['ready'])
        self.assertEqual(status['uiResolution'],[1920,1080]);self.assertEqual(status['panelResolution'],[3840,2160])

    def test_video_frames_are_complete_and_not_screenshot_polls(self):
        from editor_live import video_packets
        session=struct.pack('>III',0x80000000,1920,1080)
        config=struct.pack('>QI',1<<62,3)+b'sps'
        key=struct.pack('>QI',(1<<61)|1234,5)+b'frame'
        class Connection:
            def __init__(self):
                self.data=b'h264'+session+config+key
            def recv(self,n):
                out,self.data=self.data[:min(3,n)],self.data[min(3,n):]
                return out
        self.assertEqual(list(islice(video_packets(Connection()),3)),[('session',session),('config',config),('key',key)])

    def test_video_viewers_share_capture_and_last_viewer_stops_it(self):
        video=self.store.live
        starts=[]
        def capture():
            starts.append(True)
            with video.condition:
                video.gop=[(1,b'frame')];video.session=b'session';video.config_packet=b'config';video.sequence=1;video.phase='streaming';video.condition.notify_all()
            video.stop_event.wait(3)
            with video.condition:
                video.phase='stopped';video.worker=None;video.condition.notify_all()
        with patch.object(video,'run',side_effect=capture),patch.object(self.store.tv,'adb') as adb:
            first,second=video.frames(),video.frames()
            self.assertEqual(next(first),b'h264');self.assertEqual(next(second),b'h264')
            self.assertEqual(next(first),b'session');self.assertEqual(next(first),b'config');self.assertEqual(next(first),b'frame')
            self.assertEqual(video.status()['viewers'],2);self.assertEqual(len(starts),1)
            first.close();self.assertFalse(video.stop_event.is_set())
            second.close();self.assertTrue(video.stop_event.is_set())
            adb.assert_not_called()

    def test_new_live_viewer_waits_for_a_fresh_keyframe(self):
        video=self.store.live
        video.worker=type('Worker',(),{'is_alive':lambda _:True})()
        video.phase='streaming';video.sequence=1
        video.gop=[(1,b'old-key')];video.session=b'session';video.config_packet=b'config'
        viewer=video.frames()
        self.assertEqual(next(viewer),b'h264')
        with ThreadPoolExecutor() as pool:
            waiting=pool.submit(next,viewer)
            try:
                with self.assertRaises(TimeoutError):waiting.result(timeout=.05)
            finally:
                with video.condition:
                    video.sequence=2;video.gop=[(2,b'fresh-key')];video.condition.notify_all()
            self.assertEqual(waiting.result(timeout=1),b'session')
        self.assertEqual(next(viewer),b'config')
        self.assertEqual(next(viewer),b'fresh-key')
        viewer.close()

    def test_live_modes_are_allowlisted_and_native_keeps_full_resolution(self):
        from editor_live import capture_options
        self.assertFalse(any(v.startswith(('max_size=', 'max_fps=')) for v in capture_options('native')))
        self.assertIn('max_size=848',capture_options('smooth'))
        self.assertIn('min_size_alignment=16',capture_options('smooth'))
        self.assertIn('max_fps=30',capture_options('smooth'))
        with patch.object(self.store.tv,'adb') as adb:
            with self.assertRaises(ValueError):next(self.store.live.frames('untrusted'))
            self.assertEqual(self.store.live.viewers,0)
            self.assertIsNone(self.store.live.worker)
            adb.assert_not_called()

    def test_disconnect_during_headers_releases_live_subscription(self):
        closed=[]
        def subscription(_):
            try:yield b'h264'
            finally:closed.append(True)
        handler=object.__new__(Editor)
        handler.server=Mock(server_port=4797,store=self.store)
        handler.path='/api/editor/tv/live?profile=smooth'
        handler.headers={'Host':'127.0.0.1:4797'}
        handler.connection=Mock()
        handler.send_response=Mock(side_effect=BrokenPipeError())
        with patch.object(self.store.live,'frames',side_effect=subscription):handler.do_GET()
        self.assertEqual(closed,[True])

    def test_idle_capture_stays_open_without_counting_keepalive_as_frames(self):
        from editor_live import HEARTBEAT
        video=self.store.live
        def capture():
            with video.condition:
                video.gop=[(1,b'frame')];video.session=b'session';video.config_packet=b'config';video.sequence=1;video.phase='streaming';video.condition.notify_all()
            video.stop_event.wait(5)
            with video.condition:
                video.phase='stopped';video.worker=None;video.condition.notify_all()
        with patch.object(video,'run',side_effect=capture):
            viewer=video.frames('smooth')
            self.assertEqual([next(viewer) for _ in range(4)],[b'h264',b'session',b'config',b'frame'])
            self.assertEqual(next(viewer),HEARTBEAT)
            self.assertEqual(video.status()['frames'],1)
            self.assertEqual(video.status()['state'],'streaming')
            worker=video.worker;viewer.close();worker.join(timeout=2)
            self.assertFalse(worker.is_alive())
            self.assertEqual(video.status()['viewers'],0)


class AddonDiagnosticTests(unittest.TestCase):
    def test_only_fixed_read_commands_and_redacted_fields(self):
        from editor_tv import Television
        tv = Television(None)
        payload = dict(api=1, package='com.cleanframe.addon.appleremote', running=True,
                       pin='1234', secrets='PRIVATE', apple=dict(state='connected', pin='1234', pairedDevices=1),
                       backend='philips', philips=dict(state='connected', paired=True, certificatePinned=True, password='PRIVATE'),
                       keyboard=dict(ready=True, selected=True, text='PRIVATE'), events=[dict(id=1, kind='command', action='home', text='PRIVATE')])
        calls = []
        def adb(args, **kwargs):
            calls.append(args)
            return 'versionName=0.1.0' if 'package' in args else '  ' + json.dumps(payload)
        with patch.object(tv, 'adb', side_effect=adb):
            result = tv.addon_diagnostics()
        self.assertTrue(result['installed'])
        self.assertTrue(result['status']['keyboard']['ready'])
        self.assertTrue(result['status']['keyboard']['selected'])
        self.assertEqual(result['status']['backend'], 'philips')
        self.assertTrue(result['status']['philips']['paired'])
        self.assertTrue(result['status']['philips']['certificatePinned'])
        self.assertNotIn('PRIVATE', json.dumps(result))
        self.assertNotIn('1234', json.dumps(result))
        self.assertEqual(calls, [
            ['shell', 'dumpsys', 'package', 'com.cleanframe.addon.appleremote'],
            ['shell', 'dumpsys', 'activity', 'service', 'com.cleanframe.addon.appleremote/.RemoteService']])

    def test_stopped_service_is_not_started(self):
        from editor_tv import Television
        tv = Television(None)
        with patch.object(tv, 'adb', side_effect=['versionName=0.1.0', 'No services match']) as adb:
            result = tv.addon_diagnostics()
        self.assertIsNone(result['status'])
        self.assertEqual(adb.call_count, 2)


if __name__ == '__main__':
    unittest.main()
