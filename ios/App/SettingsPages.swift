import SwiftUI
import PhotosUI
import UIKit
import MalodyCore

struct SettingsPage: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var chooseFolder = false
    @State private var qq = ""
    @State private var name = ""
    @State private var title = ""
    @State private var photo: PhotosPickerItem?
    @State private var shareURL: URL?
    @State private var sharing = false
    @State private var updateStatus = ""
    @State private var checking = false
    @AppStorage("auto-cloud") private var autoCloud = true
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Card {
                    Label("首次使用 · 授权成绩文件夹", systemImage: "folder.badge.plus").font(.title3.bold()).foregroundStyle(Palette.accent)
                    Text("1. 在「文件」中找到 Malody V 的目录。\n2. 选择同时包含 chart 和 replay 的根文件夹。\n3. 返回首页，点击「同步本机成绩」。").font(.subheadline)
                    Text("如系统未开放 Malody 的目录，可先在电脑上复制 chart 和 replay 到 iCloud Drive 的同一文件夹，再在这里授权。").font(.caption).foregroundStyle(.secondary)
                    Text(model.folderName.isEmpty ? "尚未授权" : "已授权：" + model.folderName).font(.caption)
                    Button("选择 Malody V 文件夹") { chooseFolder = true }.buttonStyle(.borderedProminent).frame(minHeight: 44)
                }
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 12) {
                    action("同步本机成绩", icon: "arrow.triangle.2.circlepath") { model.importScores() }
                    action("解除目录关联", icon: "folder.badge.minus") { model.forgetFolder() }
                    action("上传并合并云端", icon: "icloud.and.arrow.up") { model.syncCloud(upload: true) }
                    action("从云端恢复", icon: "icloud.and.arrow.down") { model.syncCloud(upload: false) }
                    action("更新服务器谱库", icon: "arrow.clockwise") { model.updateCatalogue() }
                    action("导出成绩 JSON", icon: "square.and.arrow.up") { share { try model.exportScores() } }
                }
                Card {
                    Text("QQ 云存档").font(.headline)
                    TextField("QQ号", text: $qq).keyboardType(.numberPad).textFieldStyle(.roundedBorder).accessibilityLabel("QQ存档编号")
                    Button("保存 QQ 存档编号") { Task { await model.bindQQ(qq) } }.frame(minHeight: 44)
                    Toggle("读取后自动上传成绩", isOn: $autoCloud)
                    Text("不同 QQ 的本机成绩分别保存。QQ 仅作存档编号，请填写自己的号码。").font(.caption).foregroundStyle(.secondary)
                }
                Card {
                    Text("本地名片").font(.headline)
                    HStack { PlayerAvatar(data: model.state.avatar, side: 64); PhotosPicker(selection: $photo, matching: .images) { Label("选择头像", systemImage: "photo").frame(minHeight: 44) }; if model.state.avatar != nil { Button("移除") { Task { await model.saveAvatar(nil) } } } }
                    TextField("昵称", text: $name).textFieldStyle(.roundedBorder).accessibilityLabel("本地昵称")
                    Picker("称号", selection: $title) { Text("自动选择").tag(""); ForEach(model.unlockedTitles, id: \.self) { Text($0).tag($0) } }
                    Button("保存本地名片") { Task { await model.saveProfile(name: name, title: title) } }.frame(minHeight: 44)
                    Text("头像保存在本机；称号按实际成绩解锁。").font(.caption).foregroundStyle(.secondary)
                }
                Card {
                    Text("关于").font(.headline)
                    Text("Malody Link · iOS 0.2.0").font(.subheadline)
                    Button { Task { await checkUpdate() } } label: { Label(checking ? "检查中…" : "获取更新", systemImage: "arrow.down.app").frame(minHeight: 44) }.disabled(checking)
                    Text(updateStatus.isEmpty ? "iOS 包需自行签名安装，更新会打开 GitHub 发布页。" : updateStatus).font(.caption).foregroundStyle(.secondary)
                    Button("分享读取诊断") { share { try model.diagnosticURL() } }.frame(minHeight: 44)
                    Text(model.status).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                }
            }.padding(18).frame(maxWidth: 820).frame(maxWidth: .infinity)
        }.navigationTitle("设置").scrollDismissesKeyboard(.interactively)
        .disabled(model.busy || !model.ready)
        .onAppear { qq = model.qq; name = model.state.nickname; title = model.state.title }
        .onChange(of: model.qq) { qq = $0 }
        .onChange(of: photo) { selected in Task { if let data = try? await selected?.loadTransferable(type: Data.self), let image = UIImage(data: data) {
            let size = CGSize(width: 384, height: 384)
            let resized = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            await model.saveAvatar(resized.jpegData(compressionQuality: 0.85))
        } } }
        .sheet(isPresented: $chooseFolder) { FolderPicker { url in chooseFolder = false; model.chooseFolder(url) } }
        .sheet(isPresented: $sharing) { if let shareURL { ShareSheet(items: [shareURL]) } }
    }
    private func action(_ name: String, icon: String, perform: @escaping () -> Void) -> some View {
        Button(action: perform) { VStack(spacing: 10) { Image(systemName: icon).font(.title2); Text(name).font(.subheadline).multilineTextAlignment(.center) }.frame(maxWidth: .infinity, minHeight: 90).padding(8).background(.white.opacity(0.94), in: RoundedRectangle(cornerRadius: 18)) }.buttonStyle(.plain).foregroundStyle(Palette.accent)
    }
    private func share(_ make: () throws -> URL) { do { shareURL = try make(); sharing = true } catch { model.error = error.localizedDescription } }
    private func checkUpdate() async {
        checking = true; defer { checking = false }
        do {
            let url = URL(string: "https://api.github.com/repos/gmiya233/RMb30/releases?per_page=50")!
            let bytes = try await HTTPBytes.request(url, limit: 2_000_000)
            guard let releases = try JSONSerialization.jsonObject(with: bytes) as? [[String: Any]], let release = releases.first(where: { ($0["tag_name"] as? String ?? "").hasPrefix("ios-v") && $0["draft"] as? Bool != true }),
                  let link = release["html_url"] as? String, let target = URL(string: link), target.scheme == "https", target.host == "github.com", target.path.hasPrefix("/gmiya233/RMb30/releases/") else { throw MalodyError.invalid("尚无可用的 iOS 发布包") }
            updateStatus = "最新发布：" + (release["tag_name"] as? String ?? "") + "，请在发布页下载并自行签名。"
            openURL(target)
        } catch { updateStatus = "检查更新失败：" + error.localizedDescription }
    }
}
struct PlayerAvatar: View {
    let data: Data?
    var side: CGFloat = 64
    var body: some View {
        Group { if let data, let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFill() } else { Image(systemName: "person.crop.square.fill").resizable().scaledToFit().foregroundStyle(Palette.accent) } }.frame(width: side, height: side).clipShape(RoundedRectangle(cornerRadius: side * 0.18)).accessibilityLabel("玩家头像")
    }
}
struct RatingPreviewControls: View {
    @EnvironmentObject var model: AppModel
    @State private var cid = ""
    @State private var level = ""
    var body: some View {
        DisclosureGroup("Rating 模拟设置") {
            VStack(alignment: .leading, spacing: 12) {
                Toggle("启用模拟", isOn: $model.showingPreview)
                Picker("模拟判定", selection: $model.preview.judge) { Text("实际判定").tag(0); ForEach(1...5, id: \.self) { Text(Rating.judgeName($0)).tag($0) } }
                ForEach(1...5, id: \.self) { j in HStack { Text(Rating.judgeName(j)); Spacer(); TextField("倍率", value: $model.preview.multipliers[j], format: .number).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).frame(width: 90) } }
                HStack { TextField("谱面 CID", text: $cid).keyboardType(.numberPad); TextField("模拟等级", text: $level).keyboardType(.decimalPad) }.textFieldStyle(.roundedBorder)
                Button("应用谱面模拟等级") { if let id = Int(cid), model.catalogue.byCID[id] != nil, let value = Double(level), value.isFinite, value > 0, value <= 40 { model.preview.levels[id] = value } }.frame(minHeight: 44)
                Button("恢复默认") { model.preview = RatingPreview(); model.showingPreview = false }.frame(minHeight: 44)
                Text("模拟仅用于预览，不改变真实成绩、称号和上传数据。").font(.caption).foregroundStyle(.secondary)
            }.padding(.vertical, 10)
        }.padding(16).background(.white.opacity(0.94), in: RoundedRectangle(cornerRadius: 18))
    }
}
