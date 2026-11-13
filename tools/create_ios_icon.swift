import AppKit
import Foundation

let destination = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
let specifications: [(String, Int, Int)] = [("iphone",20,2),("iphone",20,3),("iphone",29,2),("iphone",29,3),("iphone",40,2),("iphone",40,3),("iphone",60,2),("iphone",60,3),("ipad",20,1),("ipad",20,2),("ipad",29,1),("ipad",29,2),("ipad",40,1),("ipad",40,2),("ipad",76,1),("ipad",76,2),("ios-marketing",1024,1)]
var images: [[String: String]] = []
func render(_ size: Int, filename: String) throws {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    NSColor(red: 0.027, green: 0.427, blue: 0.471, alpha: 1).setFill(); NSBezierPath(rect: NSRect(x: 0, y: 0, width: CGFloat(size), height: CGFloat(size))).fill()
    let scale = CGFloat(size) / 1024
    NSColor.white.setFill()
    for (x, height) in [(250.0,270.0),(440.0,520.0),(630.0,390.0)] {
        NSBezierPath(roundedRect: NSRect(x: CGFloat(x) * scale, y: 245 * scale, width: 135 * scale, height: CGFloat(height) * scale), xRadius: 40 * scale, yRadius: 40 * scale).fill()
    }
    NSGraphicsContext.restoreGraphicsState()
    try bitmap.representation(using: .png, properties: [:])!.write(to: destination.appendingPathComponent(filename))
}
var rendered: Set<Int> = []
for (idiom, points, factor) in specifications {
    let size = points * factor, filename = "Icon-\(size).png"
    if rendered.insert(size).inserted { try render(size, filename: filename) }
    images.append(["idiom": idiom, "size": "\(points)x\(points)", "scale": "\(factor)x", "filename": filename])
}
try render(167, filename: "Icon-167.png")
images.append(["idiom": "ipad", "size": "83.5x83.5", "scale": "2x", "filename": "Icon-167.png"])
let contents: [String: Any] = ["images": images, "info": ["version": 1, "author": "xcode"]]
try JSONSerialization.data(withJSONObject: contents, options: [.prettyPrinted, .sortedKeys]).write(to: destination.appendingPathComponent("Contents.json"))
