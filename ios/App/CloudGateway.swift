import Foundation
import MalodyCore

struct RankConfiguration: Codable {
    let server: String
    let root: String
    let username: String
    let password: String
    static func bundled() throws -> Self {
        guard let url = Bundle.main.url(forResource: "runtime-config", withExtension: "json") else { throw MalodyError.invalid("测试包缺少云端配置，请使用完整构建包") }
        return try JSONCoding.decode(Self.self, Data(contentsOf: url))
    }
}

final class RefuseRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

enum HTTPBytes {
    static let session = URLSession(configuration: .ephemeral, delegate: RefuseRedirects(), delegateQueue: nil)
    static func request(_ url: URL, method: String = "GET", headers: [String: String] = [:], body: Data? = nil, limit: Int) async throws -> Data {
        guard ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.user == nil, url.password == nil else { throw MalodyError.invalid("服务器地址无效") }
        var request = URLRequest(url: url); request.httpMethod = method; request.httpBody = body
        request.timeoutInterval = 30; request.cachePolicy = .reloadIgnoringLocalCacheData
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (stream, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse, response.statusCode == 200 else { throw MalodyError.invalid("服务器请求未成功，请检查网络后重试") }
        guard response.expectedContentLength <= Int64(limit) else { throw MalodyError.invalid("服务器文件超过读取上限") }
        var data = Data(); data.reserveCapacity(min(limit, max(0, Int(response.expectedContentLength))))
        for try await byte in stream { guard data.count < limit else { throw MalodyError.invalid("服务器文件超过读取上限") }; data.append(byte) }
        return data
    }
}

struct RemoteItem { let name: String; let directory: Bool; let size: Int }
struct RankingRow: Identifiable {
    let qq: String, name: String
    let rating: Double, old: Double, new: Double
    let accuracy: Double?, score: Int64?, judge: Int?
    var rank = 0
    var id: String { qq }
}
struct RemoteBoard { let rows: [RankingRow]; let updated: Date; let failed: Int; var legacy = false; var cached = false }

actor CloudGateway {
    private let configuration: RankConfiguration
    private var token = ""
    private var artIndex: [String: [String: String]] = [:]
    private var artChecked = Date.distantPast
    func tokenValue() -> String { token }
    init(configuration: RankConfiguration) { self.configuration = configuration }
    var server: String { configuration.server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    func api(_ path: String, _ payload: [String: Any]) async throws -> [String: Any] {
        guard let url = URL(string: server + path) else { throw MalodyError.invalid("服务器地址无效") }
        let data = try await HTTPBytes.request(url, method: "POST", headers: ["Authorization": token, "Content-Type": "application/json"], body: JSONSerialization.data(withJSONObject: payload), limit: 2_000_000)
        guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw MalodyError.invalid("云端响应格式无效") }
        return result
    }
    func checked(_ result: [String: Any]) throws -> [String: Any] {
        if [401, 403].contains(result["code"] as? Int ?? 0) { token = "" }
        guard (result["code"] as? Int) == 200 else { throw MalodyError.invalid("云端操作失败（服务代码 \(result["code"] as? Int ?? 0)）") }
        return result["data"] as? [String: Any] ?? [:]
    }
    func login() async throws {
        guard token.isEmpty else { return }
        do {
            let response = try checked(await api("/api/auth/login", ["username": configuration.username, "password": configuration.password]))
            guard let value = response["token"] as? String, !value.isEmpty else { throw MalodyError.invalid("云端登录失败") }
            token = value
            let bytes = try await HTTPBytes.request(URL(string: server + "/api/me")!, headers: ["Authorization": token], limit: 100_000)
            let root = try checked(JSONSerialization.jsonObject(with: bytes) as? [String: Any] ?? [:])["base_path"] as? String
            guard root?.trimmingCharacters(in: CharacterSet(charactersIn: "/")) == "RMB30", configuration.root == "/RMB30" else { throw MalodyError.invalid("云端账号目录不匹配，已停止操作") }
        } catch { token = ""; throw error }
    }
    func items(_ path: String, create: Bool = false) async throws -> [RemoteItem] {
        try await login()
        if create { _ = try checked(await api("/api/fs/mkdir", ["path": path])) }
        var results: [RemoteItem] = []
        for page in 1...100 {
            let response = try await api("/api/fs/list", ["path": path, "page": page, "per_page": 100, "refresh": true])
            if page == 1, !create, (response["code"] as? Int) != 200 {
                let message = (response["message"] as? String ?? "").lowercased()
                if message.contains("object not found") || message.contains("no such file") { return [] }
            }
            let data = try checked(response), rows = data["content"] as? [[String: Any]] ?? []
            for row in rows { if let name = row["name"] as? String { results.append(RemoteItem(name: name, directory: row["is_dir"] as? Bool ?? false, size: row["size"] as? Int ?? 0)) } }
            guard results.count <= 10_000 else { throw MalodyError.invalid("云目录超过读取容量") }
            if rows.count < 100 || page * 100 >= (data["total"] as? Int ?? Int.max) { return results }
        }
        throw MalodyError.invalid("云目录分页超过读取容量")
    }
    func get(_ path: String, limit: Int) async throws -> Data {
        #if DEBUG
        if server == "https://fixture.invalid", let bytes = try UITestFixture.preview(path) { return bytes }
        #endif
        try await login()
        let response = try checked(await api("/api/fs/get", ["path": path, "password": ""]))
        guard let address = response["raw_url"] as? String, let url = URL(string: address), let origin = URL(string: server) else { throw MalodyError.invalid("云端文件地址无效") }
        let same = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
        return try await HTTPBytes.request(url, headers: same ? ["Authorization": token] : [:], limit: limit)
    }
    private func put(qq: String, name: String, bytes: Data) async throws {
        guard ArchiveDocument.isFilename(name), name == ArchiveDocument.filename(bytes), bytes.count <= 1_048_576 else { throw MalodyError.invalid("待上传分片校验失败") }
        let headers = ["Authorization": token, "Content-Type": "application/octet-stream", "File-Path": "/\(qq)/\(name)", "As-Task": "false"]
        let result = try await HTTPBytes.request(URL(string: server + "/api/fs/put")!, method: "PUT", headers: headers, body: bytes, limit: 100_000)
        _ = try checked(JSONSerialization.jsonObject(with: result) as? [String: Any] ?? [:])
        guard try await get("/\(qq)/\(name)", limit: 1_048_576) == bytes else { throw MalodyError.invalid("云端回读未完成，本地成绩保留") }
    }
    func publishTitle(qq: String, selection: String, records: [ScoreRecord], catalogue: Catalogue) async throws {
        let qq = try JSONCoding.qq(qq)
        let bytes = try PlayerTitle.document(qq: qq, server: server, selection: selection, records: records, catalogue: catalogue)
        try await login()
        _ = try checked(await api("/api/fs/mkdir", ["path": "/" + qq]))
        let path = "/\(qq)/player-title.json"
        let result = try await HTTPBytes.request(URL(string: server + "/api/fs/put")!, method: "PUT",
            headers: ["Authorization": token, "Content-Type": "application/json", "File-Path": path, "As-Task": "false"], body: bytes, limit: 100_000)
        _ = try checked(JSONSerialization.jsonObject(with: result) as? [String: Any] ?? [:])
        guard try await get(path, limit: 4096) == bytes else { throw MalodyError.invalid("称号回读未完成，下次上传将重试") }
    }
    func communityRevision() async throws -> String {
        try CatalogueRevision.communityHint(await get("/_community/v1/boards.json", limit: 2 * 1024 * 1024), server: server)
    }
    private func history(qq: String, catalogue: Catalogue, create: Bool, progress: @Sendable (String) async -> Void) async throws -> [ScoreRecord] {
        let entries = try await items("/" + qq, create: create).filter { !$0.directory && ArchiveDocument.isFilename($0.name) }.sorted { $0.name < $1.name }
        guard entries.count <= 1000 else { throw MalodyError.invalid("云存档超过1000个分片") }
        let cache = LocalPaths.base.appendingPathComponent("cloud-cache/\(qq)/\(Digests.sha256(Data(server.utf8)))", isDirectory: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        var records: [ScoreRecord] = [], downloaded = 0
        for (index, item) in entries.enumerated() {
            guard item.size <= 1_048_576 else { throw MalodyError.invalid("云端分片超过1MiB") }
            await progress("合并云端成绩 \(index + 1) / \(entries.count)")
            let file = cache.appendingPathComponent(item.name)
            var data = try? Data(contentsOf: file)
            if data == nil || ArchiveDocument.filename(data!) != item.name {
                let bytes = try await get("/\(qq)/\(item.name)", limit: 1_048_576)
                downloaded += bytes.count; guard downloaded <= 32 * 1024 * 1024 else { throw MalodyError.invalid("本次新增下载超过32MiB，请稍后重试") }
                guard ArchiveDocument.filename(bytes) == item.name else { throw MalodyError.invalid("云端分片校验失败") }
                try bytes.write(to: file, options: .atomic); data = bytes
            }
            records += try ArchiveDocument.read(data!, qq: qq, server: catalogue.server)
            guard records.count <= 20_000 else { throw MalodyError.invalid("云端记录超过20000条") }
        }
        return catalogue.filtered(records)
    }
    func sync(qq value: String, records: [ScoreRecord], catalogue: Catalogue, upload: Bool, progress: @Sendable (String) async -> Void) async throws -> [ScoreRecord] {
        let qq = try JSONCoding.qq(value)
        guard catalogue.server == server else { throw MalodyError.invalid("谱库与云存档服务器不一致") }
        let remote = try await history(qq: qq, catalogue: catalogue, create: upload, progress: progress)
        var merged = try ScoreRecord.merge(local: records, remote: remote, catalogue: catalogue)
        if upload {
            let present = Set(remote), missing = merged.filter { !present.contains($0) }
            let batches = try ArchiveDocument.batches(qq: qq, server: catalogue.server, records: missing)
            for (index, batch) in batches.enumerated() { await progress("上传成绩 \(index + 1) / \(batches.count)"); try await put(qq: qq, name: batch.0, bytes: batch.1) }
            let arrivals = try await history(qq: qq, catalogue: catalogue, create: false, progress: progress)
            merged = try ScoreRecord.merge(local: merged, remote: arrivals, catalogue: catalogue)
        }
        return merged
    }
    func publishedBoard(catalogue: Catalogue, cid: Int, cached: Bool = false) async throws -> RemoteBoard {
        guard catalogue.server == server, cid >= 0 else { throw MalodyError.invalid("排行榜服务器不匹配") }
        let path = "/_rankings/v2/\(catalogue.revision())/" + (cid == 0 ? "rt.json" : "charts/\(cid).json")
        let cache = cacheURL(catalogue, "board-\(cid)")
        let data: Data
        if cached { data = try Data(contentsOf: cache) } else { data = try await get(path, limit: 16 * 1024 * 1024) }
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any], document["schema"] as? Int == 2,
              document["server"] as? String == catalogue.server, document["revision"] as? String == catalogue.revision(), document["cid"] as? Int == cid,
              let updated = document["updated"] as? Double, updated > 0, updated <= Date().timeIntervalSince1970 * 1000 + 300_000,
              let entries = document["players"] as? [[String: Any]], entries.count <= 10_000 else { throw MalodyError.invalid("云端榜单格式不匹配，稍后刷新重试") }
        var seen: Set<String> = [], rows: [RankingRow] = []
        for entry in entries {
            guard let qqValue = entry["qq"] as? String, let name = entry["name"] as? String, name.utf16.count <= 128 else { throw MalodyError.invalid("榜单玩家无效") }
            let qq = try JSONCoding.qq(qqValue)
            guard seen.insert(qq).inserted else { throw MalodyError.invalid("榜单存在重复玩家") }
            var rating = entry["rating"] as? Double ?? .nan
            var accuracy: Double?, score: Int64?, judge: Int?
            let best = entry["best"] as? [[String: Any]] ?? []
            if cid > 0 {
                guard best.count == 1, let row = best.first, row["cid"] as? Int == cid else { throw MalodyError.invalid("单曲榜谱面不匹配") }
                rating = row["rating"] as? Double ?? .nan; accuracy = row["accuracy"] as? Double
                score = (row["score"] as? NSNumber)?.int64Value; judge = row["judge"] as? Int
                guard let accuracy, accuracy.isFinite, (0...100).contains(accuracy), let score, score >= 0 else { throw MalodyError.invalid("单曲成绩无效") }
            } else if !best.isEmpty { throw MalodyError.invalid("总榜摘要无效") }
            let old = entry["o"] as? Double ?? .nan, fresh = entry["n"] as? Double ?? .nan
            guard [rating, old, fresh].allSatisfy({ $0.isFinite && $0 >= 0 }) else { throw MalodyError.invalid("榜单 rating 无效") }
            rows.append(RankingRow(qq: qq, name: name, rating: rating, old: old, new: fresh, accuracy: accuracy, score: score, judge: judge))
        }
        rows.sort { a, b in if a.rating != b.rating { return a.rating > b.rating }; if a.accuracy != b.accuracy { return (a.accuracy ?? 0) > (b.accuracy ?? 0) }; if a.score != b.score { return (a.score ?? 0) > (b.score ?? 0) }; return a.qq < b.qq }
        for index in rows.indices {
            let equal = index > 0 && rows[index].rating == rows[index - 1].rating && rows[index].accuracy == rows[index - 1].accuracy && rows[index].score == rows[index - 1].score
            rows[index].rank = equal ? rows[index - 1].rank : index + 1
        }
        if !cached { try? data.write(to: cache, options: .atomic) }
        return RemoteBoard(rows: rows, updated: Date(timeIntervalSince1970: updated / 1000), failed: document["failed"] as? Int ?? 0, cached: cached)
    }
    func artwork(_ chart: Chart) async throws -> Data {
        if Date().timeIntervalSince(artChecked) > 300 {
            let data = try await get("/_song_art/v2/index.json", limit: 4_000_000)
            struct Index: Decodable { let schema: Int; let server: String; let charts: [String: [String: String]] }
            let doc = try JSONDecoder().decode(Index.self, from: data)
            guard doc.schema == 2, doc.server == server else { throw MalodyError.invalid("曲绘来源不匹配") }
            artIndex = doc.charts; artChecked = Date()
        }
        guard let row = artIndex[String(chart.cid)], row["server_hash"] == chart.serverHash, let digest = row["image"], digest.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw MalodyError.invalid("曲绘与当前谱面不匹配") }
        let path = LocalPaths.artwork.appendingPathComponent(digest + ".jpg")
        if let data = try? Data(contentsOf: path), Digests.sha256(data) == digest { return data }
        let data = try await get("/_song_art/v2/" + digest + ".jpg", limit: 1_048_576)
        guard Digests.sha256(data) == digest else { throw MalodyError.invalid("曲绘校验失败") }
        try? FileManager.default.createDirectory(at: LocalPaths.artwork, withIntermediateDirectories: true)
        try? data.write(to: path, options: .atomic)
        return data
    }
    func publishAvatar(qq: String, image: Data?) async throws {
        _ = try JSONCoding.qq(qq); let data = image ?? Data()
        guard data.count <= 1_048_576 else { throw MalodyError.invalid("头像过大") }
        let doc: [String: Any] = ["schema": 1, "qq": qq, "server": server, "image": data.base64EncodedString(), "sha256": Digests.sha256(data)]
        let bytes = try JSONSerialization.data(withJSONObject: doc, options: [.sortedKeys])
        try await login(); _ = try checked(await api("/api/fs/mkdir", ["path": "/" + qq]))
        let path = "/" + qq + "/player-avatar.json"
        let result = try await HTTPBytes.request(URL(string: server + "/api/fs/put")!, method: "PUT", headers: ["Authorization": token, "Content-Type": "application/json", "File-Path": path, "As-Task": "false"], body: bytes, limit: 100_000)
        _ = try checked(JSONSerialization.jsonObject(with: result) as? [String: Any] ?? [:])
        guard try await get(path, limit: 1_500_000) == bytes else { throw MalodyError.invalid("头像回读未完成") }
    }
    func avatar(qq: String) async throws -> Data? {
        _ = try JSONCoding.qq(qq)
        struct Avatar: Decodable { let schema: Int; let qq: String; let server: String; let image: String; let sha256: String }
        let doc = try JSONDecoder().decode(Avatar.self, from: await get("/" + qq + "/player-avatar.json", limit: 1_500_000))
        guard doc.schema == 1, doc.qq == qq, doc.server == server, let bytes = Data(base64Encoded: doc.image), bytes.count <= 1_048_576, Digests.sha256(bytes) == doc.sha256 else { throw MalodyError.invalid("头像校验失败") }
        return bytes.isEmpty ? nil : bytes
    }
}
