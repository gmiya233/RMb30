import XCTest

final class AppUITests: XCTestCase {
    private var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(); app.launchEnvironment["MALODY_UI_TESTING"] = "1"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
    }
    func testLocalFolderImportAndRepeat() throws {
        let sync = app.buttons["同步本机成绩"]
        XCTAssertTrue(sync.waitForExistence(timeout: 15))
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: sync)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 15), .completed)
        sync.tap()
        let result = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "新增")).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 25))
        for _ in 0..<5 { if app.staticTexts["820,594"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.staticTexts["820,594"].waitForExistence(timeout: 10))
        for _ in 0..<5 { if sync.isHittable { break }; app.swipeDown() }
        sync.tap()
        let repeated = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "未变化 1")).firstMatch
        XCTAssertTrue(repeated.waitForExistence(timeout: 25))
        for _ in 0..<5 { if app.staticTexts["820,594"].isHittable { break }; app.swipeUp() }
        XCTAssertEqual(app.staticTexts.matching(identifier: "820,594").count, 1)
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "Local import on simulator"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    func testFiveDestinationsAndPoster() throws {
        XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
        for name in ["B30", "排行榜", "歌曲", "设置", "首页"] { XCTAssertTrue(app.buttons[name].exists); app.buttons[name].tap() }
        app.buttons["B30"].tap()
        XCTAssertTrue(app.buttons["保存或分享成绩图"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.images["O20 加 N10 成绩图"].waitForExistence(timeout: 15))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "B30 preview on simulator"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    func testSongFiltersAndSettings() throws {
        XCTAssertTrue(app.buttons["歌曲"].waitForExistence(timeout: 15)); app.buttons["歌曲"].tap()
        XCTAssertTrue(app.staticTexts["1 首歌曲"].waitForExistence(timeout: 10))
        app.switches["隐藏未提供等级的歌曲"].tap()
        let search = app.textFields["搜索歌曲"]; search.tap(); search.typeText("不存在的歌曲")
        XCTAssertTrue(app.staticTexts["没有找到匹配歌曲"].waitForExistence(timeout: 5))
        app.buttons["设置"].tap()
        XCTAssertTrue(app.staticTexts["首次使用 · 授权成绩文件夹"].waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "Settings folder guide and compact actions"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    func testLandscapeNavigation() throws {
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        app.buttons["歌曲"].tap()
        XCTAssertTrue(app.textFields["搜索歌曲"].waitForExistence(timeout: 10))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "Landscape song browser"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    func testAccessibleTextSettings() throws {
        app.terminate()
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 15)); app.buttons["设置"].tap()
        XCTAssertTrue(app.staticTexts["首次使用 · 授权成绩文件夹"].waitForExistence(timeout: 10))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "Settings accessibility text"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
}
