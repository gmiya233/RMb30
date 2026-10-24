import SwiftUI
import UIKit
import CoreGraphics

/// Indexed and extended-range bitmaps can fail UIImageAsset registration on
/// older iOS releases. Draw unsupported formats into explicit 8-bit sRGB and
/// give SwiftUI the CGImage directly instead of registering a UIImageAsset.
enum CompatibleBitmap {
    static func cgImage(_ source: CGImage) -> CGImage? {
        if source.colorSpace?.model == .rgb && source.bitsPerComponent == 8 && source.bitsPerPixel == 32 { return source }
        guard source.width > 0, source.height > 0, source.width <= 8192, source.height <= 8192,
              let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: source.width, height: source.height, bitsPerComponent: 8,
                                      bytesPerRow: source.width * 4, space: colorSpace,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return nil }
        context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
        return context.makeImage()
    }
}
extension Image {
    init(bitmap image: UIImage) {
        guard let source = image.cgImage, let pixels = CompatibleBitmap.cgImage(source) else { self.init(systemName: "photo"); return }
        let orientation: Image.Orientation
        switch image.imageOrientation {
        case .up: orientation = .up
        case .down: orientation = .down
        case .left: orientation = .left
        case .right: orientation = .right
        case .upMirrored: orientation = .upMirrored
        case .downMirrored: orientation = .downMirrored
        case .leftMirrored: orientation = .leftMirrored
        case .rightMirrored: orientation = .rightMirrored
        @unknown default: orientation = .up
        }
        self.init(pixels, scale: image.scale, orientation: orientation, label: Text(""))
    }
}
