"""QQ official group multipart upload. No public media web server is needed."""
import asyncio,hashlib,math
from urllib.parse import urlsplit

async def upload_video(request,put,path):
 data=await asyncio.to_thread(path.read_bytes)
 if not 0<len(data)<=30*1024*1024:raise ValueError('Video exceeds limit')
 prepared=await request('upload_prepare',{'file_type':2,'file_size':str(len(data)),'file_name':'chart-preview.mp4','md5':hashlib.md5(data).hexdigest(),'sha1':hashlib.sha1(data).hexdigest(),'md5_10m':hashlib.md5(data[:10002432]).hexdigest()})
 if not isinstance(prepared,dict):raise ValueError('Missing upload preparation')
 if prepared.get('file_info'):return prepared
 upload_id=prepared['upload_id'];block=int(prepared['block_size']);parts=prepared.get('parts',[])
 if not 0<block<=16*1024*1024 or len(parts)>200:raise ValueError('Invalid upload blocks')
 if parts:
  parts=sorted(parts,key=lambda p:p['index'])
  count=math.ceil(len(data)/block);indexes=[p['index'] for p in parts]
  if indexes not in (list(range(count)),list(range(1,count+1))):raise ValueError('Incomplete upload parts')
  for offset,part in enumerate(parts):
   index=part['index'];chunk=data[offset*block:(offset+1)*block];url=part['presigned_url']
   parsed=urlsplit(url)
   if parsed.scheme!='https' or not parsed.hostname or parsed.username or parsed.password:raise ValueError('Invalid upload URL')
   # No bot Authorization header is forwarded to the storage URL.
   await put(url,chunk)
   await request('upload_part_finish',{'upload_id':upload_id,'part_index':index,'block_size':str(len(chunk)),'md5':hashlib.md5(chunk).hexdigest()})
 media=await request('files',{'file_type':2,'file_name':'chart-preview.mp4','srv_send_msg':False,'upload_id':upload_id})
 if not isinstance(media,dict) or not media.get('file_info'):raise TimeoutError('Video upload acknowledgement missing')
 return media
