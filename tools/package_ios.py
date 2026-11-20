"""Package an unsigned arm64 device app for tester-managed signing."""
import hashlib
from pathlib import Path
import plistlib
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    app = Path(sys.argv[1]).resolve()
    with (app / 'Info.plist').open('rb') as stream:
        info = plistlib.load(stream)
    assert info['CFBundleIdentifier'] == 'cn.gmiya.malodylink.ios'
    assert 'iPhoneOS' in info.get('CFBundleSupportedPlatforms', []), 'A simulator app is not installable on devices'
    binary = (app / info['CFBundleExecutable']).read_bytes()
    assert binary[:8] == bytes.fromhex('cffaedfe0c000001'), 'Expected an arm64 Mach-O device executable'
    assert not (app / 'embedded.mobileprovision').exists(), 'Do not redistribute provisioning profiles'
    assert b'IOS-UI-TEST-FIXTURE' not in binary, 'Debug fixture should not be present in a release'
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    output = dist / f"Malody-Link-iOS-{info['CFBundleShortVersionString']}-unsigned.ipa"
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in app.rglob('*'):
            if path.is_file():
                assert path.suffix.lower() not in {'.mrv', '.mc', '.db', '.zip'}, 'Player data is not a build resource'
                archive.write(path, 'Payload/' + app.name + '/' + path.relative_to(app).as_posix())
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.ipa.sha256').write_text(digest + '  ' + output.name + '\n', encoding='ascii')
    print('Verified and packaged unsigned arm64 IPA:', output.name, output.stat().st_size, 'bytes')


if __name__ == '__main__':
    main()
