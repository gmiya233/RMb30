# 小师妹 QQ 官方机器人

直接读取现有 Java 排行服务发布的 v2 榜单，不扫描玩家原始存档，也不另算 rating。
通过腾讯官方 `qq-botpy` 接入，只回复群内 @机器人的指令。无需大模型或 AI 服务。

命令：`帮助`、`rating`、`总榜 [页码]`、`查排名 QQ号`、`查歌 歌名`、`单曲榜 谱面ID [页码]`。
`@小师妹 rating` 说明默认判定倍率、准度分段、O20+N10、同歌去重、空位补零及定级换算；
大小写均可，无需绑定QQ或读取榜单即可回复。规则对应共享的 `RatingRules`、`ChartDetails` 和 `RatingBoard`。
发送 `绑定 QQ号` 设置本群默认查询账号，以后发送 `b30` 返回 O20+N10 图片。
`我的绑定` 查看默认账号，`解绑` 清除该偏好；绑定不是 QQ 归属认证，不授予修改云端成绩的权限。
绑定保存在本机私有 SQLite 文件，群和成员标识经过哈希，不记录到消息日志。
总榜按综合 rating；单谱按 rating、准度、原始分数排序；完全同分并列，规则与 App 一致。
官方群成员 openid 不是 QQ 号码，查排名需明确输入 App 上传时使用的 QQ，不自动冒充绑定。
群榜只展示玩家昵称，不展示所有人的完整 QQ。每页10名，曲目搜索最多8张谱面。
查歌和按歌名查询单曲榜时，沿用社区榜单的默认谱师隐藏规则（包含别名、繁体及合作署名），
过滤后再统计搜索数量和截取结果。规则直接来自已部署的 `community.py`，不另维护名单。
查歌只匹配到一个 SID 时回复曲绘信息卡，展示曲名、歌手、BPM、谱师和各难度的定级与谱面ID。
同歌多个难度仍视为一首；无 SID 时按谱面区分。同名不同 SID 保留文字候选，不擅自挑选歌曲。
歌曲信息下方展示每个难度的前5名（最多12个难度），含成绩、准度、判定、rating 和榜单更新时间；
并列名次沿用 App 规则。暂无成绩与榜单读取失败分别提示，不把读取失败显示为空榜。
缺图时明确显示“暂无曲绘”；匹配多首时继续使用文字列表。

## Linux 部署

安装程序到 `/opt/malody-qqbot` 并创建独立 Python 虚拟环境，安装 `requirements.txt`。
配置文件 `/etc/malody-qqbot/config.json`：

```json
{"appid":"YOUR_APPID","rankings":"/var/lib/malody-qqbot/rankings","catalogue":"/var/lib/malody-qqbot/catalog-source/catalog.json","sandbox":false,"allowed_groups":[]}
```

`allowed_groups` 非空时只响应指定的群 openid；空数组表示响应平台服务范围内的群。
AppSecret 单独存放 `/etc/malody-qqbot/appsecret`，属主 root、权限 0600，不放在仓库、环境变量或命令参数中。
用 systemd `LoadCredential` 传给进程，SDK 访问令牌只留内存。

使用 `malody-qqbot.service`，并在部署机增加 drop-in，通过 `BindReadOnlyPaths` 把真实榜单目录挂载到
`/var/lib/malody-qqbot/rankings`、把 `/var/lib/malody-qqbot-catalog` 挂载到 `/var/lib/malody-qqbot/catalog-source`。
配置中 `catalogue` 对应 `/var/lib/malody-qqbot/catalog-source/catalog.json`。
安装 `publish_catalogue.py` 和对应 catalog service/timer，每10秒刷新公开曲目字段及 B30 摘要到专用目录，
防止 QQ 进程接触排行服务的私有恢复状态。先创建 `/var/lib/malody-qqbot-catalog` 并启动一次 catalog service。
绑定目录可跟随源端原子替换新文件，禁止绑定单个 catalogue 文件导致版本停留。
这些部署源路径不得提交到仓库。服务使用 DynamicUser，只有榜单与谱库的只读视图，不授予原始分数或私钥访问权。
发布器用排行服务的私有状态生成 B30 摘要，逐一核对总 rating、分表 rating 和收录数量。
在发布器的 drop-in 中只读挂载 v2 榜单到 `/var/lib/malody-qqbot-rank-source`，并设置
`MALODY_QQ_RANKINGS=/var/lib/malody-qqbot-rank-source`。私有状态保留在原有路径。

图片生成依赖 `fonts-noto-cjk`。机器人只读挂载已发布曲绘到 `/var/lib/malody-qqbot/artwork`。
曲绘依次回退封面、背景和谱面元数据，兼容唯一的大小写文件名匹配；不会拿不相关图片填补缺失曲绘。
如需谱面图片回退，为发布器只读挂载各谱面目录到 `/var/lib/malody-qqbot-chart-source` 下相应子目录，
设置 `MALODY_QQ_CHART_SOURCE=/var/lib/malody-qqbot-chart-source`。生成的缩略图只写专用 `thumbs` 目录。
通过官方 `file_data` 接口上传图片，再被动回复触发消息，不提供公开图片目录或公网下载链接。

