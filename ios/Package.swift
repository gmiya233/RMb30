// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MalodyCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "MalodyCore", targets: ["MalodyCore"])],
    targets: [
        .target(name: "MalodyCore"),
        .testTarget(name: "MalodyCoreTests", dependencies: ["MalodyCore"], resources: [.copy("Fixtures")])
    ]
)
