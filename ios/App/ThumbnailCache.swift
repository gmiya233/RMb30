import Foundation
import ImageIO
import UniformTypeIdentifiers
import MalodyCore

enum ThumbnailCache {
    static func copy(from folder: URL, names: [String], chart: Chart, permittedRoot: URL) {
        let destination = LocalPaths.jacket(chart)
        if FileManager.default.fileExists(atPath: destination.path) { return }
        for name in names {
            let clean = name.replacingOccurrences(of: "\\", with: "/")
            guard !clean.hasPrefix("/"), !clean.split(separator: "/").contains("..") else { continue }
            let url = folder.appendingPathComponent(clean).resolvingSymlinksInPath()
            guard url.path.hasPrefix(permittedRoot.resolvingSymlinksInPath().path + "/"),
                  let attributes = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]), attributes.isRegularFile == true,
                  (attributes.fileSize ?? Int.max) <= 16 * 1024 * 1024,
                  let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                       kCGImageSourceThumbnailMaxPixelSize: 512, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { continue }
            try? FileManager.default.createDirectory(at: LocalPaths.artwork, withIntermediateDirectories: true)
            let data = NSMutableData()
            guard let output = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { continue }
            CGImageDestinationAddImage(output, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
            if CGImageDestinationFinalize(output) { try? (data as Data).write(to: destination, options: .atomic); return }
        }
    }
}
