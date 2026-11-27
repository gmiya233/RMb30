"""Opt-in QQ Music sources and per-group no-repeat music rotations."""
import json,re,time,random,sqlite3,urllib.request,urllib.parse
import html
from pathlib import Path
from contextlib import closing
from io import BytesIO
from bindings import Bindings
from PIL import Image,ImageDraw,ImageFont,ImageOps
from music_album import album_id,fetch_album,resolve_album_input
from music_track import fetch_track, resolve_track_input
COMMANDS={'绑定歌单','我的歌单','解绑歌单','音乐推荐','添加专辑','我的专辑','移除专辑','添加单曲'}

def playlist_id(value):
 value=html.unescape(value.strip())
 if value.isascii() and value.isdigit() and 1<=len(value)<=15:return value
 u=urllib.parse.urlsplit(value)
 if u.scheme!='https' or u.hostname not in ('y.qq.com','i2.y.qq.com') or u.username or u.password or u.port not in (None,443):raise ValueError('请发送QQ音乐官方歌单分享链接或歌单编号。')
 if u.hostname=='i2.y.qq.com' and u.path=='/n3/other/pages/details/playlist.html':
  ids=urllib.parse.parse_qs(u.query).get('id',[])
  if len(ids)==1 and re.fullmatch('[0-9]{1,15}',ids[0]):return ids[0]
  raise ValueError('歌单链接缺少有效编号')
 m=re.fullmatch(r'/n/ryqq(?:_v2)?/playlist/([0-9]{1,15})/?',u.path)
 if not m or u.hostname!='y.qq.com':raise ValueError('请发送QQ音乐歌单链接，不能使用歌曲或个人主页链接。')
 return m[1]

def fetch(pid):
 url='https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&format=json&disstid='+pid
 request=urllib.request.Request(url,headers={'Referer':'https://y.qq.com/','User-Agent':'Mozilla/5.0'})
 with urllib.request.urlopen(request,timeout=8) as response:raw=response.read(4*1024*1024+1)
 if len(raw)>4*1024*1024:raise ValueError('歌单过大')
 d=json.loads(raw)
 if d.get('code')!=0 or not d.get('cdlist'):raise ValueError('歌单未公开或暂时无法读取')
 info=d['cdlist'][0];rows=info.get('songlist',[])
 if not rows or len(rows)!=int(info.get('total_song_num',-1)):raise ValueError('未能获取完整歌单，请确认公开设置后重试')
 songs={}
 for row in rows:
  mid=row.get('songmid','')
  if not re.fullmatch('[A-Za-z0-9]{1,32}',mid):continue
  album=row.get('albummid','')
  songs[mid]=dict(mid=mid,title=str(row.get('songname') or row.get('name') or '未命名')[:200],artist=' / '.join(str(x.get('name','')) for x in row.get('singer',[]))[:200],album=album if re.fullmatch('[A-Za-z0-9]{1,32}',album) else '')
 if not songs:raise ValueError('歌单内没有可读取歌曲')
 return str(info.get('dissname') or 'QQ音乐歌单')[:100],list(songs.values())

class MusicCard(bytes):
 def __new__(cls,data,song):
  result=super().__new__(cls,data);result.music=song;return result

