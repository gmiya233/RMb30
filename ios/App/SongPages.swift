import SwiftUI
import UIKit
import MalodyCore

struct CharacterBackdrop: View {
    let page: String
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Palette.background
            if let url = Bundle.main.url(forResource: page, withExtension: "png"), let image = UIImage(contentsOfFile: url.path) {
                Image(bitmap: image).resizable().scaledToFit().frame(width: 240, height: 330).opacity(0.12).accessibilityHidden(true)
            }
        }.ignoresSafeArea().allowsHitTesting(false)
    }
}
struct SongsPage: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var field: SongSearchField = .all
    @AppStorage("song-columns") private var columns = 3
    @AppStorage("hide-unrated") private var hideUnrated = false
    var groups: [SongSummary] { SongBrowse.search(model.catalogue.charts, field: field, query: query, hideUnrated: hideUnrated) }
    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Card {
                    HStack {
                        Picker("搜索范围", selection: $field) { ForEach(SongSearchField.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.menu)
                        TextField(field == .level ? "输入等级" : field == .keys ? "输入键数" : "搜索歌曲", text: $query).textFieldStyle(.roundedBorder).keyboardType(field == .level || field == .keys ? .decimalPad : .default).accessibilityLabel("搜索歌曲")
                    }
                    Toggle("隐藏未提供等级的歌曲", isOn: $hideUnrated)
                    Stepper("每行 \(columns) 列", value: $columns, in: 1...5)
                    Text("\(groups.count) 首歌曲").font(.caption).foregroundStyle(.secondary)
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: min(5, max(1, columns))), spacing: 10) {
                    ForEach(groups) { song in NavigationLink(value: song.first) { SongTile(song: song) }.buttonStyle(.plain) }
                }
                if groups.isEmpty { Text("没有找到匹配歌曲").foregroundStyle(.secondary).padding() }
            }.padding(16).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.navigationTitle("歌曲信息").scrollDismissesKeyboard(.interactively)
    }
}
struct SongTile: View {
    let song: SongSummary
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            GeometryReader { proxy in ArtView(chart: song.first, side: proxy.size.width) }.aspectRatio(1, contentMode: .fit)
            Text(song.first.title).font(.subheadline.bold()).lineLimit(2).frame(minHeight: 36, alignment: .top)
            Text(song.first.artist.isEmpty ? "歌手未提供" : song.first.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text(song.maximumLevel.map { "Lv. " + $0.formatted() } ?? "等级未提供").font(.caption.bold()).foregroundStyle(Palette.accent)
        }.padding(8).background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 14))
    }
}
struct SongDetail: View {
    @EnvironmentObject var model: AppModel
    let chart: Chart
    @State private var community: CommunityDocument?
    @State private var message = "正在读取评论…"
    @State private var text = ""
    @State private var stars = 5
    @State private var sending = false
    @State private var copied = false
    var charts: [Chart] { model.catalogue.charts.filter { $0.identity == chart.identity }.sorted { ($0.level ?? -1) < ($1.level ?? -1) } }
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                ArtView(chart: chart, side: 180)
                HStack {
                    Text(chart.title).font(.title2.bold()).textSelection(.enabled)
                    Button { UIPasteboard.general.string = chart.title; copied = true } label: { Image(systemName: copied ? "checkmark" : "doc.on.doc").frame(width: 44, height: 44) }.accessibilityLabel("复制歌曲名称")
                }
                Text(chart.artist.isEmpty ? "歌手未提供" : chart.artist).foregroundStyle(.secondary)
                Text("BPM · " + (chart.bpm.isEmpty ? "未提供" : chart.bpm)).font(.subheadline)
                ForEach(charts) { row in Card {
                    HStack { Text(row.levelLabel).font(.headline); Text(row.keysLabel).font(.caption.bold()).padding(6).background(Palette.selected, in: Capsule()); Spacer(); Label(community?.chartDownloads[row.cid].map(String.init) ?? "—", systemImage: "arrow.down.circle").font(.caption).accessibilityLabel("谱面下载量") }
                    Text(row.version).font(.subheadline)
                    NavigationLink { ChartPreviewScreen(chart: row) } label: { Label("谱面预览", systemImage: "pianokeys").frame(minHeight: 44) }
                    Text("谱师 · " + (row.creator.isEmpty ? "未提供" : row.creator)).font(.subheadline).foregroundStyle(.secondary)
                    NavigationLink("该谱面排行榜") { ChartRankingPage().onAppear { model.rankingCID = row.cid } }.frame(minHeight: 44)
                } }
                Card {
                    Text("评论与评分").font(.title3.bold())
                    if let community { Text("★ \(community.average, specifier: "%.2f") · \(community.votes) 人评分").foregroundStyle(Palette.accent) }
                    Text(message).font(.caption).foregroundStyle(.secondary)
                    HStack { ForEach(1...5, id: \.self) { n in Button { stars = n } label: { Image(systemName: n <= stars ? "star.fill" : "star").foregroundStyle(.orange).frame(minWidth: 44, minHeight: 44) }.accessibilityLabel("\(n)星").accessibilityAddTraits(n == stars ? .isSelected : []) } }
                    TextField("说说你的游玩感受（最多500字）", text: $text, axis: .vertical).lineLimit(3...6).textFieldStyle(.roundedBorder).onChange(of: text) { if $0.utf16.count > 500 { text = String($0.prefix(250)) } }
                    Button {
                        sending = true
                        Task { do { try await model.postReview(chart, stars: stars, text: text); message = "已提交，服务器稍后汇总；再次提交会更新自己的评分"; await reload(keepMessage: true) } catch { message = error.localizedDescription }; sending = false }
                    } label: { Label(sending ? "正在提交…" : "提交评论 / 评分", systemImage: "paperplane").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderedProminent).disabled(sending || model.busy || model.qq.isEmpty)
                    if model.qq.isEmpty { Text("请先在设置中填写自己的 QQ 存档编号").font(.caption).foregroundStyle(.secondary) }
                    ForEach(Array((community?.reviews ?? []).prefix(50))) { review in
                        Divider()
                        HStack { Text(review.name).font(.subheadline.bold()); Spacer(); Text(String(repeating: "★", count: review.stars)).foregroundStyle(.orange) }
                        Text(review.text.isEmpty ? "仅评分" : review.text).font(.subheadline).textSelection(.enabled)
                    }
                }
            }.padding(18).frame(maxWidth: 820).frame(maxWidth: .infinity)
        }.navigationTitle("歌曲详情").navigationBarTitleDisplayMode(.inline).task { await reload() }.refreshable { await reload() }
    }
    private func reload(keepMessage: Bool = false) async {
        do { let result = try await model.loadSongCommunity(chart); community = result.0; if !keepMessage { message = result.1 ? "离线缓存 · 下拉重试" : "每人一份评分，展示最近50条评论" }
            if let own = result.0.reviews.first(where: { $0.qq == model.qq }), text.isEmpty { stars = own.stars; text = own.text }
        } catch { if !keepMessage { message = "评论暂不可用，可下拉刷新" } }
    }
}
