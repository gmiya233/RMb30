import Foundation

public struct ReplayReceipt: Codable {
    public let stamp: String, hash: String, binding: String, record: String
    public init(stamp: String, hash: String, binding: String, record: String) {
        self.stamp = stamp; self.hash = hash; self.binding = binding; self.record = record
    }
    public func unchanged(stamp: String, bindings: [String: String], records: Set<String>) -> Bool {
        self.stamp == stamp && binding == (bindings[hash] ?? "") && (record.isEmpty || records.contains(record))
    }
}

public enum CatalogueRevision {
    /// A hint can request a catalogue refresh, but cannot itself replace the local catalogue.
    public static func communityHint(_ bytes: Data, server: String, now: Date = Date()) throws -> String {
        guard bytes.count <= 2 * 1024 * 1024,
              let doc = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], doc["schema"] as? Int == 1,
              doc["server"] as? String == server, let revision = doc["revision"] as? String,
              revision.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              let time = doc["updated"] as? Double, time.isFinite, time > 0,
              abs(time / 1000 - now.timeIntervalSince1970) <= 300 else { throw MalodyError.invalid("社区版本提示无效") }
        return revision
    }
}
