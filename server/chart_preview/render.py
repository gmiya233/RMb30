"""Beat-based unfolded previews using image assets from the supplied MSP.

Slide segment beat/x values are offsets from the note head (Malody Editor API).
No skin scripts, effects, audio or scoring code are executed.
"""
from dataclasses import dataclass
from functools import lru_cache, cached_property
from bisect import bisect_right
from fractions import Fraction
from io import BytesIO
import json
import math
from pathlib import Path
import re

from PIL import Image, ImageDraw, ImageFont

VERSION = 'rmvi-static-6-imd-companion'
SKIN = Path(__file__).parent / 'skin'
MAX_BYTES = 16 * 1024 * 1024


class Timeline:
    def __init__(self, bpms):
        values = sorted(dict(bpms).items())
        self.beats = [b for b, _ in values]
        self.bpms = [bpm for _, bpm in values]
        self.times = [0.0]
        for index in range(1, len(values)):
            self.times.append(self.times[-1] + (self.beats[index] - self.beats[index-1]) * 60 / self.bpms[index-1])

    def seconds(self, value):
        index = max(0, bisect_right(self.beats, value) - 1)
        return self.times[index] + (value - self.beats[index]) * 60 / self.bpms[index]


def number(value, low, high):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError('Invalid chart number')
    return float(value)


def beat(value):
    if not isinstance(value, list) or len(value) != 3 or any(type(v) is not int for v in value):
        raise ValueError('Invalid beat')
    whole, numerator, denominator = value
    # Humanized charts legitimately store e.g. [5,-1,384] or non-normalized fractions.
    if not -1024 <= whole <= 4096 or not 1 <= denominator <= 1_000_000 or abs(numerator) > 1_000_000:
        raise ValueError('Invalid beat fraction')
    result = whole + numerator / denominator
    if not -1024 <= result <= 4096:
        raise ValueError('Beat outside supported range')
    return result


@dataclass(frozen=True)
class Note:
    start: float
    x: float
    width: float
    direction: int
    points: tuple
    tail_direction: int = 0

    @property
    def end(self):
        return self.points[-1][0] if self.points else self.start


