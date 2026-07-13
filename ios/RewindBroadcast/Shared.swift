import Foundation

enum AppGroup {
    static let identifier = "group.app.lrtelecom.rewind"

    static var clipsDirectory: URL? {
        guard let base = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: identifier) else { return nil }
        let dir = base.appendingPathComponent("Clips", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}

enum DarwinNames {
    static let save = "app.lrtelecom.rewind.save"
    static let saved = "app.lrtelecom.rewind.saved"

    static func post(_ name: String) {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(name as CFString), nil, nil, true
        )
    }
}
