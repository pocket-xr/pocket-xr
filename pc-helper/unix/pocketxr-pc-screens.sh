#!/usr/bin/env bash
# Pocket XR PC screens setup for Linux (a PC, or a Steam Deck in Desktop Mode) and macOS.
#
# On Windows, the Pocket XR PC helper does this (Apollo, up to three screens). Apollo is Windows-only, so here the
# computer streams its screen with Sunshine (free, open source, same protocol).
#
# Linux with the Pocket XR PC Screens app (PocketXR-PC-Screens.flatpak: Sunshine with one patch, see sunshine/):
# the app's menu entry runs this with --gui (dialogs, no terminal). On KDE Plasma (Wayland) it runs one Sunshine per
# PC screen (three, ports 47989, 48989, 49989), each making its own virtual screen at the phone window's size while it
# streams. Elsewhere, one Sunshine streams the desktop. It:
#   1. lets Sunshine's virtual mouse, keyboard and gamepads in (one password prompt), if they are not allowed yet;
#   2. gives the Sunshines one web login (saved for the helper) and one certificate, so a phone paired with one is
#      let in by the others when the helper copies the pairing;
#   3. installs the Pocket XR PC helper and starts it and the Sunshines at sign-in (systemd user services).
#
# Linux without the app, and macOS: one PC screen (the computer's own desktop). Run it once in a terminal:
#   bash pocketxr-pc-screens.sh
# It installs Sunshine if it is missing (Linux: Flathub's Flatpak; macOS: Sunshine's own installer into
# ~/Applications), gives it a web login, installs the helper, and says what is left (input permissions, or macOS's
# Screen Recording and Accessibility).
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
APP_ID="com.hayeco.pocketxr.PCScreens"   # Pocket XR PC Screens (our Sunshine), see sunshine/
GUI=0
[ "${1:-}" = "--gui" ] && { GUI=1; shift; }
APP="${POCKETXR_APP:-}"
if [ -z "$APP" ] && [ "$OS" = "Linux" ] && flatpak info "$APP_ID" >/dev/null 2>&1; then APP="$APP_ID"; fi

say() { printf '%s\n' "$*"; [ "$GUI" = 1 ] && { mkdir -p "$CONF"; printf '%s %s\n' "$(date +%T)" "$*" >> "$CONF/setup.log"; }; return 0; }
dialog() {  # kind (info, error, yesno), text: a dialog in --gui mode, else the terminal
    local kind="$1" text="$2"
    if [ "$GUI" = 1 ]; then
        if command -v kdialog >/dev/null 2>&1; then
            case "$kind" in info) kdialog --title "Pocket XR PC Screens" --msgbox "$text" ;; error) kdialog --title "Pocket XR PC Screens" --error "$text" ;; yesno) kdialog --title "Pocket XR PC Screens" --yesno "$text" ;; esac
            return
        elif command -v zenity >/dev/null 2>&1; then
            case "$kind" in info) zenity --info --title="Pocket XR PC Screens" --text="$text" ;; error) zenity --error --title="Pocket XR PC Screens" --text="$text" ;; yesno) zenity --question --title="Pocket XR PC Screens" --text="$text" ;; esac
            return
        fi
    fi
    say "$text"
    [ "$kind" != yesno ] || { read -r -p "Go ahead? [Y/n] " a; [ -z "$a" ] || [ "$a" = y ] || [ "$a" = Y ]; }
}
die() { say "Setup stopped: $*"; [ "$GUI" = 1 ] && dialog error "Setup stopped: $*"; exit 1; }

SUNSHINE_RELEASES="https://api.github.com/repositories/438803890/releases/latest"  # by id: follows the repo's moves

sunshine_cmd() {
    # How to run Sunshine here: the Flatpak, Sunshine.app (the Mac installer), a system install, or Homebrew's.
    if [ "$OS" = "Linux" ] && flatpak info "$FLATPAK_ID" >/dev/null 2>&1; then echo "flatpak run $FLATPAK_ID"; return; fi
    if [ "$OS" = "Darwin" ]; then
        for app in "$HOME/Applications/Sunshine.app" "/Applications/Sunshine.app"; do
            [ -x "$app/Contents/MacOS/Sunshine" ] && { echo "$app/Contents/MacOS/Sunshine"; return; }
        done
    fi
    if command -v sunshine >/dev/null 2>&1; then command -v sunshine; else echo ""; fi
}

