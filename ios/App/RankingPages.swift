import SwiftUI
import MalodyCore

struct RankingPage: View {
    @EnvironmentObject var model: AppModel
    @State private var category = "total"
    @State private var extra: [String: [ExtraRow]] = [:]
    @State private var extraStatus = "正在读取云端…"
    @AppStorage("show-hidden-creators") private var showHidden = false
    var body: some View {
        VStack(spacing: 12) {
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(categories, id: \.0) { item in
                            Button { category = item.0 } label: {
                                Text(item.1).font(.subheadline.weight(category == item.0 ? .semibold : .regular)).lineLimit(1).fixedSize(horizontal: true, vertical: false).padding(.horizontal, 16).frame(minHeight: 48)
                                    .foregroundStyle(category == item.0 ? Palette.accent : Color.secondary)
                                    .background(category == item.0 ? Palette.selected : Color.clear, in: RoundedRectangle(cornerRadius: 16))
                            }.buttonStyle(.plain).accessibilityAddTraits(category == item.0 ? .isSelected : []).id(item.0)
                        }
                    }
                }.accessibilityLabel("榜单分类，左右滑动查看更多").onChange(of: category) { value in withAnimation(.easeInOut(duration: 0.2)) { proxy.scrollTo(value, anchor: .center) } }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(isExtra ? extraStatus : model.communityStatus).font(.caption).foregroundStyle(.secondary)
                    if isPlayer && !entries.isEmpty { CompetitionPodium(entries: Array(entries.prefix(3))) }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(boardTitle).font(.title3.bold())
                        Text(isPlayer ? (boardID.hasPrefix("activity_") ? "云端有效游玩记录 · 去重后计次" : "O20 + N10 · 缺位按0计") : "热度按下载次数 · 好评按平均星级").font(.caption).foregroundStyle(.secondary)
                    }
                    if boardID == "creator_hot" { Toggle("显示其他默认隐藏谱师", isOn: $showHidden).font(.caption) }
                    if entries.isEmpty { Text("暂无已发布数据，可下拉刷新重试").font(.subheadline).foregroundStyle(.secondary).padding(.vertical, 24) }
                    LazyVStack(spacing: 6) {
                        ForEach(Array(entries.prefix(50))) { entry in
                            if let chart = model.catalogue.byCID[entry.cid], boardID.hasPrefix("song_") {
                                NavigationLink(value: chart) { CompetitionListRow(entry: entry, mine: false) }.buttonStyle(.plain)
                            } else { CompetitionListRow(entry: entry, mine: isPlayer && entry.id == model.qq) }
                        }
                    }
                }.padding(.bottom, 12)
            }.refreshable { await refresh() }.id(boardID)
            if isPlayer { myPosition }
        }.padding(.horizontal, 18).frame(maxWidth: 760).frame(maxWidth: .infinity)
        .navigationTitle("排行榜").task { await refresh() }
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                NavigationLink { SongsPage().navigationTitle("选歌查看单曲榜") } label: { Image(systemName: "music.note.list").frame(width: 44, height: 44) }.accessibilityLabel("选歌查看单曲榜")
                Button { Task { await refresh() } } label: { Image(systemName: "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel("刷新榜单")
            }
        }
        .navigationDestination(isPresented: Binding(get: { model.rankingCID != 0 }, set: { if !$0 { model.rankingCID = 0 } })) { ChartRankingPage() }
    }
    private func rows(_ key: String) -> [CommunityRow] { model.communityBoards?.boards[key == "creator_hot" && showHidden ? "creator_hot_all" : key] ?? [] }
    private let categories = [("total", "总战力"), ("six_k", "6K rating"), ("activity_month", "近30天活跃"), ("activity_total", "累计活跃"), ("song_hot", "歌曲热度"), ("song_good", "歌曲好评"), ("creator_hot", "谱师热度"), ("creator_good", "谱师好评")]
    private var boardID: String { category }
    private var isExtra: Bool { boardID == "six_k" || boardID.hasPrefix("activity_") }
    private var isPlayer: Bool { boardID == "total" || isExtra }
    private var boardTitle: String { boardID == "six_k" ? "6K rating 排行" : boardID == "activity_month" ? "近30天活跃榜" : boardID == "activity_total" ? "累计活跃榜" : CommunityDocument.sections.first { $0.0 == boardID }?.1 ?? "排行榜" }
    private var entries: [CompetitionEntry] {
        if isExtra {
            let six = boardID == "six_k", month = boardID == "activity_month"
            func score(_ r: ExtraRow) -> Double { six ? r.rating ?? 0 : Double(month ? r.month ?? 0 : r.total ?? 0) }
            let sorted = (extra[six ? "six_k" : "activity"] ?? []).filter { score($0) > 0 }.sorted { score($0) == score($1) ? $0.qq < $1.qq : score($0) > score($1) }
            var result: [CompetitionEntry] = [], previous: Double?, rank = 0
            for (index, row) in sorted.enumerated() {
                let value = score(row)
                if value != previous { rank = index + 1 }; previous = value
                result.append(CompetitionEntry(id: row.qq, name: row.name, rank: rank, value: six ? String(format: "%.4f", value) : (month ? row.month ?? 0 : row.total ?? 0).formatted(), unit: six ? "rating" : "次游玩", subtitle: six ? "6K · O20 + N10" : "有效游玩次数", cid: 0))
            }
            return result
        }
        return rows(boardID).map { row in
            CompetitionEntry(id: row.id, name: row.name, rank: row.rank, value: boardID == "total" ? String(format: "%.4f", row.rating) : boardID.hasSuffix("good") ? String(format: "%.2f", row.average) : row.downloads.formatted(), unit: boardID == "total" ? "rating" : boardID.hasSuffix("good") ? "星 · \(row.votes)评" : "次下载", subtitle: boardID == "total" ? "O20 + N10" : "", cid: row.cid)
        }
    }
    private var myPosition: some View {
        HStack {
            if let me = entries.first(where: { $0.id == model.qq }) {
                Text("我的名次 #\(me.rank)").font(.subheadline.bold()); Spacer(); Text(me.value + " " + me.unit).font(.subheadline.bold()).monospacedDigit()
            } else { Text(model.qq.isEmpty ? "设置QQ并同步后查看我的名次" : "我暂未进入已发布榜单").font(.subheadline) }
        }.foregroundStyle(Palette.accent).padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Palette.selected, in: RoundedRectangle(cornerRadius: 16))
    }
    private func refresh() async {
        await model.loadCommunity()
        do { extra["activity"] = try await extraRows(model, six: false); extraStatus = "云端已更新" } catch { extraStatus = "更新未完成，保留已有数据，可下拉重试" }
        do { extra["six_k"] = try await extraRows(model, six: true) } catch { extraStatus = "部分榜单更新未完成，保留已有数据" }
    }
}
private struct CompetitionEntry: Identifiable {
    let id: String, name: String
    let rank: Int
    let value: String, unit: String, subtitle: String
    let cid: Int
}
struct RankingMedal: View {
    let rank: Int
    private var color: Color { rank == 1 ? Color(red: 0.65, green: 0.43, blue: 0.06) : rank == 2 ? Color(red: 0.39, green: 0.47, blue: 0.52) : Color(red: 0.63, green: 0.36, blue: 0.20) }
    var body: some View {
        VStack(spacing: 1) {
            Image(systemName: "medal.fill").font(.system(size: 28)).foregroundStyle(color)
            Text("第\(rank)名").font(.caption2.bold()).foregroundStyle(color)
        }.accessibilityElement(children: .ignore).accessibilityLabel("第\(rank)名奖牌")
    }
}
private struct CompetitionPodium: View {
    let entries: [CompetitionEntry]
    private var ordered: [CompetitionEntry] { entries.count == 3 ? [entries[1], entries[0], entries[2]] : entries }
    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            ForEach(ordered) { entry in
                VStack(spacing: 8) {
                    RankingMedal(rank: entry.rank)
                    Text(String(entry.name.prefix(1)).uppercased()).font(.title2.bold()).frame(width: 40, height: 40).background(Palette.selected, in: RoundedRectangle(cornerRadius: 12))
                    Text(entry.name).font(.caption.bold()).lineLimit(2).frame(height: 36)
                    Text(entry.value).font(.system(size: 19, weight: .bold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)
                    Text(entry.unit).font(.caption2).foregroundStyle(.secondary)
                }.foregroundStyle(Palette.accent).padding(.vertical, entry.rank == 1 ? 24 : 14).padding(.horizontal, 8).frame(maxWidth: .infinity).background(entry.rank == 1 ? Palette.selected : Color.white, in: RoundedRectangle(cornerRadius: 20))
            }
        }
    }
}
private struct CompetitionListRow: View {
    @EnvironmentObject var model: AppModel
    let entry: CompetitionEntry
    let mine: Bool
    var body: some View {
        HStack(spacing: 10) {
            Group { if entry.rank <= 3 { RankingMedal(rank: entry.rank) } else { Text(String(entry.rank)).font(.subheadline).foregroundStyle(.secondary) } }.frame(width: 36)
            if let chart = model.catalogue.byCID[entry.cid] { ArtView(chart: chart, side: 36) }
            else { Text(String(entry.name.prefix(1)).uppercased()).font(.headline).frame(width: 36, height: 36).foregroundStyle(Palette.accent).background(Palette.selected, in: RoundedRectangle(cornerRadius: 12)) }
            VStack(alignment: .leading, spacing: 3) { Text(entry.name + (mine ? " · 我" : "")).font(.subheadline.bold()).lineLimit(2); if !entry.subtitle.isEmpty { Text(entry.subtitle).font(.caption).foregroundStyle(.secondary) } }.frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 3) { Text(entry.value).font(.system(size: 17, weight: .semibold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.65); Text(entry.unit).font(.caption).foregroundStyle(.secondary) }.frame(width: 94, alignment: .trailing).foregroundStyle(Palette.accent)
        }.padding(12).background(mine ? Palette.selected : Color.white, in: RoundedRectangle(cornerRadius: 16))
    }
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
