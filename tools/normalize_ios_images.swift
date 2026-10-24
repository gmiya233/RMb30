import Foundation
import CoreGraphics
import ImageIO

// Convert the Android palette PNGs to a predictable format before iOS bundles
// them. Preserve dimensions and alpha; never flatten the character backgrounds.
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
for page in ["home", "b30", "ranking", "songs", "settings"] {
    let url = root.appendingPathComponent(page + ".png")
    guard let input = CGImageSourceCreateWithURL(url as CFURL, nil),
          let source = CGImageSourceCreateImageAtIndex(input, 0, nil),
          let space = CGColorSpace(name: CGColorSpace.sRGB),
          let context = CGContext(data: nil, width: source.width, height: source.height, bitsPerComponent: 8,
                                  bytesPerRow: source.width * 4, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else {
        fatalError("Unable to decode bounded public character resource: " + page)
    }
    context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
    guard let pixels = context.makeImage(), pixels.bitsPerComponent == 8, pixels.bitsPerPixel == 32,
          let output = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) else {
        fatalError("Unable to normalize character resource: " + page)
    }
    CGImageDestinationAddImage(output, pixels, nil)
    guard CGImageDestinationFinalize(output),
          let check = CGImageSourceCreateWithURL(url as CFURL, nil),
          let decoded = CGImageSourceCreateImageAtIndex(check, 0, nil), decoded.colorSpace?.model == .rgb,
          decoded.bitsPerComponent == 8, decoded.width == source.width, decoded.height == source.height else {
        fatalError("Invalid normalized character resource: " + page)
    }
}
print("Normalized 5 character PNGs to 8-bit sRGB with alpha")
