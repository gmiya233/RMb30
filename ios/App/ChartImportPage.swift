import SwiftUI
import UniformTypeIdentifiers
import MalodyCore

struct ChartFilePicker: UIViewControllerRepresentable {
    let selected: ([URL]) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(selected) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
        picker.allowsMultipleSelection = true; picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let selected: ([URL]) -> Void
        init(_ selected: @escaping ([URL]) -> Void) { self.selected = selected }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { selected(urls) }
    }
}

actor ChartPackageWorker {
    let root: URL
    private var previousExport: URL?
    init() { root = FileManager.default.temporaryDirectory.appendingPathComponent("ChartPackages-" + UUID().uuidString, isDirectory: true) }
    func inspect(_ source: URL) throws -> ChartPackageItem {
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        guard ChartPackage.supported(source.lastPathComponent) else { throw MalodyError.invalid("仅支持 MCZ / MCB / ZIP，RAR、7z 请先解压") }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let destination = root.appendingPathComponent(UUID().uuidString + "." + source.pathExtension)
        var coordinationError: NSError?, result: Result<Void, Error>?
        NSFileCoordinator().coordinate(readingItemAt: source, options: .withoutChanges, error: &coordinationError) { url in
            result = Result {
                let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
                guard values.isRegularFile == true, values.isSymbolicLink != true, let size = values.fileSize, size > 0, size <= ChartPackage.maximumPackage else { throw MalodyError.invalid("请选择256MB以内的谱面包") }
                let input = try FileHandle(forReadingFrom: url); defer { try? input.close() }
                FileManager.default.createFile(atPath: destination.path, contents: nil)
                let output = try FileHandle(forWritingTo: destination); defer { try? output.close() }
                var copied = 0
                while let chunk = try input.read(upToCount: 65536), !chunk.isEmpty {
                    try Task.checkCancellation(); copied += chunk.count
                    guard copied <= ChartPackage.maximumPackage else { throw MalodyError.invalid("文件超过256MB") }
                    try output.write(contentsOf: chunk)
                }
            }
        }
        do {
            if let coordinationError { throw coordinationError }
            guard let result else { throw MalodyError.invalid("文件暂时无法读取") }
            try result.get()
            return try ChartPackage.inspect(destination, name: source.lastPathComponent)
        } catch { try? FileManager.default.removeItem(at: destination); throw error }
    }
    func remove(_ item: ChartPackageItem) { try? FileManager.default.removeItem(at: item.url) }
    func export(_ items: [ChartPackageItem]) throws -> URL {
        if let previousExport { try? FileManager.default.removeItem(at: previousExport) }
        let ext = items.count == 1 && (items[0].name as NSString).pathExtension.lowercased() != "mcb" ? "mcz" : "mcb"
        let output = try ChartPackage.bundle(items, destination: root.appendingPathComponent("Malody-Link-" + UUID().uuidString + "." + ext)) { _ in }
        previousExport = output; return output
    }
    func clear() { try? FileManager.default.removeItem(at: root) }
}

