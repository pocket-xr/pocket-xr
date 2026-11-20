import Foundation

/// The Pocket XR PC helper's words, as the Windows helper (../../PocketXrPcHelper.cs) and the Linux one
/// (../../unix/pocketxr_pc_helper.py) speak them, so the phone needs nothing new:
///
///   UDP 47610  "POCKETXR-FIND 1"  ->  "POCKETXR-HERE 1 47610 <computer name>"
///   TCP 47610  one JSON line in, one out:
///     {"op":"pair","phone"}                        the Mac's user is asked; {"ok":true,"token","pc"} or {"ok":false,"why"}
///     {"op":"apollo-pin","token","pin","name","port"}  the PIN goes into Sunshine for the phone; {"ok":true} or why not
///     {"op":"screens-setup","token","phone"}       the setup and the permission guide open on the Mac; {"ok":true}
///     {"op":"status"|"start","token"}              PC VR is Windows-only: {"ok":true,"pc","steam":false,...}
public enum Wire {
    public static let port: UInt16 = 47610
    public static let defaultStreamPort = 47989

    public static func isFind(_ packet: [UInt8]) -> Bool { packet.starts(with: Array("POCKETXR-FIND".utf8)) }

    public static func here(name: String) -> String { "POCKETXR-HERE 1 \(port) \(oneLine(name))" }

    /// A request line as a JSON object; nil when it is not one.
    public static func parse(_ line: String) -> [String: Any]? {
        guard let data = line.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8), !data.isEmpty else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /// An answer as one JSON line (keys sorted, so the same answer is always the same text).
    public static func encode(_ answer: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(answer),
              let data = try? JSONSerialization.data(withJSONObject: answer, options: [.sortedKeys]) else { return #"{"ok":false}"# }
        return String(decoding: data, as: UTF8.self)
    }

    static func oneLine(_ s: String) -> String { s.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ") }

    /// A port in a request: a number, or a number in text; else the usual one.
    public static func port(_ value: Any?) -> Int {
        if let n = value as? Int, n > 0, n < 65535 { return n }
        if let s = value as? String, let n = Int(s), n > 0, n < 65535 { return n }
        return defaultStreamPort
    }
}

/// What answering needs from the Mac: the dialogs, the phone list and Sunshine.
public protocol HelperHost: AnyObject {
    var computerName: String { get }
    /// Asks this Mac's user; true when they click Allow.
    func allow(phone: String) -> Bool
    func knows(token: String) -> Bool
    /// Remembers an allowed phone; its new token.
    func addPhone(_ name: String) -> String
    /// Types [pin] into Sunshine (web port = [port] + 1) for the phone at [phoneAddress]. Nil when it went in, else why not.
    func typePin(_ pin: String, name: String, port: Int, phoneAddress: String?) -> String?
    /// Shows the setup and the permission guide on the Mac.
    func showSetup(for phone: String)
}

public enum Requests {
    public static func answer(_ request: [String: Any], from address: String?, host: HelperHost) -> [String: Any] {
        let op = request["op"] as? String ?? ""
        if op == "pair" {
            let phone = nonEmpty(request["phone"]) ?? "A phone"
            guard host.allow(phone: phone) else { return ["ok": false, "why": "not allowed on the PC"] }
            return ["ok": true, "token": host.addPhone(phone), "pc": host.computerName]
        }
        guard let token = request["token"] as? String, host.knows(token: token) else { return ["ok": false, "why": "not paired"] }
        switch op {
        case "apollo-pin":
            guard let pin = nonEmpty(request["pin"]) else { return ["ok": false, "why": "no PIN"] }
            if let why = host.typePin(pin, name: nonEmpty(request["name"]) ?? "Pocket XR", port: Wire.port(request["port"]), phoneAddress: address) {
                return ["ok": false, "why": why]
            }
            return ["ok": true]
        case "screens-setup":
            host.showSetup(for: nonEmpty(request["phone"]) ?? "A phone")
            return ["ok": true]
        case "status", "start":
            return ["ok": true, "pc": host.computerName, "steam": false, "alvr": false, "steamvr": "off"]
        default:
            return ["ok": false, "why": "unknown request"]
        }
    }

    static func nonEmpty(_ value: Any?) -> String? {
        guard let s = value as? String else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
