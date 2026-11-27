import Foundation

public struct ChartMatch {
    public let md5: String
    public let chart: Chart
    public let artworkNames: [String]
}

public struct ChartMatcher {
    private let byFilename: [String: [Chart]]
    public init(catalogue: Catalogue) { byFilename = Dictionary(grouping: catalogue.charts, by: \.filename) }
    public func match(data: Data, filename: String) throws -> ChartMatch? {
        guard data.count <= 16 * 1024 * 1024,
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let meta = root["meta"] as? [String: Any], let mode = (meta["mode"] as? NSNumber)?.intValue,
              let song = meta["song"] as? [String: Any] else { throw MalodyError.invalid("谱面元数据无效") }
        let title = (song["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let localSongID = (song["id"] as? NSNumber)?.int64Value ?? 0
        let matches = (byFilename[filename] ?? []).filter { chart in
            guard chart.mode == mode else { return false }
            if localSongID > 0, let expression = try? NSRegularExpression(pattern: "(?:^|/)_song_(\\d+)(?:/|$)"),
               let match = expression.firstMatch(in: chart.path, range: NSRange(chart.path.startIndex..., in: chart.path)),
               let range = Range(match.range(at: 1), in: chart.path), let expected = Int64(chart.path[range]) { return expected == localSongID }
            return !title.isEmpty && title.lowercased() == chart.title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        }
        guard matches.count == 1, let chart = matches.first else { return nil }
        let names = [meta["cover"] as? String, meta["background"] as? String].compactMap { $0 }.filter { !$0.isEmpty }
        return ChartMatch(md5: Digests.md5(data), chart: chart, artworkNames: names)
    }
}

public struct ArchiveDocument: Codable {
    public let schema: Int
    public let qq: String
    public let server: String
    public let scores: [ScoreRecord]
    public init(qq: String, server: String, scores: [ScoreRecord]) throws {
        schema = 1; self.qq = try JSONCoding.qq(qq); self.server = server; self.scores = scores
        guard scores.count <= 256 else { throw MalodyError.invalid("云端分片超过256条") }
    }
    public static func read(_ data: Data, qq: String, server: String) throws -> [ScoreRecord] {
        guard data.count <= 1_048_576 else { throw MalodyError.invalid("云端分片超过1MiB") }
        let document = try JSONCoding.decode(Self.self, data)
        guard document.schema == 1, document.qq == qq, document.scores.count <= 256 else { throw MalodyError.invalid("云端存档格式或QQ不匹配") }
        return document.server == server ? document.scores : []
    }
    public static func filename(_ data: Data) -> String { "scores-\(Digests.sha256(data)).json" }
    public static func isFilename(_ name: String) -> Bool { name.range(of: "^scores-[a-f0-9]{64}\\.json$", options: .regularExpression) != nil }
    public static func batches(qq: String, server: String, records: [ScoreRecord]) throws -> [(String, Data)] {
        var output: [(String, Data)] = []
        func pack(_ rows: [ScoreRecord]) throws {
            guard !rows.isEmpty else { return }
            let bytes = try JSONCoding.encode(ArchiveDocument(qq: qq, server: server, scores: rows))
            if bytes.count > 1_048_576 {
                guard rows.count > 1 else { throw MalodyError.invalid("单条成绩过大") }
                let middle = rows.count / 2; try pack(Array(rows[..<middle])); try pack(Array(rows[middle...])); return
            }
            output.append((filename(bytes), bytes))
        }
        for start in stride(from: 0, to: records.count, by: 256) { try pack(Array(records[start..<min(records.count, start + 256)])) }
        return output
    }
}
