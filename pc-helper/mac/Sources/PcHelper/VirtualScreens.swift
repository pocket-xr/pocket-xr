import Foundation
import CoreGraphics
import CVirtualDisplay
import HelperCore

/// The virtual displays the phone's PC screens show, one per Sunshine (Screens). Made with CoreGraphics' unpublished
/// CGVirtualDisplay (as Pocket XR for Mac's own screens, BetterDisplay and DeskPad): vendor "PX", product 0x0300,
/// serial = the screen's number.
///
/// Seen on the Air (macOS 14.8):
/// - macOS gives a new one the first free display id, whatever its serial (screens 1, 2, 3 each made alone all got
///   4128835), so the three are made together and kept while this helper runs: each Sunshine's mouse is tied to its
///   display id when it starts.
/// - While no stream uses one, it mirrors the built-in screen: then macOS lists only the built-in screen for windows
///   (NSScreen.screens), so nothing can be moved onto it, and the built-in keeps its own mode (1280x800); it keeps its
///   display id. Making or resizing a virtual display brings the others out of mirroring, so a stream's screen is
///   resized while still mirrored, then one change brings it out to the right of the Mac's displays and mirrors the
///   rest again (1.1 s on the Air); every few seconds a check mirrors any idle one that came out.
/// - A new display made the virtual one the main display, and the built-in screen changed mode (1280x800 to 1440x900)
///   while it was there: the built-in's own mode is put back in the same change.
/// - A display change that moves the Mac's own displays takes ~10 s even when they are already in place, so they are
///   only moved when they are out of place.
final class VirtualScreens {
    private var displays: [Int: CGVirtualDisplay] = [:]
    private var streaming: Set<Int> = []
    /// Out of mirroring until then, for a stream that may start: Sunshine tests its display (an encoder test) when a
    /// stream launches, before its prep command runs, and a mirrored display gives it no frames (seen on the Air:
    /// a Sunshine started with its display mirrored stopped at "Configuring selected display" for 47 s).
    private var expecting: [Int: Date] = [:]
    private var out: Set<Int> { streaming.union(expecting.keys) }
    private let lock = NSLock()
    private let queue = DispatchQueue(label: "virtual-screens")
    /// The Mac's own displays as they were before ours: origins (main at 0,0) and widths; the built-in screen's mode.
    private var saved: [(id: UInt32, x: Int32, y: Int32, width: Int32)] = []
    private var builtinMode: CGDisplayMode?
    private var builtinId: CGDirectDisplayID = 0

    static let vendor: UInt32 = 0x5058  // "PX"
    static let product: UInt32 = 0x0300
    /// The size a screen keeps while it waits (mirrored) for a stream.
    static let idle = Screens.Mode(width: 1280, height: 720, fps: 30)

    func id(_ index: Int) -> CGDirectDisplayID? {
        lock.lock(); defer { lock.unlock() }
        return displays[index]?.displayID
    }

    /// Screen [index] made if it is not there (idle size, not yet mirrored: call settle()). Its display id.
    func make(_ index: Int) -> CGDirectDisplayID? {
        lock.lock(); defer { lock.unlock() }
        return made(index)?.displayID
    }

    private func made(_ index: Int) -> CGVirtualDisplay? {
        if let d = displays[index] { return d }
        if displays.isEmpty { rememberMacDisplays() }
        let d = CGVirtualDisplayDescriptor()
        d.queue = queue
        d.name = "Pocket XR PC screen \(index)"
        d.maxPixelsWide = 3840
        d.maxPixelsHigh = 3840
        d.sizeInMillimeters = CGSize(width: 1280 * 25.4 / 96, height: 720 * 25.4 / 96)  // ~96 dpi: text at its usual size
        d.vendorID = Self.vendor
        d.productID = Self.product
        d.serialNum = UInt32(index)
        guard let display = CGVirtualDisplay(descriptor: d) else {
            Log.say("screen \(index): macOS did not make the virtual display")
            return nil
        }
        apply(display, Self.idle)
        displays[index] = display
        for _ in 0..<30 where !Self.active().contains(display.displayID) { Thread.sleep(forTimeInterval: 0.1) }
        Log.say("screen \(index): display \(display.displayID) made")
        return display
    }

    /// Screen [index] out for a stream at [mode]: resized while still mirrored, then out to the right of the Mac's
    /// displays. Its display id once it is there; nil if it could not be made.
    func stream(_ index: Int, _ mode: Screens.Mode) -> CGDirectDisplayID? {
        lock.lock(); defer { lock.unlock() }
        return bringOut(index, mode, until: nil)
    }

