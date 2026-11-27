// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MalodyCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "MalodyCore", targets: ["MalodyCore"])],
    dependencies: [.package(url: "https://github.com/weichsel/ZIPFoundation.git", exact: "0.9.20")],
    targets: [
        .target(name: "MalodyCore", dependencies: ["ZIPFoundation"]),
        .testTarget(name: "MalodyCoreTests", dependencies: ["MalodyCore"], resources: [.copy("Fixtures")])
    ]
)
