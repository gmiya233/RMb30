import XCTest
@testable import MalodyCore

final class CoreTests: XCTestCase {
    func chart(_ cid: Int = 1, sid: Int = 1, promoted: Int = 0, level: Double = 13, version: String = "SC10 LV.13") throws -> Chart {
        let row: [String: Any] = ["cid": cid, "sid": sid, "server_hash": "hash-\(cid)", "mc_name": "1.mc", "path": "charts/_song_\(sid)/0", "mode": 7,
                                  "version": version, "level": level, "promote": promoted, "title": "Fixture Song", "artist": "Test", "bpm": 186]
        return try JSONCoding.decode(Chart.self, JSONSerialization.data(withJSONObject: row))
    }
    func varint(_ n: UInt64) -> Data {
        var value = n, data = Data()
        repeat { var byte = UInt8(value & 127); value >>= 7; if value != 0 { byte |= 128 }; data.append(byte) } while value != 0
        return data
    }
    func bytes(_ field: Int, _ data: Data) -> Data { varint(UInt64(field << 3 | 2)) + varint(UInt64(data.count)) + data }
    func integer(_ field: Int, _ n: UInt64) -> Data { varint(UInt64(field << 3)) + varint(n) }
    func replay(hash: String, accuracy: Float = 99.472244, score: UInt64 = 820594, judge: UInt64 = 0, played: UInt64 = 1792679910) -> Data {
        var fields = integer(1, 7) + integer(4, judge) + integer(5, score) + integer(6, 2378) + integer(7, 2294) + integer(8, 84)
        fields += varint(UInt64(11 << 3 | 5))
        let bits = accuracy.bitPattern; fields += Data((0..<4).map { UInt8(truncatingIfNeeded: bits >> ($0 * 8)) })
        fields += integer(12, played)
        return bytes(1, Data("Fixture Song".utf8)) + bytes(5, Data(hash.utf8)) + bytes(6, Data("PLAYER".utf8)) + integer(7, 100) + bytes(8, fields) + bytes(9, Data(repeating: 1, count: 5000))
    }
    func record(_ chart: Chart, score: UInt64 = 820594, accuracy: Float = 99.472244, played: UInt64 = 1792679910) throws -> ScoreRecord {
        let data = replay(hash: String(repeating: "a", count: 32), accuracy: accuracy, score: score, played: played)
        return ScoreRecord(chart: chart, replay: data, header: try ReplayHeader.read(data))
    }
    func testIOSWireShape() throws {
        let data = replay(hash: String(repeating: "a", count: 32)), header = try ReplayHeader.read(data)
        XCTAssertEqual(header.score, 820594); XCTAssertEqual(header.accuracy, 99.47224426269531)
        XCTAssertEqual(header.judge, 1); XCTAssertEqual(header.playedAt, 1792679910)
        XCTAssertEqual(header.player, "PLAYER"); XCTAssertEqual(header.rawFields["7"], 2294)
        for raw in 0...4 {
            let h = try ReplayHeader.read(replay(hash: String(repeating: "a", count: 32), judge: UInt64(raw)))
            XCTAssertEqual(h.judge, raw + 1)
            XCTAssertEqual(h.rawFields["4"], Double(raw))
        }
        XCTAssertEqual(try ReplayHeader.read(replay(hash: String(repeating: "a", count: 32), judge: 5)).judge, 0)
        XCTAssertThrowsError(try Wire.decode(Data([10, 255])))
        XCTAssertThrowsError(try Wire.decode(Data(repeating: 255, count: 11)))
        XCTAssertThrowsError(try ReplayHeader.read(replay(hash: "invalid")))
        XCTAssertThrowsError(try ReplayHeader.read(replay(hash: String(repeating: "a", count: 32), accuracy: 101)))
    }
    func testChartIdentityAndAmbiguity() throws {
        let data = Data("{\"meta\":{\"mode\":7,\"song\":{\"title\":\"Fixture Song\"},\"cover\":\"art.jpg\"},\"note\":[],\"time\":[]}".utf8)
        let c = try chart(), catalogue = Catalogue(server: "https://fixture.invalid", charts: [c])
        let match = try XCTUnwrap(ChartMatcher(catalogue: catalogue).match(data: data, filename: "1.mc"))
        XCTAssertEqual(match.md5, Digests.md5(data)); XCTAssertEqual(match.chart.cid, 1)
        XCTAssertNil(try ChartMatcher(catalogue: catalogue).match(data: data, filename: "other.mc"))
        let duplicate = try chart(2)
        XCTAssertNil(try ChartMatcher(catalogue: Catalogue(server: catalogue.server, charts: [c, duplicate])).match(data: data, filename: "1.mc"))
    }
    func testRatingAndBoard() throws {
        for sc in 1...40 { XCTAssertEqual(Rating.level(raw: 32, version: "SC\(sc)"), sc <= 4 ? 12 : sc <= 13 ? 13 : 15) }
        XCTAssertNil(Rating.sc("SC9.5")); XCTAssertEqual(Rating.level(raw: 16, version: ""), 8)
        XCTAssertEqual(Rating.level(raw: 24, version: ""), 12); XCTAssertEqual(Rating.level(raw: 32, version: ""), 14)
        XCTAssertNil(Rating.level(raw: 36, version: "")); XCTAssertNil(Rating.level(raw: 16.5, version: ""))
        let c = try chart(), r = try record(c)
        XCTAssertEqual(try XCTUnwrap(Rating.score(chart: c, record: r)), 10.134343639765182, accuracy: 1e-10)
        let fresh = try chart(2, sid: 2, promoted: 1), old = try record(c), new = try record(fresh, accuracy: 99.8)
        let board = ScoreBoard(records: [old, new], catalogue: Catalogue(server: "", charts: [c, fresh]))
        XCTAssertEqual(board.old.count, 1); XCTAssertEqual(board.new.count, 1)
        XCTAssertEqual(board.rating, (board.oldRating * 20 + board.newRating * 10) / 30, accuracy: 1e-12)
        let promotedDifficulty = try chart(3, sid: 1, promoted: 1)
        XCTAssertEqual(ScoreBoard(records: [old], catalogue: Catalogue(server: "", charts: [c, promotedDifficulty])).new.count, 1)
    }
    func testArchiveMergeAndChecksum() throws {
        let c = try chart(), catalogue = Catalogue(server: "https://fixture.invalid", charts: [c]), r = try record(c)
        XCTAssertTrue(r.valid)
        XCTAssertEqual(try ScoreRecord.merge(local: [r], remote: [r], catalogue: catalogue).count, 1)
        let encoded = try JSONCoding.encode(r); XCTAssertEqual(try JSONCoding.decode(ScoreRecord.self, encoded).id, r.id)
        let batches = try ArchiveDocument.batches(qq: "123456", server: catalogue.server, records: [r])
        XCTAssertEqual(batches.count, 1); XCTAssertEqual(batches[0].0, ArchiveDocument.filename(batches[0].1))
        XCTAssertEqual(try ArchiveDocument.read(batches[0].1, qq: "123456", server: catalogue.server).count, 1)
        XCTAssertThrowsError(try ArchiveDocument.read(batches[0].1, qq: "654321", server: catalogue.server))
        XCTAssertThrowsError(try JSONCoding.qq("00123")); XCTAssertThrowsError(try JSONCoding.qq("abc"))
        let changed = try chart(1, sid: 1, level: 12, version: "new")
        XCTAssertNotEqual(Catalogue(server: catalogue.server, charts: [changed]).revision(), catalogue.revision())
        XCTAssertTrue(ArchiveDocument.isFilename(batches[0].0)); XCTAssertFalse(ArchiveDocument.isFilename("../scores-x.json"))
    }
    func testGoldenRevision() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "revisions", withExtension: "json", subdirectory: "Fixtures"))
        let vectors = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
        for vector in vectors {
            let catalogue = try JSONCoding.decode(Catalogue.self, JSONSerialization.data(withJSONObject: vector["catalogue"]!))
            XCTAssertEqual(catalogue.revision(), vector["revision"] as? String)
        }
    }
    func testBrowseKeysAndRecentPlays() throws {
        var c = try chart(version: "4K Lv.13")
        XCTAssertEqual(c.keys, 4)
        let catalogue = Catalogue(server: "https://fixture.invalid", charts: [c])
        XCTAssertEqual(SongBrowse.search([c], field: .keys, query: "4").count, 1)
        XCTAssertEqual(SongBrowse.search([c], field: .keys, query: "5").count, 0)
        XCTAssertEqual(SongBrowse.search([c], field: .level, query: "13").count, 1)
        XCTAssertEqual(SongBrowse.search([c], field: .level, query: "nan").count, 0)
        c.keysSource = "conflicting_metadata"; XCTAssertNil(c.keys)
        let plays = try (0..<65).map { try record(c, played: UInt64(1000 + $0)) }
        let recent = SongBrowse.recent(plays, catalogue: catalogue)
        XCTAssertEqual(recent.count, 50); XCTAssertEqual(recent.first?.record.playedAt, 1064)
        XCTAssertEqual(Set(recent.map(\.record.id)).count, 50)
    }
    func testPreviewKeepsActualRating() throws {
        let c = try chart(), r = try record(c), catalogue = Catalogue(server: "", charts: [c])
        let actual = ScoreBoard(records: [r], catalogue: catalogue).rating
        var preview = RatingPreview(); preview.judge = 5; preview.levels[c.cid] = 15
        XCTAssertGreaterThan(ScoreBoard(records: [r], catalogue: catalogue, preview: preview).rating, actual)
        XCTAssertEqual(ScoreBoard(records: [r], catalogue: catalogue).rating, actual)
        preview.multipliers[5] = .nan; XCTAssertNil(preview.value(chart: c, record: r))
    }
    func testReviewValidation() throws {
        let bytes = try CommunityDocument.review(server: "https://fixture.invalid", topic: "s1", qq: "2033383178", name: "PLAYER", stars: 5, text: "好玩")
        let doc = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        XCTAssertEqual(doc["stars"] as? Int, 5)
        XCTAssertThrowsError(try CommunityDocument.review(server: "x", topic: "../s1", qq: "12345", name: "P", stars: 5, text: ""))
        XCTAssertThrowsError(try CommunityDocument.review(server: "x", topic: "s1", qq: "12345", name: "P", stars: 0, text: ""))
        XCTAssertThrowsError(try CommunityDocument.review(server: "x", topic: "s1", qq: "12345", name: "P", stars: 5, text: String(repeating: "a", count: 501)))
    }
    func testRecentKeepsUnratedPlaysAndCommunityNamespace() throws {
        let unknown = try chart(level: 0, version: "4K")
        let catalogue = Catalogue(server: "https://fixture.invalid", charts: [unknown])
        let r = try record(unknown)
        let recent = SongBrowse.recent([r], catalogue: catalogue)
        XCTAssertEqual(recent.count, 1); XCTAssertFalse(try XCTUnwrap(recent.first).rating.isFinite)
        XCTAssertTrue(SongBrowse.search([unknown], hideUnrated: true).isEmpty)
        let boards: [String: [[String: Any]]] = Dictionary(uniqueKeysWithValues: CommunityDocument.sections.map { ($0.0, []) })
        var doc: [String: Any] = ["schema": 1, "server": catalogue.server, "revision": catalogue.revision(), "updated": Date().timeIntervalSince1970 * 1000, "boards": boards]
        XCTAssertNoThrow(try CommunityDocument(data: JSONSerialization.data(withJSONObject: doc), catalogue: catalogue))
        doc["revision"] = "wrong"; XCTAssertThrowsError(try CommunityDocument(data: JSONSerialization.data(withJSONObject: doc), catalogue: catalogue))
        doc.removeValue(forKey: "boards"); doc["revision"] = catalogue.revision(); doc["topic"] = "s1"; doc["reviews"] = []; doc["average"] = 0; doc["rating_count"] = 0; doc["chart_downloads"] = ["1": 123]
        let song = try CommunityDocument(data: JSONSerialization.data(withJSONObject: doc), catalogue: catalogue, topic: "s1")
        XCTAssertEqual(song.chartDownloads[1], 123)
        XCTAssertThrowsError(try CommunityDocument(data: JSONSerialization.data(withJSONObject: doc), catalogue: catalogue, topic: "s2"))
        doc["average"] = 5
        XCTAssertThrowsError(try CommunityDocument(data: JSONSerialization.data(withJSONObject: doc), catalogue: catalogue, topic: "s1"))
    }
}
