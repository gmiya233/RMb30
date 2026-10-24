import Foundation
import MalodyCore

extension CloudGateway {
    func cacheURL(_ catalogue: Catalogue, _ name: String) -> URL {
        let directory = LocalPaths.base.appendingPathComponent("published/" + Digests.sha256(Data((catalogue.server + catalogue.revision()).utf8)), isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(name + ".json")
    }
    func community(catalogue: Catalogue, topic: String? = nil) async throws -> (CommunityDocument, Bool) {
        guard catalogue.server == server, topic == nil || CommunityDocument.topic(topic!) else { throw MalodyError.invalid("社区服务器不匹配") }
        let cache = cacheURL(catalogue, topic ?? "boards")
        do {
            let bytes = try await get("/_community/v1/" + (topic.map { "songs/\($0)" } ?? "boards") + ".json", limit: 2 * 1024 * 1024)
            let doc = try CommunityDocument(data: bytes, catalogue: catalogue, topic: topic)
            guard topic != nil || Date().timeIntervalSince(doc.updated) <= 300 else { throw MalodyError.invalid("社区榜单暂未更新") }
            try bytes.write(to: cache, options: .atomic); return (doc, false)
        } catch {
            try Task.checkCancellation()
            guard let bytes = try? Data(contentsOf: cache), let doc = try? CommunityDocument(data: bytes, catalogue: catalogue, topic: topic) else { throw error }
            return (doc, true)
        }
    }
    func writeDocument(path: String, bytes: Data, limit: Int) async throws {
        guard bytes.count <= limit, path.hasPrefix("/_community/v1/reviews/") || path.hasPrefix("/_rankings/v1/") else { throw MalodyError.invalid("上传目录无效") }
        try await login()
        _ = try checked(await api("/api/fs/mkdir", ["path": (path as NSString).deletingLastPathComponent]))
        let result = try await HTTPBytes.request(URL(string: server + "/api/fs/put")!, method: "PUT", headers: ["Authorization": tokenValue(), "Content-Type": "application/json; charset=utf-8", "File-Path": path, "As-Task": "false"], body: bytes, limit: 100_000)
        _ = try checked(JSONSerialization.jsonObject(with: result) as? [String: Any] ?? [:])
        guard try await get(path, limit: limit) == bytes else { throw MalodyError.invalid("上传回读未完成，请刷新确认") }
    }
    func postReview(catalogue: Catalogue, chart: Chart, qq: String, name: String, stars: Int, text: String) async throws {
        guard catalogue.server == server, catalogue.byCID[chart.cid]?.serverHash == chart.serverHash else { throw MalodyError.invalid("谱库已变化，请重新打开歌曲") }
        let bytes = try CommunityDocument.review(server: server, topic: chart.topic, qq: qq, name: name, stars: stars, text: text)
        let path = "/_community/v1/reviews/\(chart.topic)/\(try JSONCoding.qq(qq))/review-\(Digests.sha256(bytes)).json"
        try await writeDocument(path: path, bytes: bytes, limit: 16_384)
        try bytes.write(to: cacheURL(catalogue, chart.topic + "-" + qq + "-own"), options: .atomic)
    }
    func publishIndex(qq: String, records: [ScoreRecord], catalogue: Catalogue) async throws {
        let valid = catalogue.filtered(records), board = ScoreBoard(records: valid, catalogue: catalogue)
        let newest = valid.sorted { $0.playedAt != $1.playedAt ? $0.playedAt > $1.playedAt : $0.player < $1.player }.first
        var summaries: [(Int, [ScoreRecord])] = [(0, (board.old + board.new).map(\.record))]
        for (cid, scores) in Dictionary(grouping: valid, by: \.cid).sorted(by: { $0.key < $1.key }) {
            guard let chart = catalogue.byCID[cid] else { continue }
            let best = scores.compactMap { r -> RatedScore? in
                guard let value = Rating.score(chart: chart, record: r) else { return nil }
                return RatedScore(chart: chart, record: r, rating: value, isNew: false)
            }.sorted(by: RatedScore.better).first
            if let best { summaries.append((cid, [best.record])) }
        }
        for (cid, scores) in summaries where !scores.isEmpty {
            try Task.checkCancellation()
            let rows = try JSONSerialization.jsonObject(with: JSONCoding.encode(scores))
            let doc: [String: Any] = ["schema": 1, "server": server, "revision": catalogue.revision(), "cid": cid, "qq": qq, "scores": rows, "player": newest?.player ?? "PLAYER", "named_at": newest?.playedAt ?? 0]
            let data = try JSONSerialization.data(withJSONObject: doc, options: [.sortedKeys, .withoutEscapingSlashes])
            let name = "entry-" + Digests.sha256(data) + ".json"
            let path = "/_rankings/v1/\(catalogue.revision())/" + (cid == 0 ? "rt" : "charts/\(cid)") + "/" + qq
            let receipt = cacheURL(catalogue, "published-" + qq + "-" + name)
            if (try? Data(contentsOf: receipt)) == data { continue }
            if !(try await items(path)).contains(where: { $0.name == name }) { try await writeDocument(path: path + "/" + name, bytes: data, limit: 128 * 1024) }
            try data.write(to: receipt, options: .atomic)
        }
    }
    func keys(catalogue: Catalogue) async throws -> Catalogue {
        let bytes = try await get("/_catalog/keys-v1.json", limit: 16_000_000)
        return try catalogue.enrichKeys(bytes)
    }
    func board(catalogue: Catalogue, cid: Int) async throws -> RemoteBoard {
        do {
            let result = try await publishedBoard(catalogue: catalogue, cid: cid)
            if Date().timeIntervalSince(result.updated) <= 120 { return result }
        } catch { try Task.checkCancellation() }
        do { return try await legacyBoard(catalogue: catalogue, cid: cid) }
        catch { try Task.checkCancellation(); return try await publishedBoard(catalogue: catalogue, cid: cid, cached: true) }
    }
    private func legacyBoard(catalogue: Catalogue, cid: Int) async throws -> RemoteBoard {
        guard catalogue.server == server, cid >= 0 else { throw MalodyError.invalid("排行榜服务器不匹配") }
        let base = "/_rankings/v1/\(catalogue.revision())/" + (cid == 0 ? "rt" : "charts/\(cid)")
        let directories = try await items(base).filter(\.directory)
        guard directories.count <= 1000 else { throw MalodyError.invalid("兼容榜超过读取容量") }
        var rows: [RankingRow] = []
        for entry in directories {
            guard let qq = try? JSONCoding.qq(entry.name) else { continue }
            let heads = try await items(base + "/" + qq).filter { !$0.directory && $0.name.range(of: "^entry-[a-f0-9]{64}\\.json$", options: .regularExpression) != nil }
            guard heads.count <= 256 else { throw MalodyError.invalid("排行索引分片过多") }
            var records: [ScoreRecord] = [], name = "QQ " + qq, at = -Double.infinity
            for head in heads {
                let data = try await get(base + "/" + qq + "/" + head.name, limit: 128 * 1024)
                guard head.name == "entry-" + Digests.sha256(data) + ".json",
                      let doc = try JSONSerialization.jsonObject(with: data) as? [String: Any], doc["schema"] as? Int == 1,
                      doc["server"] as? String == server, doc["revision"] as? String == catalogue.revision(), doc["qq"] as? String == qq, doc["cid"] as? Int == cid,
                      let scores = doc["scores"] as? [[String: Any]], scores.count <= (cid == 0 ? 30 : 1) else { throw MalodyError.invalid("排行索引校验失败") }
                let decoded = try JSONCoding.decode([ScoreRecord].self, JSONSerialization.data(withJSONObject: scores))
                guard cid == 0 || decoded.allSatisfy({ $0.cid == cid }) else { throw MalodyError.invalid("单曲排行谱面不匹配") }
                records += decoded
                if let n = doc["player"] as? String, !n.trimmingCharacters(in: .whitespaces).isEmpty, n.utf16.count <= 128,
                   let time = doc["named_at"] as? Double, time > at { name = n; at = time }
            }
            records = try ScoreRecord.merge(local: [], remote: records, catalogue: catalogue)
            let board = ScoreBoard(records: records, catalogue: catalogue)
            if cid == 0 { rows.append(RankingRow(qq: qq, name: name, rating: board.rating, old: board.oldRating, new: board.newRating, accuracy: nil, score: nil, judge: nil)) }
            else if let chart = catalogue.byCID[cid], let best = records.filter({ $0.cid == cid }).compactMap({ r -> RatedScore? in
                guard let value = Rating.score(chart: chart, record: r) else { return nil }; return RatedScore(chart: chart, record: r, rating: value, isNew: false)
            }).sorted(by: RatedScore.better).first {
                rows.append(RankingRow(qq: qq, name: name, rating: best.rating, old: board.oldRating, new: board.newRating, accuracy: best.record.accuracy, score: best.record.score, judge: best.record.judge))
            }
        }
        rows.sort { if $0.rating != $1.rating { return $0.rating > $1.rating }; if $0.accuracy != $1.accuracy { return ($0.accuracy ?? 0) > ($1.accuracy ?? 0) }; if $0.score != $1.score { return ($0.score ?? 0) > ($1.score ?? 0) }; return $0.qq < $1.qq }
        for i in rows.indices { rows[i].rank = i > 0 && rows[i].rating == rows[i-1].rating && rows[i].accuracy == rows[i-1].accuracy && rows[i].score == rows[i-1].score ? rows[i-1].rank : i + 1 }
        return RemoteBoard(rows: rows, updated: Date(), failed: 0, legacy: true)
    }
}

extension Catalogue {
    func enrichKeys(_ data: Data) throws -> Catalogue {
        guard data.count <= 16_000_000, let doc = try JSONSerialization.jsonObject(with: data) as? [String: Any], doc["schema"] as? Int == 1, doc["server"] as? String == server,
              let rows = doc["charts"] as? [[String: Any]], rows.count <= 100_000 else { throw MalodyError.invalid("键数信息不匹配") }
        var metadata: [Int: [String: Any]] = [:]
        for row in rows { if let cid = row["cid"] as? Int { metadata[cid] = row } }
        let enriched = charts.map { c -> Chart in
            var chart = c
            if let row = metadata[c.cid], row["server_hash"] as? String == c.serverHash, let n = row["keys"] as? Int, (0...64).contains(n) {
                chart.declaredKeys = n; chart.keysSource = row["keys_source"] as? String ?? ""
            }; return chart
        }
        return Catalogue(server: server, charts: enriched)
    }
}