class MusicRecommendations:
 def __init__(self,path,bindings,font,fetcher=fetch,clock=time.time,album_fetcher=fetch_album):
  self.path=Path(path);self.bindings=bindings;self.font=font;self.fetcher=fetcher;self.clock=clock;self.album_fetcher=album_fetcher
  self.path.parent.mkdir(parents=True,exist_ok=True)
  with closing(sqlite3.connect(self.path)) as db,db:
   db.executescript('CREATE TABLE IF NOT EXISTS playlists(owner TEXT PRIMARY KEY,pid TEXT,title TEXT,songs TEXT,updated REAL); CREATE TABLE IF NOT EXISTS music_history(group_id TEXT,mid TEXT,PRIMARY KEY(group_id,mid)); CREATE TABLE IF NOT EXISTS music_pending(group_id TEXT PRIMARY KEY,mid TEXT);')
   db.execute('CREATE TABLE IF NOT EXISTS playlist_binding_pending (member TEXT PRIMARY KEY, expires REAL)')
   db.execute('CREATE TABLE IF NOT EXISTS albums(owner TEXT,aid TEXT,title TEXT,songs TEXT,updated REAL,PRIMARY KEY(owner,aid))')
   db.execute('CREATE TABLE IF NOT EXISTS album_binding_pending(member TEXT PRIMARY KEY,expires REAL)')
   db.execute('CREATE TABLE IF NOT EXISTS tracks(owner TEXT,mid TEXT,song TEXT,PRIMARY KEY(owner,mid))')
  self.path.chmod(0o600)
 def execute(self,group,member,content):
  from b30_commands import command_parts
  command,arg=command_parts(content)
  if not self.bindings.get(group,member):return '请先发送“绑定 你的QQ号”，再使用音乐推荐功能。'
  owner=Bindings.shared_key(member)
  if command=='音乐推荐':
   if arg:return '用法：音乐推荐'
   return self.card(group)
  with closing(sqlite3.connect(self.path)) as db,db:
   if command=='添加单曲':
    if not arg:
     return '在QQ音乐复制歌曲分享文本，发送“@小师妹 添加单曲 分享文本”。也可以直接粘贴歌曲链接。'
    try:
     reference=resolve_track_input(arg)
    except ValueError as error:
     return str(error)
    try:
     song=fetch_track(*reference)
    except (ValueError,OSError):
     return '暂时无法读取这首歌，请稍后重试。'
    result=db.execute('INSERT OR IGNORE INTO tracks(owner,mid,song) VALUES(?,?,?)',
                      (owner,song['mid'],json.dumps(song,ensure_ascii=False)))
    if not result.rowcount:
     return f'你已添加过《{song["title"]}》，它已在公共音乐推荐池中。'
    return f'已添加《{song["title"]}》，已加入公共音乐推荐池。'
   if command=='添加专辑':
    if not arg:
     db.execute('DELETE FROM playlist_binding_pending WHERE member=?',(Bindings.key(group,member),))
     db.execute('INSERT OR REPLACE INTO album_binding_pending VALUES(?,?)',(Bindings.key(group,member),self.clock()+600))
     return '请打开QQ音乐专辑→分享→复制链接或分享文本，然后发送“@小师妹 添加专辑 链接”。也可直接在10分钟内@我并附上链接。仅转发专辑卡片不会触发当前机器人的@消息入口，截图和专辑标题也不能用于识别。完整读取后会加入公共音乐推送池；每人最多20张专辑。'
    try:aid=resolve_album_input(arg)
    except ValueError as e:return str(e)
    existing=db.execute('SELECT 1 FROM albums WHERE owner=? AND aid=?',(owner,aid)).fetchone()
    if not existing and db.execute('SELECT COUNT(*) FROM albums WHERE owner=?',(owner,)).fetchone()[0]>=20:
     return '已添加20张专辑，请先发送“移除专辑 专辑编号”腾出位置。'
    try:title,songs=self.album_fetcher(aid)
    except Exception:return '专辑读取未完成，请确认链接或专辑ID正确；没有添加部分曲目，也没有改变已有歌单和专辑。'
    db.execute('BEGIN IMMEDIATE')
    if not db.execute('SELECT 1 FROM albums WHERE owner=? AND aid=?',(owner,aid)).fetchone() and db.execute('SELECT COUNT(*) FROM albums WHERE owner=?',(owner,)).fetchone()[0]>=20:
     return '已添加20张专辑，请先移除一张再添加。'
    db.execute('INSERT INTO albums VALUES(?,?,?,?,?) ON CONFLICT(owner,aid) DO UPDATE SET title=excluded.title,songs=excluded.songs,updated=excluded.updated',(owner,aid,title,json.dumps(songs,ensure_ascii=False),self.clock()))
    db.execute('DELETE FROM album_binding_pending WHERE member=?',(Bindings.key(group,member),))
    return f'已添加专辑“{title}”，读取到{len(songs)}首；与大家的歌单一起加入公共推荐池，同一歌曲按songmid去重。发送“我的专辑”查看，或“移除专辑 {aid}”退出。'
   if command=='我的专辑':
    rows=db.execute('SELECT aid,title,songs FROM albums WHERE owner=? ORDER BY title,aid',(owner,)).fetchall()
    return '我的专辑（跨群共用）：\n'+'\n'.join(f'{title} · {len(json.loads(songs))}首 · ID {aid}' for aid,title,songs in rows) if rows else '还没有添加专辑。点击“添加专辑”或发送“添加专辑 专辑链接”。'
   if command=='移除专辑':
    if not arg:return '用法：移除专辑 专辑链接或编号。可先发送“我的专辑”查看编号。'
    try:aid=album_id(arg)
    except ValueError as e:return str(e)
    n=db.execute('DELETE FROM albums WHERE owner=? AND aid=?',(owner,aid)).rowcount
    return '已移除这张专辑；歌曲若还存在于其他人的歌单或专辑中，仍可能被推荐。' if n else '你的专辑列表中没有这个编号，请发送“我的专辑”核对。'
   if command=='绑定歌单':
    if not arg:
     db.execute('DELETE FROM album_binding_pending WHERE member=?',(Bindings.key(group,member),))
     db.execute('INSERT OR REPLACE INTO playlist_binding_pending VALUES(?,?)',(Bindings.key(group,member),self.clock()+600))
     return '请在10分钟内向小师妹发送QQ音乐歌单卡片或分享链接（群内需要@我）。若QQ平台未传递卡片链接，请打开卡片→分享→复制链接，再发送“绑定歌单 链接”。绑定后曲目将加入公共推荐池。'
    try:
     pid=playlist_id(arg);title,songs=self.fetcher(pid)
    except Exception:return '歌单读取未完成，请确认链接正确且已公开；没有保存或替换旧歌单。'
    db.execute('INSERT INTO playlists VALUES(?,?,?,?,?) ON CONFLICT(owner) DO UPDATE SET pid=excluded.pid,title=excluded.title,songs=excluded.songs,updated=excluded.updated',(owner,pid,title,json.dumps(songs,ensure_ascii=False),self.clock()))
    db.execute('DELETE FROM playlist_binding_pending WHERE member=?',(Bindings.key(group,member),))
    return f'已绑定“{title}”，读取到{len(songs)}首。曲目会加入所有已开启推荐群的公共音乐池；不展示歌单账号。发送“解绑歌单”可退出。'
   row=db.execute('SELECT pid,title,songs FROM playlists WHERE owner=?',(owner,)).fetchone()
   if command=='解绑歌单':
    db.execute('DELETE FROM playlists WHERE owner=?',(owner,));db.execute('DELETE FROM playlist_binding_pending WHERE member=?',(Bindings.key(group,member),));return '已解绑歌单，曲目不再由你的歌单加入推荐池。'
   return f'当前歌单：{row[1]} · {len(json.loads(row[2]))}首\nhttps://y.qq.com/n/ryqq_v2/playlist/{row[0]}' if row else '还没有绑定歌单。发送“绑定歌单 歌单链接”即可加入。'
 def binding_input(self,group,member,message):
  from music_share import extract_playlist,extract_album
  with closing(sqlite3.connect(self.path)) as db:
   album=db.execute('SELECT expires FROM album_binding_pending WHERE member=?',(Bindings.key(group,member),)).fetchone()
   row=db.execute('SELECT expires FROM playlist_binding_pending WHERE member=?',(Bindings.key(group,member),)).fetchone()
  if album and album[0]>=self.clock():
   aid=extract_album(getattr(message,'share_data',{}),getattr(message,'content','') or '')
   if aid:return '添加专辑 '+aid
   if getattr(message,'share_data',{}) or '分享专辑' in (getattr(message,'content','') or ''):return '添加专辑'
   return None
  if not row or row[0]<self.clock():return None
  pid=extract_playlist(getattr(message,'share_data',{}),getattr(message,'content','') or '')
  return '绑定歌单 '+pid if pid else None
 def pool(self):
  with closing(sqlite3.connect(self.path)) as db,db:
   due=db.execute('SELECT owner,pid FROM playlists WHERE updated<? ORDER BY updated LIMIT 2',(self.clock()-21600,)).fetchall()
  for owner,pid in due:
   try:
    title,songs=self.fetcher(pid)
    with closing(sqlite3.connect(self.path)) as db,db:db.execute('UPDATE playlists SET title=?,songs=?,updated=? WHERE owner=? AND pid=?',(title,json.dumps(songs,ensure_ascii=False),self.clock(),owner,pid))
   except Exception:
    # Retain last complete result; postpone this source so it can't starve others.
    with closing(sqlite3.connect(self.path)) as db,db:db.execute('UPDATE playlists SET updated=? WHERE owner=?',(self.clock(),owner))
  with closing(sqlite3.connect(self.path)) as db:
   albums=db.execute('SELECT owner,aid FROM albums WHERE updated<? ORDER BY updated LIMIT ?',(self.clock()-21600,max(0,2-len(due)))).fetchall()
  for owner,aid in albums:
   try:
    title,rows=self.album_fetcher(aid)
    with closing(sqlite3.connect(self.path)) as db,db:db.execute('UPDATE albums SET title=?,songs=?,updated=? WHERE owner=? AND aid=?',(title,json.dumps(rows,ensure_ascii=False),self.clock(),owner,aid))
   except Exception:
    with closing(sqlite3.connect(self.path)) as db,db:db.execute('UPDATE albums SET updated=? WHERE owner=? AND aid=?',(self.clock(),owner,aid))
  songs={}
  with closing(sqlite3.connect(self.path)) as db:
   for raw, in db.execute('SELECT songs FROM playlists UNION ALL SELECT songs FROM albums'):
    for song in json.loads(raw):songs.setdefault(song['mid'],song)
   for mid,raw in db.execute('SELECT mid,song FROM tracks'):
    if mid not in songs:songs[mid]=json.loads(raw)
  return songs
 def pick(self,group):
  songs=self.pool()
  if not songs:raise ValueError('暂无音乐推荐池，绑定QQ后可通过「绑定歌单」「添加专辑」或「添加单曲」加入曲目。')
  with closing(sqlite3.connect(self.path)) as db,db:
   db.execute('BEGIN IMMEDIATE')
   pending=db.execute('SELECT mid FROM music_pending WHERE group_id=?',(group,)).fetchone()
   if pending and pending[0] in songs:return songs[pending[0]]
   seen={x[0] for x in db.execute('SELECT mid FROM music_history WHERE group_id=?',(group,))}
   available=list(songs.keys()-seen)
   if not available:
    db.execute('DELETE FROM music_history WHERE group_id=?',(group,));available=list(songs)
   mid=random.SystemRandom().choice(available)
   db.execute('INSERT OR REPLACE INTO music_pending VALUES(?,?)',(group,mid))
  return songs[mid]
 def delivered(self,group,mid):
  with closing(sqlite3.connect(self.path)) as db,db:
   db.execute('INSERT OR IGNORE INTO music_history VALUES(?,?)',(group,mid));db.execute('DELETE FROM music_pending WHERE group_id=? AND mid=?',(group,mid))
 def card(self,group):
  song=self.pick(group);image=Image.new('RGB',(1100,450),'#F0F8F8');draw=ImageDraw.Draw(image)
  draw.rounded_rectangle((20,20,1080,430),20,fill='white')
  draw.rectangle((45,65,365,385),fill='#D2EBEB')
  if song.get('album'):
   try:
    url='https://y.gtimg.cn/music/photo_new/T002R300x300M000'+song['album']+'.jpg'
    with urllib.request.urlopen(url,timeout=5) as r:data=r.read(1024*1024)
    with Image.open(BytesIO(data)) as art:image.paste(ImageOps.fit(art.convert('RGB'),(320,320)),(45,65))
   except Exception:pass
  for y,value,size,color in [(65,'小师妹 · 音乐推荐',30,'#127C83'),(135,song['title'],36,'#18323C'),(245,song['artist'],25,'#52707D'),(335,'来自大家添加的QQ音乐曲目',22,'#52707D')]:
   font=ImageFont.truetype(str(self.font),size)
   lines=[''];
   for char in value:
    if draw.textlength(lines[-1]+char,font=font)>650:lines.append('')
    lines[-1]+=char
   for i,line in enumerate(lines[:2]):draw.text((400,y+i*(size+7)),line,font=font,fill=color)
  out=BytesIO();image.save(out,format='PNG');return MusicCard(out.getvalue(),song)
