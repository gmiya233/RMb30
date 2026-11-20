"""Arcade radar drawing; values are calibrated data, never inferred from level."""
import math
from functools import lru_cache
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

BG='#080C18';PANEL='#111729';INK='#F6F7FC';MUTED='#A9B5CA';LINE='#343C54';PINK='#FF709D'
AXES=('物量','多押','爆发','变速','长条','滑键')
COLORS=('#FA8EDF','#C9FF73','#FFE398','#7DE6FF','#BCB0FF','#FFAD85')

@lru_cache(maxsize=128)
def face(path,size):
    return ImageFont.truetype(str(path),size)

def label(draw,x,y,value,font_path,size=24,color=INK,width=1100):
    from ranking_queries import text
    value=text(value,200);f=face(str(font_path),size)
    while value and draw.textlength(value,font=f)>width:
        value=value[:-2]+'…' if len(value)>2 else ''
    draw.text((x,y),value,font=f,fill=color,anchor='lt')

def radar_values(row):
    record=row.get('radar')
    if not isinstance(record,dict):return None
    values=record.get('values')
    if not isinstance(values,list) or len(values)!=6 or any(type(v) not in (int,float) or not math.isfinite(v) or v<0 for v in values):return None
    return values

def draw_radar(image,xy,row,font_path,chart_level):
    x0,y0=xy;draw=ImageDraw.Draw(image);values=radar_values(row)
    if values is None or chart_level is None or not 0<chart_level<=16:
        label(draw,x0+170,y0+215,'暂无雷达数据',font_path,30,MUTED,350)
        label(draw,x0+105,y0+267,'谱面待解析，歌曲与排行仍可查询',font_path,21,MUTED,480)
        return
    frame=70 if chart_level<10 else 10*chart_level-40
    cx=x0+300;cy=y0+260;base=108;maximum=172
    theta=[math.pi/2-i*math.tau/6 for i in range(6)]
    def points(radii):return [(cx+math.cos(t)*r,cy-math.sin(t)*r) for t,r in zip(theta,radii)]
    boundary=points([base]*6);draw.polygon(boundary,fill='#02040B')
    for frac in (.5,1):
        pp=points([base*frac]*6);draw.line(pp+[pp[0]],fill=INK if frac==1 else LINE,width=2)
    for end in boundary:draw.line([(cx,cy),end],fill=LINE,width=1)
    radii=[min(maximum,v/frame*base) for v in values];poly=points(radii)
    overlay=Image.new('RGBA',image.size);od=ImageDraw.Draw(overlay)
    od.polygon(poly,fill=(251,50,109,90));image.paste(overlay,(0,0),overlay);draw=ImageDraw.Draw(image)
    draw.line(poly+[poly[0]],fill=PINK,width=3,joint='curve')
    for i,((x,y),v,t) in enumerate(zip(poly,values,theta)):
        over=v>frame+1e-9;color='#FFE09D' if over else PINK
        draw.ellipse((x-4,y-4,x+4,y+4),fill=color)
        if v/frame*base>maximum:
            ux,uy=math.cos(t),-math.sin(t);tip=(x+ux*11,y+uy*11)
            draw.line([(x-ux*7-uy*5,y-uy*7+ux*5),tip,(x-ux*7+uy*5,y-uy*7-ux*5)],fill=color,width=2)
        lx=cx+math.cos(t)*225;ly=cy-math.sin(t)*225
        f=face(str(font_path),25);name=AXES[i];w=draw.textlength(name,font=f)
        draw.text((lx-w/2,ly-14),name,font=f,fill=COLORS[i],anchor='lt')
        number=f'{v:.1f}';nf=face(str(font_path),24);nw=draw.textlength(number,font=nf)
        draw.text((lx-nw/2,ly+17),number,font=nf,fill=color if over else INK,anchor='lt')
        if over:
            bx=lx+34;by=ly-17
            if bx+72>x0+594:bx=lx-110
            draw.rectangle((bx,by,bx+72,by+30),fill='#290B16',outline='#9C2943',width=2)
            label(draw,bx+9,by+4,'HELL',font_path,21,'#FFE6ED',63)
    label(draw,x0+30,y0+535,f'等级参考框 {frame:g} · 超框数值不封顶',font_path,19,MUTED,540)
    if row['radar'].get('source_format')=='IMD':
        label(draw,x0+30,y0+567,'IMD 变速暂按 0 显示',font_path,18,MUTED,540)
    elif row['radar'].get('source_notice'):
        label(draw,x0+30,y0+567,row['radar']['source_notice'],font_path,18,MUTED,540)
