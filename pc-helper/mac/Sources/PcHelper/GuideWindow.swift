import AppKit

/// The two switches only the user can turn on, in plain words. macOS never asks for Accessibility (seen on macOS 14:
/// "kTCCServicePostEvent does not allow prompting; returning denied"), and without it the phone's clicks and typing are
/// dropped with no message anywhere. Whether they are on cannot be read for another app, so the user says Done.
final class GuideWindow: NSWindowController {
    private let progressLabel = NSTextField(labelWithString: "")
    private let actions: [() -> Void]

    init(openScreenRecording: @escaping () -> Void, openAccessibility: @escaping () -> Void,
         showSunshine: @escaping () -> Void, done: @escaping () -> Void) {
        actions = [openScreenRecording, openAccessibility, showSunshine, done]
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 480, height: 300),
                              styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Pocket XR PC helper"
        window.isReleasedWhenClosed = false
        super.init(window: window)

        let title = NSTextField(labelWithString: "Two switches for Sunshine")
        title.font = .boldSystemFont(ofSize: 15)
        let intro = wrapping("Sunshine streams this Mac to Pocket XR. macOS lets only you switch these on, and it does not ask by itself.")
        let one = row("1. Screen Recording: switch on Sunshine, so the phone sees this screen.", "Open Screen Recording", 0)
        let two = row("2. Accessibility: switch on Sunshine, so the phone's mouse and keyboard work.", "Open Accessibility", 1)
        let missing = row("Sunshine not in a list? Click + and choose it, or drag it in from Finder.", "Show Sunshine", 2)
        progressLabel.textColor = .secondaryLabelColor
        progressLabel.lineBreakMode = .byTruncatingTail
        let done = button("Done", 3)
        done.keyEquivalent = "\r"
        let bottom = NSStackView(views: [progressLabel, NSView(), done])
        bottom.orientation = .horizontal

        let stack = NSStackView(views: [title, intro, one, two, missing, bottom])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)
        stack.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView()
        content.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            stack.topAnchor.constraint(equalTo: content.topAnchor),
            stack.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            content.widthAnchor.constraint(equalToConstant: 480),
            bottom.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -40),
        ])
        window.contentView = content
        window.center()
    }

    required init?(coder: NSCoder) { nil }

    func show(progress: String) { progressLabel.stringValue = progress }

    private func wrapping(_ text: String) -> NSTextField {
        let t = NSTextField(wrappingLabelWithString: text)
        t.preferredMaxLayoutWidth = 440
        return t
    }

    private func row(_ text: String, _ title: String, _ tag: Int) -> NSView {
        let label = wrapping(text)
        label.preferredMaxLayoutWidth = 270
        let stack = NSStackView(views: [label, NSView(), button(title, tag)])
        stack.orientation = .horizontal
        stack.widthAnchor.constraint(equalToConstant: 440).isActive = true
        return stack
    }

    private func button(_ title: String, _ tag: Int) -> NSButton {
        let b = NSButton(title: title, target: self, action: #selector(pressed(_:)))
        b.tag = tag
        b.bezelStyle = .rounded
        return b
    }

    @objc private func pressed(_ sender: NSButton) { actions[sender.tag]() }
}
