import SwiftUI
import UIKit
import MalodyCore

/// Fixed export geometry; the interactive preview itself fits the device width.
struct PosterCanvas: View {
    let board: ScoreBoard
    let player: String
    let title: String
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 16), count: 5)
    var body: some View {
        VStack(spacing: 24) {
            HStack(alignment: .top, spacing: 40) {
                HStack(spacing: 26) {
                    Image(systemName: "person.crop.square.fill").resizable().scaledToFit().foregroundStyle(Palette.accent).frame(width: 142, height: 142)
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
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .top, spacing: 10) {
                ZStack(alignment: .topLeading) {
                    if let image = UIImage(contentsOfFile: LocalPaths.jacket(row.chart).path) { Image(uiImage: image).resizable().scaledToFill().frame(width: 90, height: 90).clipped() }
                    else { Image(systemName: "music.note").resizable().scaledToFit().padding(22).frame(width: 90, height: 90).background(Palette.selected).foregroundStyle(color) }
                    Text(String(format: "%02d", index)).font(.system(size: 15, weight: .bold)).padding(3).background(color).foregroundStyle(.white)
                }.clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 5) {
                    Text(row.chart.title).font(.system(size: 21, weight: .semibold)).lineLimit(2).frame(height: 50, alignment: .top)
                    Text(row.chart.levelLabel).font(.system(size: 19, weight: .bold)).foregroundStyle(color)
                    Text(row.record.score.formatted(.number.locale(Locale(identifier: "en_US")))).font(.system(size: 26, weight: .bold)).lineLimit(1).minimumScaleFactor(0.65)
                }
            }
            HStack { Text(Rating.judgeName(row.record.judge)); Spacer(); Text("\(row.record.accuracy, specifier: "%.3f")%") }.font(.system(size: 19)).foregroundStyle(color)
            HStack { Text("rating").font(.system(size: 19)); Text(row.rating, format: .number.precision(.fractionLength(4))).font(.system(size: 24, weight: .bold)).foregroundStyle(color).monospacedDigit() }
        }.padding(12).frame(height: 166).frame(maxWidth: .infinity, alignment: .leading).background(.white.opacity(0.96), in: RoundedRectangle(cornerRadius: 14))
    }
}

struct B30Page: View {
    @EnvironmentObject var model: AppModel
    @State private var image: UIImage?
    @State private var generating = false
    @State private var sharing = false
    @State private var zooming = false
    @State private var choosingTitle = false
    @State private var title = ""
    private var signature: String { (model.board.old + model.board.new).map(\.record.id).joined() + model.playerName + model.playerTitle }
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
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
        for row in model.board.old + model.board.new { if Task.isCancelled { return }; _ = await model.art(row.chart) }
        guard !Task.isCancelled else { return }
        let renderer = ImageRenderer(content: PosterCanvas(board: model.board, player: model.playerName, title: model.playerTitle)); renderer.scale = 1
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
