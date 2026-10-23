import Foundation

public struct Chart: Codable, Identifiable, Hashable {
    public let cid: Int
    public let sid: Int
    public let serverHash: String
    public let path: String
    public let filename: String
    public let version: String
    public let mode: Int
    public let rawLevel: Double?
    public let promoted: Bool
    public let title: String
    public let artist: String
    public let creator: String
    public let bpm: String
    public let cover: String
    public let background: String
    public var id: Int { cid }
    public var identity: String { sid > 0 ? "sid:\(sid)" : "cid:\(cid)" }
    public var level: Double? { Rating.level(raw: rawLevel, version: version) }
    public var levelLabel: String { level.map { "Lv." + Rating.formatLevel($0) } ?? "未定级" }
    enum CodingKeys: String, CodingKey {
        case cid, sid, path, version, mode, title, artist, creator, bpm, cover, background
        case serverHash = "server_hash", filename = "mc_name", rawLevel = "level", promoted = "promote"
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        cid = try c.decode(Int.self, forKey: .cid); sid = (try? c.decode(Int.self, forKey: .sid)) ?? 0
        serverHash = try c.decode(String.self, forKey: .serverHash)
        func text(_ key: CodingKeys) -> String { (try? c.decode(String.self, forKey: key)) ?? "" }
        path = text(.path); filename = text(.filename); version = text(.version); mode = (try? c.decode(Int.self, forKey: .mode)) ?? -1
        rawLevel = (try? c.decode(Double.self, forKey: .rawLevel)) ?? Double(text(.rawLevel))
        promoted = ((try? c.decode(Bool.self, forKey: .promoted)) ?? false) || ((try? c.decode(Int.self, forKey: .promoted)) == 1)
        title = text(.title); artist = text(.artist); creator = text(.creator); cover = text(.cover); background = text(.background)
        bpm = (try? c.decode(String.self, forKey: .bpm)) ?? (try? c.decode(Double.self, forKey: .bpm)).map { Rating.formatLevel($0) } ?? ""
    }
}

public struct Catalogue: Codable {
    public let server: String
    public let charts: [Chart]
    public init(server: String, charts: [Chart]) { self.server = server; self.charts = charts }
    public var byCID: [Int: Chart] { Dictionary(charts.map { ($0.cid, $0) }, uniquingKeysWith: { first, _ in first }) }
    public var promotedSongs: Set<String> { Set(charts.filter { $0.promoted || $0.path.hasPrefix("promote/") }.map(\.identity)) }
    public func revision() -> String {
        var key = "index-v1-fixed-song-category:"
        func part(_ value: String) { key += "\(value.utf16.count):\(value)" }
        part(server)
        for c in charts.sorted(by: { $0.cid < $1.cid }) {
            part(String(c.cid)); part(String(c.sid)); part(c.serverHash); part(c.version)
            part(c.rawLevel.flatMap { $0.isFinite ? Rating.formatLevel($0) : nil } ?? "?")
            part(c.promoted ? "1" : "0"); part(c.path)
        }
        return Digests.sha256(Data(key.utf8))
    }
    public func filtered(_ records: [ScoreRecord]) -> [ScoreRecord] {
        let all = byCID
        return records.filter { $0.valid && all[$0.cid]?.serverHash == $0.serverHash }
    }
}

public struct ScoreRecord: Codable, Identifiable, Hashable {
    public let id: String
    public let cid: Int
    public let serverHash: String
    public let chartMD5: String
    public let player: String
    public let playedAt: Int64
    public let score: Int64
    public let accuracy: Double
    public let rawFields: [String: Double]
    public var verification = "catalog_identity"
    public var fieldMappingVerified = false
    public var uploaded = false
    public var judge: Int { let n = rawFields["4"] ?? 0; return n.isFinite && (1...5).contains(n) && n == n.rounded() ? Int(n) : 0 }
    public var valid: Bool {
        let parts = id.split(separator: ":")
        return parts.count == 3 && Int(parts[0]) == cid && Int64(parts[1]) == playedAt &&
            parts[2].range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil &&
            chartMD5.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil &&
            cid > 0 && playedAt >= 0 && score >= 0 && accuracy.isFinite && (0...100).contains(accuracy) &&
            rawFields.count <= 64 && rawFields.values.allSatisfy(\.isFinite) && player.utf16.count <= 128
    }
    public init(chart: Chart, replay: Data, header: ReplayHeader) {
        id = "\(chart.cid):\(header.playedAt):\(Digests.md5(replay))"; cid = chart.cid; serverHash = chart.serverHash
        chartMD5 = header.hash; player = header.player; playedAt = header.playedAt; score = header.score
        accuracy = header.accuracy; rawFields = header.rawFields
    }
    enum CodingKeys: String, CodingKey {
        case id, cid, player, verification, uploaded
        case serverHash = "server_hash", chartMD5 = "chart_md5", playedAt = "played_at", score = "score_candidate"
        case accuracy = "accuracy_candidate", rawFields = "raw_score_fields", fieldMappingVerified = "field_mapping_verified"
    }
    public static func merge(local: [ScoreRecord], remote: [ScoreRecord], catalogue: Catalogue) throws -> [ScoreRecord] {
        let valid = catalogue.filtered(local + remote)
        guard valid.count <= 40_000 else { throw MalodyError.invalid("合并成绩超过容量") }
        let localIDs = Set(local.map(\.id)); var result: [String: ScoreRecord] = [:]
        for record in valid {
            if let prior = result[record.id] {
                if localIDs.contains(record.id) { continue }
                if try JSONCoding.encode(record).lexicographicallyPrecedes(JSONCoding.encode(prior)) { result[record.id] = record }
            } else { result[record.id] = record }
        }
        guard result.count <= 20_000 else { throw MalodyError.invalid("成绩超过20000条") }
        return result.values.sorted { $0.id < $1.id }
    }
}

public enum JSONCoding {
    public static func encode<T: Encodable>(_ value: T) throws -> Data { let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]; return try encoder.encode(value) }
    public static func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T { try JSONDecoder().decode(type, from: data) }
    public static func qq(_ value: String) throws -> String {
        let qq = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard qq.range(of: "^[1-9][0-9]{4,11}$", options: .regularExpression) != nil else { throw MalodyError.invalid("QQ号请填写5～12位数字，不能以0开头") }
        return qq
    }
}
