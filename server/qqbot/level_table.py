"""Level chart sheets grouped by the dominant existing radar axis, not decimal grades."""
from collections import OrderedDict
from datetime import datetime
from io import BytesIO
import math
import re
from zoneinfo import ZoneInfo
from PIL import Image, ImageDraw, ImageFont, ImageOps
from b30_data import level
from song_search import artwork

# Published radar storage order: notes, chord, peak, speed, hold, slide.
GROUPS = ((0,'物量','#C75EA1'),(1,'同押','#5E8631'),(2,'爆发','#B77920'),
          (4,'长按复合','#7968B2'),(5,'滑键','#C2734A'),(3,'变速','#25849E'),(None,'待分析','#7C8897'))


def dominant(chart):
    values = (chart.get('radar') or {}).get('values')
    if not isinstance(values,list) or len(values)!=6 or any(type(v) not in (int,float) or not math.isfinite(v) or v<0 for v in values) or max(values)<=0:
        return None
    # Stable ties follow the display order; no invented decimal difficulty.
    return max((i for i,_,_ in GROUPS if i is not None),key=lambda i:values[i])


def select(charts, value):
    if not re.fullmatch(r'(?:[1-9]|1[0-5])',value):
        raise ValueError('用法：等级 14（填写1～15的整数等级）')
    target=int(value)
    groups=OrderedDict((axis,[]) for axis,_,_ in GROUPS)
    for chart in charts:
        if chart.get('search_visible',True) is not True:
            continue
        grade=level(chart.get('level'),chart.get('version'))
        if grade is not None and target<=grade<target+1:
            groups[dominant(chart)].append(chart)
    for rows in groups.values():
        rows.sort(key=lambda row:(str(row.get('title','')).casefold(),row['cid']))
    return groups


def render(groups, value, font_path, roots):
    total=sum(map(len,groups.values()))
    if total>1000:
        raise ValueError('该等级谱面过多，暂时无法生成完整总表。')
    columns=8 if total<=160 else 10
    width=1440;left=152;cell=(width-left-32)//columns;cover=cell-20;row_height=cover+66
    blocks=[(axis,name,color,groups[axis]) for axis,name,color in GROUPS if groups[axis]]
    height=260+sum(70+math.ceil(len(rows)/columns)*row_height+22 for _,_,_,rows in blocks)+100
    image=Image.new('RGB',(width,height),'#F4FAFC');draw=ImageDraw.Draw(image)
    fonts={size:ImageFont.truetype(str(font_path),size) for size in (17,19,22,25,28,38,48,60)}
    def text(x,y,value,size=22,color='#1B3442',limit=None):
        value=str(value).replace('\n',' ');font=fonts[size]
        if limit:
            original=value
            while value and draw.textlength(value,font=font)>limit:
                value=value[:-1]
            if value!=original:
                while value and draw.textlength(value+'…',font=font)>limit:value=value[:-1]
                value+='…'
        draw.text((x,y),value,font=font,fill=color,anchor='lt')
    draw.polygon([(0,0),(850,0),(740,18),(0,18)],fill='#30C6BC')
    draw.polygon([(1080,0),(1440,0),(1440,155),(1398,155),(1398,30),(1080,30)],fill='#EAA3C3')
    text(38,49,'LEVEL '+value,60)
    text(42,126,f'谱面定级总表  /  共 {total} 张谱面',28,'#387B86')
    text(1100,60,'节奏大师',38,'#087C8B')
    text(1104,112,'RHYTHM MASTER',17,'#387B86')
    text(42,185,'按六维最高项归类 · 每张谱面只展示一次 · 不代表难度高低',25,'#586C7D')
    y=247
    for axis,name,color,rows in blocks:
        block_height=70+math.ceil(len(rows)/columns)*row_height
        draw.rounded_rectangle((24,y,width-24,y+block_height),radius=18,fill='white')
        draw.rounded_rectangle((40,y+18,46,y+block_height-18),radius=3,fill=color)
        text(64,y+24,name,28,color,limit=width-140)
        text(width-178,y+28,f'{len(rows)} 张谱面',22,'#64798B')
        for index,row in enumerate(rows):
            x=left+(index%columns)*cell;top=y+65+(index//columns)*row_height
            art=artwork([row],roots)
            if art:
                thumb=ImageOps.fit(art,(cover,cover));image.paste(thumb,(x,top));art.close();thumb.close()
            else:
                draw.rectangle((x,top,x+cover,top+cover),fill='#E1EFF2')
                text(x+10,top+cover//2-12,'暂无曲绘',19,'#547D88',cover-20)
            draw.rectangle((x,top,x+cover,top+cover),outline='#CCDCE4',width=1)
            key=row.get('keys');badge=f'{key}K' if key in (4,5,6) else ''
            if badge:
                draw.rectangle((x,top,x+42,top+27),fill=color);text(x+4,top+3,badge,19,'white')
            text(x,top+cover+7,row.get('title') or '未命名',19,limit=cover)
            text(x,top+cover+33,f"ID {row['cid']}",17,'#64798B',limit=cover)
        y+=block_height+22
    stamp=datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d')
    text(40,height-82,'定级沿用 SC / MaV 折算规则；隐藏谱面不展示。缺图使用占位，不补无关曲绘。',19,'#586C7D')
    text(40,height-45,f'查询：查歌 谱面ID  /  谱面预览 谱面ID     ·     {stamp}  ·  小师妹',19,'#586C7D')
    out=BytesIO();image.save(out,format='PNG',optimize=True)
    if out.tell()>4*1024*1024:
        out=BytesIO();image.quantize(colors=256,method=Image.Quantize.FASTOCTREE).save(out,format='PNG',optimize=True)
    if out.tell()>4*1024*1024:raise ValueError('等级总表超过发送大小限制。')
    return out.getvalue()


class LevelTables:
    COMMANDS={'等级','等级表'}
    def __init__(self,queries,font,roots):
        self.queries,self.font,self.roots=queries,font,roots
    def execute(self,content):
        from b30_commands import command_parts
        _,value=command_parts(content)
        try:
            groups=select(self.queries.catalogue()[1].values(),value)
            if not any(groups.values()):return f'当前可查询曲库没有 {value} 级谱面。'
            return render(groups,value,self.font,self.roots)
        except ValueError as error:
            return str(error)
