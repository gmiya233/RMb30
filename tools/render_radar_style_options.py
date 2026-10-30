"""Three code-native SVG/PNG radar concepts using identical measured chart data."""
import base64
from html import escape
import json,math
from pathlib import Path
import zipfile
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'dist/radar-styles-2026-10-30'
SOURCE=ROOT/'dist/radar-study-2026-10-30-v4'
AXES=['notes','chord','peak','speed','hold','slide']
LABELS=['物量','同押','爆发','变速','长按复合','滑键']
AXIS_COLORS=['#fa8edf','#c9ff73','#ffe398','#7de6ff','#bcb0ff','#ffad85']
THETA=[math.pi/2-i*math.tau/6 for i in range(6)]
W,H=620,1100
PALETTES={
 'arcade':dict(bg='#080c18',ink='#f6f7fc',muted='#a9b5ca',line='#343c54',accent='#ff527d',title='街机霓虹',tag='01 / ARCADE',desc='黑底白框、彩色轴名、红色填充；最接近参考图的街机气质。'),
 'light':dict(bg='#ffffff',ink='#18313c',muted='#607782',line='#dce7eb',accent='#087f83',title='浅色竞技',tag='02 / COMPETITIVE',desc='白色底卡、青绿网格、金色超框点；适合现有 App 和群聊分享。'),
 'instrument':dict(bg='#0c1b29',ink='#e9f5fc',muted='#a0b4c3',line='#304556',accent='#5cdad6',title='深色仪表',tag='03 / INSTRUMENT',desc='深蓝底、青色描边、逐项数据条；适合看谱面结构与数值。'),
}


def rgba(color,alpha=1):
 color=color.lstrip('#')
 return tuple(int(color[i:i+2],16) for i in (0,2,4))+(round(alpha*255),)


class Scene:
 def __init__(self,bg,font_theme='default'):
  self.font_theme=font_theme
  self.image=Image.new('RGB',(W*2,H*2),bg)
  self.draw=ImageDraw.Draw(self.image,'RGBA')
  self.svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img"><title>Malody 谱面六维样式预览</title><rect width="620" height="1100" rx="24" fill="{bg}"/>']
 def line(self,points,color,width=1,alpha=1,dash=False):
  xy=[(x*2,y*2) for x,y in points]
  if dash:
   for (x,y),(xx,yy) in zip(points,points[1:]):
    distance=math.hypot(xx-x,yy-y)
    for at in range(0,math.ceil(distance),9):
     a=at/distance if distance else 0;b=min(1,(at+4)/distance) if distance else 0
     self.draw.line([(2*(x+(xx-x)*a),2*(y+(yy-y)*a)),(2*(x+(xx-x)*b),2*(y+(yy-y)*b))],fill=rgba(color,alpha),width=max(1,round(width*2)))
  else:self.draw.line(xy,fill=rgba(color,alpha),width=max(1,round(width*2)),joint='curve')
  pts=' '.join(f'{x:.2f},{y:.2f}' for x,y in points)
  self.svg.append(f'<polyline points="{pts}" fill="none" stroke="{color}" stroke-width="{width}" stroke-opacity="{alpha}" stroke-linejoin="round"'+(' stroke-dasharray="4 5"' if dash else '')+'/>')
 def polygon(self,points,color,alpha=1):
  self.draw.polygon([(x*2,y*2) for x,y in points],fill=rgba(color,alpha))
  pts=' '.join(f'{x:.2f},{y:.2f}' for x,y in points)
  self.svg.append(f'<polygon points="{pts}" fill="{color}" fill-opacity="{alpha}"/>')
 def rect(self,x,y,w,h,color,radius=0,alpha=1):
  self.draw.rounded_rectangle((x*2,y*2,(x+w)*2,(y+h)*2),radius=radius*2,fill=rgba(color,alpha))
  self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{color}" fill-opacity="{alpha}"/>')
 def circle(self,x,y,r,color,alpha=1):
  self.draw.ellipse(((x-r)*2,(y-r)*2,(x+r)*2,(y+r)*2),fill=rgba(color,alpha))
  self.svg.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="{r}" fill="{color}" fill-opacity="{alpha}"/>')
 def text(self,x,y,text,size,color,align='left',bold=False,mono=False,display=False):
  path='C:/Windows/Fonts/consolab.ttf' if mono and bold else 'C:/Windows/Fonts/consola.ttf' if mono else 'C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc'
  family='Consolas,monospace' if mono else 'Microsoft YaHei,system-ui,sans-serif'
  if self.font_theme=='arcade-v2':
   latin=all(ord(c)<128 for c in str(text))
   path='C:/Windows/Fonts/impact.ttf' if display and latin else 'C:/Windows/Fonts/bahnschrift.ttf' if latin else 'C:/Windows/Fonts/Dengb.ttf' if bold else 'C:/Windows/Fonts/Deng.ttf'
   family='Impact,Arial,sans-serif' if display and latin else 'Bahnschrift,Arial,sans-serif' if latin else 'DengXian,Microsoft YaHei,sans-serif'
  font=ImageFont.truetype(path,round(size*2))
  if path.endswith('bahnschrift.ttf'):
   font.set_variation_by_axes([700 if bold else 400,100])
  width=self.draw.textlength(str(text),font=font)/2
  left=x-width/2 if align=='center' else x-width if align=='right' else x
  self.draw.text((left*2,y*2),str(text),font=font,fill=color,anchor='lt')
  anchor={'left':'start','center':'middle','right':'end'}[align]
  self.svg.append(f'<text x="{x}" y="{y+size*.88:.2f}" text-anchor="{anchor}" fill="{color}" font-size="{size}" font-family="{family}" font-weight="{700 if bold else 400}">{escape(str(text))}</text>')
 def hell(self,x,y,width=94,height=34):
  self.rect(x,y,width,height,'#290b16',radius=1)
  self.line([(x,y),(x+width,y),(x+width,y+height),(x,y+height),(x,y)],'#9c2943',2.5)
  self.text(x+width/2,y+3,'HELL',26,'#ffe6ed',align='center',bold=True,display=True)
 def save(self,name):
  self.image.resize((W,H),Image.Resampling.LANCZOS).save(OUT/(name+'.png'))
  (OUT/(name+'.svg')).write_text(''.join(self.svg)+'</svg>',encoding='utf-8')
 def markup(self):return ''.join(self.svg)+'</svg>'