install_sunshine_mac() {
    # Sunshine's own Mac installer from its latest GitHub release, into ~/Applications (no admin password).
    local arch url dir mnt
    case "$(uname -m)" in x86_64) arch=x86_64 ;; arm64) arch=arm64 ;; *) die "Sunshine has no Mac installer for $(uname -m)." ;; esac
    url="$(curl -fsSL "$SUNSHINE_RELEASES" | python3 -c '
import json, sys
want = "Sunshine-macOS-%s.dmg" % sys.argv[1]
print(next((a["browser_download_url"] for a in json.load(sys.stdin).get("assets", []) if a.get("name") == want), ""))
' "$arch")"
    [ -n "$url" ] || die "Could not find Sunshine's Mac installer on GitHub (Sunshine-macOS-$arch.dmg). Check the network, then run this again."
    dir="$(mktemp -d)"; mnt="$dir/mnt"; mkdir "$mnt"
    say "Downloading $url"
    curl -fL --progress-bar -o "$dir/Sunshine.dmg" "$url" || { rm -rf "$dir"; die "The download failed."; }
    hdiutil attach -nobrowse -readonly -mountpoint "$mnt" "$dir/Sunshine.dmg" >/dev/null || { rm -rf "$dir"; die "Could not open the Sunshine installer."; }
    if [ ! -d "$mnt/Sunshine.app" ]; then
        hdiutil detach "$mnt" >/dev/null; rm -rf "$dir"; die "The Sunshine installer has no Sunshine.app in it."
    fi
    if ! spctl --assess --type execute "$mnt/Sunshine.app" 2>/dev/null; then
        hdiutil detach "$mnt" >/dev/null; rm -rf "$dir"; die "macOS did not accept this Sunshine download (Gatekeeper), so it was not installed."
    fi
    mkdir -p "$HOME/Applications"
    rm -rf "$HOME/Applications/Sunshine.app"
    ditto "$mnt/Sunshine.app" "$HOME/Applications/Sunshine.app"
    hdiutil detach "$mnt" >/dev/null; rm -rf "$dir"
}

install_sunshine() {
    if [ -n "$(sunshine_cmd)" ]; then say "Sunshine is already installed: kept as it is."; return; fi
    say "Installing Sunshine (free, open source), which streams this computer's screen to Pocket XR..."
    if [ "$OS" = "Darwin" ]; then
        install_sunshine_mac
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

check_login() {  # user, password[, web port]: true when Sunshine's web API accepts it
    python3 - "$1" "$2" "${3:-47990}" <<'PY'
import base64, ssl, sys, urllib.request
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
auth = base64.b64encode(f"{sys.argv[1]}:{sys.argv[2]}".encode()).decode()
try:
    urllib.request.urlopen(urllib.request.Request("https://localhost:%s/api/apps" % sys.argv[3], headers={"Authorization": "Basic " + auth}), context=ctx, timeout=5)
    sys.exit(0)
except Exception:
    sys.exit(1)
PY
}

make_login() {
    # No login yet: make one, so nobody has to. Before Sunshine is started: a --creds run while a freshly started
    # Sunshine sets itself up was lost (seen on macOS 14, Sunshine 2026.914), and Sunshine reads it when it starts.
    if [ -f "$LOGIN" ] || has_login; then return; fi
    local user=pocketxr pass
    pass="$(python3 -c 'import secrets; print(secrets.token_urlsafe(15))')"
    $(sunshine_cmd) --creds "$user" "$pass" >/dev/null 2>&1
    # The Flatpak keeps its state elsewhere, so only the Mac's is checked here; ensure_login checks it works anyway.
    if [ "$OS" = "Darwin" ] && ! has_login; then die "Sunshine did not save its web login."; fi
    save_login "$user" "$pass"
    say "Made Sunshine's web login (user \"$user\", password \"$pass\"). The Pocket XR helper uses it to pair phones for you."
}

ensure_login() {
    # Sunshine had a login of its own already: ask for it, so the helper can pair phones (Sunshine is running now).
    if [ -f "$LOGIN" ]; then
        local u p
        u="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["username"])' "$LOGIN")"
        p="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["password"])' "$LOGIN")"
        if check_login "$u" "$p"; then say "Sunshine login: saved for the helper, and it works."
        else say "Sunshine login: saved for the helper, but Sunshine did not accept it yet (still starting?)."; fi
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
    # macOS does not ask for Accessibility (seen on macOS 14: tccd "kTCCServicePostEvent does not allow prompting;
    # returning denied"), so without this the phone's clicks and typing are dropped with no message anywhere.
    say ""
    say "One more step on this Mac, so the phone sees the screen and its mouse and keyboard work:"
    say "  1. System Settings > Privacy & Security > Screen Recording: switch on Sunshine."
    say "  2. System Settings > Privacy & Security > Accessibility: switch on Sunshine."
    say "     If Sunshine is not in a list, click +, press Cmd+Shift+G, type ~/Applications and choose Sunshine."
    say "  3. Then restart Sunshine:  launchctl kickstart -k gui/\$(id -u)/com.hayeco.pocketxr.sunshine"
    open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" 2>/dev/null || true
}

# ---- Linux with the Pocket XR PC Screens app: one Sunshine per PC screen ----

UNITS="$HOME/.config/systemd/user"
port_of() { echo $((47989 + ($1 - 1) * 1000)); }   # PC screen 1, 2, 3: 47989, 48989, 49989 (as Apollo on Windows)

virtual_screens_here() {
    # KWin makes a virtual output for each stream (the app's patch); other desktops are not done yet.
    case "${XDG_CURRENT_DESKTOP:-}" in *KDE*) [ "${XDG_SESSION_TYPE:-}" = wayland ] ;; *) return 1 ;; esac
}

