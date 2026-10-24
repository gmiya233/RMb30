import Foundation

public struct CommunityRow: Identifiable {
    public let id: String, name: String
    public let rank: Int, cid: Int, votes: Int
    public let downloads: Int64
    public let average: Double, rating: Double
}
public struct SongReview: Identifiable {
    public let qq: String, name: String, text: String
    public let stars: Int
    public let updated: Double
    public var id: String { qq }
}
public struct CommunityDocument {
    public let updated: Date
    public let boards: [String: [CommunityRow]]
    public let reviews: [SongReview]
    public let average: Double, votes: Int
    public let chartDownloads: [Int: Int64]
    public static let sections = [("total", "总榜"), ("song_hot", "歌曲热度榜"), ("song_good", "歌曲好评榜"), ("creator_hot", "谱师热度榜"), ("creator_good", "谱师好评榜")]
    public static func topic(_ value: String) -> Bool { value.range(of: "^[sc][1-9][0-9]*$", options: .regularExpression) != nil }
    public init(data: Data, catalogue: Catalogue, topic: String? = nil, now: Date = Date()) throws {
        guard data.count <= 2 * 1024 * 1024,
              let doc = try JSONSerialization.jsonObject(with: data) as? [String: Any], doc["schema"] as? Int == 1,
              doc["server"] as? String == catalogue.server, doc["revision"] as? String == catalogue.revision(),
              let time = doc["updated"] as? Double, time.isFinite, time > 0, time <= now.timeIntervalSince1970 * 1000 + 300_000 else { throw MalodyError.invalid("社区数据与当前谱库不匹配") }
        updated = Date(timeIntervalSince1970: time / 1000)
        var tables: [String: [CommunityRow]] = [:], entries: [SongReview] = [], counts: [Int: Int64] = [:]
        if let topic {
            guard Self.topic(topic), doc["topic"] as? String == topic, let array = doc["reviews"] as? [[String: Any]], array.count <= 200 else { throw MalodyError.invalid("歌曲评论不匹配") }
            var seen = Set<String>()
            for row in array {
                let qq = try JSONCoding.qq(row["qq"] as? String ?? "")
                guard seen.insert(qq).inserted, row["topic"] as? String == topic, let name = row["name"] as? String, name.utf16.count <= 128,
                      let text = row["text"] as? String, text.utf16.count <= 500, let stars = row["stars"] as? Int, (1...5).contains(stars),
                      let at = row["updated"] as? Double, at.isFinite, at >= 0 else { throw MalodyError.invalid("评论内容无效") }
                entries.append(SongReview(qq: qq, name: name, text: text, stars: stars, updated: at))
            }
            let raw = doc["chart_downloads"] as? [String: Any] ?? [:]
            guard raw.count <= 4096 else { throw MalodyError.invalid("谱面下载统计超过容量") }
            for (key, value) in raw {
                guard let cid = Int(key), cid > 0, let n = value as? NSNumber, n.doubleValue.isFinite, n.doubleValue >= 0, n.doubleValue == n.doubleValue.rounded(), n.doubleValue < Double(Int64.max) else { throw MalodyError.invalid("谱面下载量无效") }
                counts[cid] = n.int64Value
            }
        } else {
            guard let raw = doc["boards"] as? [String: [[String: Any]]] else { throw MalodyError.invalid("社区榜单缺失") }
            for key in Self.sections.map(\.0) + ["creator_hot_all"] {
                if key == "creator_hot_all" && raw[key] == nil { continue }
                guard let array = raw[key], array.count <= 50 else { throw MalodyError.invalid("榜单超过前50名容量") }
                var seen = Set<String>(), rows: [CommunityRow] = []
                for row in array {
                    let identity = row[key == "total" ? "qq" : "id"] as? String ?? ""
                    if key == "total" { _ = try JSONCoding.qq(identity) }
                    else if key.hasPrefix("song_") { guard Self.topic(identity), (row["cid"] as? Int ?? 0) > 0 else { throw MalodyError.invalid("榜单歌曲无效") } }
                    else { guard identity.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw MalodyError.invalid("谱师编号无效") } }
                    guard seen.insert(identity).inserted, let name = row["name"] as? String, name.utf16.count <= 512,
                          let rank = row["rank"] as? Int, (1...50).contains(rank) else { throw MalodyError.invalid("榜单条目无效") }
                    let average = row["average"] as? Double ?? 0, rating = row["rating"] as? Double ?? 0
                    let downloads = (row["downloads"] as? NSNumber)?.int64Value ?? 0, votes = row["rating_count"] as? Int ?? 0
                    guard average.isFinite, (0...5).contains(average), rating.isFinite, rating >= 0, downloads >= 0, votes >= 0, (votes == 0 ? average == 0 : average >= 1) else { throw MalodyError.invalid("榜单统计无效") }
                    rows.append(CommunityRow(id: identity, name: name, rank: rank, cid: row["cid"] as? Int ?? 0, votes: votes, downloads: downloads, average: average, rating: rating))
                }
                tables[key] = rows
            }
        }
        average = doc["average"] as? Double ?? 0; votes = doc["rating_count"] as? Int ?? 0
        guard average.isFinite, (0...5).contains(average), votes >= 0, entries.count <= votes, (votes == 0 ? average == 0 : average >= 1) else { throw MalodyError.invalid("歌曲评分统计无效") }
        boards = tables; reviews = entries.sorted { $0.updated > $1.updated }; chartDownloads = counts
    }
    public static func review(server: String, topic: String, qq: String, name: String, stars: Int, text: String) throws -> Data {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines), text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.topic(topic), !name.isEmpty, name.utf16.count <= 128, text.utf16.count <= 500, (1...5).contains(stars) else { throw MalodyError.invalid("请填写1～5星和500字以内的评论") }
        let doc: [String: Any] = ["schema": 1, "server": server, "topic": topic, "qq": try JSONCoding.qq(qq), "name": name, "stars": stars, "text": text, "nonce": UUID().uuidString.lowercased()]
        return try JSONSerialization.data(withJSONObject: doc, options: [.sortedKeys, .withoutEscapingSlashes])
    }
}
