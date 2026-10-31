"""Export public software-channel metadata from a verified frozen Android build."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def export(release, notes):
    manifest = json.loads((release / 'manifest.json').read_text())
    if json.loads((release / 'status.json').read_text())['state'] != 'ready':
        raise ValueError('The frozen build is not ready.')
    version_name = manifest['version']['name']
    match = re.fullmatch(r'(\d+\.\d+\.\d+)-android(\d+)', version_name)
    if not match or int(match[2]) != manifest['version']['code']:
        raise ValueError('Invalid Android release version.')
    apk = release / manifest['apk']
    if apk.parent.resolve() != release.resolve() or hashlib.sha256(apk.read_bytes()).hexdigest() != manifest['sha256']:
        raise ValueError('The APK does not match the verified build.')
    if not re.fullmatch(r'[a-f0-9]{64}', manifest['signerSha256']) or len(notes) > 4000:
        raise ValueError('Invalid certificate or release notes.')
    return dict(api=1, id='software', kind='native', name='CleanTV', packageName='com.cleanframe.philips',
                version=match[1], versionName=version_name, versionCode=int(match[2]), minAndroidApi=31,
                apkUrl=f'https://github.com/AntoinS/CleanTV/releases/download/v{match[1]}/{apk.name}',
                sha256=manifest['sha256'], signerSha256=manifest['signerSha256'], notes=notes)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('release', type=Path)
    parser.add_argument('--notes', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    value = export(args.release.resolve(), args.notes.read_text().strip())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
