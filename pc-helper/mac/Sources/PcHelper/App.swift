import AppKit
import ServiceManagement
import HelperCore

/// Pocket XR PC helper for Mac: a menu bar app that lets the phone stream this Mac's screen with Sunshine, with no PIN
/// to type. On first open it installs Sunshine, gives it a web login (kept in the keychain), starts it and itself at
/// sign-in, then guides the two switches only the user can turn on (Screen Recording and Accessibility).
final class App: NSObject, NSApplicationDelegate, NSMenuDelegate, HelperHost {
    private var statusItem: NSStatusItem!
    private let menu = NSMenu()
    private var server: Server!
    private let setupQueue = DispatchQueue(label: "setup")
    private var guide: GuideWindow?
    private var progress = "Starting…"
    private let pairLock = NSLock()
    private let defaults = UserDefaults.standard

    let phoneBook = PhoneBook(url: Home.path("Library/Application Support/Pocket XR PC helper/phones.json"))
    let computerName = computerShortName()

    // Older setups (pocketxr-pc-screens.sh, pocketxr_pc_helper.py).
    private let legacyPhones = Home.path(".config/pocketxr/pc-helper.json")
    private let legacyLogin = Home.path(".config/pocketxr/sunshine-login.json")
    private let legacyHelperLabel = "com.hayeco.pocketxr.pc-helper"

    private var permissionsDone: Bool {
        get { defaults.bool(forKey: "permissionsDone") }
        set { defaults.set(newValue, forKey: "permissionsDone") }
    }

