#!/usr/bin/env python3
"""Build and verify the experimental iPhone Remote APK; never install or start it."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'addons/apple-remote'
RUST = '1.90.0'
NDK = '27.2.12479018'
TARGET = 'armv7-linux-androideabi'
ABI = 'armeabi-v7a'
ANDROID_API = 31
PACKAGE = 'com.cleanframe.addon.appleremote'
LIBRARY = 'libcleanframe_remote.so'
JNI_ENTRY = 'Java_com_cleanframe_addon_appleremote_RemoteNative_request'
IGNORE = shutil.ignore_patterns('target', 'build', '.gradle', '.git', '.DS_Store',
                               'local.properties', 'jniLibs', '*.keystore', '*.jks',
                               '*.pem', '*.key', '.env', '__pycache__', '*.pyc')


def digest(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def inputs_at(root: Path) -> dict[str, str]:
    result = {}
    for directory, folders, files in os.walk(root):
        ignored = IGNORE(directory, folders + files)
        folders[:] = sorted(name for name in folders if name not in ignored)
        for name in sorted(files):
            if name not in ignored:
                path = Path(directory) / name
                result[str(path.relative_to(root))] = digest(path)
    return result


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def arm_library(data: bytes) -> None:
    if len(data) < 52 or data[:6] != b'\x7fELF\x01\x01' or struct.unpack_from('<H', data, 18)[0] != 40:
        raise RuntimeError('Die native Bibliothek ist kein 32-Bit-ARM-ELF für armeabi-v7a.')


def load_tools(path: Path) -> tuple[dict, dict, Path]:
    config = json.loads(path.read_text())
    for key in ('javaHome', 'sdk', 'androidUserHome', 'gradleUserHome', 'gradle', 'signerSha256'):
        if not isinstance(config.get(key), str) or not config[key]:
            raise RuntimeError(f'Werkzeugkonfiguration fehlt: {key}')
    if not re.fullmatch(r'[0-9a-fA-F]{64}', config['signerSha256']):
        raise RuntimeError('signerSha256 muss die SHA-256-Prüfsumme des bestehenden Signierzertifikats sein.')
    for path in (Path(config['javaHome']) / 'bin/java', Path(config['gradle']),
                 Path(config['androidUserHome']) / 'debug.keystore'):
        if not path.is_file():
            raise RuntimeError(f'Bestehendes Werkzeug oder Signierschlüssel fehlt: {path}')
    ndk = Path(config['sdk']) / 'ndk' / NDK
    properties = ndk / 'source.properties'
    if not properties.is_file() or not re.search(r'^Pkg.Revision\s*=\s*' + re.escape(NDK) + r'\s*$', properties.read_text(), re.M):
        raise RuntimeError(f'Android NDK {NDK} ist noch nicht vollständig installiert: {ndk}. Der Builder lädt keine zweite Kopie herunter.')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}.get(platform.system())
    if host is None:
        raise RuntimeError('Dieser Builder unterstützt macOS und Linux.')
    binary = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    for name in (f'armv7a-linux-androideabi{ANDROID_API}-clang', 'llvm-ar', 'llvm-readelf'):
        if not (binary / name).is_file():
            raise RuntimeError(f'NDK-Werkzeug fehlt: {binary / name}')
    env = os.environ.copy()
    env.pop('CARGO_BUILD_TARGET', None)
    env.update({'JAVA_HOME': config['javaHome'], 'ANDROID_HOME': config['sdk'],
                'ANDROID_SDK_ROOT': config['sdk'], 'ANDROID_USER_HOME': config['androidUserHome'],
                'GRADLE_USER_HOME': config['gradleUserHome'], 'CARGO_HOME': str(ROOT / '.local/remote-cargo'),
                'CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER': str(binary / f'armv7a-linux-androideabi{ANDROID_API}-clang'),
                'CC_armv7_linux_androideabi': str(binary / f'armv7a-linux-androideabi{ANDROID_API}-clang'),
                'AR_armv7_linux_androideabi': str(binary / 'llvm-ar')})
    env['PATH'] = str(Path(config['javaHome']) / 'bin') + os.pathsep + env.get('PATH', '')
    return config, env, binary


def build(args: argparse.Namespace) -> Path:
    config, env, binary = load_tools(args.toolchain)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    output = args.output or ROOT / '.local/apple-remote/builds' / stamp
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    checks: list[list[str]] = []
    with (output / 'build.log').open('w') as log:
        def run(command: list[str], cwd: Path, timeout: int = 1200) -> str:
            print('RUN ' + ' '.join(command), flush=True)
            log.write('RUN ' + ' '.join(command) + '\n')
            log.flush()
            result = subprocess.run(command, cwd=cwd, env=env, capture_output=True,
                                    text=True, timeout=timeout)
            log.write(result.stdout + result.stderr)
            log.flush()
            if result.returncode:
                print((result.stdout + result.stderr)[-12000:], file=sys.stderr)
                raise RuntimeError(f'Prüfung fehlgeschlagen ({result.returncode}): {command[0]}; Details: {output / "build.log"}')
            checks.append(command)
            return result.stdout

        try:
            # Snapshot all inputs before compilation; no Cargo/Gradle build tree
            # or generated source lives in an iCloud-managed source directory.
            with tempfile.TemporaryDirectory(prefix='cleanframe-apple-remote-') as temporary:
                staging = Path(temporary)
                frozen = staging / 'source'
                inputs = inputs_at(SOURCE)
                shutil.copytree(SOURCE, frozen, ignore=IGNORE)
                if inputs_at(frozen) != inputs or inputs_at(SOURCE) != inputs:
                    raise RuntimeError('Quellcode wurde während des Sicherns geändert. Bitte erneut bauen.')
                write_json(output / 'inputs.json', inputs)
                env['CARGO_TARGET_DIR'] = str(staging / 'cargo-target')
                cargo = [shutil.which('cargo') or 'cargo', '+' + RUST]
                run(cargo + ['--version'], frozen, 30)
                offline = ['--offline'] if args.offline else []
                run(cargo + ['test', '--locked', '--workspace'] + offline, frozen)
                run(cargo + ['build', '--locked', '--release', '-p', 'cleanframe-remote',
                             '--target', TARGET] + offline, frozen)
                native = staging / 'cargo-target' / TARGET / 'release' / LIBRARY
                arm_library(native.read_bytes())
                symbols = run([str(binary / 'llvm-readelf'), '--dyn-syms', '--wide', str(native)], frozen, 30)
                if JNI_ENTRY not in symbols:
                    raise RuntimeError('Der Android-JNI-Einstiegspunkt fehlt in der nativen Bibliothek.')
                jni = frozen / 'android/app/src/main/jniLibs' / ABI / LIBRARY
                jni.parent.mkdir(parents=True)
                shutil.copy2(native, jni)
                # Out-of-tree Gradle output AND project cache. No signing keys
                # are copied: AGP uses the existing ANDROID_USER_HOME keystore.
                gradle_build = staging / 'android-build'
                init = staging / 'build.init.gradle'
                init.write_text('gradle.beforeProject { p -> p.layout.buildDirectory.set(new File(System.getenv("CLEANFRAME_REMOTE_GRADLE_BUILD"), p.name)) }\n')
                env['CLEANFRAME_REMOTE_GRADLE_BUILD'] = str(gradle_build)
                run([config['gradle'], '-p', str(frozen / 'android'), '-I', str(init),
                     '--project-cache-dir', str(staging / 'gradle-project-cache'), '--no-daemon']
                    + offline + ['testDebugUnitTest', 'lintDebug', 'assembleDebug'], frozen)
                apk = gradle_build / 'app/outputs/apk/debug/app-debug.apk'
                tools = Path(config['sdk']) / 'build-tools/35.0.0'
                signed = run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], frozen, 30)
                certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', signed, re.M)
                certs = [value.lower() for value in certs]
                if certs != [config['signerSha256'].lower()]:
                    raise RuntimeError('Die APK wurde nicht mit dem vorhandenen CleanFrame-Zertifikat signiert.')
                badging = run([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], frozen, 30)
                identity = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
                if not identity or identity.group(1) != PACKAGE:
                    raise RuntimeError('Unerwartete Android-Paketkennung.')
                with zipfile.ZipFile(apk) as archive:
                    library_name = f'lib/{ABI}/{LIBRARY}'
                    packed = archive.read(library_name)
                    arm_library(packed)
                    native_names = [name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
                    if native_names != [library_name]:
                        raise RuntimeError('Die APK enthält unerwartete native Bibliotheken.')
                if any(digest(frozen / name) != sha for name, sha in inputs.items()):
                    raise RuntimeError('Ein gesicherter Quellstand wurde während des Builds verändert.')
                result = output / f'CleanTV-Apple-Remote-{identity.group(3)}-experimental.apk'
                shutil.copy2(apk, result)
                shutil.copy2(native, output / LIBRARY)
                for directory in ('reports', 'test-results'):
                    path = gradle_build / 'app' / directory
                    if path.is_dir():
                        shutil.copytree(path, output / directory)
                # Requested local JNI cache for subsequent Android builds. This
                # file is ignored by Git and never replaces source or secrets.
                installed_jni = SOURCE / 'android/app/src/main/jniLibs' / ABI / LIBRARY
                cache_updated = inputs_at(SOURCE) == inputs
                if cache_updated:
                    installed_jni.parent.mkdir(parents=True, exist_ok=True)
                    replacement = installed_jni.with_suffix('.so.tmp')
                    shutil.copy2(native, replacement)
                    os.replace(replacement, installed_jni)
                else:
                    print('Source changed during build: verified snapshot APK retained; current JNI cache not replaced.', flush=True)
                write_json(output / 'manifest.json', {
                    'state': 'experimental-candidate', 'createdAt': stamp, 'package': PACKAGE,
                    'version': identity.group(3), 'versionCode': int(identity.group(2)),
                    'apk': result.name, 'sha256': digest(result), 'signerSha256': certs[0],
                    'abi': ABI, 'nativeSha256': digest(native),
                    'packagedNativeSha256': hashlib.sha256(packed).hexdigest(),
                    'sourceManifestSha256': digest(output / 'inputs.json'),
                    'builderSha256': digest(Path(__file__)), 'jniCacheUpdated': cache_updated,
                    'rust': RUST, 'ndk': NDK, 'androidApi': ANDROID_API, 'checks': checks,
                    'deviceTestsPassed': False, 'approvedForCatalog': False, 'tvInstalled': False,
                    'limitation': 'iPhone pairing, reconnection and RTI/Android IME interoperability require real-device proof; unknown iPhone editing schemas fail closed.'
                })
                return result
        except Exception as error:
            write_json(output / 'status.json', {'state': 'failed', 'error': str(error), 'tvInstalled': False})
            raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--toolchain', type=Path, default=ROOT / '.local/editor/toolchain.json')
    parser.add_argument('--output', type=Path, help='New output directory (must not already exist)')
    parser.add_argument('--offline', action='store_true', help='Use only already cached Cargo and Gradle dependencies')
    args = parser.parse_args()
    try:
        print(f'Experimental APK ready: {build(args)}', flush=True)
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f'Build stopped: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
