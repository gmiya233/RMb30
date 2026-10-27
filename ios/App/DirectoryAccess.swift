import Foundation
import UniformTypeIdentifiers
import UIKit
import SwiftUI
import MalodyCore

struct FolderPicker: UIViewControllerRepresentable {
    let selected: (URL) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(selected) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        picker.delegate = context.coordinator; picker.allowsMultipleSelection = false
        picker.directoryURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let selected: (URL) -> Void
        init(_ selected: @escaping (URL) -> Void) { self.selected = selected }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { if let url = urls.first { selected(url) } }
    }
}

enum LocalPaths {
    static var base: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MalodyLink", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    static func profile(_ qq: String) -> URL { base.appendingPathComponent("profiles", isDirectory: true).appendingPathComponent(qq.isEmpty ? "unbound" : qq, isDirectory: true).appendingPathComponent("state.json") }
    static var artwork: URL { base.appendingPathComponent("artwork", isDirectory: true) }
    static func jacket(_ chart: Chart) -> URL { artwork.appendingPathComponent("\(chart.cid)-\(Digests.sha256(Data(chart.serverHash.utf8))).jpg") }
}

struct LocalState: Codable {
    var records: [ScoreRecord] = []
    var journal: [String: String] = [:]
    var nickname = ""
    var title = ""
    var avatar: Data? = nil
    var replayJournal: [String: ReplayReceipt] = [:]
    enum CodingKeys: String, CodingKey { case records, journal, nickname, title, avatar, replayJournal }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        records = try c.decode([ScoreRecord].self, forKey: .records)
        journal = try c.decodeIfPresent([String: String].self, forKey: .journal) ?? [:]
        nickname = try c.decodeIfPresent(String.self, forKey: .nickname) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        avatar = try c.decodeIfPresent(Data.self, forKey: .avatar)
        replayJournal = try c.decodeIfPresent([String: ReplayReceipt].self, forKey: .replayJournal) ?? [:]
    }
}

actor StateStore {
    func load(_ qq: String) throws -> LocalState {
        let file = LocalPaths.profile(qq)
        guard FileManager.default.fileExists(atPath: file.path) else { return LocalState() }
        do { return try JSONCoding.decode(LocalState.self, Data(contentsOf: file)) }
        catch { throw MalodyError.invalid("本地存档无法读取，已保留原文件。请先导出诊断，避免覆盖。") }
    }
    func save(_ state: LocalState, qq: String) throws {
        let file = LocalPaths.profile(qq)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Atomic replacement retains the old valid state if the write is interrupted.
        try JSONCoding.encode(state).write(to: file, options: .atomic)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var directory = LocalPaths.base; try? directory.setResourceValues(values)
    }
}

enum DirectoryBookmark {
    static func save(_ url: URL) throws {
        let granted = url.startAccessingSecurityScopedResource(); defer { if granted { url.stopAccessingSecurityScopedResource() } }
        let hasChart = FileManager.default.fileExists(atPath: url.appendingPathComponent("chart").path)
        let hasReplay = FileManager.default.fileExists(atPath: url.appendingPathComponent("replay").path)
        guard hasChart && hasReplay else { throw MalodyError.invalid("请选择同时包含 chart 和 replay 的 Malody V 根文件夹") }
        let data = try url.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil, relativeTo: nil)
        UserDefaults.standard.set(data, forKey: "malody-folder-bookmark")
        UserDefaults.standard.set(url.lastPathComponent, forKey: "malody-folder-name")
    }
    static func resolve() throws -> URL {
        guard let data = UserDefaults.standard.data(forKey: "malody-folder-bookmark") else { throw MalodyError.invalid("请先选择 Malody V 文件夹") }
        var stale = false
        let url = try URL(resolvingBookmarkData: data, options: [.withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale)
        if stale { throw MalodyError.invalid("文件夹授权已失效，请重新选择 Malody V 文件夹") }
        return url
    }
    static func forget() {
        UserDefaults.standard.removeObject(forKey: "malody-folder-bookmark")
        UserDefaults.standard.removeObject(forKey: "malody-folder-name")
    }
}

struct ImportReport: Codable {
    var chartFiles = 0, matchedCharts = 0, replayFiles = 0, imported = 0, unchanged = 0, excluded = 0, invalid = 0
    var summary: String { "新增 \(imported) 条 · 未变化 \(unchanged) 份 · 未收录 \(excluded) 份" + (invalid > 0 ? " · 无法读取 \(invalid) 份" : "") }
}

struct ImportOutput { let state: LocalState; let report: ImportReport }

