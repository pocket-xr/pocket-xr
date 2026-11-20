import Foundation
import Security
import HelperCore

/// Sunshine (free, open source) streams this Mac's screen to the phone. Installed from its own Mac installer into
/// ~/Applications (no admin password), started by a LaunchAgent so macOS's Screen Recording and Accessibility switches
/// belong to Sunshine itself, not to this helper. Every call blocks: run them off the main thread.
enum Sunshine {
    static let label = "com.hayeco.pocketxr.sunshine"  // the same as pocketxr-pc-screens.sh's, so the two never double up
    static var agentURL: URL { Home.path("Library/LaunchAgents/\(label).plist") }
    static var stateURL: URL { Home.path(".config/sunshine/sunshine_state.json") }
    static var domain: String { "gui/\(getuid())" }

    // MARK: Finding and installing

    static let appCandidates = [Home.path("Applications/Sunshine.app"), URL(fileURLWithPath: "/Applications/Sunshine.app")]

    /// The Sunshine program to run: Sunshine.app (the Mac installer), else one Homebrew installed.
    static func binary() -> String? {
        for app in appCandidates {
            let bin = app.appendingPathComponent("Contents/MacOS/Sunshine").path
            if FileManager.default.isExecutableFile(atPath: bin) { return bin }
        }
        for bin in ["/opt/homebrew/bin/sunshine", "/usr/local/bin/sunshine"] where FileManager.default.isExecutableFile(atPath: bin) {
            return bin
        }
        return nil
    }

    /// Sunshine.app, for showing it in Finder (it can be dragged into the privacy lists).
    static func app() -> URL? { appCandidates.first { FileManager.default.fileExists(atPath: $0.path) } }

    enum InstallError: Error, CustomStringConvertible {
        case noRelease, download, mount, notThere, notLizardByte, notAccepted, copy
        var description: String {
            switch self {
            case .noRelease: return "Sunshine's Mac installer was not found on GitHub. Check the internet connection."
            case .download: return "Sunshine's installer did not download. Check the internet connection."
            case .mount: return "Sunshine's installer could not be opened."
            case .notThere: return "Sunshine's installer has no Sunshine.app in it."
            case .notLizardByte: return "This Sunshine download is not signed by LizardByte, so it was not installed."
            case .notAccepted: return "macOS did not accept this Sunshine download, so it was not installed."
            case .copy: return "Sunshine could not be copied into your Applications folder."
            }
        }
    }

    static var arch: String {
        #if arch(arm64)
        return "arm64"
        #else
        return "x86_64"
        #endif
    }