@MainActor final class ChartPackageQueue: ObservableObject {
    @Published var items: [ChartPackageItem] = []
    @Published var busy = false
    @Published var status = "支持从「文件」多选曲包，也可以在 QQ 中分享给 Malody Link。"
    @Published var output: URL?
    private let worker = ChartPackageWorker()
    private var task: Task<Void, Never>?
    private var closed = false
    func add(_ urls: [URL]) {
        guard !closed, !busy, !urls.isEmpty else { return }; busy = true; output = nil
        task = Task {
            defer { busy = false; task = nil }
            var skipped = 0, failures: [String] = []
            for (index, url) in urls.enumerated() {
                if Task.isCancelled { status = "已停止，已添加的曲包保留"; return }
                status = "检查曲包 \(index + 1) / \(urls.count)"
                do {
                    guard items.count < 5000 else { throw MalodyError.invalid("单次最多5000个曲包") }
                    let item = try await worker.inspect(url)
                    if items.contains(where: { $0.digest == item.digest }) { skipped += 1; await worker.remove(item); continue }
                    guard items.reduce(Int64(0), { $0 + $1.expanded }) + item.expanded <= ChartPackage.maximumBatch else { await worker.remove(item); throw MalodyError.invalid("总容量超过1GB，请分批导入") }
                    items.append(item)
                } catch is CancellationError { status = "已停止，已添加的曲包保留"; return }
                catch { failures.append(url.lastPathComponent + "：" + error.localizedDescription) }
            }
            status = "已添加 \(items.count) 个曲包 · 跳过重复 \(skipped) 个" + (failures.isEmpty ? "" : "\n" + failures.prefix(3).joined(separator: "\n"))
        }
    }
    func remove(_ item: ChartPackageItem) { guard !busy else { return }; output = nil; items.removeAll { $0.id == item.id }; Task { await worker.remove(item) } }
    func export() {
        guard !busy, !items.isEmpty else { return }; busy = true; status = "正在生成导入包…"
        task = Task {
            defer { busy = false; task = nil }
            do { output = try await worker.export(items); status = "曲包已生成。点分享后选择 Malody，或保存到「文件」后用 Malody 打开。" }
            catch is CancellationError { status = "已停止生成，原曲包保留" }
            catch { status = error.localizedDescription }
        }
    }
    func cancel() { task?.cancel() }
    func close() { closed = true; task?.cancel(); let previous = task; Task { await previous?.value; await worker.clear() } }
}

struct ChartImportPage: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var queue = ChartPackageQueue()
    @State private var choosing = false
    @State private var sharing = false
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Card {
                    Label("批量导入谱面", systemImage: "square.stack.3d.up").font(.title3.bold()).foregroundStyle(Palette.accent)
                    Text("选择 MCZ / MCB / ZIP → 生成导入包 → 分享给 Malody").font(.subheadline)
                    Text("QQ 收到的曲包可先存到「文件」。多首歌曲会整理为 MCB，Malody 完成导入后，再回首页同步成绩。").font(.caption).foregroundStyle(.secondary)
                    Button { choosing = true } label: { Label("选择曲包（可多选）", systemImage: "folder.badge.plus").frame(minHeight: 44) }.buttonStyle(.borderedProminent).disabled(queue.busy)
                }
                if queue.busy { ProgressView(queue.status).frame(maxWidth: .infinity); Button("停止处理") { queue.cancel() }.frame(minHeight: 44) }
                else { Text(queue.status).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("曲包处理结果") }
                ForEach(queue.items) { item in
                    Card {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(item.name).font(.headline)
                                Text("\(item.charts) 张谱面 · " + ByteCountFormatter.string(fromByteCount: item.expanded, countStyle: .file)).font(.caption).foregroundStyle(.secondary)
                                Text(item.titles.joined(separator: " / ")).font(.subheadline).lineLimit(3)
                            }
                            Spacer()
                            Button { queue.remove(item) } label: { Image(systemName: "trash").frame(width: 44, height: 44) }.accessibilityLabel("移除 " + item.name).disabled(queue.busy)
                        }
                    }
                }
                Button { queue.export() } label: { Label("生成 Malody 导入包", systemImage: "shippingbox").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderedProminent).disabled(queue.busy || queue.items.isEmpty)
                if queue.output != nil {
                    Button { sharing = true } label: { Label("分享给 Malody / 保存到文件", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered).disabled(queue.busy)
                }
            }.padding(18).frame(maxWidth: 820).frame(maxWidth: .infinity)
        }.navigationTitle("批量导谱")
        .sheet(isPresented: $choosing) { ChartFilePicker { urls in choosing = false; queue.add(urls) } }
        .sheet(isPresented: $sharing) { if let url = queue.output { ShareSheet(items: [url]) } }
        .onAppear { acceptIncoming() }
        .onChange(of: model.incomingPackages) { _ in acceptIncoming() }
        .onChange(of: queue.busy) { if !$0 { acceptIncoming() } }
        // A document/share sheet can cover this page on small or landscape devices.
        // Keep its files available until the page itself is closed.
        .onDisappear { if !choosing && !sharing { queue.close() } }
    }
    private func acceptIncoming() {
        guard !queue.busy, !model.incomingPackages.isEmpty else { return }
        let urls = model.incomingPackages; model.incomingPackages = []; queue.add(urls)
    }
}