actor DirectoryImporter {
    private func contained(_ url: URL, in root: URL) -> Bool {
        let path = url.resolvingSymlinksInPath().standardizedFileURL.path
        return path.hasPrefix(root.resolvingSymlinksInPath().standardizedFileURL.path + "/")
    }
    private func read(_ url: URL, limit: Int) throws -> Data {
        let attributes = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard attributes.isRegularFile == true, attributes.isSymbolicLink != true, (attributes.fileSize ?? Int.max) <= limit else { throw MalodyError.invalid("文件类型或大小超出读取范围") }
        var accessError: NSError?, result: Result<Data, Error>?
        NSFileCoordinator().coordinate(readingItemAt: url, options: .withoutChanges, error: &accessError) { coordinated in
            result = Result { let bytes = try Data(contentsOf: coordinated); guard bytes.count <= limit else { throw MalodyError.invalid("文件超过读取范围") }; return bytes }
        }
        if let accessError { throw accessError }
        guard let result else { throw MalodyError.invalid("文件暂时不可用，请稍后再试") }
        return try result.get()
    }
    private func files(_ root: URL, extension suffix: String, maximum: Int) throws -> [URL] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .isSymbolicLinkKey, .isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        var failure: Error?
        guard let iterator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles, .skipsPackageDescendants], errorHandler: { _, error in failure = error; return false }) else { throw MalodyError.invalid("无法读取 Malody 文件夹，请重新授权") }
        var results: [URL] = [], inspected = 0
        for case let url as URL in iterator {
            inspected += 1; guard inspected <= 100_000 else { throw MalodyError.invalid("文件夹条目超过读取上限") }
            let values = try url.resourceValues(forKeys: Set(keys))
            if values.isSymbolicLink == true { iterator.skipDescendants(); continue }
            if url.pathExtension.lowercased() == suffix && values.isRegularFile == true && contained(url, in: root) { results.append(url) }
            guard results.count <= maximum else { throw MalodyError.invalid("文件数量超过读取上限") }
        }
        if let failure { throw failure }
        return results.sorted { $0.path < $1.path }
    }
    private func stamp(_ url: URL, revision: String) throws -> String {
        let value = try url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        return "\(revision):\(value.fileSize ?? -1):\(value.contentModificationDate?.timeIntervalSince1970 ?? -1)"
    }
    func scan(root: URL, catalogue: Catalogue, previous: LocalState, progress: @Sendable (String) async -> Void) async throws -> ImportOutput {
        let access = root.startAccessingSecurityScopedResource(); defer { if access { root.stopAccessingSecurityScopedResource() } }
        let chartRoot = root.appendingPathComponent("chart", isDirectory: true), replayRoot = root.appendingPathComponent("replay", isDirectory: true)
        guard FileManager.default.fileExists(atPath: chartRoot.path), FileManager.default.fileExists(atPath: replayRoot.path) else { throw MalodyError.invalid("无法访问 chart 或 replay，请重新选择 Malody V 根文件夹") }
        let chartFiles = try files(chartRoot, extension: "mc", maximum: 20_000)
        let replayFiles = try files(replayRoot, extension: "mrv", maximum: 20_000)
        var report = ImportReport(); report.chartFiles = chartFiles.count; report.replayFiles = replayFiles.count
        let matcher = ChartMatcher(catalogue: catalogue)
        var aliases: [String: ChartMatch] = [:], ambiguous: Set<String> = [], artFiles: [Int: (URL, [String])] = [:]
        for (index, file) in chartFiles.enumerated() {
            try Task.checkCancellation()
            if index % 25 == 0 { await progress("匹配谱面 \(index + 1) / \(chartFiles.count)") }
            do {
                guard let match = try matcher.match(data: read(file, limit: 16 * 1024 * 1024), filename: file.lastPathComponent) else { continue }
                if let previous = aliases[match.md5], previous.chart.cid != match.chart.cid { ambiguous.insert(match.md5) }
                aliases[match.md5] = match; artFiles[match.chart.cid] = (file.deletingLastPathComponent(), match.artworkNames); report.matchedCharts += 1
            } catch { report.invalid += 1 }
        }
        for hash in ambiguous { aliases.removeValue(forKey: hash) }
        var state = previous; state.records = catalogue.filtered(state.records)
        let bindings = aliases.mapValues { "\(catalogue.server)|\($0.chart.cid):\($0.chart.serverHash)" }
        var receiptRecords = Set(state.records.map { $0.id + "|" + $0.serverHash })
        var known = Set(state.records.map(\.id)), newRecords: [ScoreRecord] = []
        // Namespaced by root as well as relative path; switching folders cannot reuse unrelated stamps.
        let scope = Digests.sha256(Data(root.standardizedFileURL.path.utf8))
        var receipts: [String: ReplayReceipt] = [:]
        for (index, file) in replayFiles.enumerated() {
            try Task.checkCancellation()
            if index % 10 == 0 { await progress("读取成绩 \(index + 1) / \(replayFiles.count)") }
            let key = scope + ":" + String(file.path.dropFirst(root.path.count))
            do {
                let signature = try stamp(file, revision: "replay-v2")
                if let receipt = state.replayJournal[key], receipt.unchanged(stamp: signature, bindings: bindings, records: receiptRecords) {
                    receipts[key] = receipt; report.unchanged += 1; continue
                }
                let data = try read(file, limit: 32 * 1024 * 1024)
                guard signature == (try stamp(file, revision: "replay-v2")) else { throw MalodyError.invalid("回放正在更新") }
                let header = try ReplayHeader.read(data)
                guard let match = aliases[header.hash] else {
                    report.excluded += 1
                    receipts[key] = ReplayReceipt(stamp: signature, hash: header.hash, binding: "", record: "")
                    continue
                }
                let record = ScoreRecord(chart: match.chart, replay: data, header: header)
                guard record.valid else { throw MalodyError.invalid("回放无法转换为有效成绩") }
                if known.insert(record.id).inserted {
                    newRecords.append(record); report.imported += 1
                    if let art = artFiles[record.cid] { ThumbnailCache.copy(from: art.0, names: art.1, chart: match.chart, permittedRoot: chartRoot) }
                } else { report.unchanged += 1 }
                let recordKey = record.id + "|" + record.serverHash
                receiptRecords.insert(recordKey)
                receipts[key] = ReplayReceipt(stamp: signature, hash: header.hash, binding: bindings[header.hash] ?? "", record: recordKey)
            } catch { report.invalid += 1 }
        }
        state.records = try ScoreRecord.merge(local: state.records, remote: newRecords, catalogue: catalogue)
        state.replayJournal = receipts; state.journal = [:]
        return ImportOutput(state: state, report: report)
    }
}