input_permissions() {
    # Sunshine's additional-install.sh asks for the password nine times (one pkexec per step): here, all its steps
    # run under one prompt. Skipped when the virtual mouse and keyboard (uinput) and gamepads (uhid) are allowed.
    if [ -w /dev/uinput ] && [ -w /dev/uhid ]; then say "Input permissions: already allowed."; return; fi
    local files tmp
    files="$(flatpak info --show-location "$APP")/files/share/sunshine"
    [ -f "$files/udev/rules.d/60-sunshine.rules" ] || { say "Input permissions: the app has no udev rule (skipped)."; return; }
    if passwd -S "$USER" 2>/dev/null | awk '{exit !($2 == "NP")}'; then
        dialog info "This computer's user has no password, so the next step cannot ask for one. The phone's mouse and keyboard may not work until it is done.

Set a password (Steam Deck: open Konsole and type passwd), then open Pocket XR PC Screens again."
        return
    fi
    dialog info "Next, your computer asks for your password once, to let the phone's mouse, keyboard and game controllers in (Sunshine's input permissions)." || true
    tmp="$(mktemp)"
    cat > "$tmp" <<EOF
set -e
install -m 644 '$files/udev/rules.d/60-sunshine.rules' /etc/udev/rules.d/60-sunshine.rules
install -m 644 '$files/modules-load.d/60-sunshine.conf' /etc/modules-load.d/60-sunshine.conf
modprobe uhid || true
udevadm control --reload-rules
udevadm trigger --property-match=DEVNAME=/dev/uinput
udevadm trigger --property-match=DEVNAME=/dev/uhid
udevadm trigger --subsystem-match=hidraw
udevadm trigger --subsystem-match=input
EOF
    if pkexec sh "$tmp"; then say "Input permissions: set."; else say "Input permissions: not set (the password prompt was closed or refused)."; fi
    rm -f "$tmp"
}