    /// Screen [index] out at [mode] for a stream the phone is about to start (screen-prepare); back to mirroring if
    /// none starts within a minute.
    func prepare(_ index: Int, _ mode: Screens.Mode) -> CGDirectDisplayID? {
        lock.lock(); defer { lock.unlock() }
        return bringOut(index, mode, until: Date().addingTimeInterval(60))
    }

    /// Every waiting screen out (at its waiting size) for a minute: a phone asked for status, and a stream it does
    /// not name may follow.
    func expectAny() {
        lock.lock(); defer { lock.unlock() }
        let until = Date().addingTimeInterval(60)
        let waiting = displays.keys.filter { !streaming.contains($0) }
        let alreadyOut = waiting.allSatisfy { expecting[$0] != nil }
        for i in waiting { expecting[i] = until }
        if !alreadyOut {
            arrange()
            Log.say("screens \(waiting.sorted()) out for a minute (a phone asked for status)")
        }
    }

    private func bringOut(_ index: Int, _ mode: Screens.Mode, until: Date?) -> CGDirectDisplayID? {
        let start = Date()
        guard let display = made(index) else { return nil }
        let id = display.displayID
        let isOut = out.contains(index) && Self.active().contains(id)
        if let until { expecting[index] = until } else { expecting[index] = nil; streaming.insert(index) }
        // Already out at that size (readied by screen-prepare): nothing to change.
        if isOut, Int(CGDisplayBounds(id).width) == mode.width, Int(CGDisplayBounds(id).height) == mode.height {
            Log.say(String(format: "screen %d: display %u already out at %@", index, id, "\(mode)"))
            return id
        }
        apply(display, mode)
        Thread.sleep(forTimeInterval: 0.3)
        arrange()
        for _ in 0..<40 where !Self.active().contains(id) || Int(CGDisplayBounds(id).width) != mode.width { Thread.sleep(forTimeInterval: 0.1) }
        Log.say(String(format: "screen %d: display %u out at %dx%d (%@ asked), %.1f s", index, id,
                       Int(CGDisplayBounds(id).width), Int(CGDisplayBounds(id).height), "\(mode)", Date().timeIntervalSince(start)))
        return id
    }

    /// Screen [index] back to waiting: mirrored (at the size it has).
    func idle(_ index: Int) {
        lock.lock(); defer { lock.unlock() }
        guard let display = displays[index] else { return }
        streaming.remove(index)
        // The phone readies the next stream right after quitting the last, and Sunshine's undo for the last may come
        // after that: the readied screen stays out (its minute runs on).
        if expecting[index] != nil {
            Log.say("screen \(index): a stream ended, the next one is readied: stays out")
            return
        }
        // Mirrored at the size it has: a resize here brought the other screens out of mirroring again, after this
        // change (seen on the Air, 01:24:57-01:25:12, build 7), and a mirrored screen's size does not matter.
        arrange()
        Log.say("screen \(index): display \(display.displayID) waiting (mirrored)")
    }

    /// Screen [index] as the main display, out of mirroring (a Sunshine starting: its mouse goes to the main display).
    func makeMain(_ index: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let id = displays[index]?.displayID else { return false }
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success else { return false }
        let ours = displays.values.map(\.displayID)
        let mac = saved.map(\.id).filter { Self.online().contains($0) }
        let master = builtinId != 0 ? builtinId : (mac.first ?? 0)
        for other in ours where other != id { CGConfigureDisplayMirrorOfDisplay(config, other, master) }
        CGConfigureDisplayMirrorOfDisplay(config, id, kCGNullDirectDisplay)
        for p in Screens.mainFirst((id: id, width: Int32(CGDisplayPixelsWide(id))), others: mac.map { (id: $0, width: Int32(CGDisplayPixelsWide($0))) }) {
            CGConfigureDisplayOrigin(config, p.id, p.x, p.y)
        }
        _ = CGCompleteDisplayConfiguration(config, .forSession)
        for _ in 0..<30 where CGMainDisplayID() != id { Thread.sleep(forTimeInterval: 0.1) }
        Log.say("screen \(index): main display for a moment (\(CGMainDisplayID() == id ? "yes" : "no"))")
        return CGMainDisplayID() == id
    }

    /// Everything in its place: the Mac's own displays as they were, streaming screens out to their right, the
    /// rest mirrored.
    func settle() {
        lock.lock(); defer { lock.unlock() }
        arrange()
    }

