"""Random song cards and persistent, opt-in two-hour group schedules."""
import json
from pathlib import Path
import random
import sqlite3
import time
from contextlib import closing
from datetime import datetime
from zoneinfo import ZoneInfo
from b30_data import identity
from song_search import render, load_rankings, artwork

INTERVAL = 7200
COMMANDS = {'歌曲推荐','随机推荐','推荐歌曲','开启推荐','关闭推荐','推荐状态'}


class Recommendations:
    def __init__(self, queries, font, roots, database, clock=time.time):
        self.queries,self.font,self.roots,self.path,self.clock=queries,font,roots,Path(database),clock
        self.path.parent.mkdir(parents=True,exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS recommendations (group_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 0, next_due REAL NOT NULL DEFAULT 0, history TEXT NOT NULL DEFAULT "[]", status TEXT NOT NULL DEFAULT "")')
        self.path.chmod(0o600)

    def history(self, group):
        with closing(sqlite3.connect(self.path)) as db:
            row=db.execute('SELECT history FROM recommendations WHERE group_id=?',(group,)).fetchone()
            return json.loads(row[0]) if row else []

    def card(self, group):
        songs={}
        for row in self.queries.catalogue()[1].values():
            if row.get('search_visible',True) is True:
                songs.setdefault(identity(row),[]).append(row)
        if not songs: raise ValueError('当前没有可推荐歌曲')
        history=self.history(group)
        choices=[key for key in songs if json.dumps(key) not in history] or list(songs)
        random.SystemRandom().shuffle(choices)
        # Prefer a song whose published cover can actually be opened.
        selected=choices[0]
        for key in choices:
            cover=artwork(songs[key],self.roots)
            if cover is not None:
                cover.close();selected=key;break
        rows=sorted(songs[selected],key=lambda r:r['cid'])
        image=render(rows,self.font,self.roots,load_rankings(self.queries,rows),heading='小师妹 · 歌曲推荐')
        if len(image)>4*1024*1024: raise ValueError('推荐图超过大小限制')
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT OR IGNORE INTO recommendations(group_id) VALUES (?)',(group,))
            db.execute('UPDATE recommendations SET history=? WHERE group_id=?',(json.dumps((history+[json.dumps(selected)])[-20:]),group))
        return image

    def enable(self,group):
        due=self.clock()+INTERVAL
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT OR IGNORE INTO recommendations(group_id) VALUES (?)',(group,))
            db.execute('UPDATE recommendations SET enabled=1,next_due=?,status=? WHERE group_id=?',(due,'已开启',group))
        return '已开启每2小时歌曲推荐，下一次：'+self.stamp(due)+'。发送“关闭推荐”可停止。'

    def disable(self,group):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE recommendations SET enabled=0,status=? WHERE group_id=?',('已关闭',group))
        return '本群自动歌曲推荐已关闭，仍可手动发送“歌曲推荐”。'

    @staticmethod
    def stamp(value):
        return datetime.fromtimestamp(value,ZoneInfo('Asia/Shanghai')).strftime('%m-%d %H:%M')

    def status(self,group):
        with closing(sqlite3.connect(self.path)) as db:
            row=db.execute('SELECT enabled,next_due,status FROM recommendations WHERE group_id=?',(group,)).fetchone()
        if not row or not row[0]: return '本群自动推荐未开启。发送“开启推荐”启用每2小时推荐，或发送“歌曲推荐”立即查看。'
        return '本群每2小时推荐已开启。下一次：'+self.stamp(row[1])+'；最近状态：'+row[2]

    def due(self, allowed):
        now=self.clock()
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('BEGIN IMMEDIATE')
            groups=[r[0] for r in db.execute('SELECT group_id FROM recommendations WHERE enabled=1 AND next_due<=?',(now,)) if not allowed or r[0] in allowed]
            # Reserve the next slot before I/O. Restarts/failures never cause catch-up floods.
            for group in groups:
                db.execute('UPDATE recommendations SET next_due=?,status=? WHERE group_id=?',(now+INTERVAL,'正在发送',group))
        return groups

    def finish(self,group,ok):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE recommendations SET status=? WHERE group_id=?',('发送成功' if ok else '发送失败，请检查群内主动推送开关或发送歌曲推荐',group))