kwin_permission() {
    # KWin lets a sandboxed app use its screencast protocol (what makes the virtual screens) when the app's desktop
    # file has X-KDE-Wayland-Interfaces=zkde_screencast_unstable_v1. The app's own file has it, but Flatpak leaves the
    # key out of the copy it exports (seen with Flatpak 1.18.4 on SteamOS), and Sunshine's own permission file lands
    # inside the sandbox, where KWin does not look. So a user copy of the exported file, with the key, goes beside it
    # (same name: it takes the exported one's place in the menu too).
    local src dst="$HOME/.local/share/applications/$APP.desktop"
    for src in "$HOME/.local/share/flatpak/exports/share/applications/$APP.desktop" "/var/lib/flatpak/exports/share/applications/$APP.desktop"; do
        [ -f "$src" ] && break
    done
    [ -f "$src" ] || { say "KWin permission: the app's desktop file was not found (virtual screens may be refused)."; return; }
    mkdir -p "$(dirname "$dst")"
    grep -v '^X-KDE-Wayland-Interfaces=' "$src" | sed '/^\[Desktop Entry\]/a X-KDE-Wayland-Interfaces=zkde_screencast_unstable_v1' > "$dst"
    command -v kbuildsycoca6 >/dev/null 2>&1 && kbuildsycoca6 >/dev/null 2>&1
    command -v kbuildsycoca5 >/dev/null 2>&1 && ! command -v kbuildsycoca6 >/dev/null 2>&1 && kbuildsycoca5 >/dev/null 2>&1
    say "KWin permission: $dst"
}

write_instance() {  # PC screen number, virtual (1 or 0)
    local n="$1" d="$CONF/sunshine-$1" name
    name="${HOSTNAME:-$(uname -n)}"; name="${name%%.*}"
    [ "$n" -gt 1 ] && name="$name screen $n"   # Pocket XR groups "<PC> screen N" under the PC's own name
    mkdir -p "$d"
    {
        echo "# Written by the Pocket XR PC screens setup (PC screen $n). Running the setup again writes it again."
        echo "sunshine_name = $name"
        echo "port = $(port_of "$n")"
        echo "pkey = $CONF/sunshine-key.pem"
        echo "cert = $CONF/sunshine-cert.pem"
        echo "file_state = $d/sunshine_state.json"
        echo "credentials_file = $d/sunshine_credentials.json"
        echo "file_apps = $d/apps.json"
        echo "log_path = $d/sunshine.log"
        echo "system_tray = disabled"   # three Sunshines would be three tray icons
        if [ "$2" = 1 ]; then echo "capture = kwin"; echo "kwin_virtual_output = enabled"; fi
    } > "$d/sunshine.conf"
    [ -f "$d/apps.json" ] || printf '{\n  "env": {},\n  "apps": [\n    {\n      "name": "Desktop",\n      "image-path": "desktop.png"\n    }\n  ]\n}\n' > "$d/apps.json"
}

# --die-with-parent: flatpak run puts Sunshine in a scope of its own, so stopping the service left it running (seen on
# the Deck: a stop and start made a second Sunshine per PC screen, each with its own tray icon)
app_sunshine() { echo "$(command -v flatpak) run --die-with-parent --command=sunshine $APP"; }

