"""Refresh rating metadata from a read-only SQLite connection. No account data."""
import json
import os
from pathlib import Path
import sqlite3
import sys
import time
import re
import math
from contextlib import closing
from chart_keys import supplement


def recover_named_level(row):
    """Recover explicit positive RM grades only when the database grade is absent."""
    try:
        raw = float(row.get('level'))
        if math.isfinite(raw) and raw > 0:
            return
    except (TypeError, ValueError):
        pass
    # Do not treat key count, negative/novelty levels or conflicting labels as grades.
    matches = re.findall(r'(?<![\d.\-−－])([1-9][0-9]*(?:\.[0-9]+)?)\s*级', str(row.get('version') or ''))
    values = {float(value) for value in matches}
    if len(values) == 1:
        value = values.pop()
        if 0 < value < 16:
            row['level'] = int(value) if value.is_integer() else value


def export(database, target, archive_root=None):
    previous = json.loads(target.read_text(encoding='utf-8'))
    with closing(sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        charts = [dict(row) for row in connection.execute('''
            SELECT c.cid,s.sid,c.hash server_hash,c.path,c.mc_name,c.version,
                   c.mode,c.level,s.title,s.artist,s.promote,s.cover,s.background,
                   s.bpm,c.creator
            FROM charts c JOIN songs s ON c.sid=s.sid
            WHERE c.exist=1 AND s.exist=1 ORDER BY c.cid''')]
    if not charts:
        raise ValueError('Empty catalogue; previous metadata retained')
    for row in charts:
        # Keep the DB level in the shared revision namespace used by existing apps.
        display = dict(row)
        recover_named_level(display)
        if display.get('level') != row.get('level'):
            row['display_level'] = display['level']
    from song_metadata import supplement as supplement_song_metadata
    supplement_song_metadata(database, charts, target.with_name('song-metadata-cache.json'))
    if archive_root is not None:
        metadata = archive_root / '_catalog/keys-v1.json'
        old = json.loads(metadata.read_text(encoding='utf-8')) if metadata.exists() else {}
        rows = [dict(row) for row in supplement(database, charts, old)]
        from chart_judgements import supplement as judgement_supplement
        judgement_supplement(database, charts, rows)
        document = {'schema': 1, 'server': previous['server'], 'charts': rows}
        if document != old:
            metadata.parent.mkdir(parents=True, exist_ok=True)
            temporary = metadata.with_suffix('.tmp')
            temporary.write_text(json.dumps(document, ensure_ascii=False), encoding='utf-8')
            os.replace(temporary, metadata)
    if charts == previous['charts']:
        return
    temporary = target.with_suffix('.tmp')
    temporary.write_text(json.dumps({'server': previous['server'],
                                    'updated_at': int(time.time()), 'charts': charts},
                                   ensure_ascii=False), encoding='utf-8')
    os.replace(temporary, target)


if __name__ == '__main__':
    export(Path(sys.argv[1]), Path(sys.argv[2]))
