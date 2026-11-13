"""Build the Android derived-only radar asset from a verified public catalogue."""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'server/qqbot'))
from b30_data import level


def build(catalogue, rules, updated):
    rows = []
    for chart in catalogue['charts']:
        radar = chart.get('radar', {})
        grade = level(chart.get('display_level', chart.get('level')), chart.get('version', ''))
        if grade is None or not 10 <= grade <= 15 or radar.get('algorithm') != rules['algorithm']:
            continue
        if not chart.get('server_hash') or not radar.get('raw_metrics'):
            raise ValueError('Missing chart provenance')
        for i, axis in enumerate(rules['axes']):
            expected = radar['raw_metrics'][axis]*100/rules['anchors'][str(chart['keys'])][axis]
            if abs(expected-radar['values'][i]) > 1e-7:
                raise ValueError('Published scale mismatch')
        rows.append({k:chart[k] for k in ('cid','server_hash','keys')} |
                    {k:radar[k] for k in ('source_format','values','raw_metrics','source_sha256','slide_measurement')} |
                    {'normalized_level':grade})
    if not rows:
        raise ValueError('No matching radar data')
    return {'schema':1,'server':catalogue['server'],'algorithm':rules['algorithm'],
            'scale':rules['scale'],'updated':updated,'axes':rules['axes'],
            'calibration':rules,'charts':rows}


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalogue',type=Path)
    parser.add_argument('--date',required=True)
    args=parser.parse_args()
    result=build(json.loads(args.catalogue.read_text(encoding='utf-8')),
                 json.loads((ROOT/'server/qqbot/radar-rules.json').read_text(encoding='utf-8')),args.date)
    (ROOT/'android/assets/chart-radar.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    print('Updated chart radars:',len(result['charts']))
