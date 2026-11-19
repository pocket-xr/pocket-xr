#!/usr/bin/env bash
# Pocket XR PC screens setup for Linux (a PC, or a Steam Deck in Desktop Mode) and macOS.
#
# On Windows, the Pocket XR PC helper does this (Apollo, up to three screens). Apollo is Windows-only, so here the
# computer streams its screen with Sunshine (free, open source, same protocol). Sunshine cannot make virtual screens,
# so a Linux PC or a Mac gives one PC screen: its own desktop.
#
# Run it once in a terminal:  bash pocketxr-pc-screens.sh
# It:
#   1. installs Sunshine if it is missing (Linux: Flatpak from Flathub; macOS: Homebrew);
#   2. gives Sunshine a web login if it has none (or asks for the one it has) and saves it for the helper;
#   3. installs the Pocket XR PC helper (pocketxr_pc_helper.py, next to this script) and starts it and Sunshine at
#      sign-in (systemd user services on Linux, LaunchAgents on macOS);
#   4. says what is left for the user (Linux: input permissions; macOS: Screen Recording and Accessibility).
# Then, on the phone: open PC screen in Pocket XR, tap this computer, click Allow here. No PIN to type.
#
# Remove: bash pocketxr-pc-screens.sh --remove

set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
OS="$(uname -s)"
CONF="$HOME/.config/pocketxr"
SHARE="$HOME/.local/share/pocketxr"
LOGIN="$CONF/sunshine-login.json"
FLATPAK_ID="dev.lizardbyte.app.Sunshine"

say() { printf '%s\n' "$*"; }
die() { say "Setup stopped: $*"; exit 1; }

sunshine_cmd() {
    # How to run Sunshine here: the Flatpak, a system install, or Homebrew's.
    if [ "$OS" = "Linux" ] && flatpak info "$FLATPAK_ID" >/dev/null 2>&1; then echo "flatpak run $FLATPAK_ID"
    elif command -v sunshine >/dev/null 2>&1; then command -v sunshine
    else echo ""; fi
}

install_sunshine() {
    if [ -n "$(sunshine_cmd)" ]; then say "Sunshine is already installed: kept as it is."; return; fi
    say "Installing Sunshine (free, open source), which streams this computer's screen to Pocket XR..."
    if [ "$OS" = "Darwin" ]; then
        command -v brew >/dev/null 2>&1 || die "Homebrew is needed to install Sunshine on a Mac: install it from https://brew.sh, then run this again."
        brew tap LizardByte/homebrew >/dev/null && brew install sunshine || die "Homebrew could not install Sunshine."
    else
        command -v flatpak >/dev/null 2>&1 || die "Flatpak is needed (SteamOS and most desktops have it). Install Flatpak, or Sunshine from https://github.com/LizardByte/Sunshine/releases, then run this again."
        flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
        flatpak install --user -y flathub "$FLATPAK_ID" || die "Flatpak could not install Sunshine."
    fi
    [ -n "$(sunshine_cmd)" ] || die "Sunshine did not install."
    say "Sunshine is installed."
}

state_file() { echo "$HOME/.config/sunshine/sunshine_state.json"; }

has_login() { [ -f "$(state_file)" ] && grep -q '"username"' "$(state_file)"; }

save_login() {  # user, password
    mkdir -p "$CONF"
    python3 - "$1" "$2" "$LOGIN" <<'PY'
import json, os, sys
with open(sys.argv[3], "w") as f:
    json.dump({"username": sys.argv[1], "password": sys.argv[2]}, f)
os.chmod(sys.argv[3], 0o600)
PY
}

check_login() {  # user, password: true when Sunshine's web API accepts it
    python3 - "$1" "$2" <<'PY'
import base64, ssl, sys, urllib.request
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
auth = base64.b64encode(f"{sys.argv[1]}:{sys.argv[2]}".encode()).decode()
try:
    urllib.request.urlopen(urllib.request.Request("https://localhost:47990/api/apps", headers={"Authorization": "Basic " + auth}), context=ctx, timeout=5)
    sys.exit(0)
except Exception:
    sys.exit(1)
PY
}

