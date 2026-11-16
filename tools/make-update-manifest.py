#!/usr/bin/env python3
"""Generate update.json from a final signed APK; never reads signing material."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import quote
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, default=Path(__file__).resolve().parents[1] / 'AndroidManifest.xml')
    parser.add_argument('--repo', default='RoadKai233/shuncheng-ledger')
    parser.add_argument('--tag', required=True)
    parser.add_argument('--notes-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('update.json'))
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9][A-Za-z0-9_.-]*', args.repo):
        parser.error('repo must be an ASCII GitHub owner/repository')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.tag):
        parser.error('tag must contain ASCII letters, numbers, periods, underscores or hyphens')
    if not args.apk.is_file() or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*\.apk', args.apk.name):
        parser.error('APK must exist and use an ASCII .apk asset name')
    if 'debug' in args.apk.stem.lower():
        parser.error('A debug APK cannot be used as an official update asset')
    root = ET.parse(args.manifest).getroot()
    namespace = '{http://schemas.android.com/apk/res/android}'
    version_code = int(root.attrib[namespace + 'versionCode'])
    version_name = root.attrib[namespace + 'versionName']
    if version_code <= 0 or not version_name.strip():
        parser.error('manifest must have a positive versionCode and a nonempty versionName')
    size = args.apk.stat().st_size
    if size <= 0:
        parser.error('APK cannot be empty')
    digest = hashlib.sha256()
    with args.apk.open('rb') as apk:
        for chunk in iter(lambda: apk.read(1024 * 1024), b''):
            digest.update(chunk)
    notes = args.notes_file.read_text(encoding='utf-8').strip()
    if not notes or len(notes) > 4000:
        parser.error('notes must contain 1 to 4000 characters')
    result = {
        'versionCode': version_code,
        'versionName': version_name,
        'apkUrl': 'https://github.com/' + args.repo + '/releases/download/' + quote(args.tag, safe='') + '/' + args.apk.name,
        'sha256': digest.hexdigest(),
        'size': size,
        'notes': notes,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Wrote update manifest:', args.output)
    print('Version:', version_name, '(' + str(version_code) + ')')
    print('APK bytes:', size)
    print('SHA-256:', result['sha256'])


if __name__ == '__main__':
    main()