def make(style,row,reference):
 p=PALETTES[style];s=Scene(p['bg'],font_theme='arcade-v2' if style=='arcade' else 'default');level=row['normalized_level'];frame=10*level-40
 values=[row[a]/reference[a]*100 if reference[a] else 0 for a in AXES]
 if row['source_format']=='IMD':values[3]=0
 # The viewport is identical for all three options. Only drawing is clipped;
 # off-canvas scores keep full labels and receive an outward arrow.
 cx,cy,base=310,421,116
 def points(radii):return [(cx+math.cos(t)*r,cy-math.sin(t)*r) for t,r in zip(THETA,radii)]
 if style=='arcade':
  for y in range(14,1060,8):
   for x in range(12,608,8):s.rect(x,y,1,1,'#8f9cbb',alpha=.08)
  s.polygon([(0,0),(410,0),(345,18),(0,18)],'#273cb7')
  s.polygon([(554,0),(586,0),(620,57),(620,111)],'#f29f4b')
  s.polygon([(0,620),(14,645),(14,718),(0,693)],'#f29f4b')
  s.text(34,32,'NOTES RADAR',29,p['ink'],bold=True,display=True)
 elif style=='light':
  s.rect(30,30,6,25,p['accent'],radius=3)
  s.text(49,31,'谱面六维',21,p['ink'],bold=True)
  s.rect(489,29,99,34,'#e5f4f2',radius=17)
  s.text(538,36,f"{row['keys']}K / Lv.{level:g}",13,p['accent'],align='center',bold=True)
 else:
  s.text(34,32,'CHART / '+str(row['cid']),16,p['muted'],mono=True)
  s.text(586,32,'06 AXES',15,p['accent'],align='right',mono=True)
  s.line([(34,65),(586,65)],p['line'])
  for x in range(36,590,12):s.line([(x,660),(x,663 if x%3 else 668)],p['line'])
 title='Yooh - RPG' if row['cid']==604370 else row['title']
 if len(title)>30:title=title[:29]+'…'
 s.text(34,83,title,32,p['ink'],bold=True)
 s.text(35,133,f"Lv.{level:g}  /  {row['keys']}K  /  CID {row['cid']}",15,p['muted'],mono=True)
 s.text(586,137,f'等级框 {frame:g}',14,p['accent'],align='right',bold=True)
 # A deliberately dark center for the arcade option follows the reference.
 boundary=points([base]*6)
 if style=='arcade':s.polygon(boundary,'#02040b')
 elif style=='instrument':
  for i in (0,2,4):s.polygon([(cx,cy),boundary[i],boundary[(i+1)%6]],'#102e3d',.40)
 ticks=(.5,1) if style=='arcade' else (.25,.5,.75,1)
 for frac in ticks:
  pp=points([base*frac]*6);pp.append(pp[0])
  s.line(pp,'#f0f5ff' if style=='arcade' and frac==1 else '#67bdbf' if style=='instrument' and frac==1 else p['line'],2.4 if frac==1 else 1,.95 if frac==1 else .70,dash=style=='arcade' and frac!=1)
 for i,t in enumerate(THETA):
  end=points([base]*6)[i]
  s.line([(cx,cy),end],'#dde6ff' if style=='arcade' else p['line'],1.1,.37 if style=='arcade' else .8)
  outer=(cx+math.cos(t)*224,cy-math.sin(t)*224)
  s.line([end,outer],p['line'],1,.7,dash=True)
  labelpos=(cx+math.cos(t)*249,cy-math.sin(t)*249)
  color=AXIS_COLORS[i] if style=='arcade' else p['ink']
  if style=='arcade' and values[i]>frame+1e-9:
   # Keep the HELL tag by its axis name, never in the numeric tiles.
   # Slide the label+tag group inward if the right margin would be exceeded.
   label_width=len(LABELS[i])*22
   label_x,label_y=labelpos[0],labelpos[1]-10
   shift=max(0,label_x+label_width/2+8+94-(W-14))
   label_x-=shift
   if i==1:label_y-=14  # Clear the chord vertex and its value label.
   s.text(label_x,label_y,LABELS[i],22,color,align='center',bold=True)
   s.hell(label_x+label_width/2+8,label_y-6)
  else:
   s.text(labelpos[0],labelpos[1]-10,LABELS[i],22 if style=='arcade' else 19,color,align='center',bold=style=='arcade')
  if style=='instrument':
   s.circle(end[0],end[1],2.2,p['accent'])
 # Max on-screen radius leaves label room. Values themselves are never capped.
 radii=[min(208,v/frame*base) for v in values];poly=points(radii);closed=poly+[poly[0]]
 if style=='arcade':
  s.polygon(poly,'#fb326d',.38)
  s.line(closed,'#ff3f77',12,.05);s.line(closed,'#ff3f77',7,.10)
  s.line(closed,'#ff709d',2,.95)
 elif style=='light':
  s.polygon(poly,'#18a0a0',.13);s.line(closed,p['accent'],2.3)
 else:
  s.polygon(poly,'#60e3dc',.08);s.line(closed,p['accent'],2.4)
  for x,y in poly:s.circle(x,y,3.2,p['bg'])
 for i,((x,y),value,t) in enumerate(zip(poly,values,THETA)):
  over=value>frame+1e-9;clipped=value/frame*base>208
  color='#ffe09d' if style=='arcade' and over else '#ab6a04' if style=='light' and over else '#ffd17c' if over else p['accent']
  if over:
   s.circle(x,y,13,color,.09);s.circle(x,y,8,color,.11)
  s.circle(x,y,3.8,color)
  if clipped:
   # Outward open arrow: the full number is supplied below and in the table.
   ux,uy=math.cos(t),-math.sin(t);tip=(x+ux*11,y+uy*11)
   s.line([(x-ux*7-uy*5,y-uy*7+ux*5),tip,(x-ux*7+uy*5,y-uy*7-ux*5)],color,2)
  if over:
   s.text(x+math.cos(t)*15,y-math.sin(t)*15-8,f'{value:.1f}',16,color,align='center',bold=True,mono=True)
 # Key conveys both color and numeric overflow, independent of color perception.
 s.circle(38,705,3,p['accent']);s.text(49,695,f'外框 {frame:g}',14,p['muted'])
 count=sum(v>frame for v in values)
 if style=='arcade':
  pass  # HELL is attached only to the relevant axis names above.
 else:s.text(586,695,f'{count} 项超框',14,'#ab6a04' if style=='light' else '#ffdc98',align='right',bold=True)
 if style in ('arcade','light'):
  for i,(key,label,value) in enumerate(zip(AXES,LABELS,values)):
   col=i%2;line=i//2;x=34+col*284;y=738+line*88
   tile='#111729' if style=='arcade' else '#f3f8f9'
   s.rect(x,y,268,76,tile,radius=2 if style=='arcade' else 13)
   if style=='arcade':s.rect(x,y,3,76,AXIS_COLORS[i])
   s.text(x+16,y+10,label,16 if style=='arcade' else 14,AXIS_COLORS[i] if style=='arcade' else p['muted'],bold=style=='arcade')
   color='#ab6a04' if style=='light' and value>frame else '#ffdc98' if value>frame else p['ink']
   s.text(x+16,y+32,f'{value:.1f}',29 if style=='arcade' else 25,color,bold=True,mono=True)
   if value>frame:
    if style!='arcade':s.text(x+249,y+43,'超框',12,color,align='right',bold=True)
 else:
  bar_max=max(frame*2,max(values))
  for i,(key,label,value) in enumerate(zip(AXES,LABELS,values)):
   y=739+i*43
   s.text(34,y,label,14,p['muted'])
   s.rect(144,y+7,326,5,'#233a4a',radius=2)
   if value>0:s.rect(144,y+7,326*value/bar_max,5,p['accent'],radius=2)
   threshold=144+326*frame/bar_max
   s.line([(threshold,y+3),(threshold,y+16)],'#728f9f',1)
   s.text(586,y-3,f'{value:.1f}',22,'#ffd17c' if value>frame else p['ink'],align='right',mono=True,bold=True)
 s.line([(34,1022),(586,1022)],p['line'])
 s.text(34,1040,'谱面属性 · 等级参考框',13,p['muted'])
 s.text(586,1040,'MALODY LINK',13,p['muted'],align='right',mono=True)
 return s


