"""Build the native Java APK; all dependencies and outputs stay on E:."""
import hashlib
import os
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / 'tools/android-sdk'
LIBS = ROOT / 'tools/android-libs'
BUILD = ROOT / 'build/android'
DIST = ROOT / 'dist'
JAVA = shutil.which('java')
JAVAC = shutil.which('javac')


def run(*args):
    # Some Windows SDK executables cannot parse non-ASCII absolute arguments.
    # Relative paths keep the complete build on E: without moving the workspace.
    converted = []
    for a in args:
        if isinstance(a, Path) and a.is_relative_to(ROOT):
            converted.append(str(a.relative_to(ROOT)))
        else:
            converted.append(str(a))
    subprocess.run(converted, cwd=ROOT, check=True)


def setup():
    SDK.mkdir(parents=True, exist_ok=True)
    xml = SDK / 'repository.xml'
    if not xml.exists():
        urllib.request.urlretrieve('https://dl.google.com/android/repository/repository2-3.xml', xml)
    packages = {'platforms;android-35': ('platform.zip', 'platforms'),
                'build-tools;35.0.0': ('build-tools.zip', 'build-tools')}
    for p in ET.parse(xml).getroot().findall('remotePackage'):
        if p.attrib['path'] not in packages:
            continue
        filename, folder = packages[p.attrib['path']]
        for archive in p.findall('archives/archive'):
            if archive.findtext('host-os') not in (None, 'windows'):
                continue
            complete = archive.find('complete')
            path = SDK / filename
            if not path.exists():
                urllib.request.urlretrieve('https://dl.google.com/android/repository/' + complete.findtext('url'), path)
            if hashlib.sha1(path.read_bytes()).hexdigest() != complete.findtext('checksum'):
                raise RuntimeError('SDK archive checksum mismatch')
            if not (SDK / folder).exists():
                with zipfile.ZipFile(path) as z:
                    z.extractall(SDK / folder)
    LIBS.mkdir(parents=True, exist_ok=True)
    for name in ('api', 'provider', 'aidl', 'shared'):
        jar = LIBS / (name + '.jar')
        if not jar.exists():
            url = f'https://repo.maven.apache.org/maven2/dev/rikka/shizuku/{name}/13.1.5/{name}-13.1.5.aar'
            path = LIBS / (name + '.aar')
            urllib.request.urlretrieve(url, path)
            expected = urllib.request.urlopen(url + '.sha1').read().decode().strip()
            if hashlib.sha1(path.read_bytes()).hexdigest() != expected:
                raise RuntimeError('Shizuku checksum mismatch')
            with zipfile.ZipFile(path) as z:
                jar.write_bytes(z.read('classes.jar'))


