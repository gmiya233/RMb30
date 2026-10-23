import Foundation
import SwiftUI
import MalodyCore

@MainActor final class AppModel: ObservableObject {
    @Published var catalogue = Catalogue(server: "http://47.111.184.207:5244", charts: [])
    @Published var state = LocalState()
    @Published var board = ScoreBoard(records: [], catalogue: Catalogue(server: "", charts: []))
    @Published var recent: [RatedScore] = []
    @Published var qq = UserDefaults.standard.string(forKey: "profile-qq") ?? ""
    @Published var folderName = UserDefaults.standard.string(forKey: "malody-folder-name") ?? ""
    @Published var busy = false
    @Published var ready = false
    @Published var status = "正在准备本地谱库…"
    @Published var error: String?
    @Published var selectedTab = 0
    @Published var rankingCID = 0
    @Published var lastImport: ImportReport?
    @Published var cloudBoard: RemoteBoard?
    @Published var rankingStatus = ""
    @Published var rankingLoading = false
    private let store = StateStore(), importer = DirectoryImporter(), catalogues = CatalogueStore()
    private var cloud: CloudGateway?
    private var task: Task<Void, Never>?
    private var rankRequest = 0
    @Published private(set) var loadedRankingCID: Int?

    init() { Task { await startup() } }
    var playerName: String {
        if !state.nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return state.nickname }
        return state.records.max(by: { $0.playedAt < $1.playedAt })?.player ?? "PLAYER"
    }
    var unlockedTitles: [String] {
        var names = ["小师妹"]
        for (threshold, title) in [(10.0, "萌新"), (11.0, "高手"), (12.0, "大师"), (13.0, "龙币")] where board.rating >= threshold { names.append(title) }
        let charts = catalogue.byCID
        let songs = Set(catalogue.filtered(state.records).filter { $0.accuracy > 99.8 }.compactMap { charts[$0.cid]?.title }).sorted()
        names.append(contentsOf: songs.filter { !names.contains($0) }); return names
    }
    var playerTitle: String {
        if !state.title.isEmpty && unlockedTitles.contains(state.title) { return state.title }
        return board.rating >= 13 ? "龙币" : board.rating >= 12 ? "大师" : board.rating >= 11 ? "高手" : board.rating >= 10 ? "萌新" : "小师妹"
    }
    private func startup() async {
        do {
            #if DEBUG
            if let fixture = try UITestFixture.prepare() {
                catalogue = fixture.0; try DirectoryBookmark.save(fixture.1); folderName = fixture.1.lastPathComponent
                state = try await store.load(qq); rebuild(); ready = true; status = "测试目录已准备"; return
            }
            #endif
            catalogue = try await catalogues.load(); state = try await store.load(qq)
            cloud = try? CloudGateway(configuration: RankConfiguration.bundled())
            rebuild(); ready = true; status = "选择 Malody V 文件夹，即可读取本机成绩"
        } catch { self.error = error.localizedDescription; status = "初始化未完成，已有文件保持不变" }
    }
    private func rebuild() {
        board = ScoreBoard(records: state.records, catalogue: catalogue)
        let charts = catalogue.byCID, promoted = catalogue.promotedSongs
        recent = catalogue.filtered(state.records).sorted { $0.playedAt > $1.playedAt }.prefix(20).compactMap { record in
            guard let chart = charts[record.cid], let value = Rating.score(chart: chart, record: record) else { return nil }
            return RatedScore(chart: chart, record: record, rating: value, isNew: promoted.contains(chart.identity))
        }
    }
    func chooseFolder(_ url: URL) {
        guard !busy, ready else { return }
        do { try DirectoryBookmark.save(url); folderName = url.lastPathComponent; status = "文件夹已授权，点击同步本机成绩" }
        catch { self.error = error.localizedDescription }
    }
    func forgetFolder() {
        guard !busy else { return }; DirectoryBookmark.forget(); folderName = ""; status = "已解除本机目录关联，成绩记录保留"
    }
    private func refreshIfNeeded() async {
        #if DEBUG
        if ProcessInfo.processInfo.environment["MALODY_UI_TESTING"] == "1" { return }
        #endif
        let updated = UserDefaults.standard.double(forKey: "catalogue-updated")
        if Date().timeIntervalSince1970 - updated >= 6 * 3600 {
            status = "正在更新谱库…"
            do { catalogue = try await catalogues.refresh() }
            catch { status = "谱库暂未更新，继续按本地目录筛选" }
        }
    }
    func importScores() {
        guard !busy, ready else { return }; busy = true
        task = Task {
            defer { busy = false; task = nil }
            do {
                let root = try DirectoryBookmark.resolve(); await refreshIfNeeded()
                let previous = state, profile = qq, currentCatalogue = catalogue
                let output = try await importer.scan(root: root, catalogue: currentCatalogue, previous: previous) { [weak self] text in
                    await MainActor.run { self?.status = text }
                }
                try Task.checkCancellation()
                try await store.save(output.state, qq: profile)
                state = output.state; lastImport = output.report; rebuild(); status = output.report.summary
            } catch is CancellationError { status = "读取已取消，原成绩保留" }
            catch { self.error = error.localizedDescription; status = "读取未完成，原成绩保留；可重新选择文件夹后再试" }
        }
    }
    func cancel() { task?.cancel() }
    func updateCatalogue() {
        guard !busy, ready else { return }; busy = true
        task = Task {
            defer { busy = false; task = nil }
            do { catalogue = try await catalogues.refresh(); rebuild(); status = "谱库已更新：\(catalogue.charts.count) 张有效谱面" }
            catch { self.error = error.localizedDescription; status = "谱库更新未完成，缓存保留" }
        }
    }
    func bindQQ(_ text: String) async {
        guard !busy, ready else { return }; busy = true; defer { busy = false }
        do {
            let profile = try JSONCoding.qq(text)
            var next = try await store.load(profile)
            if !UserDefaults.standard.bool(forKey: "legacy-claimed") {
                let unbound = try await store.load("")
                next.records = try ScoreRecord.merge(local: next.records, remote: unbound.records, catalogue: catalogue)
            }
            try await store.save(next, qq: profile)
            UserDefaults.standard.set(profile, forKey: "profile-qq"); UserDefaults.standard.set(true, forKey: "legacy-claimed")
            qq = profile; state = next; rebuild(); status = "QQ 存档已切换，可手动合并同步云端"
        } catch { self.error = error.localizedDescription }
    }
    func saveProfile(name: String, title: String) async {
        guard !busy, ready else { return }
        var next = state; next.nickname = String(name.prefix(40)); next.title = title
        do { try await store.save(next, qq: qq); state = next } catch { self.error = error.localizedDescription }
    }
    func syncCloud(upload: Bool) {
        guard !busy, ready else { return }
        guard !qq.isEmpty else { selectedTab = 4; error = "请先在设置中填写自己的 QQ 存档编号"; return }
        guard let cloud else { error = "此测试包缺少云端配置"; return }
        busy = true
        task = Task {
            defer { busy = false; task = nil }
            do {
                await refreshIfNeeded(); let profile = qq
                let merged = try await cloud.sync(qq: profile, records: state.records, catalogue: catalogue, upload: upload) { [weak self] text in
                    await MainActor.run { self?.status = text }
                }
                try Task.checkCancellation(); var next = state; next.records = merged
                try await store.save(next, qq: profile); state = next; rebuild()
                status = upload ? "云端已合并，排行服务会随后刷新" : "云端成绩已恢复"
            } catch is CancellationError { status = "同步已停止，已保存的成绩保留" }
            catch { self.error = error.localizedDescription; status = "云端同步未完成，本地成绩保留" }
        }
    }
    func showRanking(cid: Int) { rankingCID = cid; selectedTab = 2 }
    func loadRanking() async {
        guard let cloud, ready else { return }
        rankRequest += 1; let request = rankRequest, cid = rankingCID
        rankingLoading = true; rankingStatus = "读取云端榜单…"
        if loadedRankingCID != cid { cloudBoard = nil }
        do {
            let result = try await cloud.board(catalogue: catalogue, cid: cid)
            guard request == rankRequest else { return }
            cloudBoard = result; loadedRankingCID = cid; rankingLoading = false
            rankingStatus = (Date().timeIntervalSince(result.updated) > 120 ? "上次发布 " : "更新 ") + result.updated.formatted(date: .abbreviated, time: .shortened)
            if result.failed > 0 { rankingStatus += " · 部分玩家存档未更新" }
        } catch {
            guard request == rankRequest else { return }; rankingLoading = false
            rankingStatus = "榜单暂时不可用，稍后下拉刷新重试。已有缓存保留。"
        }
    }
    func art(_ chart: Chart) async -> Data? {
        let path = LocalPaths.jacket(chart)
        if let bytes = try? Data(contentsOf: path) { return bytes }
        guard let cloud, let bytes = try? await cloud.artwork(chart) else { return nil }
        try? FileManager.default.createDirectory(at: LocalPaths.artwork, withIntermediateDirectories: true)
        try? bytes.write(to: path, options: .atomic); return bytes
    }
    func diagnosticURL() throws -> URL {
        var report: [String: Any] = ["app_version": "0.1.0-ios-test", "system": "iOS", "chart_count": catalogue.charts.count,
                                      "record_count": state.records.count, "directory_selected": !folderName.isEmpty,
                                      "catalogue_revision": catalogue.revision(), "created_at": ISO8601DateFormatter().string(from: Date())]
        if let lastImport { report["last_import"] = try JSONSerialization.jsonObject(with: JSONCoding.encode(lastImport)) }
        report["latest_fields"] = recent.prefix(5).map { ["cid": $0.chart.cid, "score_candidate": $0.record.score,
                                                        "accuracy_candidate": $0.record.accuracy, "judge_candidate": $0.record.judge] as [String: Any] }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Malody-Link-iOS-Diagnostic.json")
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]).write(to: url, options: .atomic)
        return url
    }
}
