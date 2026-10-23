import SwiftUI
import UIKit
import MalodyCore

struct RootView: View {
    @EnvironmentObject var model: AppModel
    private let tabs = [("首页", "house"), ("B30", "chart.bar.xaxis"), ("排行榜", "trophy"), ("歌曲", "music.note.list"), ("设置", "gearshape")]
    var body: some View {
        NavigationStack {
            Group {
                switch model.selectedTab {
                case 1: B30Page()
                case 2: RankingPage()
                case 3: SongsPage()
                case 4: SettingsPage()
                default: HomePage()
                }
            }
            .background(Palette.background)
            .navigationDestination(for: Chart.self) { SongDetail(chart: $0) }
        }
        .id(model.selectedTab)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                Divider()
                HStack(spacing: 2) {
                    ForEach(tabs.indices, id: \.self) { index in
                        Button { model.selectedTab = index } label: {
                            VStack(spacing: 5) { Image(systemName: tabs[index].1).font(.system(size: 21)); Text(tabs[index].0).font(.caption).lineLimit(1).minimumScaleFactor(0.8) }
                                .frame(maxWidth: .infinity).frame(minHeight: 54).padding(.vertical, 3)
                                .foregroundStyle(model.selectedTab == index ? Palette.accent : Color.secondary)
                                .background(model.selectedTab == index ? Palette.selected : Color.clear, in: RoundedRectangle(cornerRadius: 16))
                        }
                        .buttonStyle(.plain).accessibilityLabel(tabs[index].0).accessibilityAddTraits(model.selectedTab == index ? .isSelected : [])
                    }
                }.padding(.horizontal, 10).padding(.vertical, 7).frame(maxWidth: 850)
            }.frame(maxWidth: .infinity).background(.regularMaterial)
        }
        .alert("操作未完成", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("知道了", role: .cancel) { model.error = nil }
        } message: { Text(model.error ?? "请稍后重试") }
    }
}

struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View { VStack(alignment: .leading, spacing: 14) { content }.frame(maxWidth: .infinity, alignment: .leading).padding(20).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22)) }
}

struct HomePage: View {
    @EnvironmentObject var model: AppModel
    @State private var chooseFolder = false
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Card {
                    Text("PLAYER RATING").font(.caption.weight(.semibold)).foregroundStyle(Palette.accent)
                    Text(model.board.rating, format: .number.precision(.fractionLength(4))).font(.system(size: 50, weight: .bold, design: .rounded)).monospacedDigit().foregroundStyle(Palette.ink)
                    HStack { metric("O20", model.board.old.count, 20); Spacer(); metric("N10", model.board.new.count, 10); Spacer(); VStack(alignment: .leading) { Text("成绩记录").font(.caption).foregroundStyle(.secondary); Text("\(model.state.records.count)").font(.title3.bold()) } }
                    Divider()
                    Label(model.playerName + " · " + model.playerTitle, systemImage: "person.crop.circle").font(.subheadline)
                }
                Card {
                    if model.busy { ProgressView().accessibilityLabel("正在处理") }
                    Text(model.status).font(.subheadline).foregroundStyle(.secondary).textSelection(.enabled)
                    if model.folderName.isEmpty { action("选择 Malody V 文件夹", "folder.badge.plus") { chooseFolder = true } }
                    else { Label(model.folderName, systemImage: "folder").font(.footnote); action("同步本机成绩", "arrow.triangle.2.circlepath") { model.importScores() } }
                    action("合并同步云端", "icloud.and.arrow.up") { model.syncCloud(upload: true) }
                    if model.busy { Button("取消当前操作", role: .cancel) { model.cancel() }.frame(minHeight: 44) }
                }
                Card {
                    HStack { Text("我的 B30").font(.title3.bold()); Spacer(); Button { model.selectedTab = 1 } label: { Image(systemName: "arrow.up.right") }.accessibilityLabel("查看 B30").frame(width: 44, height: 44) }
                    HStack { Text("O20").foregroundStyle(Palette.accent); Text(model.board.oldRating, format: .number.precision(.fractionLength(4))).monospacedDigit(); Spacer(); Text("N10").foregroundStyle(.purple); Text(model.board.newRating, format: .number.precision(.fractionLength(4))).monospacedDigit() }
                    if model.state.records.isEmpty { Text("同步成绩后生成你的成绩图").font(.footnote).foregroundStyle(.secondary) }
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text("最近成绩").font(.title2.bold()).frame(maxWidth: .infinity, alignment: .leading)
                    if model.recent.isEmpty { Card { Label("还没有当前谱库的成绩", systemImage: "music.note").foregroundStyle(.secondary) } }
                    ForEach(model.recent.prefix(10)) { score in Card { ScoreRow(score: score) } }
                }
            }.padding(18).frame(maxWidth: 820)
                .frame(maxWidth: .infinity)
        }
        .navigationTitle("我的成绩")
        .sheet(isPresented: $chooseFolder) { FolderPicker { url in chooseFolder = false; model.chooseFolder(url) } }
    }
    private func metric(_ label: String, _ count: Int, _ slots: Int) -> some View { VStack(alignment: .leading) { Text(label).font(.caption).foregroundStyle(.secondary); Text("\(count) / \(slots)").font(.title3.bold()).monospacedDigit() } }
    private func action(_ title: String, _ icon: String, _ action: @escaping () -> Void) -> some View { Button(action: action) { Label(title, systemImage: icon).frame(maxWidth: .infinity, minHeight: 38) }.buttonStyle(.borderedProminent).disabled(model.busy || !model.ready) }
}

