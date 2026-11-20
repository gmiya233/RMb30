"""Read public QQ Music album metadata using an official, fixed endpoint."""
import html
import json
import re
import urllib.parse
import urllib.request
import urllib.error


def share_text(value):
    return html.unescape(value.replace('\\:',':').replace('\\_','_').replace('\\&','&').replace('\\/','/'))


def short_code(value):
    u=urllib.parse.urlsplit(value)
    if (u.scheme!='https' or not re.fullmatch(r'c[0-9]*\.y\.qq\.com',u.hostname or '')
            or u.username or u.password or u.port not in (None,443) or u.path!='/base/fcgi-bin/u'):
        raise ValueError('不是QQ音乐官方短链')
    codes=urllib.parse.parse_qs(u.query).get('__',[])
    if len(codes)!=1 or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',codes[0]):raise ValueError('QQ音乐短链编号无效')
    return codes[0]


def album_share_reference(value):
    """Pure extraction: network work happens later in the command worker."""
    try:return album_id(value)
    except ValueError:
        return 'https://c.y.qq.com/base/fcgi-bin/u?__='+short_code(value)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None


def resolve_short(value, reference=None):
    reference = reference or album_id
    for _ in range(3):
        code=short_code(value)
        # c.y.qq.com serves the same official short-link keys, avoiding slow regional c6 routes.
        url='https://c.y.qq.com/base/fcgi-bin/u?__='+code
        try:
            with urllib.request.build_opener(_NoRedirect).open(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=6) as response:
                target=response.headers.get('Location')
        except urllib.error.HTTPError as e:
            if e.code not in (301,302,303,307,308):raise ValueError('QQ音乐短链暂时无法解析') from e
            target=e.headers.get('Location')
        except (urllib.error.URLError,OSError) as e:
            raise ValueError('QQ音乐短链暂时无法解析，请稍后重试或复制详情页链接。') from e
        if not target:raise ValueError('QQ音乐短链未返回详情页地址')
        target=urllib.parse.urljoin(url,share_text(target))
        try:return reference(target)
        except ValueError:
            short_code(target) # Reject arbitrary hosts before any further request.
            value=target
    raise ValueError('QQ音乐短链跳转次数过多')


def resolve_album_input(value):
    value=share_text(value.strip())
    if re.fullmatch(r'[A-Za-z0-9]{1,32}',value):return album_id(value)
    urls=re.findall(r'https://[^\s<>"\x27]+',value)
    if len(urls)>4:raise ValueError('请一次添加一张专辑')
    refs=set()
    for url in urls:
        try:refs.add(album_share_reference(url.rstrip('，。；;）)]}》')))
        except ValueError:continue
    if not refs:raise ValueError('没有找到QQ音乐专辑链接，请直接粘贴完整的专辑分享文本。')
    ids={resolve_short(ref) if ref.startswith('https://') else ref for ref in refs}
    if len(ids)!=1:raise ValueError('检测到多张不同专辑，请一次只发送一张专辑。')
    return ids.pop()


def album_id(value):
    value=html.unescape(value.strip())
    if re.fullmatch(r'[A-Za-z0-9]{1,32}',value):return value
    u=urllib.parse.urlsplit(value)
    if (u.scheme!='https' or u.hostname not in ('y.qq.com','i.y.qq.com','i2.y.qq.com')
            or u.username or u.password or u.port not in (None,443)):
        raise ValueError('请发送QQ音乐官方专辑链接、albumMid或数字专辑ID。')
    if u.hostname=='y.qq.com':
        m=re.fullmatch(r'/n/ryqq(?:_v2)?/albumDetail/([A-Za-z0-9]{1,32})/?',u.path)
        if not m:m=re.fullmatch(r'/n/yqq/album/([A-Za-z0-9]{1,32})\.html',u.path)
        if m:return m[1]
    if u.path in ('/n3/other/pages/details/album.html','/n2/m/share/details/album.html'):
        params=urllib.parse.parse_qs(u.query)
        for key in ('albummid','albumMid','albumid','albumId','id'):
            if key in params:
                ids=params[key]
                if len(ids)==1 and re.fullmatch(r'[A-Za-z0-9]{1,32}',ids[0]):return ids[0]
                raise ValueError('专辑链接编号无效')
    raise ValueError('请发送QQ音乐专辑链接，不能使用歌单、歌曲或个人主页链接。')


def fetch_album(aid):
    if not re.fullmatch(r'[A-Za-z0-9]{1,32}',aid):raise ValueError('无效专辑编号')
    key='albumid' if aid.isdigit() else 'albummid'
    url='https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg?format=json&'+key+'='+aid
    request=urllib.request.Request(url,headers={'Referer':'https://y.qq.com/','User-Agent':'Mozilla/5.0'})
    with urllib.request.urlopen(request,timeout=8) as r:raw=r.read(4*1024*1024+1)
    if len(raw)>4*1024*1024:raise ValueError('专辑过大')
    d=json.loads(raw)
    if d.get('code')!=0 or not isinstance(d.get('data'),dict):raise ValueError('专辑暂时无法读取')
    info=d['data'];rows=info.get('list',[])
    total=int(info.get('total_song_num',info.get('total',-1)))
    if not isinstance(rows,list) or not rows or len(rows)!=total or total>5000:
        raise ValueError('未能获取完整专辑，没有保存部分曲目')
    songs={}
    for row in rows:
        mid=row.get('songmid','')
        if not isinstance(mid,str) or not re.fullmatch(r'[A-Za-z0-9]{1,32}',mid):
            raise ValueError('专辑歌曲信息不完整')
        album=row.get('albummid') or info.get('mid','')
        songs[mid]=dict(mid=mid,title=str(row.get('songname') or row.get('name') or '未命名')[:200],
            artist=' / '.join(str(s.get('name','')) for s in row.get('singer',[]))[:200],
            album=album if isinstance(album,str) and re.fullmatch(r'[A-Za-z0-9]{1,32}',album) else '')
    return str(info.get('name') or 'QQ音乐专辑')[:100],list(songs.values())
