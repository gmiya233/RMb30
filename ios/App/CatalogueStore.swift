import Foundation
import SQLite3
import MalodyCore

struct ReaderConfiguration: Codable { let server: String; let database_url: String; let username: String; let password: String }

actor CatalogueStore {
    private var cache: URL { LocalPaths.base.appendingPathComponent("catalogue.json") }
    func load() throws -> Catalogue {
        if FileManager.default.fileExists(atPath: cache.path), let catalogue = try? JSONCoding.decode(Catalogue.self, Data(contentsOf: cache)), !catalogue.charts.isEmpty { return catalogue }
        guard let url = Bundle.main.url(forResource: "catalog", withExtension: "json") else { throw MalodyError.invalid("测试包缺少谱库，请重新下载完整安装包") }
        return try JSONCoding.decode(Catalogue.self, Data(contentsOf: url))
    }
    func refresh() async throws -> Catalogue {
        guard let url = Bundle.main.url(forResource: "reader-config", withExtension: "json") else { throw MalodyError.invalid("测试包缺少谱库更新配置") }
        let config = try JSONCoding.decode(ReaderConfiguration.self, Data(contentsOf: url))
        guard let databaseURL = URL(string: config.database_url), databaseURL.host == URL(string: config.server)?.host,
              databaseURL.path == "/dav/malody.db" else { throw MalodyError.invalid("谱库读取地址不匹配") }
        let authorization = Data("\(config.username):\(config.password)".utf8).base64EncodedString()
        let bytes = try await HTTPBytes.request(databaseURL, headers: ["Authorization": "Basic " + authorization], limit: 16_000_000)
        guard bytes.starts(with: Data("SQLite format 3\0".utf8)) else { throw MalodyError.invalid("服务器未返回有效谱库") }
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("catalogue-\(UUID().uuidString).db")
        try bytes.write(to: temporary, options: .atomic); defer { try? FileManager.default.removeItem(at: temporary) }
        var database: OpaquePointer?, statement: OpaquePointer?
        guard sqlite3_open_v2(temporary.path, &database, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else { if database != nil { sqlite3_close(database) }; throw MalodyError.invalid("无法读取谱库数据库") }
        defer { sqlite3_finalize(statement); sqlite3_close(database) }
        let sql = "SELECT c.cid,c.sid,c.hash,c.path,c.mc_name,c.version,c.mode,c.level,s.title,s.artist,s.promote,s.cover,s.background,s.bpm,c.creator FROM charts c JOIN songs s ON c.sid=s.sid WHERE c.exist=1 AND s.exist=1"
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { throw MalodyError.invalid("服务器谱库结构不兼容") }
        let keys = ["cid", "sid", "server_hash", "path", "mc_name", "version", "mode", "level", "title", "artist", "promote", "cover", "background", "bpm", "creator"]
        var rows: [[String: Any]] = [], result = sqlite3_step(statement)
        while result == SQLITE_ROW {
            var row: [String: Any] = [:]
            for (index, key) in keys.enumerated() {
                let column = Int32(index)
                switch sqlite3_column_type(statement, column) {
                case SQLITE_INTEGER: row[key] = sqlite3_column_int64(statement, column)
                case SQLITE_FLOAT: row[key] = sqlite3_column_double(statement, column)
                case SQLITE_TEXT: if let text = sqlite3_column_text(statement, column) { row[key] = String(cString: text) }
                default: row[key] = NSNull()
                }
            }
            rows.append(row); guard rows.count <= 50_000 else { throw MalodyError.invalid("谱库条目超过容量") }; result = sqlite3_step(statement)
        }
        guard result == SQLITE_DONE, !rows.isEmpty else { throw MalodyError.invalid("谱库读取未完成，原目录保留") }
        let data = try JSONSerialization.data(withJSONObject: ["server": config.server, "charts": rows], options: [.sortedKeys])
        let catalogue = try JSONCoding.decode(Catalogue.self, data)
        try data.write(to: cache, options: .atomic)
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "catalogue-updated")
        UserDefaults.standard.set(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "", forKey: "catalogue-app-version")
        return catalogue
    }
}
