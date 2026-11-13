"""Read original song metadata from MC files, without modifying game files."""
import json
from pathlib import Path


def choose(song, original, translated, fallback):
    for value in (song.get(original), song.get(translated), fallback):
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ''


def apply(row, song):
    if not isinstance(song, dict):
        return
    names = [row.get('title'), row.get('artist')] + [song.get(k) for k in ('titleorg','title','artistorg','artist')]
    row['title'] = choose(song,'titleorg','title',row.get('title'))
    row['artist'] = choose(song,'artistorg','artist',row.get('artist'))
    row['search_names'] = list(dict.fromkeys(v.strip() for v in names if isinstance(v,str) and v.strip()))


def supplement(database, charts, cache_file):
    base = database.resolve().parent
    try:
        old = json.loads(cache_file.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        old = {}
    cache = {}
    for row in charts:
        relative = Path(row.get('path') or '')
        filename = row.get('mc_name') or ''
        for candidate in (base/relative/filename, base/relative, base.parent/relative/filename, base.parent/relative):
            try:
                path = candidate.resolve()
                if not path.is_relative_to(base.parent) or path.suffix.casefold() != '.mc':
                    continue
                stat = path.stat()
                if not path.is_file() or stat.st_size > 16*1024*1024:
                    continue
                key = str(row['cid'])
                signature = [str(path),stat.st_mtime_ns,stat.st_size,row.get('server_hash')]
                prior = old.get(key,{})
                if prior.get('signature') == signature:
                    song = prior.get('song',{})
                else:
                    document = json.loads(path.read_text(encoding='utf-8-sig'))
                    song = document.get('meta',{}).get('song',{})
                    if not isinstance(song,dict):
                        song = {}
                    song = {k:v for k,v in song.items() if k in ('titleorg','title','artistorg','artist') and isinstance(v,str)}
                cache[key] = dict(signature=signature,song=song)
                apply(row,song)
                break
            except (OSError, ValueError, TypeError, AttributeError):
                continue
    if cache != old:
        temporary = cache_file.with_suffix('.tmp')
        temporary.write_text(json.dumps(cache,ensure_ascii=False),encoding='utf-8')
        temporary.replace(cache_file)
