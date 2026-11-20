// Probe (not shipped): do CGVirtualDisplay ids stay the same when a display with the same vendor, product and serial
// is made again, at another size? Can one be made the main display and the main display put back?
//   vdprobe ids            make, remove, make again (other size): print the ids
//   vdprobe main           make one, make it the main display for 1 s, put the old main back
//   vdprobe hold W H SECS  make one at WxH and keep it SECS seconds (for Sunshine to capture), print its id
import Foundation
import AppKit
import CoreGraphics
import CVirtualDisplay

func make(_ w: Int, _ h: Int, serial: UInt32 = 1) -> CGVirtualDisplay? {
    let d = CGVirtualDisplayDescriptor()
    d.queue = DispatchQueue.main
    d.name = "Pocket XR screen \(serial)"
    d.maxPixelsWide = 3840
    d.maxPixelsHigh = 2160
    d.sizeInMillimeters = CGSize(width: 600, height: 340)
    d.vendorID = 0x5058
    d.productID = 0x0200
    d.serialNum = serial
    guard let display = CGVirtualDisplay(descriptor: d) else { return nil }
    let s = CGVirtualDisplaySettings()
    s.hiDPI = 0
    s.modes = [CGVirtualDisplayMode(width: UInt(w), height: UInt(h), refreshRate: 60)]
    print("applySettings \(w)x\(h): \(display.apply(s))")
    return display
}

func displays() -> [CGDirectDisplayID] {
    var n: UInt32 = 0
    CGGetActiveDisplayList(0, nil, &n)
    var ids = [CGDirectDisplayID](repeating: 0, count: Int(n))
    CGGetActiveDisplayList(n, &ids, &n)
    return ids
}

func describe(_ id: CGDirectDisplayID) -> String {
    let b = CGDisplayBounds(id)
    return "\(id) at \(Int(b.origin.x)),\(Int(b.origin.y)) \(Int(b.width))x\(Int(b.height))\(CGDisplayIsMain(id) != 0 ? " main" : "")"
}

func settle(_ s: Double) { RunLoop.main.run(until: Date().addingTimeInterval(s)) }

func setOrigin(_ id: CGDirectDisplayID, _ x: Int32, _ y: Int32) -> CGError {
    var config: CGDisplayConfigRef?
    CGBeginDisplayConfiguration(&config)
    CGConfigureDisplayOrigin(config, id, x, y)
    return CGCompleteDisplayConfiguration(config, .forSession)
}