    /// Sunshine's latest Mac installer for this Mac's chip, checked (LizardByte's signature, and Gatekeeper), copied
    /// to ~/Applications/Sunshine.app.
    static func install(say: (String) -> Void) throws {
        say("Finding Sunshine's latest version…")
        var ask = URLRequest(url: SunshineRelease.latest)
        ask.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        guard let release = fetch(ask), let found = SunshineRelease.dmgURL(in: release, arch: arch) else { throw InstallError.noRelease }
        Log.say("Sunshine \(found.version): \(found.url.absoluteString)")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pocketxr-sunshine-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        say("Downloading Sunshine \(found.version)…")
        let dmg = dir.appendingPathComponent("Sunshine.dmg")
        guard let data = fetch(URLRequest(url: found.url), timeout: 600), data.count > 1_000_000, (try? data.write(to: dmg)) != nil else {
            throw InstallError.download
        }
        say("Installing Sunshine…")
        let mount = dir.appendingPathComponent("mnt")
        try? FileManager.default.createDirectory(at: mount, withIntermediateDirectories: true)
        let attach = Shell.run("/usr/bin/hdiutil", ["attach", "-nobrowse", "-readonly", "-mountpoint", mount.path, dmg.path])
        guard attach.code == 0 else { Log.say("hdiutil: \(attach.output)"); throw InstallError.mount }
        defer { Shell.run("/usr/bin/hdiutil", ["detach", mount.path, "-force"]) }
        let source = mount.appendingPathComponent("Sunshine.app")
        guard FileManager.default.fileExists(atPath: source.path) else { throw InstallError.notThere }
        guard signedByLizardByte(source) else { throw InstallError.notLizardByte }
        let gatekeeper = Shell.run("/usr/sbin/spctl", ["--assess", "--type", "execute", source.path])
        guard gatekeeper.code == 0 else { Log.say("spctl: \(gatekeeper.output)"); throw InstallError.notAccepted }
        let target = appCandidates[0]
        try? FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: target)
        guard Shell.run("/usr/bin/ditto", [source.path, target.path]).code == 0 else { throw InstallError.copy }
        Log.say("Sunshine \(found.version) installed in \(target.path)")
    }

    static func signedByLizardByte(_ app: URL) -> Bool {
        var code: SecStaticCode?
        var requirement: SecRequirement?
        guard SecStaticCodeCreateWithPath(app as CFURL, [], &code) == errSecSuccess, let code,
              SecRequirementCreateWithString("anchor apple generic and certificate leaf[subject.OU] = \"\(SunshineRelease.team)\"" as CFString, [], &requirement) == errSecSuccess
        else { return false }
        let status = SecStaticCodeCheckValidity(code, [], requirement)
        if status != errSecSuccess { Log.say("Sunshine signature: \(status)") }
        return status == errSecSuccess
    }

    // MARK: Its web login

    static func hasLogin() -> Bool { SunshineState.hasLogin(try? Data(contentsOf: stateURL)) }

    /// Gives Sunshine a new web login. Sunshine is stopped first: a `--creds` run while a just-started Sunshine sets
    /// itself up was lost (seen with pocketxr-pc-screens.sh on macOS 14), and Sunshine reads its login when it starts.
    /// The caller starts Sunshine again afterwards.
    static func makeLogin(_ login: Keychain.Login, binary: String) -> Bool {
        if isLoaded() { stop() }
        let r = Shell.run(binary, ["--creds", login.user, login.password])
        let ok = hasLogin()
        Log.say("Sunshine --creds: exit \(r.code), login saved: \(ok)")
        return ok
    }

    // MARK: Running it

    /// Sunshine's LaunchAgent: started now, and at sign-in while [atLogin] is on.
    static func writeAgent(binary: String) {
        let plist: [String: Any] = [
            "Label": label,
            "ProgramArguments": [binary],
            "RunAtLoad": true,
            "KeepAlive": true,
            "ProcessType": "Interactive",
        ]
        guard let data = try? PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0) else { return }
        try? FileManager.default.createDirectory(at: agentURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let old = try? Data(contentsOf: agentURL)
        if old != data {
            try? data.write(to: agentURL)
            if isLoaded() { Shell.run("/bin/launchctl", ["bootout", "\(domain)/\(label)"]) }
        }
    }

    static func isLoaded() -> Bool { Shell.run("/bin/launchctl", ["print", "\(domain)/\(label)"]).code == 0 }

    /// Starts Sunshine if it is not running. With [atLogin] off it still runs now, but not at the next sign-in.
    static func start(atLogin: Bool) {
        Shell.run("/bin/launchctl", ["enable", "\(domain)/\(label)"])
        if !isLoaded() {
            let r = Shell.run("/bin/launchctl", ["bootstrap", domain, agentURL.path])
            if r.code != 0 { Log.say("launchctl bootstrap: \(r.code) \(r.output)") }
        }
        if !atLogin { Shell.run("/bin/launchctl", ["disable", "\(domain)/\(label)"]) }
    }

    static func restart() {
        let r = Shell.run("/bin/launchctl", ["kickstart", "-k", "\(domain)/\(label)"])
        Log.say("Sunshine restarted: \(r.code == 0 ? "yes" : r.output)")
    }

    static func stop() {
        Shell.run("/bin/launchctl", ["bootout", "\(domain)/\(label)"])
        for _ in 0..<20 where isLoaded() { Thread.sleep(forTimeInterval: 0.25) }
    }

    /// Sunshine's web page answers (port 47990).
    static func isAnswering() -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var to = sockaddr_in()
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = UInt16(47990).bigEndian
        to.sin_addr.s_addr = inet_addr("127.0.0.1")
        return withUnsafePointer(to: &to) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        } == 0
    }

    // MARK: Its web API

    /// One call to Sunshine's web API on this Mac (its own self-signed certificate, trusted for localhost only).
    static func api(_ method: String, _ path: String, body: [String: Any]? = nil, webPort: Int = 47990, login: Keychain.Login) -> (code: Int, json: Any?) {
        var req = URLRequest(url: URL(string: "https://localhost:\(webPort)\(path)")!, timeoutInterval: 5)
        req.httpMethod = method
        let auth = Data("\(login.user):\(login.password)".utf8).base64EncodedString()
        req.setValue("Basic \(auth)", forHTTPHeaderField: "Authorization")
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        var result: (Int, Any?) = (0, nil)
        let done = DispatchSemaphore(value: 0)
        localSession.dataTask(with: req) { data, response, _ in
            let json = data.flatMap { try? JSONSerialization.jsonObject(with: $0) }
            result = ((response as? HTTPURLResponse)?.statusCode ?? 0, json)
            done.signal()
        }.resume()
        done.wait()
        return result
    }

    /// The saved login works.
    static func loginWorks(_ login: Keychain.Login) -> Bool { api("GET", "/api/apps", login: login).code == 200 }

    /// Types [pin] for the phone at [phoneAddress]: waits up to about 40 s for its pairing to show up (the phone
    /// starts it a few seconds after its user taps Allow; the phone waits 50 s for this answer). Nil when it went in.
    static func typePin(_ pin: String, name: String, port: Int, phoneAddress: String?, login: Keychain.Login) -> String? {
        var last = ""
        var cleared = false
        for _ in 0..<40 {
            let listed = api("GET", "/api/pin", webPort: port + 1, login: login)
            var pairingId: String?
            if listed.code == 200, let waiting = SunshinePairing.waiting(in: listed.json) {
                guard let id = SunshinePairing.pick(waiting, phoneAddress: phoneAddress) else {
                    last = "no phone waiting to pair yet (\(waiting.count) waiting)"
                    Thread.sleep(forTimeInterval: 1)
                    continue
                }
                pairingId = id
            } else if listed.code == 401 {
                return "Sunshine did not accept the saved login"
            }
            if !cleared {
                // The phone is waiting to pair: its earlier pairings go first (see SunshineClients).
                cleared = true
                let clients = api("GET", "/api/clients/list", webPort: port + 1, login: login)
                for uuid in SunshineClients.uuids(named: name, in: clients.json) {
                    let r = api("POST", "/api/clients/unpair", body: ["uuid": uuid], webPort: port + 1, login: login)
                    Log.say("earlier pairing of \(name) (\(uuid)) removed: \(SunshinePairing.accepted(r.json))")
                }
            }
            let answer = api("POST", "/api/pin", body: SunshinePairing.body(pairingId: pairingId, pin: pin, name: name), webPort: port + 1, login: login)
            if SunshinePairing.accepted(answer.json) {
                Log.say("PIN typed for \(name) (\(phoneAddress ?? "?"))")
                return nil
            }
            last = "HTTP \(answer.code) \(((answer.json as? [String: Any])?["error"] as? String) ?? "")"
            Thread.sleep(forTimeInterval: 1)
        }
        Log.say("pin: \(last)")
        return "Sunshine did not take the PIN (\(last))"
    }

    private final class LocalTrust: NSObject, URLSessionDelegate {
        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            let space = challenge.protectionSpace
            if space.authenticationMethod == NSURLAuthenticationMethodServerTrust, space.host == "localhost", let trust = space.serverTrust {
                completionHandler(.useCredential, URLCredential(trust: trust))
            } else {
                completionHandler(.performDefaultHandling, nil)
            }
        }
    }

    private static let localSession = URLSession(configuration: .ephemeral, delegate: LocalTrust(), delegateQueue: nil)

    /// A GET over the internet (GitHub), waiting for it.
    static func fetch(_ request: URLRequest, timeout: TimeInterval = 30) -> Data? {
        var req = request
        req.timeoutInterval = timeout
        req.setValue("PocketXR-PC-helper", forHTTPHeaderField: "User-Agent")
        var out: Data?
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: req) { data, response, error in
            if let error { Log.say("fetch \(req.url?.absoluteString ?? ""): \(error.localizedDescription)") }
            if (response as? HTTPURLResponse)?.statusCode == 200 { out = data }
            done.signal()
        }.resume()
        done.wait()
        return out
    }
}
