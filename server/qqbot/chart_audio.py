"""Recommendation voice excerpts from chart-referenced, read-only published audio."""
import hashlib
import json
import math
from pathlib import Path
import re
from music_preview import MusicPreview


class ChartAudio(MusicPreview):
    def __init__(self,root,queries,playback,ffmpeg=None):
        super().__init__(root,ffmpeg=ffmpeg)
        self.queries=queries;self.playback=Path(playback)

    def source_key(self,charts):
        return charts

    def resolve(self,charts):
        namespace,current=self.queries.catalogue()
        for cid,server_hash in list(charts)[:5]:
            try:
                if type(cid) is not int or cid<=0 or current.get(cid,{}).get('server_hash')!=server_hash:continue
                timeline=self.playback/f'{cid}.json'
                if timeline.is_symlink() or timeline.stat().st_size>8*1024*1024:continue
                d=json.loads(timeline.read_bytes())
                if (d.get('schema')!=1 or d.get('server')!=namespace or d.get('cid')!=cid
                        or d.get('server_hash')!=server_hash or 'error' in d):continue
                a=d['audio'];name=a['file']
                if not re.fullmatch(r'[a-f0-9]{64}\.(mp3|ogg|wav|m4a)',name):continue
                path=self.playback/name
                if path.is_symlink() or path.resolve().parent!=self.playback.resolve() or not 0<path.stat().st_size<=32*1024*1024:continue
                if hashlib.sha256(path.read_bytes()).hexdigest()!=a['sha256']:continue
                chart=d.get('chart',{});notes=chart.get('notes',[])
                first=min(float(n[0]) for n in notes) if notes else 0
                start=max(0,first-float(chart.get('audio_start',0)))
                if not math.isfinite(start) or start>3600:continue
                return path,start
            except (OSError,ValueError,KeyError,TypeError,IndexError):continue
        return None

    def prepare(self,charts,directory):
        source=self.resolve(charts)
        if source is None:return None
        path,start=source
        # Only the excerpt is temporary. Never copy, alter or remove the source.
        return self.transcode(path,directory/'voice.mp3',start)