setvbuf(stdout, nil, _IONBF, 0)
let args = CommandLine.arguments
switch args.count > 1 ? args[1] : "" {
case "ids":
    print("before: \(displays().map(describe))")
    var a = make(1280, 800)
    settle(2)
    let first = a?.displayID ?? 0
    print("made: \(displays().map(describe))")
    a = nil
    settle(2)
    print("removed: \(displays().map(describe))")
    var b = make(1600, 900)
    settle(2)
    print("made again at another size: \(displays().map(describe))")
    print("same id: \(first == (b?.displayID ?? 1)) (\(first) / \(b?.displayID ?? 0))")
    let s = CGVirtualDisplaySettings()
    s.hiDPI = 0
    s.modes = [CGVirtualDisplayMode(width: 1080, height: 1920, refreshRate: 60)]
    print("resize in place to 1080x1920: \(b!.apply(s))")
    settle(2)
    print("after resize: \(displays().map(describe))")
    b = nil
    settle(1)
case "main":
    let oldMain = CGMainDisplayID()
    print("main before: \(describe(oldMain))")
    let v = make(1280, 800)!
    settle(2)
    print("made: \(displays().map(describe))")
    print("virtual to 0,0: \(setOrigin(v.displayID, 0, 0).rawValue)")
    settle(1)
    print("now: \(displays().map(describe)); CGMainDisplayID = \(CGMainDisplayID())")
    print("old main to 0,0: \(setOrigin(oldMain, 0, 0).rawValue)")
    settle(1)
    print("back: \(displays().map(describe)); CGMainDisplayID = \(CGMainDisplayID())")
    _ = v
case "mainback":
    // The built-in screen (else the first other one) back to 0,0: the main display again.
    let ids = displays()
    let main = CGMainDisplayID()
    let builtinOnes = ids.filter { CGDisplayIsBuiltin($0) != 0 }
    let others = ids.filter { $0 != main }
    let builtin: CGDirectDisplayID = builtinOnes.first ?? others.first ?? main
    // One change: the built-in to 0,0, every other display to its right (moving only the built-in does nothing).
    var config: CGDisplayConfigRef?
    CGBeginDisplayConfiguration(&config)
    CGConfigureDisplayOrigin(config, builtin, 0, 0)
    var x = Int32(CGDisplayPixelsWide(builtin))
    for id in ids where id != builtin {
        CGConfigureDisplayOrigin(config, id, x, 0)
        x += Int32(CGDisplayPixelsWide(id))
    }
    print("built-in to 0,0, others to the right: \(CGCompleteDisplayConfiguration(config, .forSession).rawValue)")
    settle(1)
    print("now: \(displays().map(describe))")
case "three":
    // Three at once (serials 1, 2, 3): their ids, then the built-in made main again, then all removed.
    var held: [CGVirtualDisplay] = []
    for s in 1...3 { if let v = make(1280, 720, serial: UInt32(s)) { held.append(v) } }
    settle(3)
    print("three: ids \(held.map(\.displayID)) -> \(displays().map(describe))")
    held.removeAll()
    settle(2)
    print("removed: \(displays().map(describe))")
case "timing":
    // One virtual display (beside the app's three), resized: how long each step takes.
    func t(_ what: String, _ body: () -> Void) { let s = Date(); body(); print(String(format: "%@: %.2f s", what, Date().timeIntervalSince(s))) }
    let builtin = displays().first { CGDisplayIsBuiltin($0) != 0 }!
    let mode = CGDisplayCopyDisplayMode(builtin)!
    var v: CGVirtualDisplay?
    t("make 1280x720") { v = make(1280, 720, serial: 9) }
    func until(_ ok: () -> Bool) { let s = Date(); while !ok() && Date().timeIntervalSince(s) < 10 { settle(0.05) }; if !ok() { print("(gave up after 10 s)") } }
    t("until listed") { until { displays().contains(v!.displayID) } }
    print("built-in now \(CGDisplayCopyDisplayMode(builtin)!.width)x\(CGDisplayCopyDisplayMode(builtin)!.height)")
    let s = CGVirtualDisplaySettings(); s.hiDPI = 0; s.modes = [CGVirtualDisplayMode(width: 2400, height: 1080, refreshRate: 60)]
    t("apply 2400x1080") { _ = v!.apply(s) }
    t("until 2400 wide") { until { CGDisplayPixelsWide(v!.displayID) == 2400 } }
    print("built-in now \(CGDisplayCopyDisplayMode(builtin)!.width)x\(CGDisplayCopyDisplayMode(builtin)!.height)")
    t("origins (no change)") { var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c); for id in displays() { let b = CGDisplayBounds(id); CGConfigureDisplayOrigin(c, id, Int32(b.origin.x), Int32(b.origin.y)) }; _ = CGCompleteDisplayConfiguration(c, .permanently) }
    t("built-in mode back") { var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c); CGConfigureDisplayWithDisplayMode(c, builtin, mode, nil); _ = CGCompleteDisplayConfiguration(c, .permanently) }
    print("built-in now \(CGDisplayCopyDisplayMode(builtin)!.width)x\(CGDisplayCopyDisplayMode(builtin)!.height)")
    let gone = v!.displayID
    t("remove") { v = nil; until { !displays().contains(gone) } }
    print("built-in now \(CGDisplayCopyDisplayMode(builtin)!.width)x\(CGDisplayCopyDisplayMode(builtin)!.height)")
