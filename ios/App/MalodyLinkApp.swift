import SwiftUI

@main struct MalodyLinkApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene { WindowGroup { RootView().environmentObject(model).tint(Palette.accent).preferredColorScheme(.light) } }
}

enum Palette {
    static let background = Color(red: 0.953, green: 0.965, blue: 0.976)
    static let ink = Color(red: 0.09, green: 0.169, blue: 0.227)
    static let accent = Color(red: 0.027, green: 0.427, blue: 0.471)
    static let selected = Color(red: 0.85, green: 0.945, blue: 0.933)
}
