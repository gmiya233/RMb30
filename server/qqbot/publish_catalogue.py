"""Copy public catalogue metadata only; keep private ranking state inaccessible."""
import json
from pathlib import Path
import os
import importlib.util
import re
from b30_data import prepare


def creator_filter():
    # Use the exact rule used by the deployed community worker, including aliases.
    deployed = Path('/opt/malody-ranking/community.py')
    source = deployed if deployed.exists() else Path(__file__).resolve().parents[1] / 'community.py'
    spec = importlib.util.spec_from_file_location('malody_community_visibility', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.creator_visible


def public_catalogue(doc):
    visible = creator_filter()
    fields = ('cid', 'sid', 'title', 'version', 'artist', 'creator', 'level', 'bpm', 'mode', 'promote')
    return {'server': doc['server'], 'charts': [
        {k: row[k] for k in fields if k in row} | {'search_visible': visible(str(row.get('creator') or ''))}
        for row in doc['charts']]}


def atomic(target, data):
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix('.tmp')
    with temporary.open('wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o644)
    temporary.replace(target)


def publish_b30(catalogue, target):
    paths = json.loads(Path('/etc/malody-ranking-paths.json').read_text())
    rankings = Path(os.environ.get('MALODY_QQ_RANKINGS', str(Path(paths['root']) / '_rankings/v2')))
    status = json.loads((rankings / 'status.json').read_text())
    revision = status['revision']
    if not re.fullmatch('[a-f0-9]{64}', revision):
        raise ValueError('Invalid revision')
    source = Path('/var/lib/malody-ranking/state') / (revision + '.json')
    totals_file = rankings / revision / 'rt.json'
    if source.is_symlink() or source.stat().st_size > 64 * 1024 * 1024 or totals_file.stat().st_size > 16 * 1024 * 1024:
        raise ValueError('Invalid source snapshot')
    totals = json.loads(totals_file.read_bytes())
    if totals['server'] != catalogue['server'] or totals['revision'] != revision:
        raise ValueError('Snapshot namespace mismatch')
    players = json.loads(source.read_bytes())['players']
    if len(players) > 10000:
        raise ValueError('Too many players')
    ranked = {p['qq']: p for p in totals['players']}
    for player in players:
        qq = player['qq']
        if qq not in ranked:
            continue
        if not re.fullmatch('[1-9][0-9]{4,11}', qq):
            raise ValueError('Invalid player identifier')
        # rt.json is published just before private state; skip during that tiny transition.
        if any(player[k] != ranked[qq][k] for k in ('rating', 'o', 'n', 'oc', 'nc', 'name')):
            continue
        doc = prepare(player, catalogue, revision, totals['updated'])
        atomic(target / 'b30' / revision / (qq + '.json'), json.dumps(doc, ensure_ascii=False, separators=(',', ':')).encode())


def main():
    source = Path('/var/lib/malody-ranking/catalog.json')
    target = Path('/var/lib/malody-qqbot-catalog/catalog.json')
    if source.stat().st_size > 16 * 1024 * 1024:
        raise ValueError('catalogue exceeds limit')
    doc = json.loads(source.read_bytes())
    # Dedicated read-only chart mounts expose artwork without exposing score archives.
    chart_source = os.environ.get('MALODY_QQ_CHART_SOURCE')
    if chart_source:
        from artwork_fallback import supplement
        supplement(doc['charts'], chart_source, target.parent / 'thumbs', atomic)
    public = public_catalogue(doc)
    data = json.dumps(public, ensure_ascii=False, separators=(',', ':')).encode()
    if not target.exists() or target.read_bytes() != data:
        atomic(target, data)
    publish_b30(doc, target.parent)


if __name__ == '__main__':
    main()
