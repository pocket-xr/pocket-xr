import AppKit
import HelperCore

// As a command, for a Sunshine's prep command (~/.config/pocketxr/screen.sh): --screen on|off N asks the running
// helper for virtual screen N at the stream's size (SUNSHINE_CLIENT_WIDTH, _HEIGHT, _FPS), or for it to go.
let arguments = CommandLine.arguments
if arguments.count >= 4, arguments[1] == "--screen" {
    exit(ScreenCommand.run(on: arguments[2] == "on", index: Int(arguments[3]) ?? 0))
}

let app = NSApplication.shared
let delegate = App()
app.delegate = delegate
app.setActivationPolicy(.accessory)  // the menu bar only, no Dock icon
app.run()