ensure_login() {
    if [ -f "$LOGIN" ]; then say "Sunshine login: saved for the helper already."; return; fi
    if ! has_login; then
        # No login yet: make one, so nobody has to.
        local user=pocketxr pass
        pass="$(python3 -c 'import secrets; print(secrets.token_urlsafe(15))')"
        $(sunshine_cmd) --creds "$user" "$pass" >/dev/null 2>&1
        save_login "$user" "$pass"
        say "Made Sunshine's web login (user \"$user\", password \"$pass\"). The Pocket XR helper uses it to pair phones for you."
        return
    fi
    say "Pocket XR's helper can pair your phone with Sunshine for you (no PIN to type) if it has Sunshine's web login."
    for _ in 1 2 3; do
        read -r -p "Sunshine web login user name (leave empty to skip): " user
        [ -z "$user" ] && { say "Skipped: pairing will show a PIN to type in Sunshine's web page."; return; }
        read -r -s -p "Sunshine web login password: " pass; echo
        if check_login "$user" "$pass"; then save_login "$user" "$pass"; say "Saved for the helper (only you can read it)."; return; fi
        say "That login did not work (is Sunshine running?). Try again."
    done
}

install_linux_services() {
    mkdir -p "$SHARE" "$HOME/.config/systemd/user"
    cp "$HERE/pocketxr_pc_helper.py" "$SHARE/"
    cat > "$HOME/.config/systemd/user/pocketxr-pc-helper.service" <<EOF
[Unit]
Description=Pocket XR PC helper (PC screens)
After=graphical-session.target

[Service]
ExecStart=/usr/bin/env python3 $SHARE/pocketxr_pc_helper.py
Restart=on-failure

[Install]
WantedBy=default.target
EOF
    cat > "$HOME/.config/systemd/user/pocketxr-sunshine.service" <<EOF
[Unit]
Description=Sunshine for Pocket XR PC screens
After=graphical-session.target

[Service]
ExecStart=$(sunshine_cmd)
Restart=on-failure

[Install]
WantedBy=graphical-session.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable --now pocketxr-sunshine.service pocketxr-pc-helper.service
    say "Sunshine and the Pocket XR helper start at sign-in, and are running now."
    if flatpak info "$FLATPAK_ID" >/dev/null 2>&1; then
        say "One more step for the mouse and keyboard to reach this computer: Sunshine needs input permissions once."
        say "Run this and type your password (Steam Deck: set one first with passwd):"
        say "  flatpak run --command=additional-install.sh $FLATPAK_ID"
    fi
}

install_mac_agents() {
    mkdir -p "$SHARE" "$HOME/Library/LaunchAgents"
    cp "$HERE/pocketxr_pc_helper.py" "$SHARE/"
    local sun; sun="$(sunshine_cmd)"
    for label in com.hayeco.pocketxr.pc-helper com.hayeco.pocketxr.sunshine; do
        local plist="$HOME/Library/LaunchAgents/$label.plist" prog
        if [ "$label" = com.hayeco.pocketxr.pc-helper ]; then prog="<string>/usr/bin/python3</string><string>$SHARE/pocketxr_pc_helper.py</string>"
        else prog="<string>$sun</string>"; fi
        cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>$label</string>
<key>ProgramArguments</key><array>$prog</array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
</dict></plist>
EOF
        launchctl unload "$plist" 2>/dev/null; launchctl load "$plist"
    done
    say "Sunshine and the Pocket XR helper start at sign-in, and are running now."
    say "macOS asks once for Screen Recording and Accessibility for Sunshine (System Settings, Privacy & Security): allow both,"
    say "so the phone sees the screen and can use the mouse and keyboard."
}

remove() {
    if [ "$OS" = "Darwin" ]; then
        for label in com.hayeco.pocketxr.pc-helper com.hayeco.pocketxr.sunshine; do
            launchctl unload "$HOME/Library/LaunchAgents/$label.plist" 2>/dev/null; rm -f "$HOME/Library/LaunchAgents/$label.plist"
        done
    else
        systemctl --user disable --now pocketxr-pc-helper.service pocketxr-sunshine.service 2>/dev/null
        rm -f "$HOME/.config/systemd/user/pocketxr-pc-helper.service" "$HOME/.config/systemd/user/pocketxr-sunshine.service"
    fi
    rm -rf "$SHARE" "$LOGIN"
    say "The Pocket XR helper is removed and Sunshine no longer starts at sign-in. Sunshine itself stays installed."
}

[ "${1:-}" = "--remove" ] && { remove; exit 0; }
case "$OS" in Linux|Darwin) ;; *) die "This is for Linux and macOS. On Windows, use the Pocket XR PC helper." ;; esac
command -v python3 >/dev/null 2>&1 || die "Python 3 is needed (macOS: xcode-select --install; Linux: your package manager)."

say "Pocket XR PC screens setup ($OS)"
install_sunshine
if [ "$OS" = "Darwin" ]; then install_mac_agents; else install_linux_services; fi
sleep 3
ensure_login
say ""
say "Done. On your phone, open PC screen in Pocket XR and tap this computer, then click Allow here."
say "Away from home: install Tailscale on this computer and the phone (tailscale.com), signed in to the same account."
