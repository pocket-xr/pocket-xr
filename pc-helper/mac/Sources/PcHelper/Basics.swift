import Foundation
import Security

/// ~/Library/Logs/Pocket XR/pc-helper.log, beside Pocket XR for Mac's own log.
enum Log {
    static let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/Pocket XR/pc-helper.log")
    private static let queue = DispatchQueue(label: "log")
    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    static func say(_ text: String) {
        let line = "\(stamp.string(from: Date())) \(text)\n"
        queue.async {
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            if let h = try? FileHandle(forWritingTo: url) {
                h.seekToEndOfFile()
                h.write(Data(line.utf8))
                try? h.close()
            } else {
                try? Data(line.utf8).write(to: url)
            }
        }
    }
}

enum Shell {
    /// Runs a program and waits; its exit code and everything it printed.
    @discardableResult
    static func run(_ path: String, _ args: [String]) -> (code: Int32, output: String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return (-1, "\(error)") }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return (p.terminationStatus, String(decoding: data, as: UTF8.self))
    }
}

/// Paths in the user's home folder.
enum Home {
    static let url = FileManager.default.homeDirectoryForCurrentUser
    static func path(_ relative: String) -> URL { url.appendingPathComponent(relative) }
}

/// Sunshine's web login, kept in the login keychain (a generic password; the account is the user name).
enum Keychain {
    static let service = "Pocket XR PC helper: Sunshine web login"

    struct Login {
        let user: String
        let password: String
    }

    static func read() -> Login? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let found = item as? [String: Any],
              let user = found[kSecAttrAccount as String] as? String,
              let data = found[kSecValueData as String] as? Data else {
            if status != errSecItemNotFound { Log.say("keychain: read failed (\(status))") }
            return nil
        }
        return Login(user: user, password: String(decoding: data, as: UTF8.self))
    }

    @discardableResult
    static func save(_ login: Login) -> Bool {
        let match: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        SecItemDelete(match as CFDictionary)
        var add = match
        add[kSecAttrAccount as String] = login.user
        add[kSecValueData as String] = Data(login.password.utf8)
        add[kSecAttrLabel as String] = service
        let status = SecItemAdd(add as CFDictionary, nil)
        if status != errSecSuccess { Log.say("keychain: save failed (\(status))") }
        return status == errSecSuccess
    }
}

/// This Mac's short name ("Halcyons-Air"), as the Linux helper and Sunshine give it to the phone.
func computerShortName() -> String {
    var buf = [CChar](repeating: 0, count: 256)
    gethostname(&buf, 255)
    let full = String(cString: buf)
    return full.split(separator: ".").first.map(String.init) ?? (full.isEmpty ? "Mac" : full)
}
