import AppKit

let app = NSApplication.shared
let delegate = App()
app.delegate = delegate
app.setActivationPolicy(.accessory)  // the menu bar only, no Dock icon
app.run()
