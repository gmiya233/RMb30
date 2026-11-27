"""Selected arcade concept: revised type and dark-red framed HELL badges."""
from pathlib import Path
import json
import zipfile
import render_radar_style_options as design


def main():
 out=design.ROOT/'dist/radar-arcade-2026-10-30-v3'
 out.mkdir(parents=True,exist_ok=True)
 design.OUT=out
 source=design.SOURCE
 doc=json.loads((source/'analysis.json').read_text(encoding='utf-8'))
 refs=json.loads((source/'overflow-candidates.json').read_text(encoding='utf-8'))['references']
 rows={r['cid']:r for r in doc['charts']}
 cards={}
 for cid in (600214,604332,604370):
  scene=design.make('arcade',rows[cid],refs[str(rows[cid]['keys'])])
  scene.save(f'arcade-hell-{cid}')
  cards[str(cid)]=scene.markup()
 page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>街机霓虹 · HELL修订版</title><style>*{box-sizing:border-box}body{margin:0;background:#eaf0f3;color:#203642;font:16px/1.6 system-ui,"Microsoft YaHei",sans-serif}main{max-width:760px;margin:auto;padding:24px}h1{font-size:28px;margin-bottom:8px}p{color:#526d7c}select,button{font:inherit;min-height:46px;padding:8px 14px;border:1px solid #b6cbd5;border-radius:10px;background:#fff;color:#203642}button{cursor:pointer}button:hover{background:#dceeea}:focus-visible{outline:3px solid #087f83;outline-offset:3px}.controls{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:20px 0}.card{max-width:620px;margin:auto;overflow:hidden;border-radius:20px}.card svg{display:block;width:100%;height:auto}footer{margin-top:24px;color:#526d7c;font-size:14px}</style><main><h1>街机霓虹 · 字体与 HELL 修订版</h1><p>HELL放在对应维度名称旁，采用更大的粗体字和深红色描边框。中文改用加粗等线，英文数值使用 DIN 风格 Bahnschrift，标题与 HELL 使用 Impact。</p><div class="controls"><label for="song">谱面</label><select id="song"><option value="600214">Vitamins · Lv.12 · 6K</option><option value="604332">New Astronomas · Lv.13 · 6K</option><option value="604370">RPG · Lv.15 · 4K</option></select><button id="download">下载 SVG</button></div><div class="card" id="card"></div><footer>设计预览，尚未替换 App 或机器人。变速位于正下方，IMD变速按0；候选刻度和等级参考框规则不变。15级框110仍为延伸试算。</footer></main><script>'''
 page+='const cards='+json.dumps(cards,ensure_ascii=False).replace('</','<\\/')+';'
 page+='''const select=document.getElementById('song');function update(){document.getElementById('card').innerHTML=cards[select.value];}select.addEventListener('change',update);document.getElementById('download').addEventListener('click',()=>{const url=URL.createObjectURL(new Blob([cards[select.value]],{type:'image/svg+xml;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='Malody-arcade-HELL-'+select.value+'.svg';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});update();</script></html>'''
 (out/'arcade-hell-preview.html').write_text(page,encoding='utf-8')
 (out/'README.md').write_text('# 街机霓虹修订版 v3\n\n用户选定第一版。中文加粗等线，英文数字Bahnschrift，标题和HELL为Impact。超过等级参考框值时，在对应轴名称旁放置深红色描边框包围的大号粗体HELL文字；不放在底部数值卡片。变速位置、数值及等级框规则不变。仅为预览，未部署。\n',encoding='utf-8')
 with zipfile.ZipFile(out.parent/'Malody-radar-arcade-HELL-2026-10-30-v3.zip','w',zipfile.ZIP_DEFLATED) as archive:
  for file in out.iterdir():
   if file.is_file():archive.write(file,file.name)
 print('Saved selected arcade revision: 3 PNG/SVG cards, switchable HTML, ZIP.')


if __name__=='__main__':main()
