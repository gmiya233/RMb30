import SwiftUI
import UIKit
import MalodyCore

/// Fixed export geometry; the interactive preview itself fits the device width.
struct PosterCanvas: View {
    let board: ScoreBoard
    let player: String
    let title: String
    var avatar: Data? = nil
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 16), count: 5)
    var body: some View {
        VStack(spacing: 24) {
            HStack(alignment: .top, spacing: 40) {
                HStack(spacing: 26) {
                    PlayerAvatar(data: avatar, side: 142)
                    VStack(alignment: .leading, spacing: 12) {
                        Text(title).font(.system(size: 26, weight: .semibold)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading).padding(12).background(Color.yellow.opacity(0.22), in: RoundedRectangle(cornerRadius: 10))
                        Text(player).font(.system(size: 46, weight: .bold)).lineLimit(1).minimumScaleFactor(0.5)
                        Text("rating \(board.rating, specifier: "%.4f")").font(.system(size: 29, weight: .bold)).foregroundStyle(Palette.accent).monospacedDigit()
                    }
                }.padding(25).frame(maxWidth: .infinity).background(.white.opacity(0.94), in: RoundedRectangle(cornerRadius: 22))
                VStack(spacing: 12) {
                    Text("节奏大师").font(.system(size: 62, weight: .heavy)).italic().foregroundStyle(Palette.accent)
                    Text("RHYTHM MASTER").font(.system(size: 21, weight: .semibold)).foregroundStyle(Palette.accent)
                    VStack(spacing: 7) { Text("PLAYER RATING").font(.system(size: 20, weight: .semibold)); Text(board.rating, format: .number.precision(.fractionLength(4))).font(.system(size: 48, weight: .bold)).foregroundStyle(.purple).monospacedDigit() }.frame(maxWidth: .infinity).padding(16).background(.white.opacity(0.9), in: RoundedRectangle(cornerRadius: 20))
                }.frame(width: 400)
            }
            section("O20", subtitle: "日常歌曲", entries: board.old, count: 20, rating: board.oldRating, color: Palette.accent)
            section("N10", subtitle: "promote 新歌", entries: board.new, count: 10, rating: board.newRating, color: .purple)
            Spacer(minLength: 0)
            HStack { Text("O20 + N10 · 缺位按0计"); Spacer(); Text("Malody Link") }.font(.system(size: 22)).foregroundStyle(Palette.ink.opacity(0.65))
        }
        .padding(45).frame(width: 1500, height: 1664, alignment: .top)
        .background(LinearGradient(colors: [Color(red: 0.50, green: 0.96, blue: 0.90), Color(red: 0.83, green: 0.95, blue: 1), Color(red: 0.88, green: 0.80, blue: 0.99)], startPoint: .topLeading, endPoint: .bottomTrailing))
        .foregroundStyle(Palette.ink).environment(\.colorScheme, .light).environment(\.locale, Locale(identifier: "zh_CN"))
    }
    private func section(_ name: String, subtitle: String, entries: [RatedScore], count: Int, rating: Double, color: Color) -> some View {
        VStack(spacing: 16) {
            HStack(spacing: 22) { Text(name).font(.system(size: 36, weight: .bold)).foregroundStyle(color); Text(subtitle).font(.system(size: 24)); Spacer(); Text("rating \(rating, specifier: "%.4f")").font(.system(size: 29, weight: .bold)).foregroundStyle(color).monospacedDigit(); Text("\(entries.count) / \(count)").font(.system(size: 24)) }.padding(.horizontal, 20).padding(.vertical, 12).background(.white.opacity(0.85), in: RoundedRectangle(cornerRadius: 12))
            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(0..<count, id: \.self) { index in
                    if index < entries.count { posterCard(entries[index], index: index + 1, color: color) }
                    else { VStack(spacing: 10) { Image(systemName: "music.note").font(.system(size: 30)); Text("待收录").font(.system(size: 25)); Text("SLOT \(index + 1)").font(.system(size: 18)) }.foregroundStyle(Palette.ink.opacity(0.36)).frame(maxWidth: .infinity).frame(height: 166).background(.white.opacity(0.48), in: RoundedRectangle(cornerRadius: 14)) }
                }
            }
        }
    }
    private func posterCard(_ row: RatedScore, index: Int, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .top, spacing: 10) {
                ZStack(alignment: .topLeading) {
                    if let image = UIImage(contentsOfFile: LocalPaths.jacket(row.chart).path) { Image(uiImage: image).resizable().scaledToFill().frame(width: 80, height: 94).clipped() }
                    else { Image(systemName: "music.note").resizable().scaledToFit().padding(22).frame(width: 80, height: 94).background(Palette.selected).foregroundStyle(color) }
                    Text(String(format: "%02d", index)).font(.system(size: 15, weight: .bold)).padding(3).background(color).foregroundStyle(.white)
                }.clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 3) {
                    Text(row.chart.title).font(.system(size: 19, weight: .semibold)).lineLimit(2).frame(height: 44, alignment: .top)
                    Text(row.displayedLevel + " · " + row.chart.keysLabel + (row.simulated ? " *" : "")).font(.system(size: 17, weight: .bold)).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.65)
                    Text(row.record.score.formatted(.number.locale(Locale(identifier: "en_US")))).font(.system(size: 23, weight: .bold)).lineLimit(1).minimumScaleFactor(0.65)
                }.frame(height: 94, alignment: .top)
            }
            HStack { Text(Rating.judgeName(row.displayedJudge)); Spacer(); Text("\(row.displayedAccuracy, specifier: "%.3f")%") }.font(.system(size: 16)).foregroundStyle(color)
            HStack { Text("rating").font(.system(size: 16)); Text(row.rating, format: .number.precision(.fractionLength(4))).font(.system(size: 20, weight: .bold)).foregroundStyle(color).monospacedDigit() }
        }.padding(8).frame(height: 166).frame(maxWidth: .infinity, alignment: .leading).background(.white.opacity(0.96), in: RoundedRectangle(cornerRadius: 14))
    }
}