struct ArtView: View {
    @EnvironmentObject var model: AppModel
    let chart: Chart
    var side: CGFloat = 64
    @State private var image: UIImage?
    var body: some View {
        ZStack { RoundedRectangle(cornerRadius: 12).fill(Palette.selected); if let image { Image(uiImage: image).resizable().scaledToFill() } else { Image(systemName: "music.note").font(.title2).foregroundStyle(Palette.accent) } }
            .frame(width: side, height: side).clipShape(RoundedRectangle(cornerRadius: 12))
            .accessibilityLabel(chart.title + "曲绘")
            .task(id: chart.cid) { image = nil; if let data = await model.art(chart) { image = UIImage(data: data) } }
    }
}

struct ScoreRow: View {
    let score: RatedScore
    var body: some View {
        HStack(spacing: 14) {
            ArtView(chart: score.chart)
            VStack(alignment: .leading, spacing: 5) {
                Text(score.chart.title).font(.headline).lineLimit(2)
                Text("\(score.chart.levelLabel) · \(Rating.judgeName(score.record.judge)) · \(score.record.accuracy, specifier: "%.3f")%").font(.caption).foregroundStyle(.secondary)
                HStack { Text(score.record.score, format: .number); Spacer(); Text("rating \(score.rating, specifier: "%.4f")").foregroundStyle(Palette.accent) }.font(.subheadline.weight(.semibold)).monospacedDigit()
            }
        }
    }
}

struct SongGroup: Identifiable {
    let id: String
    let charts: [Chart]
    var first: Chart { charts[0] }
}

struct SongsPage: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    var groups: [SongGroup] {
        let grouped = Dictionary(grouping: model.catalogue.charts, by: \.identity)
        let words = query.precomposedStringWithCompatibilityMapping.lowercased().split(whereSeparator: \.isWhitespace)
        return grouped.map { SongGroup(id: $0.key, charts: $0.value.sorted { ($0.level ?? -1) < ($1.level ?? -1) }) }.filter { song in
            let text = ([song.first.title, song.first.artist] + song.charts.map(\.creator)).joined(separator: " ").precomposedStringWithCompatibilityMapping.lowercased()
            return words.allSatisfy { text.contains($0) }
        }.sorted { $0.first.title.localizedStandardCompare($1.first.title) == .orderedAscending }
    }
    var body: some View {
        List {
            Section { Text("\(groups.count) 首歌曲").font(.caption).foregroundStyle(.secondary) }
            ForEach(groups) { song in
                NavigationLink(value: song.first) {
                    HStack(spacing: 14) { ArtView(chart: song.first); VStack(alignment: .leading, spacing: 5) {
                        Text(song.first.title).font(.headline).lineLimit(2)
                        Text(song.first.artist.isEmpty ? "歌手未提供" : song.first.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        Text(song.charts.prefix(4).map(\.levelLabel).joined(separator: " / ")).font(.caption).foregroundStyle(Palette.accent)
                    } }.padding(.vertical, 5)
                }
            }
            if groups.isEmpty { Text("没有找到匹配歌曲").foregroundStyle(.secondary) }
        }.listStyle(.insetGrouped).navigationTitle("歌曲信息").searchable(text: $query, prompt: "搜索曲名、歌手、谱师")
    }
}

