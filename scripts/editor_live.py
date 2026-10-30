"""Native scrcpy H.264 packets, passed unchanged to the browser decoder.

Only capture is enabled. Protocol framing follows scrcpy 5's documented session
and media packet headers. The host does not decode, re-encode, scale or rate-limit
the video. Slow viewers restart at the latest keyframe instead of accumulating delay.
"""
from collections import deque
import os
from pathlib import Path
import secrets
import subprocess
import socket
import struct
import threading
import time

PROFILES = {'native', 'smooth'}
# A zero-length transport packet keeps idle HTTP viewers connected. It is not a
# video frame and must never contribute to either frame counter.
HEARTBEAT = bytes(12)


def capture_options(profile):
    if profile not in PROFILES:
        raise ValueError('Unbekannter Live-Modus.')
    # The repeat interval applies only when the surface has no new frame.
    repeat = 33333 if profile == 'smooth' else 200000
    options = ['video_bit_rate=4000000',
               'video_codec_options=i-frame-interval:float=0.25,frame-rate=30,repeat-previous-frame-after:long=' + str(repeat),
               'downsize_on_error=false']
    if profile == 'smooth':
        # The Philips declares 2 px alignment but produces corrupt color planes
        # at 854 px. Complete 16 px blocks avoid its capture/encoder stride bug.
        options += ['max_size=848', 'min_size_alignment=16', 'max_fps=30']
    return options


def read_exact(stream, length):
    data = bytearray()
    while len(data) < length:
        chunk = stream.recv(length - len(data))
        if not chunk:
            raise EOFError('Der TV hat den Videokanal geschlossen.')
        data.extend(chunk)
    return bytes(data)


def video_packets(stream):
    # scrcpy 5: 12-byte session headers, or PTS/flags + payload length.
    codec = read_exact(stream, 4)
    if codec != b'h264':
        raise ValueError('Der TV liefert kein H.264-Video.')
    while True:
        header = read_exact(stream, 12)
        if header[0] & 0x80:
            yield 'session', header
            continue
        size = struct.unpack('>I', header[8:12])[0]
        if size > 16_000_000:
            raise ValueError('Ungültige Videopaketgröße.')
        data = read_exact(stream, size)
        kind = 'config' if header[0] & 0x40 else 'key' if header[0] & 0x20 else 'frame'
        yield kind, header + data


