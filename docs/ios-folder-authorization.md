# iOS 文件夹授权兼容性测试（0.3.9）

## 反馈与边界

- 2026-11-02 玩家反馈：0.1 可用；新版能够进入 Malody V，但点系统选择器右上角「打开」没有反应。
- 已对比 0.1 发布提交 `1263d20e` 与当前代码：FolderPicker、DirectoryBookmark 的原实现未变；新版增添了头像、曲包等弹窗。现有视频及源码不足以证明具体根因。
- 原选择回调会在主线程同步检查外部目录及创建书签。若文件服务阻塞，可能表现为按钮点击后不返回。此为待真机验证的假设，不能写成已定位根因。

## 调整

根页面统一全屏呈现选择器；选择／取消回调只修改呈现状态。`onDismiss` 后在 detached task 中验证目录并创建书签，主线程只提交通过验证的结果。旧书签在新授权成功前保留；取消和校验失败均不删除成绩。

`DirectoryAuthorization.prepare` 只读取根目录的直接子项元数据和书签，不枚举谱面内容、不写 Malody 目录。确认根目录与 `chart`、`replay` 为目录，拒绝符号链接。没有把复制目录或导出文件作为默认流程。

增加仅在用户主动导出诊断时附带的阶段记录。未收到选择回调时不会写入 `selection_received`；收到回调但文件服务阻塞时会停在 `validation_started`。记录中不包含目录路径、书签或文件内容。

## 验证

- 新增 Swift 核心测试：有效目录书签往返和源文件不变；缺少 replay；误选 chart 子目录；文件冒充目录；符号链接；非文件 URL。
- 本机 Windows 无 Xcode，编译和核心测试交由 GitHub macOS 工作流执行。使用 `package_only=true`，运行核心测试与 arm64 设备构建，不启动模拟器。
- GitHub Actions [41381753822](https://github.com/gmiya233/RMb30/actions/runs/41381753822) 已通过：19 项 Swift 核心测试（含 4 项新授权测试）零失败，arm64 Release 设备构建成功；未启动模拟器。
- 测试 IPA：`Malody-Link-iOS-0.3.9-unsigned.ipa`，build 13，1,844,402 字节；SHA256 `8995f3b561bf15de17883b3fa147cee378444b49709d2cf8e5b526321b711181`。已核验设备平台、版本、原 Bundle ID、无调试夹具及无分发签名配置。
- 当前状态：待苹果真机验证。成功编译不等于跨 App 授权已修复。

## 真机验证

1. 自签覆盖安装并确认设置显示 0.3.9；保留同一签名身份和 Bundle ID。
2. 首页进入 Malody V，点右上角「打开」。窗口应退出，出现「正在验证目录授权」后显示成功或错误。
3. 读取成绩、重新选择同一目录、取消后再次打开、重启 App 后再读取。
4. 若仍卡在系统窗口，取消并到设置导出读取诊断；据阶段判断系统没有返回选择，还是目录验证未完成。

参考：Apple [Providing access to directories](https://developer.apple.com/documentation/uikit/providing-access-to-directories)；其他开发者也曾报告系统目录选择卡住，但不能据此直接认定为同一问题。