struct SongDetail: View {
    @EnvironmentObject var model: AppModel
    let chart: Chart
    var charts: [Chart] { model.catalogue.charts.filter { $0.identity == chart.identity }.sorted { ($0.level ?? -1) < ($1.level ?? -1) } }
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                ArtView(chart: chart, side: 200)
                Text(chart.title).font(.title.bold()).multilineTextAlignment(.center)
                Text(chart.artist.isEmpty ? "歌手未提供" : chart.artist).foregroundStyle(.secondary)
                Text("BPM · " + (chart.bpm.isEmpty ? "未提供" : chart.bpm)).font(.subheadline)
                ForEach(charts) { row in Card {
                    Text(row.levelLabel + " · " + row.version).font(.headline).foregroundStyle(Palette.accent)
                    Text("谱师 · " + (row.creator.isEmpty ? "未提供" : row.creator)).font(.subheadline).foregroundStyle(.secondary)
                    Button { model.showRanking(cid: row.cid) } label: { Label("该谱面排行榜", systemImage: "trophy").frame(maxWidth: .infinity, minHeight: 38) }.buttonStyle(.bordered)
                } }
            }.padding(20).frame(maxWidth: 820).frame(maxWidth: .infinity)
        }.navigationTitle("歌曲详情").navigationBarTitleDisplayMode(.inline)
    }
}

