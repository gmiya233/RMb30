import XCTest

/// Uses the optimized app with its complete bundled catalogue and normal startup.
/// Does not select a directory, bind a QQ, or upload any scores.
final class StartupUITests: XCTestCase {
    func testBundledReleaseStartup() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 25), "Normal startup must present the home page")
        let choose = app.buttons["选择 Malody V 文件夹"]
        XCTAssertTrue(choose.waitForExistence(timeout: 20))
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: choose)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed, "Complete bundled catalogue must finish loading")
        let exited = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.state != .runningForeground }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [exited], timeout: 10), .timedOut, "App must stay open during normal network initialization")
        XCTAssertFalse(app.alerts["操作未完成"].exists, "Bundled resources must load without a startup error")
        app.buttons["B30"].tap()
        XCTAssertTrue(app.images["O20 加 N10 成绩图"].waitForExistence(timeout: 20), "Direct CGImage rendering must retain the poster accessibility label")
        XCTAssertTrue(app.buttons["保存或分享成绩图"].exists)
        app.buttons["歌曲"].tap()
        XCTAssertTrue(app.textFields["搜索歌曲"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["0 首歌曲"].exists)
        let image = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        image.name = "Release startup with full bundled catalogue"; image.lifetime = .keepAlways; add(image)
    }
}
