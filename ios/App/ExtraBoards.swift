import SwiftUI
import MalodyCore

private struct ExtraRow: Decodable, Identifiable {
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
@MainActor private func extraRows(_ model: AppModel, six: Bool) async throws -> [ExtraRow] {
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
struct MyActivityCard: View {
    @EnvironmentObject var model: AppModel
    @State private var counts: [Int]?
    @State private var status = "正在读取云端…"
    var body: some View {
        Card {
            HStack { Text("我的活跃度").font(.headline); Spacer(); Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("刷新我的活跃度") }
            if let counts {
                HStack { ForEach(0..<3) { i in VStack { Text(["今日", "近30天", "累计"][i]).font(.caption); Text("\(counts[i])次").font(.headline).monospacedDigit() }.frame(maxWidth: .infinity) } }
                Text("按云端去重记录 · 今日按北京时间统计").font(.caption).foregroundStyle(.secondary)
            } else { Text(status).font(.caption).foregroundStyle(.secondary) }
        }.task(id: model.qq + String(model.state.records.count)) { await load() }
    }
    private func load() async {
        counts = nil
        guard !model.qq.isEmpty else { status = "请先设置QQ并同步云端"; return }
        do { let rows = try await extraRows(model, six: false); let me = rows.first { $0.qq == model.qq }; counts = [me?.today ?? 0, me?.month ?? 0, me?.total ?? 0] }
        catch { status = "暂不可用，请同步云端后刷新" }
    }
}
