import Foundation
import XCTest
@testable import MalodyCore

final class DirectoryAuthorizationTests: XCTestCase {
    private func directory() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("Malody-Grant-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        return root
    }
    private func child(_ name: String, in root: URL) throws {
        try FileManager.default.createDirectory(at: root.appendingPathComponent(name), withIntermediateDirectories: true)
    }
    func testReadOnlyGrantRoundTripAndSourceUnchanged() throws {
        let root = try directory(); try child("chart", in: root); try child("replay", in: root)
        let source = root.appendingPathComponent("replay/fixture.mrv"), bytes = Data([1, 2, 3, 4])
        try bytes.write(to: source)
        let before = try FileManager.default.attributesOfItem(atPath: source.path)
        let authorization = try DirectoryAuthorization.prepare(root)
        XCTAssertEqual(authorization.name, root.lastPathComponent)
        var stale = false
        let resolved = try URL(resolvingBookmarkData: authorization.bookmark, options: [.withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale)
        XCTAssertFalse(stale)
        XCTAssertEqual(resolved.resolvingSymlinksInPath().path, root.resolvingSymlinksInPath().path)
        XCTAssertEqual(try Data(contentsOf: source), bytes)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: source.path)[.modificationDate] as? Date, before[.modificationDate] as? Date)
    }
    func testRejectsSubfolderAndMissingReplay() throws {
        let root = try directory(); try child("chart", in: root)
        XCTAssertThrowsError(try DirectoryAuthorization.prepare(root))
        try child("replay", in: root)
        XCTAssertThrowsError(try DirectoryAuthorization.prepare(root.appendingPathComponent("chart")))
    }
    func testRejectsFileInPlaceOfDirectory() throws {
        let root = try directory(); try child("chart", in: root)
        try Data().write(to: root.appendingPathComponent("replay"))
        XCTAssertThrowsError(try DirectoryAuthorization.prepare(root))
    }
    func testRejectsLinksAndNonFileURLs() throws {
        let root = try directory(), outside = try directory(); try child("chart", in: root)
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("replay"), withDestinationURL: outside)
        XCTAssertThrowsError(try DirectoryAuthorization.prepare(root))
        XCTAssertThrowsError(try DirectoryAuthorization.prepare(URL(string: "https://example.invalid/Malody")!))
    }
}
