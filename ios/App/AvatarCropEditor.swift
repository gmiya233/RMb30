import SwiftUI
import UIKit

struct AvatarCropSelection: Identifiable {
    let id = UUID()
    let image: UIImage
    let profile: String
}
private final class AvatarCropState: ObservableObject { weak var scroll: AvatarCropScroll? }
struct AvatarCropEditor: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var state = AvatarCropState()
    let selection: AvatarCropSelection
    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                let side = max(100, min(geometry.size.width - 32, geometry.size.height - 120, 420))
                VStack(spacing: 20) {
                    Spacer()
                    AvatarCropSurface(image: selection.image, state: state).frame(width: side, height: side).clipped().overlay(Rectangle().stroke(.white, lineWidth: 1)).accessibilityLabel("方形头像裁剪区域，拖动图片并双指缩放")
                    Text("拖动调整位置，双指缩放\n仅保存方框内的区域").font(.subheadline).multilineTextAlignment(.center).foregroundStyle(.secondary)
                    Button("重置") { state.scroll?.reset() }.frame(minHeight: 44)
                    Spacer()
                }.frame(maxWidth: .infinity)
            }.navigationTitle("裁剪头像").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("使用头像") {
                        guard selection.profile == model.qq, let data = state.scroll?.cropped().jpegData(compressionQuality: 0.85) else { return }
                        Task { await model.saveAvatar(data); dismiss() }
                    }.disabled(model.busy || !model.ready) }
                }
        }
    }
}
private struct AvatarCropSurface: UIViewRepresentable {
    let image: UIImage
    let state: AvatarCropState
    func makeUIView(context: Context) -> AvatarCropScroll { let scroll = AvatarCropScroll(image: image); state.scroll = scroll; return scroll }
    func updateUIView(_ view: AvatarCropScroll, context: Context) {}
}
private final class AvatarCropScroll: UIScrollView, UIScrollViewDelegate {
    let source: UIImage
    let picture = UIImageView()
    private var previous = CGSize.zero
    init(image: UIImage) {
        source = image
        super.init(frame: .zero)
        picture.image = image; addSubview(picture); delegate = self
        minimumZoomScale = 1; maximumZoomScale = 6; bounces = false; bouncesZoom = false
        showsVerticalScrollIndicator = false; showsHorizontalScrollIndicator = false; contentInsetAdjustmentBehavior = .never
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != previous && bounds.width > 0 && bounds.height > 0 { previous = bounds.size; reset() }
    }
    func reset() {
        zoomScale = 1
        let scale = max(bounds.width/source.size.width, bounds.height/source.size.height)
        picture.frame = CGRect(origin: .zero, size: CGSize(width: source.size.width*scale, height: source.size.height*scale))
        contentSize = picture.frame.size
        contentOffset = CGPoint(x: max(0,(contentSize.width-bounds.width)/2), y: max(0,(contentSize.height-bounds.height)/2))
    }
    func viewForZooming(in scrollView: UIScrollView) -> UIView? { picture }
    func cropped() -> UIImage {
        let output: CGFloat = 384
        let x = max(0,min(contentOffset.x,contentSize.width-bounds.width)), y = max(0,min(contentOffset.y,contentSize.height-bounds.height))
        return UIGraphicsImageRenderer(size: CGSize(width: output, height: output)).image { context in
            context.cgContext.scaleBy(x: output/bounds.width, y: output/bounds.height)
            source.draw(in: CGRect(x: -x, y: -y, width: picture.bounds.width*zoomScale, height: picture.bounds.height*zoomScale))
        }
    }
}