在 QQ 开放平台添加测试群到服务范围；如后台要求 IP 白名单，填写服务器出口地址。
使用官方 WebSocket 接入，不开放新的公网 HTTP 端口。首次上线后由管理员在群内 @机器人发送「帮助」「总榜」验证。
SDK 初始化和回复失败日志只记录错误类别，不记录消息、凭据和群成员标识。

`python -B -m unittest discover -s tests -p 'test_qqbot*.py'` 验证查询、排序、去重限流、绑定隔离、B30及曲绘回退。
旧榜单超过两分钟会明确标记延迟，损坏或命名空间不符的数据不会当作有效排行返回。

腾讯官方示例：https://github.com/tencent-connect/botpy/blob/master/examples/demo_group_reply_text.py
# 谱面预览

`@小师妹 谱面预览 谱面ID` 返回 EX Rhythm Master VI 等宽直轨展开图。也可输入歌名，多难度会列出 ID。机器人只读预先生成的 PNG，不加载原始 MC 或执行皮肤脚本。服务与目录配置见 [谱面预览服务](../chart_preview/README.md)。

图片上传 HTTP 超时为30秒；超时没有返回媒体信息时给出明确提示，避免后续将空返回值当作上传结果。

## Markdown 与按钮能力实测

2026-10-28，小师妹在测试群通过 `@小师妹 markdown测试` 验证：自定义 Markdown 与 `keyboard.content` 指令按钮均被平台接受，玩家确认标题、加粗文字及三个按钮正常显示。该诊断指令仅被动回复触发它的消息；不会主动群发。按钮以现有 b30／总榜／谱面预览命令工作，预览按钮先填入指令以便输入参数。

## 正式按钮菜单

群内 @小师妹 发送「菜单」「帮助」「help」或仅 @，返回 Markdown 九宫格按钮菜单。第一行：我的 B30／总战力榜／rating 规则；第二行：查找歌曲／单曲排行／谱面预览；第三行：绑定 QQ／我的绑定／玩家排名。需要参数的按钮只填入指令，不自动发送。Markdown 发送失败时回退文字帮助；原有命令继续使用。

## 歌曲推荐与两小时定时推送

- `歌曲推荐`（别名 `推荐歌曲`、`随机推荐`）：随机歌曲卡，含曲绘、歌名、BPM、可见难度和各谱面前五名排行榜；优先选取有可用曲绘的歌曲。无成绩或榜单不可用会明确标注。
- `开启推荐`：先立即尝试一张不携带旧 msg_id 的主动推荐；平台确认发送成功后，启动本群每7200秒一次的定时任务。
- `关闭推荐`：停止本群自动推荐，手动查询仍可使用。
- `推荐状态`：查看开启状态、北京时间下一次发送时间及最近结果。

分群设置与最近20首推荐记录保存在私有 STATE_DIRECTORY/recommendations.sqlite3；重启后保留。每30秒检查到期任务，发送前预留下一时段，停机期间不补发、不失败连发。被允许的群才参与定时发送。QQ群需要允许机器人主动推送；主动发送失败不会伪造过期 msg_id，也不会假称已开启。所有人可在当前群开启/关闭，群设置相互独立。日志仅记录成功或错误类型，不打印群ID、消息正文或密钥。

## 活跃度与6K排行

`活跃榜 累计 [页码]`／`累计活跃榜` 按云端有效去重记录累计次数；`活跃榜 月 [页码]`／`月活跃榜` 统计最近30×24小时，按实际游玩时间计算。未上传与不属于当前谱库的记录不计。`6K榜 [页码]`／`6K排行` 只按明确6K谱面的 O20+N10 rating 排名，缺位按0计，沿用现有定级、判定倍率与歌曲去重规则。三榜每页10人，同值并列。服务器统一生成 activity.json 和6k.json，机器人只读取快照。

## 6K B30

群内 `@小师妹 6kb30` 或 `6K B30` 返回已绑定QQ的6K专属O20+N10图。只收录键数明确为6K的谱面，独立rating须与6K榜一致；图上标注6K。复用现有绑定与曲绘摘要，缺位按0计；没有有效6K成绩则给出同步提示。
# 头像同步与曲绘

新版客户端在同步 QQ 云存档时上传裁剪后的头像至 `<QQ>/player-avatar.json`；移除头像后再次同步会清除云端显示。机器人校验 QQ、服务器、摘要及图片大小后用于普通 B30 和 6K B30，错误或缺失时使用默认头像。

客户端与机器人共用 `/_song_art/v2/index.json` 中的当前谱面曲绘映射。索引包含 CID、谱面 hash 与图片内容 SHA-256；不再信任旧的 CID 同名缓存。目录发布服务通过 `MALODY_PUBLIC_ART_ROOT` 指向可写的曲绘挂载，机器人通过 `MALODY_QQ_PROFILES` 读取头像目录。

部署 systemd 目录挂载时，源和目标间的冒号须作为字段分隔符；不要将整个 `源:目标` 包在同一对引号中。保持目录读写范围限制，并在重启后确认 `QQ bot connected`，不能仅凭启动命令成功判断在线。

