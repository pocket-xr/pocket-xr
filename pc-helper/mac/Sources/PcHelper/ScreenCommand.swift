import Foundation
import HelperCore

/// The command a Sunshine runs before and after each stream (its global_prep_cmd): one request to the running helper
/// on this Mac (127.0.0.1, with the secret only this user can read), waiting for the screen to be there. Exit code 0
/// when it is; else Sunshine does not start the stream (better than streaming the wrong screen).
enum ScreenCommand {
    static func run(on: Bool, index: Int) -> Int32 {
        let env = ProcessInfo.processInfo.environment
        guard let secret = try? String(contentsOf: Home.path(".config/pocketxr/screens.secret"), encoding: .utf8) else {
            print("Pocket XR PC helper: no secret (is it running?)")
            return 1
        }
        var request: [String: Any] = ["op": on ? "screen-on" : "screen-off", "secret": secret.trimmingCharacters(in: .whitespacesAndNewlines), "screen": index]
        if on {
            let mode = Screens.mode(width: env["SUNSHINE_CLIENT_WIDTH"], height: env["SUNSHINE_CLIENT_HEIGHT"], fps: env["SUNSHINE_CLIENT_FPS"])
            request["width"] = mode.width
            request["height"] = mode.height
            request["fps"] = mode.fps
        }
        let answer = ask(Wire.encode(request))
        print("Pocket XR PC helper: screen \(index) \(on ? "on" : "off"): \(answer ?? "no answer")")
        guard let answer, let object = Wire.parse(answer) else { return 1 }
        return object["ok"] as? Bool == true ? 0 : 1
    }

    private static func ask(_ line: String) -> String? {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var limit = timeval(tv_sec: 20, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
        var to = sockaddr_in()
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = Wire.port.bigEndian
        to.sin_addr.s_addr = inet_addr("127.0.0.1")
        let rc = withUnsafePointer(to: &to) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard rc == 0 else { return nil }
        let out = Array((line + "\n").utf8)
        guard send(fd, out, out.count, 0) == out.count else { return nil }
        var got = [UInt8]()
        var buf = [UInt8](repeating: 0, count: 1024)
        while !got.contains(10) {
            let n = recv(fd, &buf, buf.count, 0)
            if n <= 0 { break }
            got.append(contentsOf: buf[0..<n])
        }
        return String(decoding: got, as: UTF8.self).split(whereSeparator: \.isNewline).first.map(String.init)
    }
}
