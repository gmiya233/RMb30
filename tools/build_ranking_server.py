"""Build the server from the exact same rating/validation classes as Android."""
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build/ranking-server'

def main():
    classes = BUILD / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    names = ['ChartMatcher', 'ChartDetails', 'ChartKeys', 'RatingRules', 'RatingBoard', 'SongCatalogue',
             'CloudRecords', 'CloudArchive', 'RankingData', 'RankingIndex']
    sources = [ROOT / f'android/src/cn/gmiya/malodylink/{name}.java' for name in names]
    sources.append(ROOT / 'server/RankingDaemon.java')
    subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp',
                    str(ROOT / 'tools/test-libs/json.jar'), '-d', str(classes), *map(str, sources)], check=True, cwd=ROOT)
    jar = BUILD / 'ranking-server.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as out:
        for p in classes.rglob('*.class'):
            out.write(p, p.relative_to(classes).as_posix())
    print('Built shared-rule ranking server:', jar)

if __name__ == '__main__':
    main()
