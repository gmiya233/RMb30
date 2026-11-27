"""Arcade radar drawing; values are calibrated data, never inferred from level."""
import math
from functools import lru_cache
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
from typing import NamedTuple

BG='#080C18';PANEL='#111729';INK='#F6F7FC';MUTED='#A9B5CA';LINE='#343C54';PINK='#FF709D'
AXES=('物量','多押','爆发','变速','长条','滑键')
COLORS=('#FA8EDF','#C9FF73','#FFE398','#7DE6FF','#BCB0FF','#FFAD85')


class RadarPalette(NamedTuple):
    ink: str = INK
    muted: str = MUTED
    line: str = LINE
    trace: str = PINK
    interior: str = '#02040B'
    fill: tuple = (251,50,109,90)
    over: str = '#FFE09D'
    axes: tuple = COLORS


DEFAULT_PALETTE = RadarPalette()

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

def draw_radar(image,xy,row,font_path,chart_level,*,palette=DEFAULT_PALETTE,center_hell=False,
               show_reference=True,axis_icon=None):
    x0,y0=xy;draw=ImageDraw.Draw(image);values=radar_values(row)
    if values is None or chart_level is None or not 0<chart_level<=16:
        label(draw,x0+170,y0+215,'暂无雷达数据',font_path,30,palette.muted,350)
        label(draw,x0+105,y0+267,'谱面待解析，歌曲与排行仍可查询',font_path,21,palette.muted,480)
        return
    frame=70 if chart_level<10 else 10*chart_level-40
    cx=x0+300;cy=y0+260;base=108;maximum=172
    theta=[math.pi/2-i*math.tau/6 for i in range(6)]
    def points(radii):return [(cx+math.cos(t)*r,cy-math.sin(t)*r) for t,r in zip(theta,radii)]
    boundary=points([base]*6);draw.polygon(boundary,fill=palette.interior)
    for frac in (.5,1):
        pp=points([base*frac]*6);draw.line(pp+[pp[0]],fill=palette.ink if frac==1 else palette.line,width=2)
    for end in boundary:draw.line([(cx,cy),end],fill=palette.line,width=1)
    radii=[min(maximum,v/frame*base) for v in values];poly=points(radii)
    overlay=Image.new('RGBA',image.size);od=ImageDraw.Draw(overlay)
    od.polygon(poly,fill=palette.fill);image.paste(overlay,(0,0),overlay);draw=ImageDraw.Draw(image)
    draw.line(poly+[poly[0]],fill=palette.trace,width=3,joint='curve')
    for i,((x,y),v,t) in enumerate(zip(poly,values,theta)):
        over=v>frame+1e-9;color=palette.over if over else palette.trace
        draw.ellipse((x-4,y-4,x+4,y+4),fill=color)
        if v/frame*base>maximum:
            ux,uy=math.cos(t),-math.sin(t);tip=(x+ux*11,y+uy*11)
            draw.line([(x-ux*7-uy*5,y-uy*7+ux*5),tip,(x-ux*7+uy*5,y-uy*7-ux*5)],fill=color,width=2)
        lx=cx+math.cos(t)*225;ly=cy-math.sin(t)*225
        f=face(str(font_path),25);name=AXES[i];w=draw.textlength(name,font=f)
        draw.text((lx-w/2,ly-14),name,font=f,fill=palette.axes[i],anchor='lt')
        if axis_icon is not None:
            side=1 if i in (1,2) else -1
            axis_icon(image,i,(round(lx+side*(w/2+20)),round(ly-3)),palette.axes[i])
        number=f'{v:.1f}';nf=face(str(font_path),24);nw=draw.textlength(number,font=nf)
        draw.text((lx-nw/2,ly+17),number,font=nf,fill=color if over else palette.ink,anchor='lt')
        if over:
            bx=lx+34;by=ly-17
            if bx+72>x0+594:bx=lx-110
            draw.rectangle((bx,by,bx+72,by+30),fill='#290B16',outline='#9C2943',width=2)
            if center_hell:
                font=face(str(font_path),21)
                mask,offset=font.getmask2('HELL',mode='L',anchor='lt')
                left,top,right,bottom=mask.getbbox()
                glyph_x=offset[0]+(left+right-1)/2
                glyph_y=offset[1]+(top+bottom-1)/2
                draw.text((round(int(bx)+36-glyph_x),round(int(by)+15-glyph_y)),
                          'HELL',font=font,fill='#FFE6ED',anchor='lt')
            else:
                label(draw,bx+9,by+4,'HELL',font_path,21,'#FFE6ED',63)
    if show_reference:
        label(draw,x0+30,y0+535,f'等级参考框 {frame:g} · 超框数值不封顶',font_path,19,palette.muted,540)
    if row['radar'].get('source_format')=='IMD':
        label(draw,x0+30,y0+567,'IMD 变速暂按 0 显示',font_path,18,palette.muted,540)
    elif row['radar'].get('source_notice'):
        label(draw,x0+30,y0+567,row['radar']['source_notice'],font_path,18,palette.muted,540)
