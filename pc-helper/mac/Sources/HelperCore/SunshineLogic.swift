import Foundation

/// Sunshine's web API for pairing, as Sunshine 2026.914 on macOS answered it: GET /api/pin lists the phones waiting to
/// pair ({"pairings":[{"address","id","name"}]}), and POST /api/pin takes {"pairing_id","pin","name"}; without
/// pairing_id it answers 400 "pairing_id must contain exactly 32 hexadecimal characters". Older Sunshine has no list
/// and takes {"pin","name"}. Success is {"status": true} (or "true").
public enum SunshinePairing {
    public struct Waiting: Equatable {
        public let id: String
        public let address: String
        public let name: String
    }

    /// The waiting phones in a GET /api/pin answer; nil when the answer has no list (an older Sunshine).
    public static func waiting(in answer: Any?) -> [Waiting]? {
        guard let object = answer as? [String: Any], let list = object["pairings"] else { return nil }
        guard let items = list as? [[String: Any]] else { return [] }
        return items.compactMap { item in
            guard let id = item["id"] as? String, !id.isEmpty else { return nil }
            return Waiting(id: id, address: item["address"] as? String ?? "", name: item["name"] as? String ?? "")
        }
    }

    /// The one to give the PIN to: the latest from the asking phone's address, else the only one waiting.
    public static func pick(_ waiting: [Waiting], phoneAddress: String?) -> String? {
        if let address = phoneAddress, let mine = waiting.last(where: { $0.address == address }) { return mine.id }
        return waiting.count == 1 ? waiting[0].id : nil
    }

    public static func body(pairingId: String?, pin: String, name: String) -> [String: Any] {
        var body: [String: Any] = ["pin": pin, "name": name]
        if let id = pairingId { body["pairing_id"] = id }
        return body
    }

    public static func accepted(_ answer: Any?) -> Bool {
        guard let object = answer as? [String: Any] else { return false }
        if let b = object["status"] as? Bool { return b }
        return (object["status"] as? String) == "true"
    }
}

/// Sunshine's paired phones (GET /api/clients/list: {"named_certs":[{"name","uuid","enabled"}],"status":true}).
/// Sunshine 2026.914 adds a new record each time a phone pairs, even with a certificate it already has, and lets a
/// certificate in only when exactly one record matches it (nvhttp.cpp is_client_enabled): a phone paired again
/// was refused with "Client certificate identity is not enabled" (seen on the Air, 22:56, four records of one phone).
/// So before a phone pairs again, its earlier records go: those with the name the helper gives it.
public enum SunshineClients {
    public static func uuids(named name: String, in answer: Any?) -> [String] {
        guard let object = answer as? [String: Any], let list = object["named_certs"] as? [[String: Any]] else { return [] }
        return list.compactMap { c in
            guard c["name"] as? String == name, let uuid = c["uuid"] as? String, !uuid.isEmpty else { return nil }
            return uuid
        }
    }
}

/// Sunshine's state file (~/.config/sunshine/sunshine_state.json): its web login sits at the top
/// ("username", "salt", "password", seen on macOS).
public enum SunshineState {
    public static func hasLogin(_ data: Data?) -> Bool {
        guard let data, let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return false }
        return !((object["username"] as? String) ?? "").isEmpty
    }
}

/// Sunshine's Mac installer in a GitHub release answer: Sunshine-macOS-<arch>.dmg.
public enum SunshineRelease {
    public static let latest = URL(string: "https://api.github.com/repositories/438803890/releases/latest")!  // by id: follows moves
    /// LizardByte LLC's Developer ID team, which signed and notarised Sunshine.app (seen in 2026.914).
    public static let team = "F8XQ7XCN2R"

    public static func dmgName(arch: String) -> String { "Sunshine-macOS-\(arch).dmg" }

    public static func dmgURL(in release: Data, arch: String) -> (url: URL, version: String)? {
        guard let object = (try? JSONSerialization.jsonObject(with: release)) as? [String: Any],
              let assets = object["assets"] as? [[String: Any]] else { return nil }
        let want = dmgName(arch: arch)
        guard let asset = assets.first(where: { $0["name"] as? String == want }),
              let text = asset["browser_download_url"] as? String, let url = URL(string: text), url.scheme == "https" else { return nil }
        return (url, object["tag_name"] as? String ?? "?")
    }
}

/// The phones allowed to use this Mac, in the same file format as the Linux helper's (~/.config/pocketxr/pc-helper.json):
/// {"phones":[{"name","token"}]}.
public final class PhoneBook {
    public let url: URL
    private let lock = NSLock()

    public init(url: URL) { self.url = url }

    public func phones() -> [[String: String]] {
        lock.lock(); defer { lock.unlock() }
        return read()
    }

    public func knows(_ token: String) -> Bool {
        !token.isEmpty && phones().contains { $0["token"] == token }
    }

    public func add(_ name: String, token: String = PhoneBook.newToken()) -> String {
        lock.lock(); defer { lock.unlock() }
        var list = read()
        list.append(["name": name, "token": token])
        write(list)
        return token
    }

    public func forgetAll() {
        lock.lock(); defer { lock.unlock() }
        write([])
    }

    /// Takes the phones of an older helper's file when this one has none yet. True when it took some.
    @discardableResult
    public func importPhones(from other: URL) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard read().isEmpty, let data = try? Data(contentsOf: other) else { return false }
        let theirs = PhoneBook.parse(data)
        guard !theirs.isEmpty else { return false }
        write(theirs)
        return true
    }

    public static func newToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        for i in bytes.indices { bytes[i] = UInt8.random(in: 0...255) }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func parse(_ data: Data) -> [[String: String]] {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = object["phones"] as? [[String: Any]] else { return [] }
        return list.compactMap { p in
            guard let token = p["token"] as? String, !token.isEmpty else { return nil }
            return ["name": p["name"] as? String ?? "A phone", "token": token]
        }
    }

    private func read() -> [[String: String]] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return PhoneBook.parse(data)
    }

    private func write(_ list: [[String: String]]) {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let data = try? JSONSerialization.data(withJSONObject: ["phones": list], options: [.sortedKeys]) else { return }
        FileManager.default.createFile(atPath: url.path, contents: data, attributes: [.posixPermissions: 0o600])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}
