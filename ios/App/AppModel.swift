import Foundation
import SwiftUI
import UIKit
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
    @Published var communityBoards: CommunityDocument?
    @Published var communityStatus = ""
    @Published var preview: RatingPreview = UserDefaults.standard.data(forKey: "rating-preview").flatMap { try? JSONCoding.decode(RatingPreview.self, $0) } ?? RatingPreview() {
        didSet { if let data = try? JSONCoding.encode(preview) { UserDefaults.standard.set(data, forKey: "rating-preview") } }
    }
    @Published var showingPreview = false
    @Published var showingPackages = false
    @Published var incomingPackages: [URL] = []
    var previewBoard: ScoreBoard { showingPreview ? ScoreBoard(records: state.records, catalogue: catalogue, preview: preview) : board }
    private let store = StateStore(), importer = DirectoryImporter(), catalogues = CatalogueStore()
    var cloud: CloudGateway?
    private var task: Task<Void, Never>?
    private var rankRequest = 0
    private var revisionChecked = Date.distantPast
    @Published private(set) var loadedRankingCID: Int?

    init() { UserDefaults.standard.register(defaults: ["auto-cloud": true]); Task { await startup() } }
    var playerName: String {
        if !state.nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return state.nickname }
        return state.records.max(by: { $0.playedAt < $1.playedAt })?.player ?? "PLAYER"
    }
    var unlockedTitles: [String] {
        titleOptions.filter { !$0.key.isEmpty }.map(\.name)
    }
    var titleOptions: [(key: String, name: String)] { PlayerTitle.choices(records: state.records, catalogue: catalogue) }
    var selectedTitleKey: String { PlayerTitle.key(for: state.title, records: state.records, catalogue: catalogue) }
    var playerTitle: String {
        PlayerTitle.name(for: state.title, records: state.records, catalogue: catalogue)
    }
    func receivePackage(_ url: URL) {
        guard ChartPackage.supported(url.lastPathComponent) else { error = "请选择 MCZ / MCB / ZIP 谱面包"; return }
        incomingPackages.append(url); showingPackages = true
    }
    private func startup() async {
        do {
            #if DEBUG
            if let fixture = try UITestFixture.prepare() {
                catalogue = fixture.0; try DirectoryBookmark.save(fixture.1); folderName = fixture.1.lastPathComponent
                state = try await store.load(qq); rebuild(); ready = true; status = "测试目录已准备"
                if ProcessInfo.processInfo.environment["MALODY_PREVIEW_UI_TESTING"] == "1" { cloud = CloudGateway(configuration: RankConfiguration(server: fixture.0.server, root: "/RMB30", username: "fixture", password: "fixture")) }
                if ProcessInfo.processInfo.environment["MALODY_PACKAGE_UI_TESTING"] == "1" { receivePackage(fixture.1.appendingPathComponent("synthetic.mcz")) }
                return
            }
            #endif
            catalogue = try await catalogues.load()
            if let url = Bundle.main.url(forResource: "chart-keys", withExtension: "json"), let bytes = try? Data(contentsOf: url), let enriched = try? catalogue.enrichKeys(bytes) { catalogue = enriched }
            state = try await store.load(qq)
            cloud = try? CloudGateway(configuration: RankConfiguration.bundled())
            rebuild(); ready = true; status = "选择 Malody V 文件夹，即可读取本机成绩"
            if let cloud, let enriched = try? await cloud.keys(catalogue: catalogue) { catalogue = enriched; rebuild() }
        } catch { self.error = error.localizedDescription; status = "初始化未完成，已有文件保持不变" }
    }
    private func rebuild() {
        board = ScoreBoard(records: state.records, catalogue: catalogue)
        recent = SongBrowse.recent(state.records, catalogue: catalogue)
    }
    func chooseFolder(_ url: URL) {
        guard !busy, ready else { return }
        do { try DirectoryBookmark.save(url); folderName = url.lastPathComponent; status = "文件夹已授权，点击同步本机成绩" }
        catch { self.error = error.localizedDescription }
    }
    func forgetFolder() {
        guard !busy else { return }; DirectoryBookmark.forget(); folderName = ""; status = "已解除本机目录关联，成绩记录保留"
    }
    private func refreshIfNeeded(checkRevision: Bool = false) async {
        #if DEBUG
        if ProcessInfo.processInfo.environment["MALODY_UI_TESTING"] == "1" { return }
        #endif
        let updated = UserDefaults.standard.double(forKey: "catalogue-updated")
        var changed = false
        if checkRevision, Date().timeIntervalSince(revisionChecked) >= 60, let cloud {
            revisionChecked = Date()
            if let revision = try? await cloud.communityRevision() { changed = revision != catalogue.revision() }
        }
        let appVersion = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        if changed || UserDefaults.standard.string(forKey: "catalogue-app-version") != appVersion || Date().timeIntervalSince1970 - updated >= 6 * 3600 {
            status = "正在更新谱库…"
            do { catalogue = try await catalogues.refresh(); if let cloud, let enriched = try? await cloud.keys(catalogue: catalogue) { catalogue = enriched }; rebuild() }
            catch { status = "谱库暂未更新，继续按本地目录筛选" }
        }
    }
    func importScores() {
        guard !busy, ready else { return }; busy = true
        task = Task {
            defer { busy = false; task = nil }
            do {
                let root = try DirectoryBookmark.resolve(); await refreshIfNeeded(checkRevision: true)
                let previous = state, profile = qq, currentCatalogue = catalogue
                let output = try await importer.scan(root: root, catalogue: currentCatalogue, previous: previous) { [weak self] text in
                    await self?.setProgress(text)
                }
                try Task.checkCancellation()
                try await store.save(output.state, qq: profile)
                state = output.state; lastImport = output.report; rebuild(); status = output.report.summary
                if UserDefaults.standard.bool(forKey: "auto-cloud"), !qq.isEmpty {
                    do { try await mergeCloud(upload: true) } catch { status = output.report.summary + "；云端同步未完成，本机成绩已保存"; self.error = error.localizedDescription }
                }
            } catch is CancellationError { status = "读取已取消，原成绩保留" }
            catch { self.error = error.localizedDescription; status = "读取未完成，原成绩保留；可重新选择文件夹后再试" }
        }
    }
    private func setProgress(_ text: String) { status = text }
    func cancel() { task?.cancel() }
    func updateCatalogue() {
        guard !busy, ready else { return }; busy = true
        task = Task {
            defer { busy = false; task = nil }
            do { catalogue = try await catalogues.refresh(); if let cloud, let enriched = try? await cloud.keys(catalogue: catalogue) { catalogue = enriched }; rebuild(); status = "谱库已更新：\(catalogue.charts.count) 张有效谱面" }
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
            qq = profile; state = next; rebuild(); status = "QQ 存档已切换"
            try await mergeCloud(upload: true)
        } catch { self.error = error.localizedDescription }
    }
    func saveProfile(name: String, title: String) async {
        guard !busy, ready else { return }
        busy = true; defer { busy = false }
        var next = state; next.nickname = String(name.prefix(40)); next.title = PlayerTitle.key(for: title, records: state.records, catalogue: catalogue)
        do {
            try await store.save(next, qq: qq); state = next; status = "名片已保存"
            if !qq.isEmpty, let cloud {
                do { try await cloud.publishTitle(qq: qq, selection: next.title, records: next.records, catalogue: catalogue); status = "名片已保存，称号已同步到服务器" }
                catch { status = "名片已保存，称号将在下次上传时重试" }
            }
        } catch { self.error = error.localizedDescription }
    }
    func saveAvatar(_ bytes: Data?) async {
        guard !busy, ready, bytes == nil || bytes!.count <= 2_000_000 else { return }
        var next = state; next.avatar = bytes; next.avatarDirty = true
        do { try await store.save(next, qq: qq); state = next } catch { self.error = error.localizedDescription }
    }
    private func mergeCloud(upload: Bool) async throws {
        guard let cloud else { throw MalodyError.invalid("安装包缺少云端配置") }
        let profile = qq
        let merged = try await cloud.sync(qq: profile, records: state.records, catalogue: catalogue, upload: upload) { [weak self] text in await self?.setProgress(text) }
        try Task.checkCancellation(); var next = state; next.records = merged
        try await store.save(next, qq: profile); state = next; rebuild()
        status = upload ? "成绩已上传到服务器，排行榜会随后刷新" : "云端成绩已恢复"
        if upload {
            do { try await cloud.publishTitle(qq: profile, selection: state.title, records: merged, catalogue: catalogue) }
            catch { status += "；称号同步未完成，下次上传将重试" }
            do { try await cloud.publishIndex(qq: profile, records: merged, catalogue: catalogue) }
            catch { status = "成绩已上传；兼容排行榜索引暂未更新，可稍后再同步" }
        }
        if upload && (state.avatar != nil || state.avatarDirty) {
            do {
                var bytes: Data?
                if let raw = state.avatar {
                    guard let image = UIImage(data: raw) else { throw MalodyError.invalid("本机头像无法解码，请重新选择") }
                    let format = UIGraphicsImageRendererFormat(); format.scale = 1
                    let normalized = UIGraphicsImageRenderer(size: CGSize(width: 384, height: 384), format: format).image { _ in image.draw(in: CGRect(x: 0, y: 0, width: 384, height: 384)) }
                    bytes = normalized.jpegData(compressionQuality: 0.85)
                }
                try await cloud.publishAvatar(qq: profile, image: bytes)
                state.avatarDirty = false; try await store.save(state, qq: profile); status += "；头像已上传"
            } catch { status += "；头像同步未完成，下次上传会重试" }
        } else if !state.avatarDirty {
            do { let bytes = try await cloud.avatar(qq: profile); if bytes == nil || UIImage(data: bytes!) != nil { state.avatar = bytes; try await store.save(state, qq: profile) } } catch { /* Old cloud archives may not include an avatar. */ }
        }
    }
    func syncCloud(upload: Bool) {
        guard !busy, ready else { return }
        guard !qq.isEmpty else { selectedTab = 4; error = "请先在设置中填写自己的 QQ 存档编号"; return }
        guard cloud != nil else { error = "此测试包缺少云端配置"; return }
        busy = true
        task = Task {
            defer { busy = false; task = nil }
            do {
                await refreshIfNeeded(checkRevision: true)
                try await mergeCloud(upload: upload)
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
            if result.legacy { rankingStatus = "兼容榜 · " + rankingStatus }
            if result.cached { rankingStatus = "离线缓存 · " + rankingStatus }
            if result.failed > 0 { rankingStatus += " · 部分玩家存档未更新" }
        } catch {
            guard request == rankRequest else { return }; rankingLoading = false
            rankingStatus = "榜单暂时不可用，稍后下拉刷新重试。已有缓存保留。"
        }
    }
    func loadCommunity() async {
        guard let cloud, ready else { return }
        if !busy { busy = true; await refreshIfNeeded(checkRevision: true); busy = false }
        do { let result = try await cloud.community(catalogue: catalogue); communityBoards = result.0; communityStatus = result.1 ? "离线缓存 · 下拉重试" : "更新于 " + result.0.updated.formatted(date: .omitted, time: .shortened) }
        catch { communityStatus = "社区榜单暂不可用，可下拉重试" }
    }
    func loadSongCommunity(_ chart: Chart) async throws -> (CommunityDocument, Bool) {
        guard let cloud else { throw MalodyError.invalid("安装包缺少社区配置") }
        return try await cloud.community(catalogue: catalogue, topic: chart.topic)
    }
    func postReview(_ chart: Chart, stars: Int, text: String) async throws {
        guard ready, !busy, let cloud else { throw MalodyError.invalid("请等待当前同步完成") }
        let profile = try JSONCoding.qq(qq)
        try await cloud.postReview(catalogue: catalogue, chart: chart, qq: profile, name: playerName, stars: stars, text: text)
    }
    func exportScores() throws -> URL {
        let records = try JSONSerialization.jsonObject(with: JSONCoding.encode(catalogue.filtered(state.records)))
        let data = try JSONSerialization.data(withJSONObject: ["schema": 1, "server": catalogue.server, "scores": records], options: [.prettyPrinted, .sortedKeys])
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Malody-Link-Scores.json")
        try data.write(to: url, options: .atomic); return url
    }
    func art(_ chart: Chart) async -> Data? {
        let path = LocalPaths.jacket(chart)
        guard let cloud, let bytes = try? await cloud.artwork(chart) else { return try? Data(contentsOf: path) }
        try? FileManager.default.createDirectory(at: LocalPaths.artwork, withIntermediateDirectories: true)
        try? bytes.write(to: path, options: .atomic); return bytes
    }
    func diagnosticURL() throws -> URL {
        var report: [String: Any] = ["app_version": "0.3.1-ios", "system": "iOS", "chart_count": catalogue.charts.count,
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
