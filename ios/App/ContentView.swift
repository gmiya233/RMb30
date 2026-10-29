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
            .background { CharacterBackdrop(page: ["home", "b30", "ranking", "songs", "settings"][model.selectedTab]) }
            .navigationDestination(for: Chart.self) { SongDetail(chart: $0) }
        }
        .id(model.selectedTab)
        .sheet(isPresented: $model.showingPackages) {
            NavigationStack { ChartImportPage().toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { model.showingPackages = false } } } }
        }
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
            }.frame(maxWidth: .infinity).background(.regularMaterial).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
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
            VStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        Button { model.selectedTab = 4 } label: { PlayerAvatar(data: model.state.avatar, side: 48).padding(3).background(Palette.selected, in: RoundedRectangle(cornerRadius: 14)) }.buttonStyle(.plain).accessibilityLabel("设置本地头像与昵称")
                        VStack(alignment: .leading, spacing: 3) {
                            Text(model.playerTitle).font(.caption).foregroundStyle(.white.opacity(0.9)).lineLimit(2)
                            Text(model.playerName).font(.title3.bold()).foregroundStyle(.white).lineLimit(2)
                        }
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(model.showingPreview ? "RATING 模拟" : "PLAYER RATING").font(.caption2.weight(.semibold)).foregroundStyle(.white.opacity(0.9))
                        Text(model.previewBoard.rating, format: .number.precision(.fractionLength(4))).font(.system(size: 38, weight: .bold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6).foregroundStyle(.white)
                        Text("O20 \(model.board.old.count)/20  ·  N10 \(model.board.new.count)/10  ·  \(model.state.records.count) 条记录").font(.caption).foregroundStyle(.white.opacity(0.9))
                    }
                }.padding(18).frame(maxWidth: .infinity, alignment: .leading).background(Palette.accent, in: RoundedRectangle(cornerRadius: 20))
                MyActivityCard()
                VStack(alignment: .leading, spacing: 8) {
                    if model.busy { ProgressView().accessibilityLabel("正在处理") }
                    HStack(spacing: 10) {
                        if model.folderName.isEmpty { action("选择文件夹", "folder.badge.plus") { chooseFolder = true } }
                        else { action("本机同步", "arrow.triangle.2.circlepath") { model.importScores() } }
                        action("云端同步", "icloud.and.arrow.up") { model.syncCloud(upload: true) }
                    }
                    DisclosureGroup { Text(model.status).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) } label: { Text(model.status).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                    if model.busy { Button("取消当前操作", role: .cancel) { model.cancel() }.frame(minHeight: 44) }
                }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
                VStack(alignment: .leading, spacing: 8) {
                    HStack { Text("我的 B30").font(.headline); Spacer(); Button { model.selectedTab = 1 } label: { Image(systemName: "arrow.up.right") }.accessibilityLabel("查看 B30").frame(width: 44, height: 44) }
                    HStack { Text("O20").foregroundStyle(Palette.accent); Text(model.board.oldRating, format: .number.precision(.fractionLength(4))).monospacedDigit(); Spacer(); Text("N10").foregroundStyle(.purple); Text(model.board.newRating, format: .number.precision(.fractionLength(4))).monospacedDigit() }
                        .font(.caption)
                    HomePosterThumbnail()
                }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
                RatingPreviewControls()
                VStack(alignment: .leading, spacing: 12) {
                    Text("最近游玩 · R50").font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                    if model.recent.isEmpty { Card { Label("还没有当前谱库的成绩", systemImage: "music.note").foregroundStyle(.secondary) } }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 240))], spacing: 12) { ForEach(model.recent) { score in NavigationLink(value: score.chart) { Card { ScoreRow(score: score) } }.buttonStyle(.plain) } }
                }
            }.padding(16).frame(maxWidth: 820)
                .frame(maxWidth: .infinity)
        }
        .navigationTitle("我的节奏").navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $chooseFolder) { FolderPicker { url in chooseFolder = false; model.chooseFolder(url) } }
    }
    private func metric(_ label: String, _ count: Int, _ slots: Int) -> some View { VStack(alignment: .leading) { Text(label).font(.caption).foregroundStyle(.secondary); Text("\(count) / \(slots)").font(.title3.bold()).monospacedDigit() } }
    private func action(_ title: String, _ icon: String, _ action: @escaping () -> Void) -> some View { Button(action: action) { Label(title, systemImage: icon).font(.subheadline).frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered).disabled(model.busy || !model.ready) }
}

struct ArtView: View {
    @EnvironmentObject var model: AppModel
    let chart: Chart
    var side: CGFloat = 64
    @State private var image: UIImage?
    var body: some View {
        ZStack { RoundedRectangle(cornerRadius: 12).fill(Palette.selected); if let image { Image(bitmap: image).resizable().scaledToFill() } else { Image(systemName: "music.note").font(.title2).foregroundStyle(Palette.accent) } }
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
                Text("\(score.chart.levelLabel) · \(score.chart.keysLabel) · \(Rating.judgeName(score.record.judge)) · \(score.record.accuracy, specifier: "%.3f")%").font(.caption).foregroundStyle(.secondary)
                HStack { Text(score.record.score, format: .number); Spacer(); Text(score.rating.isFinite ? "rating " + String(format: "%.4f", score.rating) : "rating 待定").foregroundStyle(Palette.accent) }.font(.subheadline.weight(.semibold)).monospacedDigit()
                Text(Date(timeIntervalSince1970: Double(score.record.playedAt)), format: .dateTime.month().day().hour().minute()).font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

struct ChartRankingPage: View {
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
