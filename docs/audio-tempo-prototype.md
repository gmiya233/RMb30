# 纯音频变速 BPM 原型

双击 `tools/start_audio_tempo.cmd`，选择 MP3/OGG/WAV/M4A，分析后自动打开本地报告。当前默认使用 final0 神经节拍候选加独立网格复核，结果按时间保存到 E 盘 `dist/audio-tempo-grid`，包含 HTML 曲线、分段 CSV 和 JSON。当前启动器使用本电脑的 Python 路径，未制作独立安装包。首次运行缺少权重时会从作者官方云下载模型；音频不上传。

CLI：`python -B tools/analyze_audio_tempo.py input.mp3 --out dist/audio-tempo-example`。可用 `--min-bpm 60 --max-bpm 240` 调整检测范围，`--hint-bpm 180`提供可选先验；默认自动分析。依赖安装在 E 盘 `tools/tempo-libs`（librosa 0.11.0及依赖），另使用已有imageio-ffmpeg。所有缓存及报告保留在 E 盘，不上传音频。

方法：音频解码为22.05kHz单声道，提取起音强度，8秒滑窗自相关构建局部速度候选，每0.2秒跟踪一次，通过动态规划惩罚不连续跳动；将持续变化合为分段，再以变速点两侧的起音周期相位拟合细化候选时间。短于4秒、节奏弱的片段标记需复核。指标rhythm_strength不是校准后的正确概率。

已知120→180→150、在20/40秒改变速度的合成MP3，首轮检测120/179.5/150.5，变速位置20.01/40.03秒。这只证明合成稳定节拍能检测，不代表真实音乐具有同等精度。A Site De La Rue真实音频的主段识别为186；前奏/尾奏及三连音型出现候选误报，需复核，不自动当作真实变速。

当前不能可靠解决全部半速/倍速与三连音分组歧义；很短的变速、连续渐变、自由速度和弱节拍段也可能漏检或误检。报告是辅助定位，不直接生成可无校对使用的写谱BPM锚点。音频处理上限15分钟。下一步需使用用户提供、人工已核实的变速歌曲做真实样本评估。


## 神经网络候选实测

进一步查阅Beat This!（https://github.com/CPJKU/beat_this，ISMIR 2024）和BeatNet（https://github.com/mjhydri/BeatNet，ISMIR 2021）的官方实现。前者提供逐拍/重拍输出，并有专为较大速度变化减少解码约束的模型；后者联合跟踪节拍、重拍、速度和拍号。本次实际运行Beat This的small0和final0，未运行BeatNet，不能声称它已改善本样本。

依赖分别留在tools/beat-libs（CPU版torch/torchaudio 2.8.0）、tools/beat-extra（beat-this 1.1.0、einops、rotary-embedding-torch、ruptures等），权重在tools/models，均不进Git。官方作者云下载small0约8.5MB、final0约78MB。单次ÅMARA全曲CPU推理2线程约5～8秒，不含下载。

运行：`python -B tools/analyze_neural_tempo.py input.mp3 --model final0 --decoder probability --out dist/neural-tempo-result`。beat-grid解码是神经网络逐拍+PELT分段线性网格拟合；probability解码将神经节拍概率代替普通起音强度，保留速度轨迹约束。二者均不读取MC。对照统一按最终分段时长计算BPM相对误差≤3%占比：旧原型19.5%，small0网格45.8%，final0网格47.0%，final0概率59.2%；允许半速/倍速则分别61.0/57.8/56.1/70.2%。这是单个用户样本的对照，不是模型总体准确率，也未验证样本是否属于模型训练音乐。

最好候选变化点11个，参考为7个，仍有漏检和误报。开头115 BPM恢复正确，但复杂中段仍不能对齐；阶段性改进不等于已满足自动写谱需求。后续应结合重拍/拍号约束、多尺度节拍网格拟合和多模型一致性；鼓声分离可以作为待验证分支，不能先宣称有效。

## 网格复核改进（2026-11-05）

默认命令：`python -B tools/analyze_neural_tempo.py input.mp3 --decoder grid-verified --out dist/tempo-result`。使用相同 final0 的另一套解码方式检查整段节拍相位，结合重拍、遗漏节拍惩罚、段内漂移检查。两套方法共享神经模型，不是两个独立模型，不能把一致比例解释为正确概率。相位网格只增加复核提示，不改写主候选 BPM 或时间。

报告及 CSV 给出两方法分歧、拍级／三连音分组歧义和累计漂移原因；JSON 另保存完整网格检查轨迹。纯相位网格可用 `--decoder phase-grid` 单独运行，属于实验分支。

ÅMARA 试验中，无漏拍惩罚版本与参考严格一致时长达到62.5%，但在120→180→150的合成三连音干扰测试中出错；加入惩罚后通过该测试，ÅMARA严格一致降至51.2%，容许半速／倍速时72.0%。因此没有用它替换主候选；默认仍保持59.2%的单曲参考一致时长，并明确提示冲突。改进主要在误判提示，不是宣称新的准确率突破。参考 MC 只用于事后评价，从不作为推理输入。

验证：`python -B tests/test_tempo_grid.py`，包含三段变速加三连音干扰、恒速、静音拒绝，以及网格审计不覆盖主候选的检查。合成测试不能替代真实歌曲评测；当前仍无法可靠自动完成 ÅMARA 的全部变速定时。
