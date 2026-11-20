import Foundation

/// The Pocket XR PC helper's words, as the Windows helper (../../PocketXrPcHelper.cs) and the Linux one
/// (../../unix/pocketxr_pc_helper.py) speak them, so the phone needs nothing new:
///
///   UDP 47610  "POCKETXR-FIND 1"  ->  "POCKETXR-HERE 1 47610 <computer name>"
///   TCP 47610  one JSON line in, one out:
///     {"op":"pair","phone"}                        the Mac's user is asked; {"ok":true,"token","pc"} or {"ok":false,"why"}
///     {"op":"apollo-pin","token","pin","name","port"}  the PIN goes into Sunshine for the phone; {"ok":true} or why not
///     {"op":"screens-setup","token","phone"}       the setup and the permission guide open on the Mac; {"ok":true}
///     {"op":"screen-prepare","token","port","width","height","fps"}  just before streaming the Sunshine on [port]:
///                                                  its virtual screen ready at that size; {"ok":true,"screen":N}
///     {"op":"status"|"start","token"}              PC VR is Windows-only: {"ok":true,"pc","steam":false,...,
///                                                  "os":"mac","virtual":<the virtual screens are up>}
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
    /// Its virtual screens are up: each PC screen gets its own display at the window's shape (the phone then asks for
    /// the window's size, as with Apollo's VirtualDisplayCapable).
    var virtualScreensReady: Bool { get }
    /// The secret this Mac's own Sunshines send with screen-on and screen-off (a file only this user can read).
    var screenSecret: String { get }
    /// Virtual screen [index] at [mode] (on) or gone (off), for a Sunshine's stream. Nil when done, else why not.
    func screen(on: Bool, index: Int, mode: Screens.Mode) -> String?
    /// The phone is about to stream screen [index] at [mode]: it is made ready now, because Sunshine looks at its
    /// display (an encoder test) before its prep command runs. Nil when ready, else why not.
    func prepareScreen(index: Int, mode: Screens.Mode, token: String) -> String?
    /// A phone asked how this Mac is: a stream may follow, from a phone that does not say which screen (one that has
    /// sent screen-prepare is left to do so).
    func expectStream(token: String)
}

public enum Requests {
    public static func answer(_ request: [String: Any], from address: String?, host: HelperHost) -> [String: Any] {
        let op = request["op"] as? String ?? ""
        if op == "screen-on" || op == "screen-off" {
            // From this Mac's own Sunshines only (their prep commands): its own address and the secret.
            guard address == "127.0.0.1", let secret = request["secret"] as? String, !secret.isEmpty, secret == host.screenSecret else {
                return ["ok": false, "why": "not allowed"]
            }
            guard let index = request["screen"] as? Int, (1...Screens.count).contains(index) else { return ["ok": false, "why": "no such screen"] }
            let mode = Screens.mode(width: "\(request["width"] ?? "")", height: "\(request["height"] ?? "")", fps: "\(request["fps"] ?? "")")
            if let why = host.screen(on: op == "screen-on", index: index, mode: mode) { return ["ok": false, "why": why] }
            return ["ok": true]
        }
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
        case "screen-prepare":
            guard let index = Screens.index(ofPort: Wire.port(request["port"])) else { return ["ok": false, "why": "no such screen"] }
            let mode = Screens.mode(width: "\(request["width"] ?? "")", height: "\(request["height"] ?? "")", fps: "\(request["fps"] ?? "")")
            if let why = host.prepareScreen(index: index, mode: mode, token: token) { return ["ok": false, "why": why] }
            return ["ok": true, "screen": index]
        case "status", "start":
            host.expectStream(token: token)
            return ["ok": true, "pc": host.computerName, "steam": false, "alvr": false, "steamvr": "off",
                    "os": "mac", "virtual": host.virtualScreensReady]
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
