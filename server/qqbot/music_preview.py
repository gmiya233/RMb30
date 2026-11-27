"""Official anonymous QQ Music trial clips -> transient QQ voice messages."""
import asyncio
import base64
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
import uuid

MAX_BYTES=2*1024*1024


def json_get(url):
    req=urllib.request.Request(url,headers={'Referer':'https://y.qq.com/','User-Agent':'Mozilla/5.0'})
    with urllib.request.urlopen(req,timeout=8) as response:raw=response.read(MAX_BYTES+1)
    if len(raw)>MAX_BYTES:raise ValueError('Metadata exceeds limit')
    return json.loads(raw)


def preview_url(mid):
    if not re.fullmatch(r'[A-Za-z0-9]{1,32}',mid):raise ValueError('Invalid song identity')
    detail=json_get('https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?'+urllib.parse.urlencode({'songmid':mid,'tpl':'yqq_song_detail','format':'json'}))
    if detail.get('code')!=0 or not detail.get('data'):return None
    row=detail['data'][0];file=row.get('file',{});media=file.get('media_mid','')
    if not re.fullmatch(r'[A-Za-z0-9]{1,32}',media) or not file.get('size_try'):return None
    filename='RS02'+media+'.mp3'
    data={'req_0':{'module':'vkey.GetVkeyServer','method':'CgiGetVkey','param':{
        'guid':str(uuid.uuid4().int%10**10),'filename':[filename],'songmid':[mid],
        'songtype':[0],'uin':'0','loginflag':0,'platform':'20'}},'comm':{'uin':0,'format':'json','ct':24,'cv':0}}
    result=json_get('https://u.y.qq.com/cgi-bin/musicu.fcg?'+urllib.parse.urlencode({'data':json.dumps(data,separators=(',',':'))}))
    response=result.get('req_0',{})
    if response.get('code')!=0:return None
    info=response.get('data',{});purl=(info.get('midurlinfo')or[{}])[0].get('purl','')
    parsed=urllib.parse.urlsplit(purl)
    if parsed.netloc or parsed.scheme or parsed.path!=filename:return None
    for base in info.get('sip',[]):
        b=urllib.parse.urlsplit(base)
        if (b.scheme in ('http','https') and (b.hostname or '').endswith('.qqmusic.qq.com')
                and not b.username and not b.password and b.port in (None,80,443)):
            return urllib.parse.urljoin('https://'+b.hostname+'/',purl)
    return None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None


def download(url,path):
    parsed=urllib.parse.urlsplit(url)
    if parsed.scheme!='https' or not (parsed.hostname or '').endswith('.qqmusic.qq.com') or parsed.username or parsed.password:
        raise ValueError('Invalid official trial host')
    req=urllib.request.Request(url,headers={'Referer':'https://y.qq.com/','User-Agent':'Mozilla/5.0'})
    # Do not follow an arbitrary redirect or expose expiring playback URLs in logs.
    with urllib.request.build_opener(NoRedirect).open(req,timeout=10) as r:
        raw=r.read(MAX_BYTES+1)
    if not 0<len(raw)<=MAX_BYTES or not (raw.startswith(b'ID3') or (len(raw)>2 and raw[0]==255 and raw[1]&224==224)):
        raise ValueError('Invalid MP3 trial clip')
    path.write_bytes(raw)


class MusicPreview:
    def __init__(self,root,resolver=preview_url,downloader=download,ffmpeg=None):
        self.root=Path(root)
        if self.root.is_symlink():raise ValueError('Preview directory cannot be a symlink')
        self.root.mkdir(parents=True,exist_ok=True);self.root.chmod(0o700)
        self.resolver=resolver;self.downloader=downloader;self.ffmpeg=ffmpeg;self.slot=asyncio.Semaphore(1)
        # Previous process may have been terminated before its finally block ran.
        for child in self.root.glob('preview-*'):
            if child.is_dir() and not child.is_symlink() and child.resolve().parent==self.root.resolve():shutil.rmtree(child)

    def prepare(self,mid,directory):
        url=self.resolver(mid)
        if not url:return None
        source=directory/'trial.mp3';target=directory/'voice.mp3';self.downloader(url,source)
        return self.transcode(source,target)

    def transcode(self,source,target,start=0):
        ffmpeg=self.ffmpeg
        if not ffmpeg:
            import imageio_ffmpeg
            ffmpeg=imageio_ffmpeg.get_ffmpeg_exe()
        subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-nostdin','-protocol_whitelist','file,pipe',
            '-ss',str(start),'-i',str(source),'-t','30','-map_metadata','-1','-vn','-ac','1','-ar','24000',
            '-codec:a','libmp3lame','-b:a','48k','-y',str(target)],
            check=True,timeout=25,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if not target.is_file() or not 0<target.stat().st_size<=MAX_BYTES:raise ValueError('Invalid voice preview size')
        return target

    def source_key(self,item):
        return item['mid']

    async def send(self,request,song,message_id=None,allowed=lambda:True,sequence=2):
        try:
            async with self.slot:
                if not allowed():return False
                # No permanent audio or upload-info cache. Clean on success, failure and cancellation.
                with tempfile.TemporaryDirectory(prefix='preview-',dir=self.root) as tmp:
                    path=await asyncio.to_thread(self.prepare,self.source_key(song),Path(tmp))
                    if path is None:
                        print('Music voice: no public trial clip',flush=True);return False
                    if not allowed():return False
                    data=await asyncio.to_thread(path.read_bytes)
                    media=await request('files',{'file_type':3,'file_name':'music-preview.mp3',
                        'file_data':base64.b64encode(data).decode('ascii'),'srv_send_msg':False})
                    if not isinstance(media,dict) or not media.get('file_info'):raise TimeoutError('Voice upload acknowledgement missing')
                    if not allowed():return False
                    payload={'msg_type':7,'content':' ','media':{'file_info':media['file_info']}}
                    if message_id:payload.update(msg_id=message_id,msg_seq=sequence)
                    result=await request('messages',payload)
                    if not isinstance(result,dict) or not result.get('id'):raise TimeoutError('Voice acknowledgement missing')
                print('Music voice: accepted; temporary audio cleared',flush=True);return True
        except Exception as e:
            # The recommendation image already succeeded; never resend it because audio failed.
            print('Music voice: unavailable ('+type(e).__name__+'); temporary audio cleared',flush=True)
            return False
