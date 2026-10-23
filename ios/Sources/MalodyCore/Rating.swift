import Foundation

public enum Rating {
    public static func formatLevel(_ value: Double) -> String {
        if value == value.rounded(), abs(value) < 1e12 { return String(format: "%.0f", locale: Locale(identifier: "en_US_POSIX"), value) }
        return String(value)
    }
    public static func sc(_ version: String) -> Int? {
        let pattern = "(?i)(?<![A-Z])SC\\s*([1-9][0-9]*)(?![0-9.])"
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let match = expression.firstMatch(in: version, range: NSRange(version.startIndex..., in: version)),
              let range = Range(match.range(at: 1), in: version), let value = Int(version[range]), value <= Int(Int32.max) else { return nil }
        return value
    }
    public static func level(raw: Double?, version: String) -> Double? {
        if let sc = sc(version) { return sc <= 4 ? 12 : sc <= 13 ? 13 : 15 }
        var n = raw
        if n == nil || !(n! > 0) {
            let expression = try? NSRegularExpression(pattern: "(?i)(?:lv\\.?|level)\\s*(\\d+(?:\\.\\d+)?)")
            if let match = expression?.firstMatch(in: version, range: NSRange(version.startIndex..., in: version)),
               let range = Range(match.range(at: 1), in: version) { n = Double(version[range]) }
        }
        guard let n, n.isFinite, n > 0 else { return nil }
        if n < 16 { return n }; if n == 16 { return 8 }
        guard n <= 35, n == n.rounded() else { return nil }
        if n <= 18 { return 9 }; if n <= 20 { return 10 }; if n <= 23 { return 11 }
        if n <= 26 { return 12 }; if n <= 29 { return 13 }; if n <= 32 { return 14 }; return 15
    }
    public static func accuracyFactor(_ n: Double) -> Double? {
        guard n.isFinite, (0...100).contains(n) else { return nil }
        if n >= 99.8 { return 1.01 }; if n >= 99.5 { return 1 + (n - 99.5) / 30 }
        return pow(n / 99.5, 2)
    }
    public static func score(chart: Chart, record: ScoreRecord) -> Double? {
        guard let level = chart.level, let factor = accuracyFactor(record.accuracy), (1...5).contains(record.judge) else { return nil }
        return level * factor * [0, 0.78, 0.85, 1, 1.10, 1.20][record.judge]
    }
    public static func judgeName(_ judge: Int) -> String { (1...5).contains(judge) ? "\(Array("ABCDE")[judge - 1])判" : "判定待核对" }
}

public struct RatedScore: Identifiable {
    public let chart: Chart
    public let record: ScoreRecord
    public let rating: Double
    public let isNew: Bool
    public var id: String { record.id }
    public static func better(_ lhs: RatedScore, _ rhs: RatedScore) -> Bool {
        if lhs.rating != rhs.rating { return lhs.rating > rhs.rating }
        if lhs.record.accuracy != rhs.record.accuracy { return lhs.record.accuracy > rhs.record.accuracy }
        if lhs.record.score != rhs.record.score { return lhs.record.score > rhs.record.score }
        return lhs.chart.cid < rhs.chart.cid
    }
}

public struct ScoreBoard {
    public var old: [RatedScore] = []
    public var new: [RatedScore] = []
    public var oldRating: Double { old.reduce(0) { $0 + $1.rating } / 20 }
    public var newRating: Double { new.reduce(0) { $0 + $1.rating } / 10 }
    public var rating: Double { (old.reduce(0) { $0 + $1.rating } + new.reduce(0) { $0 + $1.rating }) / 30 }
    public init(records: [ScoreRecord], catalogue: Catalogue) {
        let charts = catalogue.byCID, promoted = catalogue.promotedSongs
        var songs: [String: RatedScore] = [:]
        for record in catalogue.filtered(records) {
            guard let chart = charts[record.cid], let rating = Rating.score(chart: chart, record: record) else { continue }
            let row = RatedScore(chart: chart, record: record, rating: rating, isNew: promoted.contains(chart.identity))
            if let previous = songs[chart.identity], !RatedScore.better(row, previous) { continue }
            songs[chart.identity] = row
        }
        old = Array(songs.values.filter { !$0.isNew }.sorted(by: RatedScore.better).prefix(20))
        new = Array(songs.values.filter(\.isNew).sorted(by: RatedScore.better).prefix(10))
    }
}
