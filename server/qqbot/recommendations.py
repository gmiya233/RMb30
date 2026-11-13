from song_buttons import SongCard, send_actions
"""Random song cards and persistent, opt-in two-hour group schedules."""
import json
from pathlib import Path
import random
import sqlite3
import time
from contextlib import closing
from datetime import datetime, timezone, timedelta
from b30_data import identity
from song_search import render, load_rankings, artwork

INTERVAL = 3600
BEIJING = timezone(timedelta(hours=8))
QUIET_TEXT = '北京时间00:00～10:00不主动推歌'
COMMANDS = {'歌曲推荐','随机推荐','推荐歌曲','开启推荐','关闭推荐','推荐状态'}

def next_push_time(value):
    local=datetime.fromtimestamp(value,BEIJING)
    return local.replace(hour=10,minute=0,second=0,microsecond=0).timestamp() if local.hour<10 else value

def enable_failure_hint(error):
    # botpy 1.2.1 labels unrecognized HTTP statuses ServerError, including 400;
    # the class name does not establish a QQ service outage.
    reason=str(getattr(error,'msgs',''))
    if '主动消息' in reason and '无权限' in reason:
        return '自动推荐尚未开启：QQ平台拒绝了本群主动消息权限（40034105）。请群主在本群的小师妹机器人设置中检查并允许主动推送，再发送“开启推荐”。旧群的授权不会自动带到新群；现在仍可发送“歌曲推荐”手动查看。'
    if type(error).__name__=='ServerError':
        return '自动推荐尚未开启：QQ平台没有接受主动发送，请稍后重试；若持续失败，请群主检查本群机器人主动推送权限。现在仍可发送“歌曲推荐”手动查看。'
    if isinstance(error,TimeoutError):
        return '自动推荐尚未开启：等待QQ平台确认发送超时，请稍后重试。现在仍可发送“歌曲推荐”手动查看。'
    return '自动推荐尚未开启：主动发送未获确认，暂时无法确定原因。请稍后重试；若持续失败，请群主检查是否允许机器人主动推送，也可发送“歌曲推荐”手动查看。'


class Recommendations:
    def __init__(self, queries, font, roots, database, clock=time.time):
        self.queries,self.font,self.roots,self.path,self.clock=queries,font,roots,Path(database),clock
        self.path.parent.mkdir(parents=True,exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS recommendations (group_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 0, next_due REAL NOT NULL DEFAULT 0, history TEXT NOT NULL DEFAULT "[]", status TEXT NOT NULL DEFAULT "")')
        with closing(sqlite3.connect(self.path)) as db, db:
            columns={r[1] for r in db.execute('PRAGMA table_info(recommendations)')}
            if 'next_kind' not in columns:
                db.execute("ALTER TABLE recommendations ADD COLUMN next_kind TEXT NOT NULL DEFAULT 'music'")
                db.execute("ALTER TABLE recommendations ADD COLUMN dispatch_kind TEXT NOT NULL DEFAULT 'chart'")
                for group,due in db.execute('SELECT group_id,next_due FROM recommendations WHERE enabled=1').fetchall():
                    db.execute('UPDATE recommendations SET next_due=? WHERE group_id=?',(next_push_time(max(self.clock()+60,due-3600)),group))
        self.path.chmod(0o600)

    def history(self, group):
        with closing(sqlite3.connect(self.path)) as db:
            row=db.execute('SELECT history FROM recommendations WHERE group_id=?',(group,)).fetchone()
            return json.loads(row[0]) if row else []

    def card(self, group):
        songs={}
        for row in self.queries.catalogue()[1].values():
            if row.get('search_visible',True) is True and row.get('recommend_visible',True) is True:
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
        image=render(rows,self.font,self.roots,load_rankings(self.queries,rows),heading='小师妹 · 这次来打这首！')
        if len(image)>4*1024*1024: raise ValueError('推荐图超过大小限制')
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT OR IGNORE INTO recommendations(group_id) VALUES (?)',(group,))
            db.execute('UPDATE recommendations SET history=? WHERE group_id=?',(json.dumps((history+[json.dumps(selected)])[-20:]),group))
        return SongCard(image, rows)

    def enable(self,group):
        now=self.clock();due=next_push_time(now if self.quiet() else now+INTERVAL)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT OR IGNORE INTO recommendations(group_id) VALUES (?)',(group,))
            db.execute("UPDATE recommendations SET enabled=1,next_kind=?,next_due=?,status=? WHERE group_id=?",('chart' if self.quiet() else 'music',due,'已开启',group))
        return '选曲任务交给小师妹啦！已开启每小时交替推送谱面和音乐，'+QUIET_TEXT+'。下一次：'+self.stamp(due)+'。'+('现在是休息时间，10点后再尝试推送；是否发送成功以QQ平台确认为准。' if self.quiet() else '想暂停时，发送“关闭推荐”就好～')

    def quiet(self):
        now=self.clock()
        return next_push_time(now)>now

    def defer(self,group):
        now=self.clock()
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE recommendations SET next_kind=dispatch_kind,next_due=?,status=? WHERE group_id=? AND enabled=1',(next_push_time(now),'静默时段，10:00后再推荐',group))

    def disable(self,group):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE recommendations SET enabled=0,status=? WHERE group_id=?',('已关闭',group))
        return '本群自动歌曲推荐已关闭，小师妹先把选曲本收好啦～想临时抽一首，随时发送“歌曲推荐”！'

    @staticmethod
    def stamp(value):
        return datetime.fromtimestamp(value,BEIJING).strftime('%m-%d %H:%M')

    def status(self,group):
        with closing(sqlite3.connect(self.path)) as db:
            row=db.execute('SELECT enabled,next_due,status,next_kind FROM recommendations WHERE group_id=?',(group,)).fetchone()
        if not row or not row[0]: return '本群自动推荐未开启。想让小师妹帮忙选曲吗？发送“开启推荐”启用每小时交替推荐，或发送“歌曲推荐”马上抽一首～'
        return '小师妹的选曲日程来啦！本群每小时交替推荐已开启，'+QUIET_TEXT+'。下一次：'+self.stamp(next_push_time(max(row[1],self.clock())))+'（'+('音乐' if row[3]=='music' else '谱面')+'）；最近状态：'+row[2]

    def due(self, allowed):
        now=self.clock()
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('BEGIN IMMEDIATE')
            resume=next_push_time(now)
            if resume>now:
                db.execute('UPDATE recommendations SET next_due=?,status=? WHERE enabled=1 AND next_due<?',(resume,'静默时段，10:00后再推荐',resume))
                return []
            groups=[r[0] for r in db.execute('SELECT group_id FROM recommendations WHERE enabled=1 AND next_due<=?',(now,)) if not allowed or r[0] in allowed]
            # Reserve the next slot before I/O. Restarts/failures never cause catch-up floods.
            for group in groups:
                db.execute("UPDATE recommendations SET dispatch_kind=next_kind,next_kind=CASE next_kind WHEN 'music' THEN 'chart' ELSE 'music' END,next_due=?,status=? WHERE group_id=?",(next_push_time(now+INTERVAL),'正在发送',group))
        return groups

    def finish(self,group,ok):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE recommendations SET status=? WHERE group_id=?',('发送成功' if ok else '发送失败，请检查群内主动推送开关或发送歌曲推荐',group))

    def kind(self,group):
        with closing(sqlite3.connect(self.path)) as db:
            row=db.execute('SELECT dispatch_kind FROM recommendations WHERE group_id=?',(group,)).fetchone()
            return row[0] if row else 'chart'