struct B30Page: View {
    @EnvironmentObject var model: AppModel
    @State private var image: UIImage?
    @State private var generating = false
    @State private var sharing = false
    @State private var zooming = false
    @State private var choosingTitle = false
    @State private var demo = false
    private var displayedBoard: ScoreBoard { demo ? ExamplePoster.board(model.catalogue) : model.previewBoard }
    @State private var title = ""
    private var signature: String { String(demo) + (model.previewBoard.old + model.previewBoard.new).map(\.record.id).joined() + model.playerName + model.playerTitle + String(describing: model.preview) + String(model.showingPreview) + String(model.state.avatar?.hashValue ?? 0) }
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Button { model.syncCloud(upload: true) } label: { Label("上传成绩到服务器和排行榜", systemImage: "icloud.and.arrow.up").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderedProminent).tint(.green).disabled(model.busy || !model.ready)
                Toggle("查看示例布局", isOn: $demo)
                if demo { Text("示例成绩，仅用于查看排版，不参与同步与排行").font(.caption).foregroundStyle(.orange) }
                RatingPreviewControls()
                Text(model.status).font(.caption).foregroundStyle(.secondary)
                if model.showingPreview { Text("当前为模拟成绩图").font(.caption).foregroundStyle(.orange) }
                HStack(spacing: 16) {
                    icon("square.and.arrow.up", label: "保存或分享成绩图") { sharing = true }.disabled(image == nil)
                    icon("rosette", label: "选择称号") { title = model.state.title; choosingTitle = true }
                    icon("arrow.up.left.and.arrow.down.right", label: "放大成绩图") { zooming = true }.disabled(image == nil)
                    Spacer()
                    if generating { ProgressView().accessibilityLabel("正在生成成绩图") }
                }
                if let image { Image(uiImage: image).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 14)).onTapGesture { zooming = true }.accessibilityLabel("O20 加 N10 成绩图") }
                else { ProgressView("正在生成成绩图…").frame(minHeight: 300) }
            }.padding(18).frame(maxWidth: 900).frame(maxWidth: .infinity)
        }.navigationTitle("我的 B30")
            .task(id: signature) { await render() }
            .sheet(isPresented: $sharing) { if let image { ShareSheet(items: [image]) } }
            .fullScreenCover(isPresented: $zooming) { NavigationStack { if let image { ZoomImage(image: image).background(.black).ignoresSafeArea(edges: .bottom).toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { zooming = false } } } } } }
            .sheet(isPresented: $choosingTitle) {
                NavigationStack { List { Button("自动选择") { Task { await model.saveProfile(name: model.state.nickname, title: ""); choosingTitle = false } }; ForEach(model.unlockedTitles, id: \.self) { name in Button(name) { Task { await model.saveProfile(name: model.state.nickname, title: name); choosingTitle = false } } } }.navigationTitle("选择称号").toolbar { Button("关闭") { choosingTitle = false } } }
            }
    }
    private func icon(_ system: String, label: String, action: @escaping () -> Void) -> some View { Button(action: action) { Image(systemName: system).font(.title3).frame(width: 52, height: 50) }.buttonStyle(.bordered).accessibilityLabel(label) }
    private func render() async {
        generating = true; defer { generating = false }
        for row in displayedBoard.old + displayedBoard.new { if Task.isCancelled { return }; _ = await model.art(row.chart) }
        guard !Task.isCancelled else { return }
        let renderer = ImageRenderer(content: PosterCanvas(board: displayedBoard, player: demo ? "示例玩家 · DEMO" : model.playerName, title: demo ? "示例布局" : model.playerTitle, avatar: demo ? nil : model.state.avatar)); renderer.scale = 1
        image = renderer.uiImage
    }
}