case "mirror":
    // vdprobe mirror ID ID ID: those (another process's idle screens) mirror the built-in screen; then a display of
    // this probe's own goes the same way, comes back out (un-mirror, resize, place right), timed; everything back.
    func t(_ what: String, _ body: () -> Void) { let s = Date(); body(); print(String(format: "%@: %.2f s", what, Date().timeIntervalSince(s))) }
    func until(_ ok: () -> Bool) { let s = Date(); while !ok() && Date().timeIntervalSince(s) < 10 { settle(0.05) }; if !ok() { print("(gave up after 10 s)") } }
    func state(_ label: String) {
        let b = displays().first { CGDisplayIsBuiltin($0) != 0 }!
        let m = CGDisplayCopyDisplayMode(b)!
        let screens = NSScreen.screens.map { s -> String in
            let id = (s.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value ?? 0
            return "\(id)@\(Int(s.frame.origin.x)),\(Int(s.frame.origin.y)) \(Int(s.frame.width))x\(Int(s.frame.height))"
        }
        let mirrors = displays().map { "\($0)->\(CGDisplayMirrorsDisplay($0))" }
        print("\(label): built-in \(m.width)x\(m.height) px \(m.pixelWidth)x\(m.pixelHeight); screens for windows \(screens); mirrors \(mirrors); online \(displays().count)")
    }
    func mirror(_ ids: [CGDirectDisplayID], of master: CGDirectDisplayID) -> CGError {
        var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c)
        for id in ids { CGConfigureDisplayMirrorOfDisplay(c, id, master) }
        return CGCompleteDisplayConfiguration(c, .forSession)
    }
    let builtin = displays().first { CGDisplayIsBuiltin($0) != 0 }!
    let theirs = args.dropFirst(2).compactMap { UInt32($0) }
    state("before")
    t("mirror their \(theirs.count) to the built-in") { print("  result \(mirror(theirs, of: builtin).rawValue)"); settle(1) }
    state("their idle screens mirrored")
    var v: CGVirtualDisplay? = make(1280, 720, serial: 9)
    settle(2)
    let mine = v!.displayID
    t("mirror mine too") { print("  result \(mirror([mine], of: builtin).rawValue)"); settle(0.5) }
    state("mine mirrored")
    t("un-mirror mine") { print("  result \(mirror([mine], of: kCGNullDirectDisplay).rawValue)"); until { CGDisplayMirrorsDisplay(mine) == kCGNullDirectDisplay } }
    let s = CGVirtualDisplaySettings(); s.hiDPI = 0; s.modes = [CGVirtualDisplayMode(width: 2400, height: 1080, refreshRate: 60)]
    t("size 2400x1080") { _ = v!.apply(s); until { CGDisplayPixelsWide(mine) == 2400 } }
    t("place right of the built-in") {
        var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c)
        CGConfigureDisplayOrigin(c, mine, Int32(CGDisplayBounds(builtin).width), 0)
        print("  result \(CGCompleteDisplayConfiguration(c, .forSession).rawValue)")
    }
    print("mine: id \(v!.displayID) (was \(mine)), \(Int(CGDisplayBounds(mine).origin.x)),\(Int(CGDisplayBounds(mine).origin.y)) \(Int(CGDisplayPixelsWide(mine)))x\(Int(CGDisplayPixelsHigh(mine)))")
    state("mine out for a stream")
    t("mirror mine again") { print("  result \(mirror([mine], of: builtin).rawValue)"); settle(0.5) }
    state("mine idle again")
    v = nil
    settle(1)
    t("un-mirror theirs") { print("  result \(mirror(theirs, of: kCGNullDirectDisplay).rawValue)"); settle(1) }
    state("theirs back")
case "setmirror":
    // vdprobe setmirror on|off ID...: those mirror the built-in screen (on) or stop (off), in one change.
    let builtin = displays().first { CGDisplayIsBuiltin($0) != 0 }!
    var online = [CGDirectDisplayID](repeating: 0, count: 16); var n: UInt32 = 0
    _ = CGGetOnlineDisplayList(16, &online, &n)
    let ids = args.dropFirst(3).compactMap { UInt32($0) }
    let s = Date()
    var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c)
    for id in ids { CGConfigureDisplayMirrorOfDisplay(c, id, args[2] == "on" ? builtin : kCGNullDirectDisplay) }
    let r = CGCompleteDisplayConfiguration(c, .forSession)
    print(String(format: "mirror %@ %@: result %d, %.2f s", args[2], "\(ids)", r.rawValue, Date().timeIntervalSince(s)))
