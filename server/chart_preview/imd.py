"""Read IMD records using the supplied RM VI skin's static format rules.
No skin code executes. Times are milliseconds, lanes are zero based.
"""
import math
import struct
from bisect import bisect_right
from render import Chart, Note, MAX_BYTES


def parse_imd(data, keys):
    if keys not in (4, 5, 6) or not 26 <= len(data) <= MAX_BYTES:
        raise ValueError('Invalid IMD size or lane count')
    count = struct.unpack_from('<I', data, 4)[0]
    offset = 8 + count * 12
    if not 1 <= count <= 100000 or offset + 6 > len(data) or data[offset:offset+2] != b'\x03\x03':
        raise ValueError('Invalid IMD header')
    records = struct.unpack_from('<I', data, offset + 2)[0]
    if not 1 <= records <= 100000 or offset + 6 + records * 11 != len(data):
        raise ValueError('Invalid IMD record count')
    times, bpms, beats = [], [], []
    for i in range(count):
        time, bpm = struct.unpack_from('<Id', data, 8 + i * 12)
        if not math.isfinite(bpm) or not 1 <= bpm <= 10000 or (times and time <= times[-1]):
            raise ValueError('Invalid IMD BPM')
        beats.append(0.0 if not times else beats[-1] + (time-times[-1]) * bpms[-1] / 60000)
        times.append(time); bpms.append(bpm)
    def beat(time):
        i = max(0, bisect_right(times, time)-1)
        return beats[i] + (time-times[i]) * bpms[i] / 60000
    def x(lane):
        if not 0 <= lane < keys:
            raise ValueError('Invalid IMD lane')
        return (lane + .5) * 256 / keys
    width = {4:60,5:50,6:40}[keys]
    notes = []; active = None; points = None; previous = -1
    for i in range(records):
        kind, reserved, time, lane, value = struct.unpack_from('<BBIBi', data, offset+6+i*11)
        px = x(lane); at = beat(time)
        if kind in (0, 1, 2, 0x61, 0x62):
            if active is not None:
                raise ValueError('Unterminated IMD chain')
            if kind == 0:
                notes.append(Note(at, px, width, 0, ()))
            elif kind == 1:
                if value == 0: raise ValueError('Empty IMD flick')
                x(lane + value)
                notes.append(Note(at, px, width + abs(value), 2 if value > 0 else 8, ()))
            elif kind == 2:
                if value < 0: raise ValueError('Negative IMD hold')
                notes.append(Note(at, px, width, 0, ((at,px),(beat(time+value),px))))
            else:
                active = (at,px); points = [(at,px)]; previous = time
                if kind == 0x61:
                    if value == 0: raise ValueError('Empty IMD chain flick')
                    points.append((at,x(lane+value)))
                elif value < 0:
                    raise ValueError('Negative IMD chain hold')
        elif kind in (0x21,0x22,0xa1,0xa2):
            if active is None or time < previous:
                raise ValueError('Invalid IMD chain order')
            points.append((at,px)); previous=time
            if kind in (0x21,0xa1):
                if value == 0: raise ValueError('Empty IMD chain flick')
                points.append((at,x(lane+value)))
            elif kind == 0xa2:
                if value < 0: raise ValueError('Negative IMD chain tail')
                points.append((beat(time+value),px))
            elif value < 0:
                raise ValueError('Negative IMD chain hold')
            if kind in (0xa1,0xa2):
                notes.append(Note(active[0],active[1],width,0,tuple(points)))
                active=None
        else:
            raise ValueError('Unsupported IMD record')
    if active is not None or not notes:
        raise ValueError('Incomplete IMD chart')
    changes = [(beats[i],bpms[i]) for i in range(count) if i == 0 or bpms[i] != bpms[i-1]]
    return Chart(sorted(notes,key=lambda n:n.start),changes,keys,
                 math.floor(min(n.start for n in notes)/4)*4,max(n.end for n in notes),False,source_format='IMD')
