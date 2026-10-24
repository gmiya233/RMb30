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
        XCTAssertTrue(app.staticTexts["820,594"].waitForExistence(timeout: 10))
        sync.tap()
        let repeated = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "未变化 1")).firstMatch
        XCTAssertTrue(repeated.waitForExistence(timeout: 25))
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
}
