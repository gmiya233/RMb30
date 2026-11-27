import Foundation
import CryptoKit
import ZIPFoundation

public struct ChartPackageItem: Identifiable {
    public let url: URL, name: String, digest: String, titles: [String]
    public let charts: Int, expanded: Int64
    public var id: String { digest }
}

/// Creates a separate export; never writes into the game's authorized directory.
public enum ChartPackage {
    public static let maximumPackage = 256 * 1024 * 1024
    public static let maximumBatch: Int64 = 1024 * 1024 * 1024
    public static func supported(_ name: String) -> Bool { ["mcz", "mcb", "zip"].contains((name as NSString).pathExtension.lowercased()) }
    public static func path(_ name: String) throws -> String {
        let value = name.hasSuffix("/") ? String(name.dropLast()) : name
        guard !value.isEmpty, value.utf16.count <= 1024, !value.hasPrefix("/"), !value.contains("\\"), !value.contains(":"),
              !value.unicodeScalars.contains(where: { $0.value < 32 }),
              value.split(separator: "/", omittingEmptySubsequences: false).allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { throw MalodyError.invalid("谱面包包含不安全的路径") }
        return value
    }
    private static func digest(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var hash = SHA256()
        while let data = try handle.read(upToCount: 65536), !data.isEmpty { try Task.checkCancellation(); hash.update(data: data) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    public static func inspect(_ url: URL, name: String) throws -> ChartPackageItem {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard supported(name), values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, size > 0, size <= maximumPackage else { throw MalodyError.invalid("请选择256MB以内的 MCZ / MCB / ZIP 谱面包") }
        let archive = try Archive(url: url, accessMode: .read)
        var paths = Set<String>(), titles: [String] = [], count = 0, charts = 0, expanded: Int64 = 0
        for entry in archive {
            try Task.checkCancellation(); count += 1
            guard count <= 20_000, entry.type != .symlink else { throw MalodyError.invalid("谱面包条目过多或含符号链接") }
            let name = try path(entry.path)
            guard paths.insert(name.folding(options: [.caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))).inserted else { throw MalodyError.invalid("谱面包内存在重复路径") }
            if entry.type == .directory { continue }
            let isChart = name.lowercased().hasSuffix(".mc")
            var data = Data()
            let crc = try archive.extract(entry) { chunk in
                try Task.checkCancellation(); expanded += Int64(chunk.count)
                guard expanded <= maximumBatch else { throw MalodyError.invalid("解压后超过1GB，请分批处理") }
                if isChart {
                    guard data.count + chunk.count <= 16 * 1024 * 1024 else { throw MalodyError.invalid("单张谱面过大") }
                    data.append(chunk)
                }
            }
            guard crc == entry.checksum else { throw MalodyError.invalid("谱面包校验失败") }
            if isChart {
                guard let doc = try JSONSerialization.jsonObject(with: data) as? [String: Any], let meta = doc["meta"] as? [String: Any],
                      meta["mode"] is NSNumber, doc["note"] is [Any], doc["time"] is [Any] else { throw MalodyError.invalid("包含无法识别的 MC 谱面") }
                charts += 1
                let title = (meta["song"] as? [String: Any])?["title"] as? String ?? name
                if titles.count < 5, !titles.contains(title) { titles.append(String(title.prefix(128))) }
            }
        }
        guard charts > 0 else { throw MalodyError.invalid("压缩包内未找到 MC 谱面；RAR、7z 请先解压") }
        return ChartPackageItem(url: url, name: name, digest: try digest(url), titles: titles, charts: charts, expanded: expanded)
    }
    public static func bundle(_ items: [ChartPackageItem], destination: URL, progress: (String) -> Void) throws -> URL {
        guard !items.isEmpty, items.count <= 5000, Set(items.map(\.digest)).count == items.count,
              items.reduce(Int64(0), { $0 + $1.expanded }) <= maximumBatch,
              !FileManager.default.fileExists(atPath: destination.path) else { throw MalodyError.invalid("曲包重复、容量过大或目标已存在") }
        let batch = destination.pathExtension.lowercased() == "mcb"
        guard batch || (destination.pathExtension.lowercased() == "mcz" && items.count == 1 && (items[0].name as NSString).pathExtension.lowercased() != "mcb") else { throw MalodyError.invalid("多首歌曲请使用 MCB 曲包") }
        let scratch = destination.deletingLastPathComponent().appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }
        let stage = scratch.appendingPathComponent("result.zip")
        do {
            let out = try Archive(url: stage, accessMode: .create)
            var written: Int64 = 0, entries = 0
            for (index, item) in items.enumerated() {
                try Task.checkCancellation(); progress("整理谱面包 \(index + 1) / \(items.count)")
                guard try digest(item.url) == item.digest else { throw MalodyError.invalid("源文件已变化，请重新选择") }
                let archive = try Archive(url: item.url, accessMode: .read)
                let nestedBatch = (item.name as NSString).pathExtension.lowercased() == "mcb"
                for entry in archive {
                    entries += 1; guard entries <= 20_000, entry.type != .symlink else { throw MalodyError.invalid("导入条目超过上限") }
                    let original = try path(entry.path)
                    if entry.type == .directory { continue }
                    // Preserve each song root of an existing MCB instead of nesting it one level deeper.
                    let parts = original.split(separator: "/", maxSplits: 1)
                    if nestedBatch && parts.count != 2 { throw MalodyError.invalid("MCB 的歌曲应放在独立根目录中") }
                    let target = !batch ? original : nestedBatch ? "malody-link-\(item.digest)-\(parts[0])/\(parts[1])" : "malody-link-\(item.digest)/\(original)"
                    let temporary = scratch.appendingPathComponent("entry")
                    FileManager.default.createFile(atPath: temporary.path, contents: nil)
                    let handle = try FileHandle(forWritingTo: temporary)
                    do {
                        let crc = try archive.extract(entry) { chunk in
                            try Task.checkCancellation(); written += Int64(chunk.count)
                            guard written <= maximumBatch else { throw MalodyError.invalid("导入超过1GB，请分批处理") }
                            try handle.write(contentsOf: chunk)
                        }
                        try handle.close()
                        guard crc == entry.checksum else { throw MalodyError.invalid("曲包校验失败") }
                        try out.addEntry(with: target, fileURL: temporary, compressionMethod: .deflate)
                    } catch { try? handle.close(); throw error }
                    try FileManager.default.removeItem(at: temporary)
                }
            }
        }
        try FileManager.default.moveItem(at: stage, to: destination)
        return destination
    }
}