struct ZoomImage: UIViewRepresentable {
    let image: UIImage
    func makeUIView(context: Context) -> UIScrollView {
        let scroll = ZoomScroll(); scroll.minimumZoomScale = 1; scroll.maximumZoomScale = 5; scroll.delegate = context.coordinator
        scroll.picture.image = image; scroll.picture.contentMode = .scaleAspectFit; scroll.addSubview(scroll.picture)
        return scroll
    }
    func updateUIView(_ scroll: UIScrollView, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator() }
    final class Coordinator: NSObject, UIScrollViewDelegate { func viewForZooming(in scrollView: UIScrollView) -> UIView? { (scrollView as? ZoomScroll)?.picture } }
    final class ZoomScroll: UIScrollView {
        let picture = UIImageView()
        private var previous = CGSize.zero
        override func layoutSubviews() { super.layoutSubviews(); if bounds.size != previous { previous = bounds.size; zoomScale = 1; picture.frame = bounds; contentSize = bounds.size } }
    }
}

struct HomePosterThumbnail: View {
    @EnvironmentObject var model: AppModel
    @State private var image: UIImage?
    private var signature: String { (model.board.old + model.board.new).map(\.record.id).joined() + model.playerName + model.playerTitle + String(model.state.avatar?.hashValue ?? 0) }
    var body: some View {
        Button { model.selectedTab = 1 } label: {
            Group { if let image { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 420) } else { ProgressView("生成成绩缩略图…").frame(height: 180) } }.frame(maxWidth: .infinity)
        }.buttonStyle(.plain).accessibilityLabel("查看完整 B30 成绩图")
        .task(id: signature) {
            for row in model.board.old + model.board.new { if Task.isCancelled { return }; _ = await model.art(row.chart) }
            guard !Task.isCancelled else { return }
            let renderer = ImageRenderer(content: PosterCanvas(board: model.board, player: model.playerName, title: model.playerTitle, avatar: model.state.avatar)); renderer.scale = 0.5; image = renderer.uiImage
        }
    }
}

/// Demonstration records exist only in this view's rendered board, never LocalState.
enum ExamplePoster {
    static func board(_ catalogue: Catalogue) -> ScoreBoard {
        var selected: [Chart] = [], seen = Set<String>(), old = 0, fresh = 0
        for chart in catalogue.charts.sorted(by: { $0.cid < $1.cid }) where chart.level != nil {
            let isNew = catalogue.promotedSongs.contains(chart.identity)
            if seen.contains(chart.identity) || (isNew ? fresh >= 10 : old >= 20) { continue }
            seen.insert(chart.identity); selected.append(chart)
            if isNew { fresh += 1 } else { old += 1 }
            if old == 20 && fresh == 10 { break }
        }
        let records = selected.enumerated().compactMap { index, chart -> ScoreRecord? in
            let row: [String: Any] = ["id": "\(chart.cid):0:" + String(repeating: "0", count: 32), "cid": chart.cid, "server_hash": chart.serverHash,
                "chart_md5": String(repeating: "0", count: 32), "player": "DEMO", "played_at": 0, "score_candidate": 1_000_000 - index * 1000,
                "accuracy_candidate": 99.9 - Double(index % 10) * 0.1, "raw_score_fields": ["4": 4], "verification": "preview_only", "field_mapping_verified": false, "uploaded": false]
            guard let data = try? JSONSerialization.data(withJSONObject: row) else { return nil }
            return try? JSONCoding.decode(ScoreRecord.self, data)
        }
        return ScoreBoard(records: records, catalogue: catalogue)
    }
}
