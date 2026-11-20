import Foundation
import HelperCore

/// The helper's ears on the local network: UDP 47610 for "is a helper here?", TCP 47610 for one request per
/// connection. Each runs on its own thread; a port another program holds is tried again every 5 s.
final class Server {
    private let host: HelperHost

    init(host: HelperHost) { self.host = host }

    func start() {
        Thread.detachNewThread { [self] in serveUdp() }
        Thread.detachNewThread { [self] in serveTcp() }
    }

    private func bound(_ type: Int32) -> Int32 {
        while true {
            let fd = socket(AF_INET, type, type == SOCK_DGRAM ? IPPROTO_UDP : IPPROTO_TCP)
            var yes: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
            var at = sockaddr_in()
            at.sin_family = sa_family_t(AF_INET)
            at.sin_port = Wire.port.bigEndian
            at.sin_addr.s_addr = INADDR_ANY
            let ok = withUnsafePointer(to: &at) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            } == 0
            if ok, type == SOCK_DGRAM || listen(fd, 8) == 0 { return fd }
            Log.say("\(type == SOCK_DGRAM ? "UDP" : "TCP") \(Wire.port) is busy (\(String(cString: strerror(errno)))): trying again in 5 s")
            close(fd)
            Thread.sleep(forTimeInterval: 5)
        }
    }

    private func serveUdp() {
        let fd = bound(SOCK_DGRAM)
        Log.say("listening on UDP \(Wire.port)")
        var buf = [UInt8](repeating: 0, count: 256)
        while true {
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buf, buf.count, 0, $0, &len) }
            }
            guard n > 0, Wire.isFind(Array(buf[0..<n])) else { continue }
            let reply = Array(Wire.here(name: host.computerName).utf8)
            _ = withUnsafePointer(to: &from) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, reply, reply.count, 0, $0, len) }
            }
        }
    }

    private func serveTcp() {
        let fd = bound(SOCK_STREAM)
        Log.say("listening on TCP \(Wire.port)")
        while true {
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let conn = withUnsafeMutablePointer(to: &from) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { accept(fd, $0, &len) }
            }
            guard conn >= 0 else { continue }
            var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &from.sin_addr, &text, socklen_t(INET_ADDRSTRLEN))
            let address = String(cString: text)
            Thread.detachNewThread { [self] in client(conn, address) }
        }
    }

    private func client(_ fd: Int32, _ address: String) {
        defer { close(fd) }
        var limit = timeval(tv_sec: 130, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
        var noSigPipe: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, socklen_t(MemoryLayout<Int32>.size))
        var got = [UInt8]()
        var buf = [UInt8](repeating: 0, count: 4096)
        while !got.contains(10), got.count < 65536 {
            let n = recv(fd, &buf, buf.count, 0)
            if n <= 0 { break }
            got.append(contentsOf: buf[0..<n])
        }
        let line = String(decoding: got, as: UTF8.self).split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
        let request = Wire.parse(line) ?? [:]
        let op = request["op"] as? String ?? "?"
        let answer = Requests.answer(request, from: address, host: host)
        Log.say("\(address) \(op): \(answer["ok"] as? Bool == true ? "ok" : (answer["why"] as? String ?? "no"))")
        let out = Array((Wire.encode(answer) + "\n").utf8)
        _ = send(fd, out, out.count, 0)
    }
}