@dataclass
class Chart:
    notes: list
    bpms: list
    keys: int
    first: float
    end: float
    has_effects: bool
    reordered_segments: bool = False
    source_format: str = "MC"

    @property
    def beats_per_column(self):
        # Fit the complete chart in one sheet; boundaries stay on whole measures.
        span = self.end - self.first + 0.000001
        return max(12, math.ceil(span / 20 / 4) * 4)

    @property
    def columns(self):
        return max(1, math.ceil((self.end - self.first + 0.000001) / self.beats_per_column))

    @property
    def pages(self):
        return 1

    @cached_property
    def spacing(self):
        clock = Timeline(self.bpms)
        beats = sorted({n.start for n in self.notes} | {p[0] for n in self.notes for p in n.points})
        grouped = []
        for value in beats:
            if not grouped or value - grouped[-1] >= 1 / 48:
                grouped.append(value)
        intervals = [b - a for a, b in zip(grouped, grouped[1:])]
        # Musical beats, not elapsed seconds: low BPM and stops must not stretch
        # empty space or make other columns occupy only a fraction of their height.
        rate = min(300, max(90, 22 / min(intervals))) if intervals else 90
        width = max(960, self.columns * 376 + 40)
        limit = min(3600, 16_000_000 // width - 262)
        height = min(limit, max(640, math.ceil(self.beats_per_column * rate)))
        return {'clock': clock, 'rate': height / self.beats_per_column,
                'height': height, 'per_page': self.columns, 'events': grouped}


def parse(data, keys=None):
    if len(data) > MAX_BYTES:
        raise ValueError('Chart too large')
    doc = json.loads(data)
    if not isinstance(doc, dict) or doc.get('meta', {}).get('mode') != 7:
        raise ValueError('Only Slide mode is supported')
    rows = doc.get('note')
    if not isinstance(rows, list) or len(rows) > 40_000:
        raise ValueError('Invalid note list')
    if isinstance(keys, int) and keys > 0 and keys not in (4, 5, 6):
        raise ValueError('Unsupported lane count')
    if keys not in (4, 5, 6):
        widths = [r['w'] for r in rows if isinstance(r, dict) and type(r.get('w')) in (int, float) and r['w'] > 0]
        if widths:
            width = max(widths); keys = 4 if width >= 57 else 5 if width >= 47 else 6
        else:
            version = str(doc.get('meta', {}).get('version', ''))
            declared = set(re.findall(r'(?i)(?<![0-9])([456])\s*k(?![a-z])', version))
            if len(declared) == 1:
                keys = int(next(iter(declared)))
            else:
                raise ValueError('Missing lane metadata')
    notes = []; reordered = False; total_points = 0
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('Invalid note')
        if 'x' not in row and 'sound' in row:
            continue  # Audio/sample events have no playable lane.
        start = beat(row['beat'])
        x = number(row['x'], 0, 255)
        default_width = {4: 60, 5: 50, 6: 40}[keys] + (1 if row.get('dir', 0) & 10 else 0)
        width = number(row.get('w', default_width), 1, 256)
        direction = row.get('dir', 0)
        if type(direction) is not int or not 0 <= direction <= 63:
            raise ValueError('Invalid direction')
        segments = row.get('seg', [])
        if not isinstance(segments, list) or len(segments) > 1024:
            raise ValueError('Invalid slide segments')
        total_points += len(segments)
        if total_points > 100_000:
            raise ValueError('Too many slide segments')
        points = [(start, x)]
        previous = start
        tail_direction = 0
        for segment in segments:
            if not isinstance(segment, dict):
                raise ValueError('Invalid slide segment')
            at = start + beat(segment['beat'])
            sx = x + number(segment.get('x', 0), -255, 255)
            if at < start or previous - at > 0.01 or not 0 <= sx <= 255:
                raise ValueError('Invalid slide path')
            if at < previous:
                reordered = True
            tail_direction = segment.get('dir', 0)
            if type(tail_direction) is not int or not 0 <= tail_direction <= 63:
                raise ValueError('Invalid tail direction')
            points.append((at, sx)); previous = at
        # Some legacy converters emit a few milliseconds of reversed segment order.
        # Sort by their original timestamps; no time values or source files change.
        if segments:
            points = [points[0]] + sorted(points[1:], key=lambda p: p[0])
        notes.append(Note(start, x, width, direction, tuple(points) if segments else (), tail_direction))
    if not notes:
        raise ValueError('No playable notes')
    times = doc.get('time')
    if not isinstance(times, list) or not times or len(times) > 2048:
        raise ValueError('Invalid BPM list')
    bpms = []
    for row in times:
        bpms.append((beat(row['beat']), number(row['bpm'], 1, 10000)))
    bpms.sort()
    first = math.floor(min(n.start for n in notes) / 4) * 4
    end = max(n.end for n in notes)
    chart = Chart(sorted(notes, key=lambda n: n.start), bpms, keys, first, end, bool(doc.get('effect')) or any(t.get('delay') for t in times), reordered)
    return chart


def clean(value, limit=100):
    return ''.join(c for c in str(value or '') if c.isprintable() and c not in '\r\n')[:limit]


@lru_cache(maxsize=128)
def font(path, size):
    return ImageFont.truetype(str(path), size)


@lru_cache(maxsize=128)
def sprite(name, width, height):
    with Image.open(SKIN / name) as source:
        image = source.convert('RGBA')
        return image.crop(image.getbbox() or (0, 0, image.width, image.height)).resize((width, height), Image.Resampling.LANCZOS)


def render(chart, metadata, font_path, page=1):
    if type(page) is not int or not 1 <= page <= chart.pages:
        raise ValueError('Invalid preview page')
    beats_per_column = chart.beats_per_column
    spacing = chart.spacing; clock = spacing['clock']; per_page = spacing['per_page']
    start_column = (page - 1) * per_page
    count = min(per_page, chart.columns - start_column)
    column_width, track_width, margin = 376, 216, 68
    header, track_top, track_height, footer = 144, 36, spacing['height'] + 16, 66
    width, height = max(960, count * column_width + 40), header + track_top + track_height + footer
    image = Image.new('RGB', (width, height), '#101924')
    draw = ImageDraw.Draw(image)

    def label(x, y, text, size=18, color='#d4e3ed', maximum=None):
        value = clean(text, 160); face = font(str(font_path), size)
        if maximum:
            while value and draw.textlength(value, font=face) > maximum:
                value = value[:-2] + '…' if len(value) > 2 else ''
        draw.text((x, y), value, font=face, fill=color)

    label(24, 16, metadata.get('title') or '未命名歌曲', 32, '#ecf9ff', width - 48)
    label(24, 62, f"{clean(metadata.get('version'))}  ·  {chart.keys}K  ·  CID {metadata.get('cid', '')}  ·  {len(chart.notes)} 音符  ·  {chart.source_format} · 全谱单图", 22, maximum=width - 48)
    label(24, 103, f'EX Rhythm Master VI · 从下往上，再向右读 · 每栏{beats_per_column // 4}小节 · 按节拍/分度排版', 18, '#94dce6', width - 48)
    for index in range(count):
        at = chart.first + (start_column + index) * beats_per_column
        tile = Image.new('RGBA', (track_width + margin*2, track_height + 1), '#101924')
        td = ImageDraw.Draw(tile)
        rate = spacing['rate']

        def pixel(time, x):
            return margin + x / 255 * track_width, 8 + spacing['height'] - (time - at) * rate

        for lane in range(chart.keys):
            x1, x2 = margin + round(lane * track_width / chart.keys), margin + round((lane + 1) * track_width / chart.keys)
            td.rectangle((x1, 0, x2, track_height), fill='#263545' if lane % 2 else '#1d2a39')
            td.line((x1, 0, x1, track_height), fill='#526472', width=1)
        for subdivision in range(beats_per_column*4 + 1):
            _, y = pixel(at + subdivision / 4, 0)
            td.line((margin, y, margin + track_width, y), fill='#536d7d' if subdivision % 16 == 0 else '#354653' if subdivision % 4 == 0 else '#2b3b49')

        def head(time, x, lane, long=False):
            if at <= time < at + beats_per_column:
                px, py = pixel(time, x)
                item = sprite(f'{chart.keys}note{"l" if long else ""}{lane}.png', round(track_width / chart.keys) - 3, 14)
                tile.alpha_composite(item, (round(px - item.width / 2), round(py - item.height / 2)))

        def arrow(time, x, direction):
            if not at <= time < at + beats_per_column or not direction:
                return
            px, py = pixel(time, x)
            # NoteFlag: right=2, up=4, left=8, down=16 (Malody Editor API).
            dx, dy = (-1, 0) if direction & 8 else (0, -1) if direction & 4 else (0, 1) if direction & 16 else (1, 0)
            back = (px - dx*13, py - dy*13)
            td.line((back[0] - dy*8, back[1] + dx*8, px, py, back[0] + dy*8, back[1] - dx*8), fill='#c5ff91', width=4)

        def swipe(time, origin, target, outside_head=False):
            if not at <= time < at + beats_per_column or origin == target:
                return
            sx, cy = pixel(time, origin); ex, _ = pixel(time, target)
            sign = 1 if ex > sx else -1
            if outside_head:
                shift = sign * (track_width / chart.keys / 2 + 3)
                sx += shift; ex += shift
            # A plain shaft leaving the rectangle, ending in an open chevron.
            td.line((sx, cy, ex, cy), fill='#b7f27c', width=4)
            length = min(15, max(9, abs(ex-sx) * 0.35))
            td.line((ex-sign*length, cy-9, ex, cy, ex-sign*length, cy+9), fill='#c5ff91', width=4)

        for note in chart.notes:
            if note.end < at or note.start >= at + beats_per_column:
                continue
            lane = min(chart.keys, max(1, int(note.x / 256 * chart.keys) + 1))
            if note.points:
                for segment_index, ((t1, x1), (t2, x2)) in enumerate(zip(note.points, note.points[1:])):
                    if t2 < at or t1 > at + beats_per_column:
                        continue
                    a, b = max(at, t1), min(at + beats_per_column, t2)
                    if b < a:
                        continue
                    # Rhythm Master holds stay in the original lane until the segment
                    # endpoint, then flick horizontally. Never interpolate a diagonal.
                    p1, p2 = pixel(a, x1), pixel(b, x1)
                    if b > a:
                        body = sprite('notebody.png', 8, max(1, round(abs(p2[1] - p1[1]))))
                        tile.alpha_composite(body, (round(p1[0] - 4), round(min(p1[1], p2[1]))))
                    if x1 != x2 and at <= t2 < at + beats_per_column:
                        before, after = pixel(t2, x1), pixel(t2, x2)
                        td.line((*before, *after), fill='#9ee94f', width=5)
                        td.line((*before, *after), fill='#efffd1', width=2)
                        if segment_index == len(note.points) - 2:
                            swipe(t2, x1, x2)
                end, ex = note.points[-1]
                if at <= end < at + beats_per_column and note.points[-2][1] == ex:
                    px, py = pixel(end, ex); tail = sprite('notefbody0.png', 13, 13)
                    tile.alpha_composite(tail, (round(px - 6), round(py - 6)))
                arrow(end, ex, note.tail_direction)
            head(note.start, note.x, lane, bool(note.points) or bool(note.direction))
            if note.direction & (2 | 8) and at <= note.start < at + beats_per_column:
                distance = int(note.width) % 10
                target = note.x + (1 if note.direction & 2 else -1) * max(1, distance) * 256 / chart.keys
                start_point, end_point = pixel(note.start, note.x), pixel(note.start, target)
                if target != note.x:
                    swipe(note.start, note.x, target, outside_head=True)
                else:
                    arrow(note.start, target, note.direction)
            else:
                arrow(note.start, note.x, note.direction)
        left = 24 + index * column_width
        image.paste(tile.convert('RGB'), (left, header + track_top))
        label(left + margin, header + 3, f'#{start_column + index + 1:02d}  {at:g}–{at+beats_per_column:g} 拍', 16, '#9bd6e4')
        for bar in range(beats_per_column // 4 + 1):
            _, y = pixel(at + bar*4, 0)
            label(left + 6, header + track_top + y - 9, f'#{int((at + bar*4)/4)}', 13, '#b4cbd7')
        events = [b for b in spacing['events'] if at <= b < at+beats_per_column]
        all_events = spacing['events']
        for value in events:
            position = bisect_right(all_events, value)
            if position >= len(all_events):
                continue
            gap = all_events[position] - value
            fraction = Fraction(gap / 4).limit_denominator(192)
            _, y = pixel(value, 0)
            label(left + margin + track_width + 7, header + track_top + y - 18, str(fraction), 12, '#aec8d2')
        changes = list(chart.bpms)
        if index == 0 and not any(change == chart.first for change, _ in changes):
            changes.append((chart.first, clock.bpms[max(0, bisect_right(clock.beats, chart.first) - 1)]))
        for change, bpm in sorted(changes):
            if at <= change < at + beats_per_column:
                _, position = pixel(change, 0); y = header + track_top + position
                draw.line((left + margin, y, left + margin + track_width, y), fill='#50a0b8', width=2)
                bpm_y = y + 5 if y + 34 < header + track_top + track_height else y - 43
                label(left + 2, bpm_y, 'BPM', 11, '#8ee5f1')
                label(left + 2, bpm_y + 14, f'{bpm:g}', 12, '#8ee5f1', margin - 5)

    note = '静态直轨读谱 · 按节拍/分度排版 · 不执行滚速、停顿或皮肤脚本'
    if chart.has_effects:
        note += ' · 本谱包含效果/停顿'
    if chart.reordered_segments:
        note += ' · 微小分段倒序按原时间排列'
    label(24, height - 58, note, 17, '#afc5d2', width - 48)
    label(24, height - 30, '皮肤：dreamcat / Acc@9961 / Sam_0324  ·  Malody Link', 15, '#8aa7b8', width - 48)
    output = BytesIO(); image.save(output, format='PNG', optimize=True)
    data = output.getvalue()
    if len(data) > 4 * 1024 * 1024:
        raise ValueError('Preview exceeds image upload limit')
    return data, image.size
