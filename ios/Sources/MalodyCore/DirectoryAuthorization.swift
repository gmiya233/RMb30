import Foundation

/// Validates a read-only directory grant without changing the previous stored bookmark.
/// File Provider operations can block, so the app calls this off the main actor.
public struct DirectoryAuthorization: Sendable {
    public let bookmark: Data
    public let name: String

    public static func prepare(_ url: URL) throws -> DirectoryAuthorization {
        guard url.isFileURL else { throw MalodyError.invalid("请选择本机或文件服务中的 Malody V 文件夹") }
        let granted = url.startAccessingSecurityScopedResource()
        defer { if granted { url.stopAccessingSecurityScopedResource() } }
        let keys: Set<URLResourceKey> = [.isDirectoryKey, .isSymbolicLinkKey]
        let root = try url.resourceValues(forKeys: keys)
        guard root.isDirectory == true, root.isSymbolicLink != true else {
            throw MalodyError.invalid("请选择 Malody V 根文件夹，而不是文件或快捷链接")
        }
        // Reading immediate child metadata is sufficient; do not scan or copy the game here.
        let children = try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles])
        for name in ["chart", "replay"] {
            guard let child = children.first(where: { $0.lastPathComponent == name }) else {
                throw MalodyError.invalid("请选择同时包含 chart 和 replay 的 Malody V 根文件夹；进入后点右上角「打开」")
            }
            let values = try child.resourceValues(forKeys: keys)
            guard values.isDirectory == true, values.isSymbolicLink != true else {
                throw MalodyError.invalid("chart 和 replay 必须是可读取的文件夹")
            }
        }
        let bookmark = try url.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil, relativeTo: nil)
        return DirectoryAuthorization(bookmark: bookmark, name: url.lastPathComponent)
    }
}