def main():
 OUT.mkdir(parents=True,exist_ok=True)
 doc=json.loads((SOURCE/'analysis.json').read_text(encoding='utf-8'))
 refs=json.loads((SOURCE/'overflow-candidates.json').read_text(encoding='utf-8'))['references']
 rows={r['cid']:r for r in doc['charts']}
 cards={};raw={}
 for cid in [600214,604332,604370]:
  row=rows[cid];cards[str(cid)]={}
  raw[str(cid)]={**{k:row[k] for k in ['cid','title','keys','normalized_level','source_format']},'frame':10*row['normalized_level']-40,'scores':{a:row[a]/refs[str(row['keys'])][a]*100 if refs[str(row['keys'])][a] else 0 for a in AXES}}
  for style in PALETTES:
   scene=make(style,row,refs[str(row['keys'])]);cards[str(cid)][style]=scene.markup()
   if cid==600214:scene.save('style-'+style)
 sheet=Image.new('RGB',(1976,1390),'#eaf0f3');draw=ImageDraw.Draw(sheet)
 def text(x,y,value,size,color,bold=False):draw.text((x,y),value,font=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc',size),fill=color,anchor='lt')
 text(34,30,'谱面六维 · 三套样式方案',37,'#1e3440',True)
 text(34,89,'同一谱面、相同数值与等级框；变速位于正下方。统一样本：Vitamins（维他命） / Lv.12 / 6K / 外框80',19,'#4f6876')
 for i,(style,p) in enumerate(PALETTES.items()):
  x=34+i*648
  text(x,142,p['tag'],16,'#637c8a',True);text(x,176,p['title'],28,'#203642',True)
  sheet.paste(Image.open(OUT/f'style-{style}.png'),(x,228))
 sheet.save(OUT/'three-radar-styles.png')
 (OUT/'sample-data.json').write_text(json.dumps({'note':'style proposal only; same measured data in all three styles','clockwise_axes':AXES,'charts':raw},ensure_ascii=False,indent=2),encoding='utf-8')
 page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Malody 六维图 · 三套样式</title><style>
*{box-sizing:border-box}body{margin:0;background:#eaf0f3;color:#203642;font:16px/1.65 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1960px;margin:auto;padding:32px}h1{margin:0;font-size:clamp(28px,3vw,44px)}.intro{color:#526d7c;max-width:1000px}.controls{display:flex;gap:14px;align-items:center;flex-wrap:wrap;padding:16px 0 24px}select,button{min-height:46px;border:1px solid #b6cbd5;background:white;border-radius:10px;padding:8px 14px;font:inherit;color:inherit}select:focus-visible,button:focus-visible{outline:3px solid #087f83;outline-offset:3px}button{cursor:pointer;transition:background .18s}button:hover{background:#dceeea}button:active{background:#c3e1db}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px}h2{margin:8px 0;font-size:25px}.tag{font-size:13px;color:#617b8a;letter-spacing:1px}.desc{min-height:54px;color:#526d7c}.card{border-radius:24px;overflow:hidden;box-shadow:0 14px 30px #173f5012}.card svg{display:block;width:100%;height:auto}details{margin-top:32px;border-top:1px solid #b6cbd5;padding-top:16px}summary{cursor:pointer}.note{color:#526d7c;font-size:14px}article button{margin-top:14px}footer{margin-top:30px;color:#526d7c}@media(max-width:1050px){.grid{grid-template-columns:1fr;max-width:620px;margin:auto}.desc{min-height:0}main{padding:22px}}@media(prefers-reduced-motion:reduce){*{transition:none!important}}</style><main><h1>谱面六维 · 三套样式</h1><p class="intro">这三套只改变视觉，不改变统计数值。变速统一位于正下方；超框点保留真实数值，超过画布以箭头表示。</p><div class="controls"><label for="song">对比谱面</label><select id="song"><option value="600214">Vitamins（维他命） · Lv.12 · 6K</option><option value="604332">New Astronomas · Lv.13 · 6K</option><option value="604370">Yooh - RPG · Lv.15 · 4K</option></select><span id="frame" class="note"></span></div><div class="grid">'''
 for style,p in PALETTES.items():page+=f'<article><div class="tag">{p["tag"]}</div><h2>{p["title"]}</h2><p class="desc">{p["desc"]}</p><div class="card" id="card-{style}"></div><button data-style="{style}">下载此样式 SVG</button></article>'
 page+='''</div><details><summary>数据与显示规则</summary><ul><li>三个样式共用同一份真实谱面统计：各轴以同键数前五均值为100；这是候选刻度。</li><li>等级框：12级80、13级90、14级100；15级110是按规律延伸的试算方案。</li><li>绘图半径＝六维值÷等级框值。图表显示上限不改变真实数值。</li><li>IMD谱面的变速按0显示。</li><li>从顶部起顺时针：物量、同押、爆发、变速、长按复合、滑键。</li><li>样式为设计预览，尚未替换 App 和机器人。</li></ul></details><footer>建议：A 偏街机氛围；B 最贴合现有 App；C 适合数据查看。三个样式均可用于 App 或机器人图片。</footer></main><script>'''
 page+='const cards='+json.dumps(cards,ensure_ascii=False).replace('</','<\\/')+';const data='+json.dumps(raw,ensure_ascii=False)+';'
 page+='''const select=document.getElementById('song');function update(){const cid=select.value;for(const style of ['arcade','light','instrument'])document.getElementById('card-'+style).innerHTML=cards[cid][style];document.getElementById('frame').textContent='等级参考框 '+data[cid].frame+(data[cid].normalized_level===15?'（15级110为延伸试算）':'');}select.addEventListener('change',update);document.querySelectorAll('button[data-style]').forEach(button=>button.addEventListener('click',()=>{const style=button.dataset.style;const blob=new Blob([cards[select.value][style]],{type:'image/svg+xml;charset=utf-8'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='Malody-radar-'+select.value+'-'+style+'.svg';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}));update();</script></html>'''
 (OUT/'radar-style-options.html').write_text(page,encoding='utf-8')
 notes=['# 六维图三套样式方案','','样式：街机霓虹、浅色竞技、深色仪表。三者共用真实数据、等级参考框与轴位置；变速位于正下方。','', '默认样本：Vitamins（维他命），CID600214，12级6K，等级参考框80。','','打开 radar-style-options.html 可切换 New Astronomas、RPG，对比三种样式并下载SVG。','', '等级框、候选刻度及超框样式均为设计预览，尚未替换App或机器人。IMD变速按0。']
 (OUT/'README.md').write_text('\n'.join(notes),encoding='utf-8')
 bundle=OUT.parent/'Malody-radar-three-styles-2026-10-30.zip'
 with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as archive:
  for file in OUT.iterdir():
   if file.is_file():archive.write(file,file.name)
 print('Saved three PNG/SVG options, comparison sheet, interactive offline HTML and ZIP.')


if __name__=='__main__':main()