def main():
    setup()
    test_apk = '--test-apk' in sys.argv
    tools = SDK / 'build-tools/android-15'
    android = SDK / 'platforms/android-35/android.jar'
    for folder in (BUILD, DIST, BUILD / 'classes', BUILD / 'dex', ROOT / 'android/generated'):
        folder.mkdir(parents=True, exist_ok=True)
    for folder in (BUILD / 'classes', BUILD / 'dex'):
        for stale in folder.rglob('*.class' if folder.name == 'classes' else '*.dex'):
            stale.unlink()
    if not (ROOT / 'android/assets/catalog.json').exists():
        raise RuntimeError('Run tools/prepare_usb_sync.py --prepare-only first')
    config = ROOT / '.secrets/default-reader.json'
    if not config.exists():
        raise RuntimeError('Missing private build configuration: .secrets/default-reader.json')
    shutil.copyfile(config, ROOT / 'android/assets/default-reader.json')
    rank_config = ROOT / '.secrets/default-rank.json'
    if not rank_config.exists():
        raise RuntimeError('Missing private build configuration: .secrets/default-rank.json')
    shutil.copyfile(rank_config, ROOT / 'android/assets/default-rank.json')
    extra = {
        'libadb': 'e6fcb495a20a507ca4a125afc9c554e838a84b3a15fbcc29f7deafd0f88bbb58',
        'spake2': '8798fb6c04b5d53a6307ed9481e9afe563227abd4e8b9e917c8514fa850071bb',
        'conscrypt': '551ae4e301c571760d1791e647db6ed1dcb10d34dcae7aa12b67f220f2ce98d1',
    }
    for name, digest in extra.items():
        archive = LIBS / (name + '.aar')
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Missing or invalid internal-reader dependency: ' + name)
        with zipfile.ZipFile(archive) as z:
            original = z.read('classes.jar')
            if name == 'libadb':
                import io
                with zipfile.ZipFile(io.BytesIO(original)) as upstream, zipfile.ZipFile(LIBS / 'libadb.jar', 'w', zipfile.ZIP_DEFLATED) as patched:
                    for member in upstream.namelist():
                        if not any(member.startswith('io/github/muntashirakon/adb/' + patched_class) for patched_class in ('AdbProtocol', 'AdbStream', 'AdbInputStream')):
                            patched.writestr(member, upstream.read(member))
            else:
                (LIBS / (name + '.jar')).write_bytes(original)
    if hashlib.sha256((LIBS / 'bcprov.jar').read_bytes()).hexdigest() != '0ef9c4d9536719aaa1c92a7c694d87981fa9d4d64dd9479cc322a08aebbc651e':
        raise RuntimeError('Bouncy Castle checksum mismatch')
    compiled = BUILD / 'resources.zip' 
    run(tools / 'aapt2.exe', 'compile', '--dir', ROOT / 'android/res', '-o', compiled)
    resource_apk = BUILD / 'resources.apk'
    manifest = ROOT / 'android/AndroidManifest.xml'
    manifest_root = ET.parse(manifest).getroot()
    if test_apk:
        ns = '{http://schemas.android.com/apk/res/android}'
        ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
        manifest_root.find('application').set(ns + 'debuggable', 'true')
        manifest_root.set(ns + 'versionName', manifest_root.get(ns + 'versionName') + '-test')
        for source in (ROOT / 'android/src/cn/gmiya/malodylink').glob('*TestInstrumentation.java'):
            ET.SubElement(manifest_root, 'instrumentation', {ns + 'name': '.' + source.stem, ns + 'targetPackage': 'cn.gmiya.malodylink'})
        manifest = BUILD / 'test-manifest.xml'
        ET.ElementTree(manifest_root).write(manifest, encoding='utf-8', xml_declaration=True)
    run(tools / 'aapt2.exe', 'link', '-o', resource_apk, '-I', android,
        '--manifest', manifest, '--java', ROOT / 'android/generated',
        '-A', ROOT / 'android/assets', compiled)
    jars = sorted(LIBS.glob('*.jar'))
    classpath = os.pathsep.join(str(x) for x in [android] + jars)
    debug = manifest_root.find('application').get('{http://schemas.android.com/apk/res/android}debuggable') == 'true'
    sources = [path for path in sorted((ROOT / 'android/src').rglob('*.java'))
               if debug or not path.name.endswith('TestInstrumentation.java')] + sorted((ROOT / 'android/generated').rglob('*.java'))
    run(JAVAC, '--release', '8', '-encoding', 'UTF-8', '-cp', classpath, '-d', BUILD / 'classes', *sources)
    classes_jar = BUILD / 'classes.jar'
    with zipfile.ZipFile(classes_jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for path in (BUILD / 'classes').rglob('*.class'):
            z.write(path, path.relative_to(BUILD / 'classes').as_posix())
    run(JAVA, '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--lib', android, '--min-api', '26', '--output', BUILD / 'dex', classes_jar, *jars)
    unaligned = BUILD / 'unaligned.apk'
    shutil.copyfile(resource_apk, unaligned)
    with zipfile.ZipFile(unaligned, 'a', zipfile.ZIP_DEFLATED) as z:
        for name in extra:
            with zipfile.ZipFile(LIBS / (name + '.aar')) as library:
                for member in library.namelist():
                    if member.startswith('jni/') and member.endswith('.so'):
                        z.writestr('lib/' + member[4:], library.read(member))
        for p in (BUILD / 'dex').glob('*.dex'):

            z.write(p, p.name)
    aligned = BUILD / 'aligned.apk'
    run(tools / 'zipalign.exe', '-f', '-p', '4', unaligned, aligned)
    keys = ROOT / '.secrets'
    keys.mkdir(exist_ok=True)
    keystore = keys / 'debug.jks'
    if not keystore.exists():
        run(Path(JAVA).with_name('keytool.exe'), '-genkeypair', '-keystore', keystore,
            '-storepass', 'android', '-alias', 'androiddebugkey', '-keypass', 'android',
            '-dname', 'CN=Malody Link Test', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650')
    version = manifest_root.get('{http://schemas.android.com/apk/res/android}versionName')
    apk = DIST / f'Malody-Link-{version}.apk'
    run(JAVA, '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', keystore,
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, aligned)
    run(JAVA, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', apk)
    print('APK:', str(apk), 'bytes:', apk.stat().st_size)


if __name__ == '__main__':
    main()
