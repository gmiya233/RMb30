import SwiftUI
import UIKit
import Photos
import MalodyCore

struct ChartPreviewScreen: View {
    @EnvironmentObject var model: AppModel
    let chart: Chart
    @State private var document: ChartPreviewDocument?
    @State private var image: UIImage?
    @State private var page = 0
    @State private var loading = false
    @State private var message = "正在读取谱面预览…"
    @State private var sharing = false
    var body: some View {
        VStack(spacing: 12) {
            Text(chart.title + " · " + chart.version).font(.headline).multilineTextAlignment(.center).padding(.horizontal)
            Text(message).font(.caption).foregroundStyle(.secondary).padding(.horizontal).accessibilityIdentifier("谱面预览状态")
            if loading { ProgressView().accessibilityLabel("加载谱面预览") }
            if let image { ZoomImage(image: image, maximumZoom: 16).accessibilityLabel("直轨谱面展开图").frame(maxWidth: .infinity, maxHeight: .infinity) }
            else { Spacer(); Image(systemName: "pianokeys").font(.system(size: 50)).foregroundStyle(Palette.accent).accessibilityHidden(true); Spacer() }
            if let document, document.pages.count > 1 {
                HStack {
                    Button("上一页") { page -= 1 }.disabled(page == 0 || loading).frame(minHeight: 44)
                    Spacer(); Text("\(page + 1) / \(document.pages.count)").monospacedDigit(); Spacer()
                    Button("下一页") { page += 1 }.disabled(page + 1 >= document.pages.count || loading).frame(minHeight: 44)
                }.padding(.horizontal)
            }
            HStack {
                Button { Task { await load() } } label: { Label("刷新", systemImage: "arrow.clockwise").frame(minHeight: 44) }.disabled(loading)
                Spacer()
                Button { Task { await save() } } label: { Image(systemName: "square.and.arrow.down").frame(width: 48, height: 48) }.accessibilityLabel("保存谱面预览到相册").disabled(image == nil || loading)
                Button { sharing = true } label: { Image(systemName: "square.and.arrow.up").frame(width: 48, height: 48) }.accessibilityLabel("分享谱面预览").disabled(image == nil || loading)
            }.padding(.horizontal)
        }.background(Palette.background).navigationTitle("谱面预览").navigationBarTitleDisplayMode(.inline)
        .task { await load() }.task(id: page) { if document != nil { await loadImage() } }
        .sheet(isPresented: $sharing) { if let image { ShareSheet(items: [image]) } }
    }
    @MainActor private func load() async {
        guard !loading, let cloud = model.cloud else { return }; loading = true; defer { loading = false }
        do {
            document = nil; image = nil
            let bytes = try await cloud.previewManifest(chart: chart, catalogue: model.catalogue)
            let doc = try ChartPreviewDocument(bytes, chart: chart, server: model.catalogue.server)
            document = doc; page = min(page, doc.pages.count - 1)
        } catch { message = error.localizedDescription; return }
        await fetchImage()
    }
    @MainActor private func loadImage() async {
        guard !loading else { return }; loading = true; defer { loading = false }; await fetchImage()
    }
    @MainActor private func fetchImage() async {
        guard let document, document.pages.indices.contains(page), let cloud = model.cloud else { return }
        image = nil; message = "正在加载第 \(page + 1) 页…"
        do {
            let item = document.pages[page]
            let bytes = try await cloud.previewImage(item, catalogue: model.catalogue)
            try Task.checkCancellation()
            guard let decoded = UIImage(data: bytes), Int(decoded.size.width * decoded.scale) == item.width, Int(decoded.size.height * decoded.scale) == item.height else { throw MalodyError.invalid("预览图尺寸不匹配") }
            image = decoded; message = "EX Rhythm Master VI · \(document.keys)K · \(document.notes)音符 · 双指放大，拖动查看"
        } catch is CancellationError { }
        catch { message = error.localizedDescription }
    }
    @MainActor private func save() async {
        guard let image else { return }
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { message = "请在系统设置允许保存图片到照片"; return }
        do { try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.creationRequestForAsset(from: image) }; message = "谱面预览已保存到照片" }
        catch { message = "保存失败，可使用分享按钮保存" }
    }
}

extension CloudGateway {
    func previewManifest(chart: Chart, catalogue: Catalogue) async throws -> Data {
        guard catalogue.server == server, catalogue.byCID[chart.cid]?.serverHash == chart.serverHash else { throw MalodyError.invalid("谱库已变化，请重新打开歌曲") }
        let path = cacheURL(catalogue, "preview-\(chart.cid)")
        let bytes: Data
        do { bytes = try await get("/_chart_previews/v1/\(chart.cid).json", limit: 16384) }
        catch { try Task.checkCancellation(); if let cached = try? Data(contentsOf: path) { _ = try ChartPreviewDocument(cached, chart: chart, server: server); return cached }; throw error }
        _ = try ChartPreviewDocument(bytes, chart: chart, server: server)
        try? bytes.write(to: path, options: .atomic); return bytes
    }
    func previewImage(_ item: ChartPreviewPage, catalogue: Catalogue) async throws -> Data {
        guard catalogue.server == server else { throw MalodyError.invalid("预览服务器不匹配") }
        let cache = LocalPaths.base.appendingPathComponent("chart-previews", isDirectory: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        let path = cache.appendingPathComponent(item.sha256 + ".png")
        if let cached = try? Data(contentsOf: path), (try? item.verify(cached)) != nil { return cached }
        let bytes = try await get("/_chart_previews/v1/" + item.image, limit: 4194304)
        try item.verify(bytes); try? bytes.write(to: path, options: .atomic); return bytes
    }
}
