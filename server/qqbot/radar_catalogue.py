"""Derive public radar values during catalogue publication, never on chat queries."""
import hashlib
import json
import math
from pathlib import Path
import sys

HERE=Path(__file__).resolve().parent
RUNTIME=HERE/'radar-runtime'
sys.path.insert(0,str(RUNTIME/'tools' if RUNTIME.exists() else HERE.parents[1]/'tools'))
from analyze_chart_radar import analyze,parse,parse_imd,VERSION
from artwork_fallback import relative,actual_image,contained


def supplement(charts,source_root,cache_path,write):
    rules=json.loads((HERE/'radar-rules.json').read_text(encoding='utf-8'))
    assert rules['algorithm']==VERSION
    fingerprint=hashlib.sha256(json.dumps(rules,sort_keys=True).encode()).hexdigest()
    cache_path=Path(cache_path);source_root=Path(source_root)
    cache={}
    try:
        if cache_path.is_file() and not cache_path.is_symlink() and cache_path.stat().st_size<16*1024*1024:
            cache=json.loads(cache_path.read_bytes())
    except (OSError,ValueError):pass
    updated={};ready=0
    for row in charts:
        try:
            keys=row.get('keys')
            if keys not in (4,5,6):continue
            p=relative(row['path'])/relative(row['mc_name'])
            mc=actual_image(source_root,p)
            if mc.is_symlink() or not contained(mc,source_root) or mc.stat().st_size>16*1024*1024:continue
            raw=mc.read_bytes();doc=json.loads(raw)
            candidates=[mc.with_suffix('.imd')]
            try:
                title=relative(str(row.get('title',''))+'.imd')
                if len(title.parts)==1:candidates.append(mc.parent/title)
            except ValueError:pass
            imd=next((p for p in candidates if p.is_file() and not p.is_symlink() and contained(p,source_root)),None)
            if imd and imd.stat().st_size>16*1024*1024:continue
            source=imd.read_bytes() if imd else raw
            digest=hashlib.sha256(fingerprint.encode()+str(keys).encode()+raw+source).hexdigest()
            record=cache.get(str(row['cid']))
            if not record or record.get('digest')!=digest:
                fallback=False
                try:
                    chart=parse_imd(source,keys) if imd else parse(raw,keys)
                    stats=analyze(chart,{} if imd else doc)
                except (ValueError,KeyError,TypeError,IndexError,OverflowError):
                    if not imd:raise
                    # Some packages contain malformed IMD alongside a complete MC.
                    # Use the actual MC notes, and disclose this source in the card.
                    chart=parse(raw,keys);stats=analyze(chart,doc);fallback=True
                values=[stats[axis]*100/rules['anchors'][str(keys)][axis] for axis in rules['axes']]
                if not all(math.isfinite(v) and v>=0 for v in values):continue
                record={'digest':digest,'values':values,'keys':keys,'source_format':chart.source_format,'algorithm':VERSION,
                        'raw_metrics':{axis:stats[axis] for axis in rules['axes']},
                        'slide_measurement':{'density':stats['slide_density'],'points':stats['slide_points'],
                                             'duration_seconds':stats['duration_s'],
                                             'average_density':stats['slide_average'],
                                             'window_seconds':4,
                                             'weights':{'volume':.7,'average_density':.2,'peak_density':.1}},
                        'source_sha256':hashlib.sha256(source).hexdigest()}
                if fallback:record['source_notice']='IMD异常，按MC谱面计算'
            updated[str(row['cid'])]=record
            row['radar']={k:v for k,v in record.items() if k!='digest'}
            ready+=1
        except (OSError,ValueError,KeyError,TypeError,IndexError,OverflowError):
            # One malformed chart cannot prevent unrelated songs from publishing.
            continue
    write(cache_path,json.dumps(updated,separators=(',',':')).encode())
    print('Public chart radars ready: '+str(ready)+'/'+str(len(charts)),flush=True)
