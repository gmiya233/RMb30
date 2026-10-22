"""Run synthetic-only feature and migration checks; no devices or network."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build/feature-tests'


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    names = ['ChartMatcher', 'ChartDetails', 'RatingRules', 'RatingBoard',
             'SongCatalogue', 'CloudRecords', 'CloudArchive', 'RankingData',
             'RankingIndex', 'RankingSnapshot', 'RecentPlays', 'ChartKeys', 'CommunityData', 'UpdateData']
    tests = ['RankingTest', 'RankingIndexTest', 'CloudArchiveTest',
             'RecentSearchTest', 'RankingDaemonTest', 'ChartKeysTest', 'CommunityDataTest', 'UpdateDataTest']
    sources = [ROOT / f'android/src/cn/gmiya/malodylink/{n}.java' for n in names]
    sources += [ROOT / 'server/RankingDaemon.java']
    sources += [ROOT / f'tests/{n}.java' for n in tests]
    library = ROOT / 'tools/test-libs/json.jar'
    subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp',
                    str(library), '-d', str(BUILD), *map(str, sources)],
                   check=True, cwd=ROOT)
    for test in tests:
        subprocess.run(['java', '-cp', os.pathsep.join(map(str, [BUILD, library])),
                        test], check=True, cwd=ROOT)


if __name__ == '__main__':
    main()
