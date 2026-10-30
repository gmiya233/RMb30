import Foundation

/// Uses the same stable title keys as Android and the QQ bot. Avatars stay local.
public enum PlayerTitle {
    public static func automatic(_ rating: Double) -> String {
        rating >= 13 ? "龙币" : rating >= 12 ? "大师" : rating >= 11 ? "高手" : rating >= 10 ? "萌新" : "小师妹"
    }
    public static func choices(records: [ScoreRecord], catalogue: Catalogue) -> [(key: String, name: String)] {
        let board = ScoreBoard(records: records, catalogue: catalogue)
        var choices = [("", "自动选择"), ("rank:0", "小师妹")]
        for rank in 10...13 where board.rating >= Double(rank) { choices.append(("rank:\(rank)", automatic(Double(rank)))) }
        let charts = catalogue.byCID
        var songs: [String: String] = [:]
        for record in catalogue.filtered(records) where record.accuracy > 99.8 {
            if let chart = charts[record.cid], !chart.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, chart.title.utf16.count <= 128 {
                songs[chart.identity] = chart.title
            }
        }
        choices += songs.sorted { $0.value == $1.value ? $0.key < $1.key : $0.value < $1.value }.map { ($0.key, $0.value) }
        return choices
    }
    public static func key(for selection: String, records: [ScoreRecord], catalogue: Catalogue) -> String {
        let options = choices(records: records, catalogue: catalogue)
        if options.contains(where: { $0.key == selection }) { return selection }
        // Migrate names stored by older iOS builds to Android-compatible identities.
        return options.first(where: { $0.name == selection })?.key ?? ""
    }
    public static func name(for selection: String, records: [ScoreRecord], catalogue: Catalogue) -> String {
        let key = key(for: selection, records: records, catalogue: catalogue)
        return key.isEmpty ? automatic(ScoreBoard(records: records, catalogue: catalogue).rating) : choices(records: records, catalogue: catalogue).first(where: { $0.key == key })!.name
    }
    public static func document(qq: String, server: String, selection: String, records: [ScoreRecord], catalogue: Catalogue) throws -> Data {
        guard server == catalogue.server else { throw MalodyError.invalid("称号服务器不匹配") }
        return try JSONSerialization.data(withJSONObject: ["schema": 1, "qq": try JSONCoding.qq(qq), "server": server,
            "selected": key(for: selection, records: records, catalogue: catalogue)], options: [.sortedKeys, .withoutEscapingSlashes])
    }
}
