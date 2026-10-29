import SwiftUI
import MalodyCore

struct ExtraRow: Decodable, Identifiable {
    let qq: String
    let name: String
    let total: Int?
    let month: Int?
    let today: Int?
    let rating: Double?
    var id: String { qq }
}
private struct ExtraDocument: Decodable {
    let schema: Int
    let server: String
    let revision: String
    let updated: Double
    let players: [ExtraRow]
}
@MainActor func extraRows(_ model: AppModel, six: Bool) async throws -> [ExtraRow] {
    guard let cloud = model.cloud else { throw MalodyError.invalid("请先连接云端") }
    struct Status: Decodable { let schema: Int; let revision: String }
    let status = try JSONDecoder().decode(Status.self, from: await cloud.get("/_rankings/v2/status.json", limit: 100000))
    guard status.schema == 2, status.revision.count == 64, status.revision.allSatisfy({ "0123456789abcdef".contains($0) }) else { throw MalodyError.invalid("榜单版本无效") }
    let path = "/_rankings/v2/" + status.revision + (six ? "/6k.json" : "/activity.json")
    let doc = try JSONDecoder().decode(ExtraDocument.self, from: await cloud.get(path, limit: 16000000))
    guard doc.schema == 2, doc.server == model.catalogue.server, doc.revision == status.revision,
          doc.players.count <= 10000, Set(doc.players.map(\.qq)).count == doc.players.count,
          doc.updated <= Date().timeIntervalSince1970 * 1000 + 300000,
          doc.players.allSatisfy({ ($0.total ?? 0) >= 0 && ($0.month ?? 0) >= 0 && ($0.today ?? 0) >= 0 && ($0.rating ?? 0).isFinite && ($0.rating ?? 0) >= 0 }) else { throw MalodyError.invalid("榜单数据无效") }
    return doc.players
}
struct ExtraRankingPage: View {
    @EnvironmentObject var model: AppModel
    let mode: Int
    @State private var rows: [ExtraRow] = []
    @State private var status = "正在读取云端…"
    private var title: String { mode == 2 ? "6K rating 排行" : mode == 1 ? "近30天活跃榜" : "累计活跃榜" }
    private func value(_ row: ExtraRow) -> Double { mode == 2 ? row.rating ?? 0 : Double(mode == 1 ? row.month ?? 0 : row.total ?? 0) }
    var body: some View {
        List {
            Text(status).font(.caption).foregroundStyle(.secondary)
            Text(mode == 2 ? "仅6K谱面 · O20+N10 · 缺位按0计" : "云端去重后的有效游玩次数 · 今日按北京时间统计").font(.caption)
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                HStack { Text("#\(rows.firstIndex(where: { value($0) == value(row) })! + 1)"); Text(row.name); Spacer(); Text(mode == 2 ? String(format: "%.4f", value(row)) : "\(Int(value(row)))次").monospacedDigit() }
            }
        }.navigationTitle(title).task { await load() }.refreshable { await load() }
    }
    private func load() async {
        do { rows = try await extraRows(model, six: mode == 2).filter { value($0) > 0 }.sorted { value($0) == value($1) ? $0.qq < $1.qq : value($0) > value($1) }; status = rows.isEmpty ? "暂无云端记录" : "下拉刷新" }
        catch { rows = []; status = error.localizedDescription }
    }
}
struct ExtraPlayerTile: View {
    @EnvironmentObject var model: AppModel
    let row: ExtraRow
    let rank: Int
    let mode: Int
    private var value: String { mode == 2 ? String(format: "%.4f", row.rating ?? 0) : (mode == 1 ? row.month ?? 0 : row.total ?? 0).formatted() }
    var body: some View {
        VStack(spacing: 10) {
            Text("#\(rank)").font(.subheadline.bold()).foregroundStyle(rank <= 3 ? Color(red: 0.58, green: 0.38, blue: 0) : .secondary).frame(maxWidth: .infinity, alignment: .leading)
            Text(String(row.name.prefix(1)).uppercased()).font(.title2.bold()).frame(width: 48, height: 48).foregroundStyle(Palette.accent).background(Palette.selected, in: RoundedRectangle(cornerRadius: 16))
            Text(row.name).font(.subheadline.bold()).lineLimit(2).frame(height: 38)
            Text(value).font(.system(size: 25, weight: .bold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.65).foregroundStyle(Palette.accent)
            Text(mode == 2 ? "6K · O20 + N10" : "次游玩").font(.caption).foregroundStyle(.secondary)
        }.padding(16).frame(maxWidth: .infinity).frame(height: 202).background(row.qq == model.qq ? Palette.selected : Color.white.opacity(0.96), in: RoundedRectangle(cornerRadius: 22)).overlay(RoundedRectangle(cornerRadius: 22).stroke(Palette.ink.opacity(0.10), lineWidth: 1))
    }
}
struct ExtraRankingStrip: View {
    @EnvironmentObject var model: AppModel
    let mode: Int
    @State private var rows: [ExtraRow] = []
    @State private var status = "正在读取云端…"
    private var title: String { mode == 2 ? "6K 排行" : mode == 1 ? "近30天活跃榜" : "累计活跃榜" }
    private func value(_ r: ExtraRow) -> Double { mode == 2 ? r.rating ?? 0 : Double(mode == 1 ? r.month ?? 0 : r.total ?? 0) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Text(title).font(.title3.bold()); Spacer(); Text("左右滑动").font(.caption).foregroundStyle(.secondary) }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(Array(rows.prefix(10))) { row in ExtraPlayerTile(row: row, rank: (rows.firstIndex { value($0) == value(row) } ?? 0) + 1, mode: mode).frame(width: 170) }
                    NavigationLink { ExtraRankingPage(mode: mode) } label: { Label("查看更多", systemImage: "arrow.right.circle").frame(width: 135, height: 202).background(Palette.selected, in: RoundedRectangle(cornerRadius: 22)) }.buttonStyle(.plain)
                }
            }
            if rows.isEmpty { Text(status).font(.caption).foregroundStyle(.secondary) }
        }.task { await load() }
    }
    private func load() async {
        do { rows = try await extraRows(model, six: mode == 2).filter { value($0) > 0 }.sorted { value($0) == value($1) ? $0.qq < $1.qq : value($0) > value($1) }; status = "暂无云端记录" }
        catch { status = "更新未完成，进入查看更多重试" }
    }
}
struct MyActivityCard: View {
    @EnvironmentObject var model: AppModel
    @State private var counts: [Int]?
    @State private var status = "正在读取云端…"
    var body: some View {
        Card {
            HStack {
                Label("我的活跃度", systemImage: "chart.bar.xaxis").font(.headline).foregroundStyle(Palette.ink)
                Spacer()
                Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel("刷新我的活跃度")
            }
            HStack(spacing: 22) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("今日游玩").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.accent)
                    Text(counts.map { $0[0].formatted() } ?? "—").font(.system(size: 42, weight: .bold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
                    Text("次").font(.caption).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading).padding(18).background(Palette.selected, in: RoundedRectangle(cornerRadius: 20))
                VStack(alignment: .leading, spacing: 16) {
                    activityValue("近30天", index: 1)
                    activityValue("累计游玩", index: 2)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(counts == nil ? status : "云端去重记录 · 今日按北京时间统计").font(.caption).foregroundStyle(.secondary)
        }.task(id: model.qq + String(model.state.records.count)) { await load() }
    }
    private func activityValue(_ label: String, index: Int) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(counts.map { $0[index].formatted() } ?? "—").font(.system(size: 25, weight: .semibold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)
        }
    }
    private func load() async {
        status = "正在更新云端记录…"
        guard !model.qq.isEmpty else { counts = nil; status = "设置QQ并同步云端后查看活跃度"; return }
        do { let rows = try await extraRows(model, six: false); let me = rows.first { $0.qq == model.qq }; counts = [me?.today ?? 0, me?.month ?? 0, me?.total ?? 0] }
        catch { status = "更新未完成，点刷新重试" }
    }
}
