#if DEBUG
import Foundation
import MalodyCore
import UIKit

/// Synthetic local-only fixture. This entire file is excluded by Release compilation.
enum UITestFixture {
    static func prepare() throws -> (Catalogue, URL)? {
        guard ProcessInfo.processInfo.environment["MALODY_UI_TESTING"] == "1" else { return nil }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("IOS-UI-TEST-FIXTURE", isDirectory: true)
        let chartFolder = root.appendingPathComponent("chart/_song_1/0", isDirectory: true)
        let replays = root.appendingPathComponent("replay", isDirectory: true)
        try FileManager.default.createDirectory(at: chartFolder, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: replays, withIntermediateDirectories: true)
        let chart = Data("{\"meta\":{\"mode\":7,\"song\":{\"title\":\"UITest Song\"}},\"note\":[],\"time\":[]}".utf8)
        try chart.write(to: chartFolder.appendingPathComponent("1.mc"), options: .atomic)
        let row: [String: Any] = ["cid": 1, "sid": 1, "server_hash": "fixture-hash", "mode": 7, "mc_name": "1.mc", "path": "promote/_song_1/0", "version": "SC10 LV.13", "level": 13, "promote": 1, "title": "UITest Song", "artist": "TEST", "creator": "TEST", "bpm": 186]
        let catalogue = try JSONCoding.decode(Catalogue.self, JSONSerialization.data(withJSONObject: ["server": "https://fixture.invalid", "charts": [row]]))
        func varint(_ n: UInt64) -> Data { var value = n, result = Data(); repeat { var byte = UInt8(value & 127); value >>= 7; if value > 0 { byte |= 128 }; result.append(byte) } while value > 0; return result }
        func integer(_ field: Int, _ n: UInt64) -> Data { varint(UInt64(field << 3)) + varint(n) }
        func bytes(_ field: Int, _ data: Data) -> Data { varint(UInt64(field << 3 | 2)) + varint(UInt64(data.count)) + data }
        var score = integer(4, 1) + integer(5, 820594) + integer(12, 1792679910)
        score += varint(UInt64(11 << 3 | 5)); let bits = Float(99.472244).bitPattern
        score += Data((0..<4).map { UInt8(truncatingIfNeeded: bits >> ($0 * 8)) })
        let replay = bytes(5, Data(Digests.md5(chart).utf8)) + bytes(6, Data("TEST PLAYER".utf8)) + bytes(8, score)
        try replay.write(to: replays.appendingPathComponent("fixture.mrv"), options: .atomic)
        if ProcessInfo.processInfo.environment["MALODY_PREVIEW_UI_TESTING"] == "1" {
            let folder = root.appendingPathComponent("previews", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let format = UIGraphicsImageRendererFormat(); format.scale = 1
            let image = UIGraphicsImageRenderer(size: CGSize(width: 960, height: 1300), format: format).image { context in
                UIColor(red: 0.06, green: 0.1, blue: 0.15, alpha: 1).setFill(); context.fill(CGRect(x: 0, y: 0, width: 960, height: 1300))
                for lane in 0..<4 { UIColor.gray.setStroke(); context.cgContext.stroke(CGRect(x: 80 + lane * 180, y: 100, width: 180, height: 1100)) }
                UIColor.cyan.setFill(); context.fill(CGRect(x: 90, y: 280, width: 160, height: 16))
                UIColor.green.setFill(); context.fill(CGRect(x: 350, y: 400, width: 12, height: 280))
            }
            let bytes = image.pngData()!, hash = Digests.sha256(bytes), name = "1-\(hash).png"
            try bytes.write(to: folder.appendingPathComponent(name), options: .atomic)
            let manifest: [String: Any] = ["schema": 1, "server": catalogue.server, "cid": 1, "server_hash": "fixture-hash", "status": "ready", "keys": 4, "notes": 2,
                "pages": [["image": name, "sha256": hash, "bytes": bytes.count, "width": 960, "height": 1300]]]
            try JSONSerialization.data(withJSONObject: manifest).write(to: folder.appendingPathComponent("1.json"), options: .atomic)
        }
        if ProcessInfo.processInfo.environment["MALODY_PACKAGE_UI_TESTING"] == "1" {
            let package = Data(base64Encoded: "UEsDBBQAAAAIADpGW13kJ9JnRAAAAEsAAAAEAAAAMS5tY6tWyk0tSVSyqlbKzU9JVbIy11Eqzs9LBwmUZJbkAEWUQj0VnDMSi0oUAhKTsxPTU5Vqa3WU8vJLgHLRsTpAZblgVi0AUEsBAhQAFAAAAAgAOkZbXeQn0mdEAAAASwAAAAQAAAAAAAAAAAAAAIABAAAAADEubWNQSwUGAAAAAAEAAQAyAAAAZgAAAAAA")!
            try package.write(to: root.appendingPathComponent("synthetic.mcz"), options: .atomic)
        }
        return (catalogue, root)
    }
    static func preview(_ path: String) throws -> Data? {
        guard ProcessInfo.processInfo.environment["MALODY_UI_TESTING"] == "1", ProcessInfo.processInfo.environment["MALODY_PREVIEW_UI_TESTING"] == "1", path.hasPrefix("/_chart_previews/v1/") else { return nil }
        let name = String(path.dropFirst("/_chart_previews/v1/".count))
        guard name == "1.json" || name.range(of: "^1-[a-f0-9]{64}\\.png$", options: .regularExpression) != nil else { throw MalodyError.invalid("Invalid synthetic preview") }
        return try Data(contentsOf: FileManager.default.temporaryDirectory.appendingPathComponent("IOS-UI-TEST-FIXTURE/previews/" + name))
    }
}
#endif