class LiveVideo:
    def __init__(self, television):
        self.tv = television
        self.condition = threading.Condition()
        self.worker = None
        self.stop_event = threading.Event()
        self.viewers = 0
        self.connection = None
        self.gop = []
        self.session = None
        self.config_packet = None
        self.metadata_revision = 0
        self.sequence = 0
        self.times = deque(maxlen=120)
        self.dimensions = None
        self.error = None
        self.phase = 'stopped'
        self.profile = 'native'

    def status(self):
        with self.condition:
            recent = [t for t in self.times if t > time.monotonic() - 4]
            fps = (len(recent) - 1) / (recent[-1] - recent[0]) if len(recent) > 1 else 0
            return dict(state=self.phase, viewers=self.viewers, frames=self.sequence,
                        fps=round(fps, 1), resolution=self.dimensions, profile=self.profile, error=self.error,
                        age=round(time.monotonic() - self.times[-1], 2) if self.times else None)

    def frames(self, profile='native'):
        capture_options(profile)  # Validate before touching the device or shared capture.
        with self.condition:
            # Abort from this viewer's previous fetch may still be arriving. Give
            # it time to disconnect, without replacing another tab's active stream.
            if self.worker and self.worker.is_alive() and self.profile != profile:
                available = self.condition.wait_for(lambda: not self.worker or not self.worker.is_alive() or self.stop_event.is_set(), timeout=3)
                if not available:
                    raise ValueError('Ein anderer Editor nutzt gerade einen anderen Live-Modus. Dort den Livefeed schließen und erneut verbinden.')
            # A quick tab switch may arrive while the previous capture is closing.
            while self.worker and self.worker.is_alive() and self.stop_event.is_set():
                self.condition.wait(timeout=.2)
            self.viewers += 1
            if not self.worker or not self.worker.is_alive():
                self.stop_event.clear()
                self.gop = []
                self.session = None
                self.config_packet = None
                self.sequence = 0
                self.times.clear()
                self.dimensions = None
                self.error = None
                self.profile = profile
                self.phase = 'starting'
                self.worker = threading.Thread(target=self.run, daemon=True)
                self.worker.start()
            # A new viewer joins at a future keyframe. Replaying the cached GOP
            # first shows old screen contents and can overload the decoder queue.
            seen = self.sequence
        metadata_revision = None
        try:
            yield b'h264'
            while True:
                with self.condition:
                    ready = self.condition.wait_for(lambda: (self.gop and self.session and self.config_packet and self.sequence != seen and (metadata_revision is not None or self.gop[0][0] > seen)) or self.phase in ['error', 'stopped'], timeout=1)
                    if self.phase in ['error', 'stopped']:
                        return
                    if not ready:
                        packets = [HEARTBEAT]
                    else:
                        bootstrap = metadata_revision is None or seen < self.gop[0][0] - 1 or metadata_revision != self.metadata_revision
                        packets = ([self.session, self.config_packet] if bootstrap else [])
                        packets += [packet for index, packet in self.gop if bootstrap or index > seen]
                        seen = self.sequence
                        metadata_revision = self.metadata_revision
                for packet in packets:
                    yield packet
        finally:
            with self.condition:
                self.viewers -= 1
                if not self.viewers:
                    self.stop_event.set()
                    if self.phase != 'error':
                        self.phase = 'stopping'
                    if self.connection:
                        try:self.connection.shutdown(socket.SHUT_RDWR)
                        except OSError:pass
                    self.condition.notify_all()

    def run(self):
        server = None
        connection = None
        port = None
        remote = None
        log = None
        try:
            config = self.tv.config() or {}
            for key in ['scrcpyServer']:
                if not config.get(key) or not Path(config[key]).is_file():
                    raise ValueError('Live-Video braucht den lokalen scrcpy-Server. Der Programmpfad fehlt.')
            version = config.get('scrcpyVersion', '')
            if not version or any(c not in '0123456789.' for c in version):
                raise ValueError('Die scrcpy-Serverversion fehlt.')
            scid = secrets.randbelow(0x7fffffff)
            remote = '/data/local/tmp/cleanframe-live-' + format(scid, '08x') + '.jar'
            self.tv.adb(['push', config['scrcpyServer'], remote], timeout=20)
            port = self.tv.adb(['forward', 'tcp:0', 'localabstract:scrcpy_' + format(scid, '08x')]).strip()
            if not port.isdigit():
                raise ValueError('ADB konnte keinen lokalen Videokanal öffnen.')
            log_path = self.tv.store.path / 'live-video.log'
            log = open(log_path, 'wb')
            os.chmod(log_path, 0o600)
            command = [config['adb'], '-s', config['serial'], 'shell', 'CLASSPATH=' + remote,
                       'app_process', '/', 'com.genymobile.scrcpy.Server', version,
                       'scid=' + format(scid, 'x'), 'tunnel_forward=true', 'audio=false', 'control=false',
                       'clipboard_autosync=false', 'power_on=false', 'stay_awake=false', 'cleanup=false',
                       'send_device_meta=false', 'send_dummy_byte=false', 'send_frame_meta=true', 'send_stream_meta=true',
                       *capture_options(self.profile)]
            server = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
            # ADB forwarding can accept TCP before Android's abstract socket exists.
            # Wait for the real listener instead of racing it with a fixed delay.
            socket_name = '@scrcpy_' + format(scid, '08x')
            deadline = time.monotonic() + 10
            while True:
                if self.stop_event.wait(.1):
                    return
                if server.poll() is not None or time.monotonic() >= deadline:
                    raise ValueError('Der TV konnte den Videodienst nicht starten. Details stehen in live-video.log.')
                if socket_name in self.tv.adb(['shell', 'cat', '/proc/net/unix'], timeout=3):
                    break
            connection = socket.create_connection(('127.0.0.1', int(port)), timeout=8)
            connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self.condition:
                self.connection = connection
            for kind, packet in video_packets(connection):
                if self.stop_event.is_set():
                    break
                with self.condition:
                    if kind == 'session':
                        self.metadata_revision += 1
                        self.session = packet
                        self.dimensions = list(struct.unpack('>II', packet[4:12]))
                        self.gop = []
                    elif kind == 'config':
                        if self.config_packet != packet:
                            self.metadata_revision += 1
                        self.config_packet = packet
                        self.gop = []
                    else:
                        # No screen changes can legitimately mean no new packet
                        # for minutes. Keep the startup timeout, then block until
                        # real data, EOF, or last-viewer shutdown of this socket.
                        connection.settimeout(None)
                        self.sequence += 1
                        self.times.append(time.monotonic())
                        if kind == 'key':
                            self.gop = []
                        if kind == 'key' or self.gop:
                            self.gop.append((self.sequence, packet))
                        if len(self.gop) > 300 or sum(len(p) for _, p in self.gop) > 16_000_000:
                            self.gop = []  # Wait for a keyframe rather than buffer indefinitely.
                        self.phase = 'streaming'
                    self.condition.notify_all()
            if not self.stop_event.is_set():
                raise ValueError('Der Videostream wurde unterbrochen. Bitte erneut verbinden. Details: live-video.log.')
        except Exception as error:
            with self.condition:
                if not self.stop_event.is_set():
                    self.error = str(error)
                    self.phase = 'error'
                self.condition.notify_all()
        finally:
            if connection:
                connection.close()
            for process in [server]:
                if process and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
            if log:
                log.close()
            # Only this session's forward and uniquely named temporary helper are removed.
            for args in ([['forward', '--remove', 'tcp:' + port]] if port else []) + ([['shell', 'rm', '-f', remote]] if remote else []):
                try:
                    self.tv.adb(args, timeout=3)
                except (ValueError, OSError, subprocess.SubprocessError):
                    pass
            with self.condition:
                self.connection = None
                if self.phase != 'error':
                    self.phase = 'stopped'
                self.worker = None
                self.condition.notify_all()

    def close(self):
        self.stop_event.set()
        with self.condition:
            if self.phase not in ['error', 'stopped']:
                self.phase = 'stopping'
            if self.connection:
                try:self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:pass
            self.condition.notify_all()
