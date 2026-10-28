import Foundation

public struct ChartPreviewPage: Decodable {
    public let image: String, sha256: String
    public let bytes: Int, width: Int, height: Int
    public func verify(_ data: Data) throws {
        guard data.count == bytes, data.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]), Digests.sha256(data) == sha256 else { throw MalodyError.invalid("预览图片校验失败，请刷新重试") }
    }
}

public struct ChartPreviewDocument {
    public let pages: [ChartPreviewPage]
    public let keys: Int, notes: Int
    public init(_ data: Data, chart: Chart, server: String) throws {
        guard data.count <= 16384, let doc = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              doc["schema"] as? Int == 1, doc["server"] as? String == server, doc["cid"] as? Int == chart.cid,
              doc["server_hash"] as? String == chart.serverHash else { throw MalodyError.invalid("预览与当前谱面不匹配，请更新谱库后重试") }
        guard doc["status"] as? String == "ready", let rows = doc["pages"] as? [[String: Any]], (1...4).contains(rows.count),
              let keys = doc["keys"] as? Int, (4...6).contains(keys), let notes = doc["notes"] as? Int, (1...40000).contains(notes) else { throw MalodyError.invalid("谱面预览暂未就绪，目前支持4K／5K／6K直轨") }
        let pages = try JSONCoding.decode([ChartPreviewPage].self, JSONSerialization.data(withJSONObject: rows))
        for page in pages {
            guard page.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
                  page.image == "\(chart.cid)-\(page.sha256).png", (1...4194304).contains(page.bytes),
                  (1...8192).contains(page.width), (1...4096).contains(page.height), page.width * page.height <= 16_000_000 else { throw MalodyError.invalid("预览图片信息无效") }
        }
        self.pages = pages; self.keys = keys; self.notes = notes
    }
}
