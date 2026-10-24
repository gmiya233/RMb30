import SwiftUI
import MalodyCore

struct RankingPage: View {
    @EnvironmentObject var model: AppModel
    @AppStorage("show-hidden-creators") private var showHidden = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                NavigationLink { SongsPage().navigationTitle("选歌查看单曲榜") } label: { Label("单曲榜 · 选择歌曲", systemImage: "music.note.list").frame(maxWidth: .infinity, minHeight: 48) }.buttonStyle(.borderedProminent)
                Text(model.communityStatus).font(.caption).foregroundStyle(.secondary)
                ForEach(CommunityDocument.sections, id: \.0) { section in
                    VStack(alignment: .leading, spacing: 10) {
                        Text(section.1).font(.title3.bold())
                        if section.0 == "creator_hot" { Toggle("显示其他默认隐藏谱师", isOn: $showHidden).font(.caption) }
                        ScrollView(.horizontal) {
                            HStack(spacing: 12) {
                                ForEach(Array(rows(section.0).prefix(5))) { row in CommunityTile(row: row, section: section.0).frame(width: 180) }
                                NavigationLink { CommunityList(section: section.0, title: section.1, rows: rows(section.0)) } label: { Label("查看更多\n前50名", systemImage: "arrow.right.circle").frame(width: 135, height: 140).background(Palette.selected, in: RoundedRectangle(cornerRadius: 18)) }
                            }
                        }
                        if rows(section.0).isEmpty { Text("暂无榜单数据").font(.caption).foregroundStyle(.secondary) }
                    }
                }
                NavigationLink("查看实时总战力榜") { ChartRankingPage().onAppear { model.rankingCID = 0 } }.frame(minHeight: 44)
            }.padding(18).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.navigationTitle("排行榜").task { await model.loadCommunity() }.refreshable { await model.loadCommunity() }
        .navigationDestination(isPresented: Binding(get: { model.rankingCID != 0 }, set: { if !$0 { model.rankingCID = 0 } })) { ChartRankingPage() }
    }
    private func rows(_ key: String) -> [CommunityRow] { model.communityBoards?.boards[key == "creator_hot" && showHidden ? "creator_hot_all" : key] ?? [] }
}
struct CommunityTile: View {
    @EnvironmentObject var model: AppModel
    let row: CommunityRow
    let section: String
    var body: some View {
        Group {
            if let chart = model.catalogue.byCID[row.cid], section.hasPrefix("song_") { NavigationLink(value: chart) { content(chart) }.buttonStyle(.plain) }
            else { content(nil) }
        }
    }
    private func content(_ chart: Chart?) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack { Text("#\(row.rank)").font(.headline).foregroundStyle(row.rank <= 3 ? Color.orange : Palette.accent); Spacer(); if let chart { ArtView(chart: chart, side: 44) } }
            Text(row.name).font(.subheadline.bold()).lineLimit(2).frame(minHeight: 36, alignment: .top)
            if section == "total" { Text("rating \(row.rating, specifier: "%.4f")").font(.subheadline.bold()).foregroundStyle(Palette.accent) }
            else if section.hasSuffix("good") { Label("\(row.average, specifier: "%.2f") · \(row.votes)人", systemImage: "star.fill").font(.caption).foregroundStyle(.orange) }
            else { Label(row.downloads.formatted(), systemImage: "arrow.down.circle").font(.subheadline).foregroundStyle(Palette.accent) }
        }.padding(16).frame(maxWidth: .infinity, minHeight: 140, alignment: .topLeading).background(.white.opacity(0.94), in: RoundedRectangle(cornerRadius: 18))
    }
}
struct CommunityList: View {
    let section: String, title: String
    let rows: [CommunityRow]
    var body: some View { ScrollView { LazyVGrid(columns: [GridItem(.adaptive(minimum: 250))], spacing: 12) { ForEach(rows) { CommunityTile(row: $0, section: section) } }.padding(18) }.navigationTitle(title + " · 前50") }
}