setup_linux_app() {
    local screens=1 virtual=0 n user pass sun
    if virtual_screens_here; then screens=3; virtual=1; fi
    if [ "$GUI" = 1 ] && [ ! -f "$UNITS/pocketxr-sunshine@.service" ]; then
        dialog yesno "Set up this computer for Pocket XR PC screens?

Your phone will be able to show $([ $screens = 3 ] && echo "up to three screens from it, each its own virtual screen at the window's size" || echo "its desktop") in Pocket XR. It starts by itself when you sign in." || exit 0
    fi
    say "Pocket XR PC screens setup (Linux, $APP, $screens PC screen$([ $screens = 1 ] || echo s))"
    sun="$(app_sunshine)"
    input_permissions
    [ "$virtual" = 1 ] && kwin_permission
    [ "$GUI" = 1 ] && command -v kdialog >/dev/null 2>&1 && kdialog --title "Pocket XR PC Screens" --passivepopup "Setting up... (about a minute)" 30 &

    # One login for all: made before the Sunshines start (a --creds run while Sunshine sets itself up was lost)
    if [ -f "$LOGIN" ]; then
        user="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["username"])' "$LOGIN")"
        pass="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["password"])' "$LOGIN")"
    else
        user=pocketxr; pass="$(python3 -c 'import secrets; print(secrets.token_urlsafe(15))')"
        save_login "$user" "$pass"
    fi

    # The one-screen setup (Flathub's Sunshine) goes, if it was set up before
    if [ -f "$UNITS/pocketxr-sunshine.service" ]; then
        systemctl --user disable --now pocketxr-sunshine.service 2>/dev/null
        rm -f "$UNITS/pocketxr-sunshine.service"
        say "The earlier one-screen Sunshine service is replaced."
    fi
    mkdir -p "$UNITS" "$SHARE"
    for n in 1 2 3; do systemctl --user stop "pocketxr-sunshine@$n.service" 2>/dev/null; done
    flatpak kill "$APP" 2>/dev/null   # any left over from an earlier setup (see app_sunshine)
    for n in $(seq 1 "$screens"); do
        write_instance "$n" "$virtual"
        # Each time (the Sunshines are stopped): a login made again after --remove must reach every Sunshine
        $sun "$CONF/sunshine-$n/sunshine.conf" --creds "$user" "$pass" >/dev/null 2>&1
    done
    for n in 2 3; do [ "$n" -le "$screens" ] || systemctl --user disable "pocketxr-sunshine@$n.service" 2>/dev/null; done
    printf '{"app": "%s", "ports": [%s], "virtual": %s}\n' "$APP" \
        "$(for n in $(seq 1 "$screens"); do printf '%s%s' "$([ "$n" = 1 ] || echo ', ')" "$(port_of "$n")"; done)" \
        "$([ "$virtual" = 1 ] && echo true || echo false)" > "$CONF/screens.json"

    [ -f "$HERE/pocketxr_pc_helper.py" ] && [ "$HERE" != "$SHARE" ] && cp "$HERE/pocketxr_pc_helper.py" "$SHARE/"
    cat > "$UNITS/pocketxr-sunshine@.service" <<EOF
[Unit]
Description=Sunshine for Pocket XR PC screen %i
After=graphical-session.target
PartOf=graphical-session.target

[Service]
ExecStart=$sun %h/.config/pocketxr/sunshine-%i/sunshine.conf
Restart=on-failure
RestartSec=5

[Install]
WantedBy=graphical-session.target
EOF
    cat > "$UNITS/pocketxr-pc-helper.service" <<EOF
[Unit]
Description=Pocket XR PC helper (PC screens)
After=graphical-session.target

[Service]
ExecStart=/usr/bin/env python3 $SHARE/pocketxr_pc_helper.py
Restart=on-failure

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    # PC screen 1 first: it makes the certificate the others share
    systemctl --user enable --now pocketxr-sunshine@1.service
    for _ in $(seq 1 30); do [ -f "$CONF/sunshine-cert.pem" ] && break; sleep 1; done
    for n in $(seq 2 "$screens"); do systemctl --user enable --now "pocketxr-sunshine@$n.service"; done
    systemctl --user enable pocketxr-pc-helper.service
    systemctl --user restart pocketxr-pc-helper.service
    sleep 5
    local ok=0
    for n in $(seq 1 "$screens"); do
        if check_login "$user" "$pass" "$(( $(port_of "$n") + 1 ))"; then ok=$((ok + 1)); say "PC screen $n: Sunshine is running (port $(port_of "$n"))."
        else say "PC screen $n: Sunshine did not answer yet (port $(port_of "$n"); log: $CONF/sunshine-$n/sunshine.log)."; fi
    done
    dialog info "Done: $ok of $screens Sunshine$([ $screens = 1 ] || echo s) running, and the Pocket XR helper.

On your phone, open PC screen in Pocket XR and tap this computer, then click Allow here.$([ $screens = 3 ] && echo "

Each PC screen gets its own virtual screen on this computer while it streams.")"
}

remove() {
    if [ -n "$APP" ] || [ -f "$UNITS/pocketxr-sunshine@.service" ]; then
        for n in 1 2 3; do systemctl --user disable --now "pocketxr-sunshine@$n.service" 2>/dev/null; done
        flatpak kill "$APP_ID" 2>/dev/null
        rm -f "$UNITS/pocketxr-sunshine@.service" "$CONF/screens.json" "$HOME/.local/share/applications/$APP_ID.desktop"
    fi
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

if [ "$OS" = "Linux" ] && [ -n "$APP" ]; then setup_linux_app; exit 0; fi
say "Pocket XR PC screens setup ($OS)"
install_sunshine
make_login
if [ "$OS" = "Darwin" ]; then install_mac_agents; else install_linux_services; fi
sleep 5
ensure_login
say ""
say "Done. On your phone, open PC screen in Pocket XR and tap this computer, then click Allow here."
say "Away from home: install Tailscale on this computer and the phone (tailscale.com), signed in to the same account."
