import Foundation

public enum SongSearchField: String, CaseIterable, Identifiable {
    case all = "综合", title = "曲名", artist = "歌手", creator = "谱师", level = "等级", keys = "键数"
    public var id: String { rawValue }
}
public struct SongSummary: Identifiable {
    public let id: String
    public let charts: [Chart]
    public var first: Chart { charts[0] }
    public var maximumLevel: Double? { charts.compactMap(\.level).max() }
    public init(id: String, charts: [Chart]) { self.id = id; self.charts = charts.sorted { $0.cid < $1.cid } }
}
public enum SongBrowse {
    public static func search(_ charts: [Chart], field: SongSearchField = .all, query: String = "", hideUnrated: Bool = false) -> [SongSummary] {
        let words = query.precomposedStringWithCompatibilityMapping.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
        return Dictionary(grouping: charts, by: \.identity).map { SongSummary(id: $0.key, charts: $0.value) }.filter { song in
            if hideUnrated && song.maximumLevel == nil { return false }
            if field == .level || field == .keys {
                if words.isEmpty { return true }
                guard words.count == 1, let n = Double(words[0]), n.isFinite else { return false }
                return song.charts.contains { field == .level ? $0.level == n : $0.keys.map(Double.init) == n }
            }
            let text: String
            switch field {
            case .title: text = song.first.title
            case .artist: text = song.first.artist
            case .creator: text = song.charts.map(\.creator).joined(separator: " ")
            default: text = ([song.first.title, song.first.artist] + song.charts.map(\.creator)).joined(separator: " ")
            }
            let clean = text.precomposedStringWithCompatibilityMapping.lowercased()
            return words.allSatisfy { clean.contains($0) }
        }.sorted { $0.first.title.localizedStandardCompare($1.first.title) == .orderedAscending }
    }
    public static func recent(_ records: [ScoreRecord], catalogue: Catalogue, limit: Int = 50) -> [RatedScore] {
        let charts = catalogue.byCID, promoted = catalogue.promotedSongs
        return catalogue.filtered(records).sorted { $0.playedAt != $1.playedAt ? $0.playedAt > $1.playedAt : $0.id < $1.id }.compactMap { r in
            guard let c = charts[r.cid] else { return nil }
            let rating = Rating.score(chart: c, record: r) ?? .nan
            return RatedScore(chart: c, record: r, rating: rating, isNew: promoted.contains(c.identity))
        }.prefix(max(0, limit)).map { $0 }
    }
}

public struct RatingPreview: Codable {
    public var multipliers: [Double] = Rating.multipliers
    public var judge = 0
    public var levels: [Int: Double] = [:]
    public var accuracies: [Int: Double] = [:]
    public var judges: [Int: Int] = [:]
    public init() {}
    public func migratedRules() -> RatingPreview {
        var result = self
        if result.multipliers == [0, 0.78, 0.85, 1, 1.1, 1.2] { result.multipliers = Rating.multipliers }
        return result
    }
    public func value(chart: Chart, record: ScoreRecord) -> Double? {
        let j = judge == 0 ? (judges[chart.cid] ?? record.judge) : judge
        guard multipliers.count == 6, (1...5).contains(j), multipliers[j].isFinite, multipliers[j] > 0, multipliers[j] <= 10,
              let factor = Rating.accuracyFactor(accuracies[chart.cid] ?? record.accuracy), let level = levels[chart.cid].flatMap({ Rating.level(raw: $0, version: "") }) ?? chart.level else { return nil }
        return level * factor * multipliers[j]
    }
}