case "screens":
    // A fresh look, as a new app sees it: the screens windows can go to, the built-in screen's mode.
    let b = CGMainDisplayID()
    var online = [CGDirectDisplayID](repeating: 0, count: 16); var n: UInt32 = 0
    _ = CGGetOnlineDisplayList(16, &online, &n)
    let onlineIds = Array(online.prefix(Int(n)))
    let builtinOnes = onlineIds.filter { CGDisplayIsBuiltin($0) != 0 }
    let builtin: CGDirectDisplayID = builtinOnes.first ?? b
    let m = CGDisplayCopyDisplayMode(builtin)!
    print("built-in \(m.width)x\(m.height) px \(m.pixelWidth)x\(m.pixelHeight); active \(displays()); online \(Array(online.prefix(Int(n))).map { "\($0)->\(CGDisplayMirrorsDisplay($0))" })")
    print("screens for windows: \(NSScreen.screens.map { "\(($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value ?? 0) \(Int($0.frame.width))x\(Int($0.frame.height))" })")
case "streamflow":
    // vdprobe streamflow ID...: a display of this probe's own made first, then it and ID... mirrored together (idle);
    // then mine out for a stream (un-mirror, 2400x1080, right of the built-in), timed; then idle again; all back.
    func t(_ what: String, _ body: () -> Void) { let s = Date(); body(); print(String(format: "%@: %.2f s", what, Date().timeIntervalSince(s))) }
    func until(_ ok: () -> Bool) { let s = Date(); while !ok() && Date().timeIntervalSince(s) < 15 { settle(0.05) }; if !ok() { print("(gave up after 15 s)") } }
    func look(_ label: String) {
        let b = displays().first { CGDisplayIsBuiltin($0) != 0 }!
        let m = CGDisplayCopyDisplayMode(b)!
        print("\(label): built-in \(m.width)x\(m.height); active \(displays().map(describe))")
    }
    let builtin = displays().first { CGDisplayIsBuiltin($0) != 0 }!
    let theirs = args.dropFirst(2).compactMap { UInt32($0) }
    var v: CGVirtualDisplay? = make(1280, 720, serial: 9)
    settle(3)
    let mine = v!.displayID
    look("made mine (\(mine))")
    func mirror(_ ids: [CGDirectDisplayID], _ on: Bool) {
        var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c)
        for id in ids { CGConfigureDisplayMirrorOfDisplay(c, id, on ? builtin : kCGNullDirectDisplay) }
        _ = CGCompleteDisplayConfiguration(c, .forSession)
    }
    t("all four idle (mirrored)") { mirror(theirs + [mine], true); until { displays() == [builtin] } }
    look("idle")
    settle(2)
    look("idle, 2 s later")
    t("mine: un-mirror") { mirror([mine], false); until { displays().contains(mine) } }
    look("mine un-mirrored")
    let s = CGVirtualDisplaySettings(); s.hiDPI = 0; s.modes = [CGVirtualDisplayMode(width: 2400, height: 1080, refreshRate: 60)]
    t("mine: 2400x1080") { _ = v!.apply(s); until { Int(CGDisplayBounds(mine).width) == 2400 } }
    t("mine: right of the built-in") {
        var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c)
        CGConfigureDisplayOrigin(c, mine, Int32(CGDisplayBounds(builtin).width), 0)
        _ = CGCompleteDisplayConfiguration(c, .forSession)
    }
    look("mine streaming (id \(v!.displayID))")
    t("mine: idle again") { mirror([mine], true); until { !displays().contains(mine) } }
    look("mine idle again")
    v = nil
    settle(2)
    t("theirs back out") { mirror(theirs, false); until { displays().count == 1 + theirs.count } }
    look("end")
