# EX Rhythm Master VI 直轨谱面预览

读取服务器当前谱库的 MC 文件，将 Slide 4K／5K／6K 展开为等宽直轨 PNG。普通音符为皮肤青蓝素材，长条／滑动为绿色素材。按节拍从下到上、再向右读，每栏32拍，每页最多24栏，超过一页可翻页，最多4页。分段 beat 与 x 均相对原音符头，方向位沿用 Malody Editor API（右2／上4／左8／下16）。长条先在原轨道竖直保持，到分段终点再水平换轨，拐点圆点和箭头标出方向，不画斜向连线。

皮肤来自用户提供的 `6091_EX_Rhythm_Master_VI.msp`，原信息中的作者为 dreamcat、Acc@9961、Sam_0324。只提取所用 PNG，来源摘要在 `skin/source.json`；不执行 Lua、ASM 或修改原谱面。本图是节拍展开图，不模拟游戏脚本、透视、变速或停顿，也不播放音频。含效果时图片标注提示。

## 发布文件

Rank1 目录下 `/_chart_previews/v1/<CID>.json` 为小型清单，绑定 server、CID、server_hash。图片名为 `<CID>-<PNG-SHA256>.png`，清单包含字节数和尺寸。App 和机器人均验证目录、谱面身份及图片摘要，单图上限4MiB、16百万像素，不读取用户成绩或回放。

清单最后原子替换；图片按内容命名，旧文件保留供正在读取的客户端使用。MC、皮肤、绘图代码或谱库元信息改变时重画，未改变时使用缓存。无法读取／解析的谱面不继续公布旧图；个别谱面失败不停止其他预览生成。

## 服务配置

将本目录复制到 `/opt/malody-chart-preview`。复用机器人 venv 的 Pillow 与服务器 Noto CJK 字体。`/etc/malody-chart-preview/config.json` 由部署端保存（不提交 Git），内容字段：

- `catalogue`：只读的完整服务端 catalogue JSON。
- `source`：仅含 charts、charts_tagged、promote、events 的只读挂载根。
- `output`：Rank1 目录中的 `_chart_previews/v1`，仅存派生图和清单。
- `state`：`/var/lib/malody-chart-preview`，保存增量标记。
- `font`：Noto CJK 字体文件。

安装 `malody-chart-preview.service`，用部署端 systemd drop-in 加上只读谱面挂载与独立输出目录的 `ReadWritePaths`；不向机器人开放原始谱面目录。机器人只需将 output 只读绑定到 `/var/lib/malody-qqbot/chart-previews`。服务每60秒检查更新；首次会预热谱库。

手动检查：`python -B publish.py --config /etc/malody-chart-preview/config.json --once`。日志仅记录数量和状态，不记录私人路径或文件正文。

## 使用

- Android／iOS：歌曲详情 → 每个难度的「谱面预览」，支持缩放、翻页、保存。
- 小师妹：`@小师妹 谱面预览 谱面ID [页码]`；歌名有多个难度时列出 ID 供选择。
- 机器人搜索遵循原有隐藏谱师规则。