    private var startAtLogin: Bool {
        get { defaults.object(forKey: "startAtLogin") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "startAtLogin") }
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        Log.say("started on \(computerName), version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") ?? "?")")
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        let image = NSImage(systemSymbolName: "rectangle.on.rectangle", accessibilityDescription: "Pocket XR PC helper")
        image?.isTemplate = true
        statusItem.button?.image = image
        menu.delegate = self
        statusItem.menu = menu
        if phoneBook.importPhones(from: legacyPhones) { Log.say("phones taken from \(legacyPhones.path)") }
        server = Server(host: self)
        server.start()
        if defaults.object(forKey: "startAtLogin") == nil { setStartAtLogin(true) }
        runSetup(showGuide: false)
    }

    // MARK: Setup

    private func say(_ text: String) {
        Log.say(text)
        DispatchQueue.main.async {
            self.progress = text
            self.guide?.show(progress: text)
        }
    }

    /// Everything the first open does; safe to run again (it keeps what is there).
    func runSetup(showGuide: Bool) {
        setupQueue.async { [self] in
            retireScriptHelper()
            if Sunshine.binary() == nil {
                if showGuide || !permissionsDone { DispatchQueue.main.sync { openGuide() } }
                do { try Sunshine.install(say: say) } catch {
                    say("Sunshine is not installed: \(error)")
                    DispatchQueue.main.async { self.alert("Sunshine could not be installed", "\(error)\n\nPocket XR PC helper tries again the next time it opens.") }
                    return
                }
            }
            guard let binary = Sunshine.binary() else { return say("Sunshine is not installed.") }
            say("Sunshine: \(binary)")
            Sunshine.writeAgent(binary: binary, keepRunning: !virtualScreens)
            ensureLogin(binary: binary)
            if virtualScreens {
                setUpScreens(binary: binary)
            } else {
                setUpOwnScreen(binary: binary)
            }
            for _ in 0..<40 where !Sunshine.isAnswering() { Thread.sleep(forTimeInterval: 0.5) }
            if let login = Keychain.read() {
                say(Sunshine.loginWorks(login) ? "Ready." : "Ready, but Sunshine did not accept the saved login: phones pair with a PIN.")
            } else {
                say("Ready, without Sunshine's web login: phones pair with a PIN typed in Sunshine's web page.")
            }
            if showGuide || !permissionsDone { DispatchQueue.main.async { self.openGuide() } }
        }
    }

    // MARK: Virtual screens

    private var virtualScreens: Bool {
        get { defaults.object(forKey: "virtualScreens") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "virtualScreens") }
    }

    private let screens = VirtualScreens()
    private var screensReady = false
    var virtualScreensReady: Bool { virtualScreens && screensReady }
    private var watchdog: Timer?
    private var mirrorCheck: Timer?
    let screenSecret = PhoneBook.newToken()
    private let secretURL = Home.path(".config/pocketxr/screens.secret")
    private let scriptURL = Home.path(".config/pocketxr/screen.sh")

    /// Sunshine's prep commands run this script, which runs this app as a command (main.swift: --screen on|off N):
    /// a path with no spaces, and the secret in a file only this user can read.
    private func writeScreenScript() {
        let dir = secretURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: secretURL.path, contents: Data(screenSecret.utf8), attributes: [.posixPermissions: 0o600])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: secretURL.path)
        let exe = Bundle.main.executablePath ?? ""
        let script = "#!/bin/sh\n# Pocket XR PC helper: a virtual screen for a Sunshine stream (its prep command).\nexec \"\(exe)\" --screen \"$@\"\n"
        FileManager.default.createFile(atPath: scriptURL.path, contents: Data(script.utf8), attributes: [.posixPermissions: 0o700])
        try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: scriptURL.path)
    }

    /// Three Sunshines, each started while its own virtual screen is the main display (so its mouse goes there), the
    /// Mac's own screen main again after. Their screens are made again, at the phone window's shape, for each stream.
    private func setUpScreens(binary: String) {
        screensReady = false
        writeScreenScript()
        let name = Host.current().localizedName ?? computerName
        // All three made first, so each has a display id of its own (see VirtualScreens).
        for i in Sunshine.Instance.all { _ = screens.make(i.index) }
        for i in Sunshine.Instance.all {
            try? FileManager.default.createDirectory(at: i.dir, withIntermediateDirectories: true)
            var values: [(String, String?)] = [("global_prep_cmd", Screens.prepCmd(script: scriptURL.path, index: i.index))]
            if i != .main {
                if !FileManager.default.fileExists(atPath: i.appsURL.path) {
                    try? FileManager.default.copyItem(at: Sunshine.Instance.main.appsURL, to: i.appsURL)
                }
                values += [("port", "\(i.port)"), ("sunshine_name", "\(name) screen \(i.index)"),
                           ("file_state", i.stateURL.path), ("credentials_file", i.stateURL.path),
                           ("file_apps", i.appsURL.path), ("log_path", i.logURL.path)]
            }
            Sunshine.setConf(i, values)
            Sunshine.writeAgent(binary: binary, i, keepRunning: false)
            if i != .main, let login = Keychain.read(), !Sunshine.hasLogin(i) { _ = Sunshine.makeLogin(login, binary: binary, i) }
            say("Starting PC screen \(i.index) (port \(i.port))…")
            startOnItsScreen(i)
        }
        screensReady = true
        say("Ready: 3 virtual screens.")
        DispatchQueue.main.async { self.startWatchdog() }
    }

    /// Sunshine [i] (re)started while its virtual screen is the main display; then the Mac's own screen is main again.
    private func startOnItsScreen(_ i: Sunshine.Instance) {
        if Sunshine.isLoaded(i) { Sunshine.stop(i) }
        guard let id = screens.make(i.index) else {
            return say("PC screen \(i.index): no virtual screen")
        }
        Sunshine.setConf(i, [("output_name", "\(id)")])
        _ = screens.makeMain(i.index)
        Sunshine.start(atLogin: true, i)
        for _ in 0..<60 where !Sunshine.isAnswering(i) { Thread.sleep(forTimeInterval: 0.5) }
        Thread.sleep(forTimeInterval: 1)
        screens.settle()
        Log.say("PC screen \(i.index): Sunshine on port \(i.port) \(Sunshine.isAnswering(i) ? "up" : "NOT up"), display \(id)")
    }

    /// One Sunshine on the Mac's own screen, as before virtual screens.
    private func setUpOwnScreen(binary: String) {
        watchdog?.invalidate()
        mirrorCheck?.invalidate()
        for i in Sunshine.Instance.all where i != .main { Sunshine.remove(i) }
        screens.removeAll()
        if Sunshine.setConf(.main, [("global_prep_cmd", nil), ("output_name", nil)]), Sunshine.isLoaded() { Sunshine.stop() }
        Sunshine.writeAgent(binary: binary, keepRunning: true)
        Sunshine.start(atLogin: startAtLogin)
    }

    /// A Sunshine that stopped is started again the same way (launchd would start it with the wrong main display).
    private func startWatchdog() {
        watchdog?.invalidate()
        mirrorCheck?.invalidate()
        // An idle screen that another display change brought out of mirroring goes back within 3 s.
        mirrorCheck = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            guard let self, self.virtualScreens, self.screensReady else { return }
            DispatchQueue.global().async { self.screens.checkIdle() }
        }
        watchdog = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            guard let self, self.virtualScreens, self.screensReady else { return }
            self.setupQueue.async {
                for i in Sunshine.Instance.all where !Sunshine.isRunning(i) {
                    Log.say("PC screen \(i.index): Sunshine stopped; starting it again")
                    self.startOnItsScreen(i)
                }
            }
        }
    }

    func screen(on: Bool, index: Int, mode: Screens.Mode) -> String? {
        guard virtualScreens else { return on ? "virtual screens are off" : nil }
        guard on else {
            // Kept (its id stays Sunshine's), mirrored until the next stream.
            screens.idle(index)
            return nil
        }
        guard let id = screens.stream(index, mode) else { return "the virtual screen could not be made" }
        let i = Sunshine.Instance(index: index)
        if Screens.value("output_name", in: Sunshine.conf(i)) != "\(id)" {
            Log.say("PC screen \(index): display id \(id) is not Sunshine's; starting it again")
            screens.idle(index)
            setupQueue.async { self.startOnItsScreen(i) }
            return "the virtual screen changed: try again"
        }
        return nil
    }

    /// Phones (tokens) that ready their screens themselves: their status asks bring nothing out. Kept on disk
    /// (one token a line, 0600), so the first status after the helper starts brings nothing out either.
    private let preparingFile = Home.path(".config/pocketxr/screen-prepare-phones")
    private lazy var preparingPhones: Set<String> = {
        let text = (try? String(contentsOf: preparingFile, encoding: .utf8)) ?? ""
        return Set(text.split(separator: "\n").map(String.init).filter { !$0.isEmpty })
    }()
    private let preparingLock = NSLock()

    func prepareScreen(index: Int, mode: Screens.Mode, token: String) -> String? {
        preparingLock.lock()
        if preparingPhones.insert(token).inserted {
            let text = preparingPhones.sorted().joined(separator: "\n") + "\n"
            try? text.write(to: preparingFile, atomically: true, encoding: .utf8)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: preparingFile.path)
        }
        preparingLock.unlock()
        guard virtualScreensReady else { return "virtual screens are not up" }
        guard let id = screens.prepare(index, mode) else { return "the virtual screen could not be made" }
        Log.say("PC screen \(index): ready for a stream at \(mode) (display \(id))")
        return nil
    }

    func expectStream(token: String) {
        preparingLock.lock(); let prepares = preparingPhones.contains(token); preparingLock.unlock()
        guard virtualScreensReady, !prepares else { return }
        screens.expectAny()
    }

    @objc private func toggleVirtualScreens() {
        virtualScreens.toggle()
        Log.say("virtual screens: \(virtualScreens)")
        runSetup(showGuide: false)
    }

    /// The Python helper of pocketxr-pc-screens.sh would hold the same ports: this app takes over from it.
    private func retireScriptHelper() {
        let agent = Home.path("Library/LaunchAgents/\(legacyHelperLabel).plist")
        guard FileManager.default.fileExists(atPath: agent.path) else { return }
        Shell.run("/bin/launchctl", ["bootout", "\(Sunshine.domain)/\(legacyHelperLabel)"])
        try? FileManager.default.removeItem(at: agent)
        Log.say("stopped the script's helper (\(legacyHelperLabel))")
    }

    /// Sunshine's web login in the keychain: taken from the script's file, made new while Sunshine has none, or
    /// asked for when Sunshine has one of its own.
    private func ensureLogin(binary: String) {
        if Keychain.read() != nil { return }
        if let data = try? Data(contentsOf: legacyLogin),
           let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: String],
           let user = object["username"], let password = object["password"],
           Keychain.save(.init(user: user, password: password)) {
            try? FileManager.default.removeItem(at: legacyLogin)
            say("Sunshine's web login moved into the keychain.")
            return
        }
        if !Sunshine.hasLogin() {
            makeLogin(binary: binary)
            return
        }
        // Sunshine has a login nobody told us: ask for it (Sunshine running, so it can be checked).
        Sunshine.start(atLogin: startAtLogin)
        for _ in 0..<40 where !Sunshine.isAnswering() { Thread.sleep(forTimeInterval: 0.5) }
        for _ in 0..<3 {
            var choice = LoginChoice.skip
            DispatchQueue.main.sync { choice = askLogin() }
            switch choice {
            case .skip:
                return say("Skipped Sunshine's web login: phones pair with a PIN.")
            case .makeNew:
                return makeLogin(binary: binary)
            case .typed(let login):
                if Sunshine.loginWorks(login), Keychain.save(login) { return say("Sunshine's web login saved in the keychain.") }
                DispatchQueue.main.sync { alert("That login did not work", "Check the user name and password of Sunshine's web page.") }
            }
        }
    }

    private func makeLogin(binary: String) {
        let login = Keychain.Login(user: "pocketxr", password: PhoneBook.newToken())
        if Sunshine.makeLogin(login, binary: binary), Keychain.save(login) {
            say("Made Sunshine's web login (user pocketxr; the password is in the keychain).")
        } else {
            say("Could not make Sunshine's web login: phones pair with a PIN.")
        }
    }

    private enum LoginChoice { case typed(Keychain.Login), makeNew, skip }

    private func askLogin() -> LoginChoice {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Sunshine already has a web login"
        a.informativeText = "Type it, so Pocket XR can pair phones for you with no PIN to type. Or make a new one: it replaces Sunshine's web login, and phones already paired stay paired."
        let user = NSTextField(frame: NSRect(x: 0, y: 30, width: 260, height: 24))
        user.placeholderString = "User name"
        let password = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24))
        password.placeholderString = "Password"
        let box = NSView(frame: NSRect(x: 0, y: 0, width: 260, height: 54))
        box.addSubview(user)
        box.addSubview(password)
        a.accessoryView = box
        a.window.initialFirstResponder = user
        a.addButton(withTitle: "Save")
        a.addButton(withTitle: "Make a new login")
        a.addButton(withTitle: "Skip")
        switch a.runModal() {
        case .alertFirstButtonReturn: return .typed(.init(user: user.stringValue, password: password.stringValue))
        case .alertSecondButtonReturn: return .makeNew
        default: return .skip
        }
    }

    // MARK: The permission guide

    func openGuide() {
        if guide == nil {
            guide = GuideWindow(
                openScreenRecording: { Self.openPrivacy("Privacy_ScreenCapture") },
                openAccessibility: { Self.openPrivacy("Privacy_Accessibility") },
                showSunshine: { if let app = Sunshine.app() { NSWorkspace.shared.activateFileViewerSelecting([app]) } },
                done: { [weak self] in self?.permissionsSet() })
        }
        guide?.show(progress: progress)
        NSApp.activate(ignoringOtherApps: true)
        guide?.window?.makeKeyAndOrderFront(nil)
    }

    static func openPrivacy(_ pane: String) {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(pane)") { NSWorkspace.shared.open(url) }
    }

    private func permissionsSet() {
        permissionsDone = true
        guide?.close()
        say("Restarting Sunshine, so the switches take effect…")
        setupQueue.async { [self] in
            if virtualScreens {
                for i in Sunshine.Instance.all { startOnItsScreen(i) }
            } else {
                Sunshine.restart()
            }
            for _ in 0..<40 where !Sunshine.isAnswering() { Thread.sleep(forTimeInterval: 0.5) }
            say(Sunshine.isAnswering() ? "Ready." : "Sunshine did not start again: see \(Log.url.path).")
        }
    }

    // MARK: The menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let running = Sunshine.isLoaded() && Sunshine.isAnswering()
        let sunshine = Sunshine.binary() == nil ? "not installed" : (running ? "running" : "not running")
        let phones = phoneBook.phones().count
        let screensLine = virtualScreens
            ? "PC screens: virtual, \(Sunshine.Instance.all.filter { Sunshine.isRunning($0) }.count) of \(Screens.count) ready"
            : "PC screens: this Mac's own screen"
        for line in ["Pocket XR PC helper on \(computerName)", "Sunshine: \(sunshine)", screensLine,
                     "Phones allowed: \(phones)", "Status: \(progress)"] {
            let item = NSMenuItem(title: line, action: nil, keyEquivalent: "")
            item.isEnabled = false
            menu.addItem(item)
        }
        menu.addItem(.separator())
        let virtual = add(menu, "Virtual screens", #selector(toggleVirtualScreens))
        virtual.state = virtualScreens ? .on : .off
        add(menu, "Set up permissions again…", #selector(setUpAgain))
        add(menu, "Forget phones…", #selector(forgetPhones))
        let login = add(menu, "Start at login", #selector(toggleStartAtLogin))
        login.state = startAtLogin ? .on : .off
        menu.addItem(.separator())
        add(menu, "Quit Pocket XR PC helper", #selector(quit))
    }

    @discardableResult
    private func add(_ menu: NSMenu, _ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        menu.addItem(item)
        return item
    }

    @objc private func setUpAgain() { runSetup(showGuide: true) }

    @objc private func forgetPhones() {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Forget all phones?"
        a.informativeText = "Phones will have to be allowed on this Mac again, and paired with Sunshine again, before they can stream it."
        a.addButton(withTitle: "Forget phones")
        a.addButton(withTitle: "Cancel")
        guard a.runModal() == .alertFirstButtonReturn else { return }
        phoneBook.forgetAll()
        preparingLock.lock(); preparingPhones.removeAll(); preparingLock.unlock()
        try? FileManager.default.removeItem(at: preparingFile)
        setupQueue.async {
            guard let login = Keychain.read() else { return Log.say("phones forgotten (Sunshine's pairings kept: no login)") }
            for i in Sunshine.Instance.all where Sunshine.isAnswering(i) {
                let r = Sunshine.api("POST", "/api/clients/unpair-all", body: [:], webPort: i.webPort, login: login)
                Log.say("phones forgotten; Sunshine \(i.index) unpair-all: HTTP \(r.code)")
            }
        }
    }

    @objc private func toggleStartAtLogin() { setStartAtLogin(!startAtLogin) }

    private func setStartAtLogin(_ on: Bool) {
        startAtLogin = on
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            Log.say("start at login (\(on)): \(error.localizedDescription)")
        }
        if on, SMAppService.mainApp.status == .requiresApproval { SMAppService.openSystemSettingsLoginItems() }
        Log.say("start at login: \(on), login item status \(SMAppService.mainApp.status.rawValue)")
        setupQueue.async {
            // Sunshine follows: still running now, and started (or not) at the next sign-in. (With virtual screens
            // this helper starts the Sunshines itself.)
            for i in Sunshine.Instance.all { Shell.run("/bin/launchctl", [on ? "enable" : "disable", i.target]) }
        }
    }

    @objc private func quit() { NSApp.terminate(nil) }

    private func alert(_ title: String, _ text: String) {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = title
        a.informativeText = text
        a.runModal()
    }

    // MARK: HelperHost (called on the server's threads)

    func allow(phone: String) -> Bool {
        pairLock.lock()
        defer { pairLock.unlock() }
        var allowed = false
        let done = DispatchSemaphore(value: 0)
        DispatchQueue.main.async {
            NSApp.activate(ignoringOtherApps: true)
            let a = NSAlert()
            a.messageText = "Allow \(phone)?"
            a.informativeText = "\(phone) wants to use this Mac with Pocket XR (PC screens): to see its screen and use its mouse and keyboard."
            a.addButton(withTitle: "Allow")
            a.addButton(withTitle: "Don't Allow")
            // No answer in a minute is a no (the phone waits 65 s).
            let timer = Timer(timeInterval: 60, repeats: false) { _ in NSApp.abortModal() }
            RunLoop.main.add(timer, forMode: .modalPanel)
            allowed = a.runModal() == .alertFirstButtonReturn
            timer.invalidate()
            done.signal()
        }
        done.wait()
        Log.say("\(phone): \(allowed ? "allowed" : "not allowed")")
        return allowed
    }

    func knows(token: String) -> Bool { phoneBook.knows(token) }

    func addPhone(_ name: String) -> String { phoneBook.add(name) }

    func typePin(_ pin: String, name: String, port: Int, phoneAddress: String?) -> String? {
        guard let login = Keychain.read() else { return "no Sunshine login saved: open Pocket XR PC helper on the Mac" }
        return Sunshine.typePin(pin, name: name, port: port, phoneAddress: phoneAddress, login: login)
    }

    func showSetup(for phone: String) {
        Log.say("\(phone) asked for the setup")
        DispatchQueue.main.async { self.runSetup(showGuide: true) }
    }
}
