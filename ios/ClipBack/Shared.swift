import Foundation

enum AppGroup {
    static let identifier = "group.com.clipback.app"

    static var clipsDirectory: URL? {
        guard let base = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: identifier) else { return nil }
        let dir = base.appendingPathComponent("Clips", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}

enum DarwinNames {
    static let save = "com.clipback.app.save"
    static let saved = "com.clipback.app.saved"

    static func post(_ name: String) {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(name as CFString), nil, nil, true
        )
    }
}