case "streamflow2":
    // As streamflow, but: resize while still mirrored, then one change: mine out (un-mirror, right of the built-in)
    // and the others mirrored again; idle again: one change, mine and the others mirrored.
    func t(_ what: String, _ body: () -> Void) { let s = Date(); body(); print(String(format: "%@: %.2f s", what, Date().timeIntervalSince(s))) }
    func until(_ ok: () -> Bool) { let s = Date(); while !ok() && Date().timeIntervalSince(s) < 15 { settle(0.05) }; if !ok() { print("(gave up after 15 s)") } }
    func look(_ label: String) {
        let b = displays().first { CGDisplayIsBuiltin($0) != 0 }!
        let m = CGDisplayCopyDisplayMode(b)!
        print("\(label): built-in \(m.width)x\(m.height); active \(displays().map(describe))")
    }
    let builtin = displays().first { CGDisplayIsBuiltin($0) != 0 }!
    let builtinMode = CGDisplayCopyDisplayMode(builtin)!
    let theirs = args.dropFirst(2).compactMap { UInt32($0) }
    var v: CGVirtualDisplay? = make(1280, 720, serial: 9)
    settle(3)
    let mine = v!.displayID
    func change(out: [CGDirectDisplayID], mirrored: [CGDirectDisplayID]) {
        var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c)
        for id in mirrored { CGConfigureDisplayMirrorOfDisplay(c, id, builtin) }
        var x = Int32(CGDisplayBounds(builtin).width)
        for id in out {
            CGConfigureDisplayMirrorOfDisplay(c, id, kCGNullDirectDisplay)
            CGConfigureDisplayOrigin(c, id, x, 0)
            x += Int32(CGDisplayPixelsWide(id))
        }
        if let now = CGDisplayCopyDisplayMode(builtin), now.width != builtinMode.width || now.height != builtinMode.height {
            CGConfigureDisplayWithDisplayMode(c, builtin, builtinMode, nil)
        }
        _ = CGCompleteDisplayConfiguration(c, .forSession)
    }
    t("all four idle") { change(out: [], mirrored: theirs + [mine]); until { displays() == [builtin] } }
    look("idle")
    let s = CGVirtualDisplaySettings(); s.hiDPI = 0; s.modes = [CGVirtualDisplayMode(width: 2400, height: 1080, refreshRate: 60)]
    t("mine: 2400x1080 while mirrored") { _ = v!.apply(s); settle(0.3) }
    look("after resize")
    t("mine out, others mirrored") { change(out: [mine], mirrored: theirs); until { displays().contains(mine) && Int(CGDisplayBounds(mine).width) == 2400 && displays().count == 2 } }
    look("mine streaming")
    settle(2)
    look("mine streaming, 2 s later")
    t("mine idle again") { change(out: [], mirrored: theirs + [mine]); until { displays() == [builtin] } }
    look("all idle")
    let s2 = CGVirtualDisplaySettings(); s2.hiDPI = 0; s2.modes = [CGVirtualDisplayMode(width: 1080, height: 1920, refreshRate: 60)]
    t("again, upright 1080x1920") { _ = v!.apply(s2); settle(0.3); change(out: [mine], mirrored: theirs); until { displays().contains(mine) && Int(CGDisplayBounds(mine).width) == 1080 && displays().count == 2 } }
    look("mine streaming upright")
    t("mine idle again") { change(out: [], mirrored: theirs + [mine]); until { displays() == [builtin] } }
    v = nil
    settle(2)
    t("theirs back out") { change(out: theirs, mirrored: []); until { displays().count == 1 + theirs.count } }
    look("end")
case "makemain":
    // vdprobe makemain ID: that display (out of mirroring) at 0,0, the others to its right.
    let id = UInt32(args[2])!
    var c: CGDisplayConfigRef?; CGBeginDisplayConfiguration(&c)
    CGConfigureDisplayMirrorOfDisplay(c, id, kCGNullDirectDisplay)
    CGConfigureDisplayOrigin(c, id, 0, 0)
    var x = Int32(CGDisplayPixelsWide(id))
    for o in displays() where o != id { CGConfigureDisplayOrigin(c, o, x, 0); x += Int32(CGDisplayPixelsWide(o)) }
    print("makemain \(id): \(CGCompleteDisplayConfiguration(c, .forSession).rawValue)")
    settle(1)
    print("main now \(CGMainDisplayID())")
case "list":
    print(displays().map(describe))
case "hold":
    let w = Int(args[2])!, h = Int(args[3])!, secs = Double(args[4])!
    let v = make(w, h)!
    settle(1)
    print("holding \(describe(v.displayID)) for \(Int(secs)) s")
    fflush(stdout)
    settle(secs)
default:
    print("vdprobe ids | main | hold W H SECS")
}