struct RankingPage: View {
    @EnvironmentObject var model: AppModel
    var chart: Chart? { model.catalogue.byCID[model.rankingCID] }
    var body: some View {
        List {
            Section {
                if let chart { Text(chart.title).font(.headline); Text(chart.version).font(.subheadline).foregroundStyle(.secondary) }
                else { Text("O20 + N10 综合 rating").font(.headline) }
                HStack { Text(model.rankingStatus).font(.caption).foregroundStyle(.secondary); Spacer(); if model.rankingLoading { ProgressView() } }
                if model.rankingCID != 0 { Button("返回总战力榜") { model.rankingCID = 0 } }
                Button("搜索歌曲查看单曲榜") { model.selectedTab = 3 }.frame(minHeight: 36)
            }
            if let board = model.cloudBoard, model.loadedRankingCID == model.rankingCID {
                ForEach(board.rows) { row in
                    HStack(alignment: .top, spacing: 14) {
                        Text("#\(row.rank)").font(.title3.bold()).foregroundStyle(row.rank <= 3 ? Color.orange : .secondary).frame(width: 48)
                        VStack(alignment: .leading, spacing: 7) {
                            Text(row.name).font(.headline); Text("QQ " + row.qq).font(.caption).foregroundStyle(.secondary)
                            Text("rating \(row.rating, specifier: "%.4f")").font(.title3.bold()).monospacedDigit().foregroundStyle(Palette.accent)
                            if let accuracy = row.accuracy, let score = row.score { Text("\(score.formatted()) · \(accuracy, specifier: "%.3f")% · \(Rating.judgeName(row.judge ?? 0))").font(.caption).foregroundStyle(.secondary) }
                            else { Text("O20 \(row.old, specifier: "%.4f") · N10 \(row.new, specifier: "%.4f")").font(.caption).foregroundStyle(.secondary) }
                        }
                    }.padding(.vertical, 9).listRowBackground(row.qq == model.qq ? Palette.selected : Color(uiColor: .secondarySystemGroupedBackground))
                }
                if board.rows.isEmpty { Text("暂无该榜单成绩").foregroundStyle(.secondary) }
            }
        }.listStyle(.insetGrouped).navigationTitle(model.rankingCID == 0 ? "总战力榜" : "单曲排行榜")
            .refreshable { await model.loadRanking() }.task(id: model.rankingCID) { await model.loadRanking() }
            .toolbar { Button { Task { await model.loadRanking() } } label: { Image(systemName: "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel("刷新排行榜").disabled(model.rankingLoading) }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let presenter = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController?.view {
            controller.popoverPresentationController?.sourceView = presenter
            controller.popoverPresentationController?.sourceRect = CGRect(x: presenter.bounds.midX, y: presenter.bounds.midY, width: 1, height: 1)
            controller.popoverPresentationController?.permittedArrowDirections = []
        }
        return controller
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct SettingsPage: View {
    @EnvironmentObject var model: AppModel
    @State private var chooseFolder = false
    @State private var qqDraft = ""
    @State private var nameDraft = ""
    @State private var selectedTitle = ""
    @State private var diagnostic: URL?
    @State private var share = false
    var body: some View {
        Form {
            Section {
                LabeledContent("授权目录", value: model.folderName.isEmpty ? "尚未选择" : model.folderName)
                Button { chooseFolder = true } label: { Label("选择 Malody V 文件夹", systemImage: "folder.badge.plus") }
                Button { model.importScores() } label: { Label("同步本机成绩", systemImage: "arrow.triangle.2.circlepath") }
                Button("解除目录关联", role: .destructive) { model.forgetFolder() }.disabled(model.folderName.isEmpty)
            } header: { Text("本机读取") } footer: { Text("请选择包含 chart 和 replay 的根文件夹。仅读取谱面、回放及曲绘，不修改 Malody 文件。授权失效时重新选择即可。") }
            Section {
                TextField("QQ号", text: $qqDraft).keyboardType(.numberPad).textContentType(.username).accessibilityLabel("QQ存档编号")
                Button("保存 QQ 存档编号") { Task { await model.bindQQ(qqDraft) } }
                if !model.qq.isEmpty { LabeledContent("当前存档", value: model.qq) }
                Button { model.syncCloud(upload: true) } label: { Label("合并同步云端", systemImage: "icloud.and.arrow.up") }
                Button { model.syncCloud(upload: false) } label: { Label("只从云端恢复", systemImage: "icloud.and.arrow.down") }
            } header: { Text("QQ 云存档") } footer: { Text("首次设置会带入未绑定的本机成绩。不同 QQ 的本地记录分别保存。QQ 目前仅作存档编号，未验证归属。") }
            Section {
                TextField("昵称", text: $nameDraft).accessibilityLabel("本地昵称")
                Picker("称号", selection: $selectedTitle) {
                    Text("自动选择").tag("")
                    ForEach(model.unlockedTitles, id: \.self) { Text($0).tag($0) }
                }
                Button("保存本地名片") { Task { await model.saveProfile(name: nameDraft, title: selectedTitle) } }
            } header: { Text("本地名片") } footer: { Text("昵称和称号偏好只保存在这台设备。准度严格高于99.8%的歌曲可作为称号。") }
            Section("谱库") {
                LabeledContent("当前有效谱面", value: String(model.catalogue.charts.count))
                Button("更新服务器谱库") { model.updateCatalogue() }
            }
            Section("测试反馈") {
                Text(model.status).font(.footnote).foregroundStyle(.secondary)
                Button { do { diagnostic = try model.diagnosticURL(); share = true } catch { model.error = error.localizedDescription } } label: { Label("分享读取诊断", systemImage: "square.and.arrow.up") }
                Text("请核对首次导入的分数、准度和判定与游戏结算一致。诊断不含目录路径、目录授权、云端密码或回放正文。").font(.footnote).foregroundStyle(.secondary)
                LabeledContent("版本", value: "0.1.0 · iOS 内测")
            }
        }
        .disabled(model.busy || !model.ready)
        .navigationTitle("设置").scrollDismissesKeyboard(.interactively)
        .onAppear { qqDraft = model.qq; nameDraft = model.state.nickname; selectedTitle = model.state.title }
        .onChange(of: model.qq) { qqDraft = $0 }
        .sheet(isPresented: $chooseFolder) { FolderPicker { url in chooseFolder = false; model.chooseFolder(url) } }
        .sheet(isPresented: $share) { if let diagnostic { ShareSheet(items: [diagnostic]) } }
    }
}