    /// A screen readied for a stream that did not come goes back to mirroring; one that came out of mirroring by
    /// itself (another display was made or changed) is mirrored again.
    func checkIdle() {
        guard lock.try() else { return }
        defer { lock.unlock() }
        let now = Date()
        let expired = expecting.filter { $0.value < now }.map(\.key)
        for i in expired { expecting[i] = nil }
        let active = Self.active()
        let stray = displays.filter { !out.contains($0.key) && active.contains($0.value.displayID) }
        guard !expired.isEmpty || !stray.isEmpty else { return }
        if !expired.isEmpty { Log.say("screens \(expired.sorted()): no stream came; mirrored again") }
        if !stray.isEmpty { Log.say("screens \(stray.keys.sorted()) came out of mirroring: mirrored again") }
        arrange()
    }

    /// All gone (virtual screens off).
    func removeAll() {
        lock.lock(); defer { lock.unlock() }
        streaming.removeAll()
        expecting.removeAll()
        displays.removeAll()
        Thread.sleep(forTimeInterval: 1)
        arrange()
    }

    // MARK: Inside (with the lock held)

    private func apply(_ display: CGVirtualDisplay, _ mode: Screens.Mode) {
        let settings = CGVirtualDisplaySettings()
        settings.hiDPI = 0
        settings.modes = [CGVirtualDisplayMode(width: UInt(mode.width), height: UInt(mode.height), refreshRate: CGFloat(mode.fps))]
        if !display.apply(settings) { Log.say("display \(display.displayID): \(mode) not taken") }
    }

    /// One display change: idle screens mirror the built-in screen, streaming ones out (to the right of the Mac's
    /// displays, in screen order), the Mac's displays back where they were if they moved, the built-in's mode back.
    private func arrange() {
        let online = Self.online()
        let macIds = saved.map(\.id).filter { online.contains($0) }
        let master = builtinId != 0 && online.contains(builtinId) ? builtinId : (macIds.first ?? CGMainDisplayID())
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success else { return }
        var changed = false
        // The Mac's own: only if one is out of place (see above).
        let keep = saved.filter { online.contains($0.id) }
        let outSet = out
        let outIds = displays.filter { outSet.contains($0.key) }.sorted { $0.key < $1.key }.map(\.value.displayID)
        let placed = Screens.layout(saved: keep, ours: outIds.map { (id: $0, width: Int32(CGDisplayPixelsWide($0))) })
        let macMoved = placed.contains { p in
            keep.contains { $0.id == p.id } && (Int32(CGDisplayBounds(p.id).origin.x) != p.x || Int32(CGDisplayBounds(p.id).origin.y) != p.y)
        }
        for p in placed where macMoved || !keep.contains(where: { $0.id == p.id }) {
            CGConfigureDisplayOrigin(config, p.id, p.x, p.y)
            changed = true
        }
        for (index, display) in displays {
            let id = display.displayID
            if outSet.contains(index) {
                if CGDisplayMirrorsDisplay(id) != kCGNullDirectDisplay { CGConfigureDisplayMirrorOfDisplay(config, id, kCGNullDirectDisplay); changed = true }
            } else if CGDisplayMirrorsDisplay(id) != master {
                CGConfigureDisplayMirrorOfDisplay(config, id, master)
                changed = true
            }
        }
        if builtinId != 0, let wanted = builtinMode, let now = CGDisplayCopyDisplayMode(builtinId),
           now.width != wanted.width || now.height != wanted.height || now.pixelWidth != wanted.pixelWidth {
            CGConfigureDisplayWithDisplayMode(config, builtinId, wanted, nil)
            changed = true
        }
        if changed {
            let r = CGCompleteDisplayConfiguration(config, .forSession)
            if r != .success { Log.say("display change: error \(r.rawValue)") }
        } else {
            CGCancelDisplayConfiguration(config)
        }
    }

    private func rememberMacDisplays() {
        let active = Self.active()
        let main = CGMainDisplayID()
        let origin = CGDisplayBounds(main).origin
        saved = active.map { id in
            let b = CGDisplayBounds(id)
            return (id: id, x: Int32(b.origin.x - origin.x), y: Int32(b.origin.y - origin.y), width: Int32(b.width))
        }
        builtinId = active.first { CGDisplayIsBuiltin($0) != 0 } ?? 0
        builtinMode = builtinId != 0 ? CGDisplayCopyDisplayMode(builtinId) : nil
    }

    /// Displays macOS draws on (mirrors not included).
    static func active() -> [CGDirectDisplayID] {
        var n: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &n)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(n))
        CGGetActiveDisplayList(n, &ids, &n)
        return Array(ids.prefix(Int(n)))
    }

    /// Every display, mirrors too.
    static func online() -> [CGDirectDisplayID] {
        var ids = [CGDirectDisplayID](repeating: 0, count: 32)
        var n: UInt32 = 0
        CGGetOnlineDisplayList(32, &ids, &n)
        return Array(ids.prefix(Int(n)))
    }
}
