"""Conservative whole-chart capacity plus diagnostic RM VI capacity; never execute skin or chart scripts."""
import bisect
import hashlib
import json
import math
from pathlib import Path
import struct

RULE = 'slide-whole-accuracy-upper-v1'


def ticks(milliseconds, bpm):
    if not math.isfinite(milliseconds) or milliseconds < 0:
        raise ValueError('Invalid hold duration')
    return math.floor(math.floor((milliseconds + 2) * bpm / 1250) / 12 + 1)


def count_imd(raw):
    if not 26 <= len(raw) <= 16*1024*1024:
        raise ValueError('Invalid IMD')
    count = struct.unpack_from('<I',raw,4)[0]
    offset = 8 + count*12
    if not 1 <= count <= 100000 or offset+6 > len(raw) or raw[offset:offset+2] != b'\x03\x03':
        raise ValueError('Invalid IMD header')
    bpm = struct.unpack_from('<d',raw,12)[0]
    if not math.isfinite(bpm) or not 1 <= bpm <= 10000:
        raise ValueError('Invalid BPM')
    n = struct.unpack_from('<I',raw,offset+2)[0]
    if not 1 <= n <= 100000 or offset+6+n*11 != len(raw):
        raise ValueError('Invalid IMD records')
    total = 0
    active = False
    for i in range(n):
        kind,_,_,_,value=struct.unpack_from('<BBIBi',raw,offset+6+i*11)
        if kind in (0,1,2,0x61,0x62):
            if active:raise ValueError('Unterminated IMD chain')
            active=kind in (0x61,0x62)
        elif kind in (0x21,0x22,0xa1,0xa2):
            if not active:raise ValueError('Invalid chain')
            if kind in (0xa1,0xa2):active=False
        else:raise ValueError('Unsupported IMD type')
        if kind in (0,1,0xa1):total+=1
        elif kind in (2,0x62,0x22,0xa2):total+=ticks(value,bpm)
    if active or not 0 < total <= 10000000:raise ValueError('Invalid count')
    return total


def beat(value):
    a,b,c=value
    if not c:raise ValueError('Invalid beat')
    n=a+b/c
    if not math.isfinite(n):raise ValueError('Invalid beat')
    return n


def count_mc(raw):
    doc=json.loads(raw)
    if doc.get('meta',{}).get('mode') != 7 or doc.get('script'):
        raise ValueError('Only static Slide charts supported')
    bpms=sorted((beat(r['beat']),float(r['bpm'])) for r in doc['time'])
    if not bpms or any(not math.isfinite(v) or not 1 <= v <= 10000 for _,v in bpms):raise ValueError('Invalid BPM')
    positions=[p for p,_ in bpms];seconds=[0.0]
    for i in range(1,len(bpms)):
        seconds.append(seconds[-1]+(positions[i]-positions[i-1])*60/bpms[i-1][1])
    def time(at):
        i=max(0,bisect.bisect_right(positions,at)-1)
        return seconds[i]+(at-positions[i])*60/bpms[i][1]
    total=0
    notes=doc['note']
    if not isinstance(notes,list) or len(notes)>40000:raise ValueError('Invalid notes')
    for row in notes:
        if 'x' not in row and 'sound' in row:continue
        start=beat(row['beat']);segments=row.get('seg',[])
        if not segments:total+=1;continue
        if len(segments)>1024:raise ValueError('Too many segments')
        points=[(beat(s['beat']),float(s.get('x',0))) for s in segments]
        if any(t<0 or (i and t<points[i-1][0]) for i,(t,_) in enumerate(points)):raise ValueError('Unordered segments')
        def duration(a,b):return (time(start+b)-time(start+a))*1000
        if points[0][0]!=0 or len(points)==1:
            total+=ticks(duration(0,points[0][0]),bpms[0][1])
        if len(points)==1:
            if points[0][1]!=0:total+=1
        else:
            for (a,_),(b,_) in zip(points,points[1:]):total+=ticks(duration(a,b),bpms[0][1])
            if points[-1][1]!=points[-2][1]:total+=1
    if not 0 < total <= 10000000:raise ValueError('Invalid count')
    return total


def supplement(database,charts,metadata):
    base=Path(database).resolve().parent
    by_id={r['cid']:r for r in metadata}
    for chart in charts:
        row=by_id[chart['cid']]
        try:
            mc=base.parent/chart['path']/chart['mc_name']
            if not mc.is_file():mc=base/chart['path']/chart['mc_name']
            if mc.is_symlink() or not mc.resolve().is_relative_to(base.parent) or mc.stat().st_size>16*1024*1024:continue
            raw=mc.read_bytes();imd=mc.with_suffix('.imd')
            source=raw
            if imd.is_file() and not imd.is_symlink() and imd.resolve().is_relative_to(base.parent):
                if imd.stat().st_size>16*1024*1024:continue
                source=imd.read_bytes();rmvi_count=count_imd(source)
                offset=8+struct.unpack_from('<I',source,4)[0]*12
                size=struct.unpack_from('<I',source,offset+2)[0]
                count=sum(source[offset+6+i*11] in (0,1,2,0x61,0x62) for i in range(size))
            else:
                rmvi_count=count_mc(raw)
                doc=json.loads(raw)
                count=sum('x' in n for n in doc['note'])
            fields={'judgement_count':min(count,rmvi_count),'rmvi_judgement_count':rmvi_count,'judgement_rule':RULE,
                    'judgement_source_sha256':hashlib.sha256(raw+source).hexdigest()}
            chart.update(fields);row.update(fields)
        except (OSError,ValueError,KeyError,TypeError,IndexError,struct.error):
            # Unknown/scripted profiles must not borrow a note-head count.
            for k in ('judgement_count','judgement_rule','judgement_source_sha256'):row.pop(k,None)
