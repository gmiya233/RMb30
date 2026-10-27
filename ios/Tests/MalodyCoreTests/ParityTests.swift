import XCTest
import ZIPFoundation
@testable import MalodyCore

final class ParityTests: XCTestCase {
    func chart() throws -> Chart {
        let row: [String: Any] = ["cid": 1, "sid": 2, "server_hash": "hash", "version": "LV.12", "mode": 7, "level": 12, "title": "Song"]
        return try JSONCoding.decode(Chart.self, JSONSerialization.data(withJSONObject: row))
    }
    func record(_ chart: Chart, accuracy: Float) throws -> ScoreRecord {
        func v(_ value: UInt64) -> Data { var n = value, out = Data(); repeat { var b = UInt8(n & 127); n >>= 7; if n > 0 { b |= 128 }; out.append(b) } while n > 0; return out }
        func bytes(_ field: Int, _ data: Data) -> Data { v(UInt64(field << 3 | 2)) + v(UInt64(data.count)) + data }
        var score = v(40) + v(100) + v(96) + v(1) + v(93)
        score += Data((0..<4).map { UInt8(truncatingIfNeeded: accuracy.bitPattern >> ($0 * 8)) })
        let replay = bytes(5, Data(String(repeating: "a", count: 32).utf8)) + bytes(6, Data("PLAYER".utf8)) + bytes(8, score)
        return ScoreRecord(chart: chart, replay: replay, header: try ReplayHeader.read(replay))
    }
    func testTitleWireCompatibilityAndThreshold() throws {
        let c = try chart(), catalogue = Catalogue(server: "https://fixture.invalid", charts: [c])
        let records = [try record(c, accuracy: 99.9)]
        XCTAssertEqual(PlayerTitle.key(for: "Song", records: records, catalogue: catalogue), "sid:2")
        XCTAssertEqual(PlayerTitle.name(for: "sid:2", records: records, catalogue: catalogue), "Song")
        let bytes = try PlayerTitle.document(qq: "12345", server: catalogue.server, selection: "sid:2", records: records, catalogue: catalogue)
        let doc = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        XCTAssertEqual(doc["selected"] as? String, "sid:2"); XCTAssertEqual(doc["schema"] as? Int, 1)
        XCTAssertNil(doc["avatar"]); XCTAssertNil(doc["nickname"])
        XCTAssertEqual(PlayerTitle.key(for: "sid:2", records: [try record(c, accuracy: 99.7)], catalogue: catalogue), "")
        XCTAssertEqual(PlayerTitle.name(for: "rank:13", records: [], catalogue: catalogue), "小师妹")
        XCTAssertEqual(PlayerTitle.name(for: "rank:0", records: records, catalogue: catalogue), "小师妹")
        XCTAssertThrowsError(try PlayerTitle.document(qq: "12345", server: "other", selection: "", records: records, catalogue: catalogue))
    }
    func testPerReplayDependencies() {
        let receipt = ReplayReceipt(stamp: "100:20", hash: "abc", binding: "s|1:h", record: "id|h")
        XCTAssertTrue(receipt.unchanged(stamp: "100:20", bindings: ["abc": "s|1:h", "new-song": "s|2:h2"], records: ["id|h"]))
        XCTAssertFalse(receipt.unchanged(stamp: "100:21", bindings: ["abc": "s|1:h"], records: ["id|h"]))
        XCTAssertFalse(receipt.unchanged(stamp: "100:20", bindings: ["abc": "s|1:new"], records: ["id|h"]))
        XCTAssertFalse(receipt.unchanged(stamp: "100:20", bindings: ["abc": "s|1:h"], records: []))
        let excluded = ReplayReceipt(stamp: "1", hash: "x", binding: "", record: "")
        XCTAssertTrue(excluded.unchanged(stamp: "1", bindings: [:], records: []))
        XCTAssertFalse(excluded.unchanged(stamp: "1", bindings: ["x": "s|2:h"], records: []))
    }
    func testRevisionHintScopeAndFreshness() throws {
        let server = "https://fixture.invalid", now = Date()
        func doc(_ source: String, _ time: Double) throws -> Data { try JSONSerialization.data(withJSONObject: ["schema": 1, "server": source, "revision": String(repeating: "a", count: 64), "updated": time]) }
        XCTAssertEqual(try CatalogueRevision.communityHint(doc(server, now.timeIntervalSince1970 * 1000), server: server), String(repeating: "a", count: 64))
        XCTAssertThrowsError(try CatalogueRevision.communityHint(doc("other", now.timeIntervalSince1970 * 1000), server: server))
        XCTAssertThrowsError(try CatalogueRevision.communityHint(doc(server, (now.timeIntervalSince1970 - 1000) * 1000), server: server))
    }
    func package(_ url: URL, entries: [String: Data]) throws {
        let archive = try Archive(url: url, accessMode: .create)
        for (path, data) in entries {
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(data.count), compressionMethod: .deflate) { position, size in
                data.subdata(in: Int(position)..<min(data.count, Int(position) + size))
            }
        }
    }
    func testBatchRoundTripAndUnsafeInputs() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let mc = Data("{\"meta\":{\"mode\":7,\"song\":{\"title\":\"Test\"}},\"note\":[],\"time\":[]}".utf8)
        let a = root.appendingPathComponent("a.mcz"), b = root.appendingPathComponent("b.zip")
        try package(a, entries: ["1.mc": mc, "art.jpg": Data([1, 2, 3])])
        try package(b, entries: ["2.mc": mc])
        let first = try ChartPackage.inspect(a, name: "a.mcz"), second = try ChartPackage.inspect(b, name: "b.zip")
        let result = try ChartPackage.bundle([first, second], destination: root.appendingPathComponent("batch.mcb")) { _ in }
        let combined = try ChartPackage.inspect(result, name: "batch.mcb")
        XCTAssertEqual(combined.charts, 2)
        let archive = try Archive(url: result, accessMode: .read)
        XCTAssertNotNil(archive["malody-link-\(first.digest)/1.mc"])
        var restored = Data()
        _ = try archive.extract(try XCTUnwrap(archive["malody-link-\(first.digest)/1.mc"])) { restored.append($0) }
        XCTAssertEqual(restored, mc)
        let again = try ChartPackage.bundle([combined], destination: root.appendingPathComponent("again.mcb")) { _ in }
        XCTAssertEqual(try ChartPackage.inspect(again, name: "again.mcb").charts, 2)
        XCTAssertThrowsError(try ChartPackage.bundle([first, first], destination: root.appendingPathComponent("duplicate.mcb")) { _ in })
        for path in ["../x", "/x", "a/../x", "C:/x", "a\\x", "a//x"] { XCTAssertThrowsError(try ChartPackage.path(path)) }
        let bad = root.appendingPathComponent("bad.zip"); try package(bad, entries: ["../1.mc": mc])
        XCTAssertThrowsError(try ChartPackage.inspect(bad, name: "bad.zip"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("duplicate.mcb").path))
    }
}
