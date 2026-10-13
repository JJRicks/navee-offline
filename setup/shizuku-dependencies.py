#!/usr/bin/env python3
"""Explicit, pinned dependency fetch. The normal Android build only verifies cached files."""
import argparse
import hashlib
import pathlib
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / '.local' / 'shizuku-dependencies'
ARTIFACTS = {
    'api-13.1.5.aar': ('https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar', '4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b'),
    'provider-13.1.5.aar': ('https://repo.maven.apache.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.aar', 'b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3'),
    'aidl-13.1.5.aar': ('https://repo.maven.apache.org/maven2/dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar', '33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963'),
    'shared-13.1.5.aar': ('https://repo.maven.apache.org/maven2/dev/rikka/shizuku/shared/13.1.5/shared-13.1.5.aar', '4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635'),
    'annotation-1.3.0.jar': ('https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar', '97dc45afefe3a1e421da42b8b6e9f90491477c45fc6178203e3a5e8a05ee8553'),
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fetch', action='store_true', help='Explicitly download missing, checksum-pinned dependencies')
    parser.add_argument('--prepare', type=pathlib.Path, help='Verify cached artifacts and extract classes jars here (offline)')
    args = parser.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in ARTIFACTS.items():
        path = CACHE / name
        if not path.is_file():
            if not args.fetch:
                raise SystemExit('Missing pinned dependencies. Run: python3 setup/shizuku-dependencies.py --fetch')
            data = urllib.request.urlopen(url, timeout=30).read(16 * 1024 * 1024)
            if hashlib.sha256(data).hexdigest() != expected:
                raise SystemExit('Dependency checksum mismatch: ' + name)
            path.write_bytes(data)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise SystemExit('Dependency checksum mismatch: ' + name + '. Remove it and explicitly fetch again.')
        if args.prepare:
            args.prepare.mkdir(parents=True, exist_ok=True)
            target = args.prepare / (path.stem + '.jar')
            target.write_bytes(zipfile.ZipFile(path).read('classes.jar') if path.suffix == '.aar' else path.read_bytes())
    print('Pinned Shizuku dependencies verified.')

if __name__ == '__main__':
    main()
