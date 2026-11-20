import Foundation

/// Virtual screens: one Sunshine per PC screen (ports as the Windows setup's Apollos), each streaming its own virtual
/// display at the phone window's shape. Found on the Air (Sunshine 2026.914, macOS 14):
/// - Sunshine captures the display whose id is its `output_name`, looked up again at each stream (video.cpp
///   refresh_displays), and runs `global_prep_cmd` (waiting for it) before it, with SUNSHINE_CLIENT_WIDTH, _HEIGHT,
///   _FPS set (process.cpp); its undo command runs when the stream's app ends.
/// - Its mouse (libvirtualhid macos_backend.cpp) goes to the display that was the main display when Sunshine started
///   (CGMainDisplayID, read once), clamped to that display's bounds, read at each event. So each Sunshine is started
///   while its own virtual display is the main display, and the Mac's own screen is made main again after.
/// - A virtual display made again with the same vendor, product and serial gets the same id, at any size.
public enum Screens {
    public static let ports = [47989, 48989, 49989]
    public static let count = ports.count

    public static func index(ofPort port: Int) -> Int? { ports.firstIndex(of: port).map { $0 + 1 } }

    public struct Mode: Equatable, CustomStringConvertible {
        public let width: Int
        public let height: Int
        public let fps: Int
        public init(width: Int, height: Int, fps: Int) { self.width = width; self.height = height; self.fps = fps }
        public var description: String { "\(width)x\(height)@\(fps)" }
        public static let start = Mode(width: 1920, height: 1080, fps: 60)
    }

    /// The phone's window as Sunshine passes it (texts from the environment): each side 360..3840 (either way up),
    /// even, at 30..120 Hz; what is missing is 1920x1080 at 60.
    public static func mode(width: String?, height: String?, fps: String?) -> Mode {
        func number(_ s: String?, _ fallback: Int, _ low: Int, _ high: Int) -> Int {
            guard let v = s.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), v > 0 else { return fallback }
            return min(max(v, low), high)
        }
        let w = number(width, 1920, 360, 3840) & ~1
        let h = number(height, 1080, 360, 3840) & ~1
        return Mode(width: max(w, 360), height: max(h, 360), fps: number(fps, 60, 30, 120))
    }

    /// A Sunshine config file with [values] set (lines with those keys replaced, others kept as they are) and [remove]
    /// keys taken out.
    public static func conf(_ existing: String, set values: [(String, String)], remove: [String] = []) -> String {
        let keys = Set(values.map(\.0) + remove)
        var lines = existing.split(separator: "\n", omittingEmptySubsequences: false).map(String.init).filter { line in
            let key = line.split(separator: "=", maxSplits: 1).first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
            return !keys.contains(key)
        }
        while let last = lines.last, last.trimmingCharacters(in: .whitespaces).isEmpty { lines.removeLast() }
        lines += values.map { "\($0.0) = \($0.1)" }
        return lines.joined(separator: "\n") + "\n"
    }

    /// A config value, or nil.
    public static func value(_ key: String, in conf: String) -> String? {
        for line in conf.split(separator: "\n") {
            let parts = line.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count == 2, parts[0] == key { return parts[1] }
        }
        return nil
    }

    /// Sunshine's global_prep_cmd: [script] on/off [index] around every stream.
    public static func prepCmd(script: String, index: Int) -> String {
        let cmd: [[String: Any]] = [["do": "\(script) on \(index)", "undo": "\(script) off \(index)", "elevated": false]]
        let data = (try? JSONSerialization.data(withJSONObject: cmd, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    public struct Placed: Equatable {
        public let id: UInt32
        public let x: Int32
        public let y: Int32
        public init(id: UInt32, x: Int32, y: Int32) { self.id = id; self.x = x; self.y = y }
    }

    /// Where the displays go: the Mac's own as they were ([saved] origins, the main one at 0,0), then [ours] (virtual,
    /// with widths) in a row to the right of them all, at the top.
    public static func layout(saved: [(id: UInt32, x: Int32, y: Int32, width: Int32)], ours: [(id: UInt32, width: Int32)]) -> [Placed] {
        var placed = saved.map { Placed(id: $0.id, x: $0.x, y: $0.y) }
        var x = saved.map { $0.x + $0.width }.max() ?? 0
        for v in ours {
            placed.append(Placed(id: v.id, x: x, y: 0))
            x += v.width
        }
        return placed
    }

    /// The same, with [first] (one of ours) at 0,0 so it is the main display, and everything else to its right.
    public static func mainFirst(_ first: (id: UInt32, width: Int32), others: [(id: UInt32, width: Int32)]) -> [Placed] {
        var placed = [Placed(id: first.id, x: 0, y: 0)]
        var x = first.width
        for o in others where o.id != first.id {
            placed.append(Placed(id: o.id, x: x, y: 0))
            x += o.width
        }
        return placed
    }
}
