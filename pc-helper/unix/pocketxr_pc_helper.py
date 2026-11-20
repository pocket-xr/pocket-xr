#!/usr/bin/env python3
"""Pocket XR PC helper for Linux and macOS (PC screens).

The Windows helper (../PocketXrPcHelper.cs) has a counterpart here, for a Linux PC, a Steam Deck in Desktop Mode or a
Mac that streams its screen to Pocket XR with Sunshine (Apollo is Windows-only). It speaks the same protocol on the
same port, so the phone needs nothing new:

  UDP 47610  "POCKETXR-FIND 1"  ->  "POCKETXR-HERE 1 47610 <computer name>"
  TCP 47610  one JSON line in, one out:
    {"op":"pair","phone":"<name>"}   asks this computer's user (a dialog); {"ok":true,"token":...} or {"ok":false,...}
    {"op":"apollo-pin","token","pin","name","port"}
        types the phone's pairing PIN into Sunshine's web API (Basic sign-in, the login the setup saved), so pairing
        needs no PIN typed; {"ok":true} or {"ok":false,"why":...}
    {"op":"screens-setup",...}       {"ok":false,"why":...}: on Linux and macOS the setup is pocketxr-pc-screens.sh
    {"op":"status"|"start",...}      PC VR is Windows-only: {"ok":true,"steam":false,...}

Phones are allowed once (tokens in ~/.config/pocketxr/pc-helper.json); the Sunshine login is in
~/.config/pocketxr/sunshine-login.json (this user only, mode 600). Python 3 standard library only.

Run: python3 pocketxr_pc_helper.py   (the setup starts it at sign-in: systemd user service, or a LaunchAgent)
"""

import base64
import json
import os
import platform
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

PORT = 47610
CONFIG_DIR = os.path.expanduser("~/.config/pocketxr")
TOKENS = os.path.join(CONFIG_DIR, "pc-helper.json")
LOGIN = os.path.join(CONFIG_DIR, "sunshine-login.json")
LOG = os.path.join(CONFIG_DIR, "pc-helper.log")
NAME = socket.gethostname().split(".")[0]
_lock = threading.Lock()


def log(text):
    try:
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ") + text + "\n")
    except OSError:
        pass


def load_tokens():
    try:
        with open(TOKENS, encoding="utf-8") as f:
            return json.load(f).get("phones", [])
    except (OSError, ValueError):
        return []


def known(token):
    return bool(token) and any(p.get("token") == token for p in load_tokens())


def add_phone(phone):
    with _lock:
        phones = load_tokens()
        token = secrets.token_hex(16)
        phones.append({"name": phone, "token": token})
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(TOKENS, "w", encoding="utf-8") as f:
            json.dump({"phones": phones}, f)
        os.chmod(TOKENS, 0o600)
        return token


def ask(question):
    """Yes or no from this computer's user: a dialog (osascript, zenity or kdialog); no dialog tool means no."""
    try:
        if platform.system() == "Darwin":
            script = 'display dialog %s buttons {"No", "Allow"} default button "Allow" with title "Pocket XR" giving up after 60' % json.dumps(question)
            r = subprocess.run(["osascript", "-e", script], capture_output=True, text=True, timeout=70)
            return "Allow" in r.stdout
        if shutil.which("zenity"):
            return subprocess.run(["zenity", "--question", "--title=Pocket XR", "--text=" + question, "--timeout=60"], timeout=70).returncode == 0
        if shutil.which("kdialog"):
            return subprocess.run(["kdialog", "--title", "Pocket XR", "--yesno", question], timeout=70).returncode == 0
    except (OSError, subprocess.SubprocessError) as e:
        log("ask: %s" % e)
    log("no dialog tool: pairing refused")
    return False


def pick_pairing(pairings, phone_address):
    """The waiting pairing to give the PIN to: the one from the asking phone's address, else the only one waiting."""
    if not isinstance(pairings, list):
        return None
    mine = [p for p in pairings if isinstance(p, dict) and p.get("address") == phone_address and p.get("id")]
    if mine:
        return mine[-1]["id"]
    ids = [p.get("id") for p in pairings if isinstance(p, dict) and p.get("id")]
    return ids[0] if len(ids) == 1 else None


def sunshine_pin(pin, name, port, phone_address=None):
    """Types [pin] into Sunshine's web API on [port] + 1 (Basic sign-in with the saved login).

    Newer Sunshine (seen in 2026.914) lists the phones waiting to pair at GET /api/pin
    ({"pairings":[{"address","id","name"}]}) and wants {"pairing_id","pin","name"}; without pairing_id it answers 400.
    Older Sunshine takes {"pin","name"} alone. The phone starts pairing a few seconds after its user taps Allow, so
    this waits up to about 40 s for it to show up."""
    try:
        with open(LOGIN, encoding="utf-8") as f:
            login = json.load(f)
    except (OSError, ValueError):
        return {"ok": False, "why": "no Sunshine login saved: run pocketxr-pc-screens.sh"}
    if not pin:
        return {"ok": False, "why": "no PIN"}
    auth = base64.b64encode(("%s:%s" % (login["username"], login["password"])).encode()).decode()
    # Sunshine's own self-signed certificate, on this computer's own address only.
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    url = "https://localhost:%d/api/pin" % (port + 1)

    def call(method, body=None):
        req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                     headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=5) as r:
                return r.status, json.loads(r.read().decode() or "{}")
        except urllib.error.HTTPError as e:
            try:  # the body may be JSON, HTML or nothing at all
                data = json.loads(e.read().decode() or "{}")
                return e.code, data if isinstance(data, dict) else {}
            except Exception:
                return e.code, {}

    def clear_earlier(phone_name):
        # Sunshine 2026.914 adds a record each time a phone pairs, even with a certificate it already has, and lets a
        # certificate in only when exactly one record matches it: a phone paired again was refused with "Client
        # certificate identity is not enabled". So the phone's earlier records (the name given here) go first.
        req = urllib.request.Request(url.replace("/api/pin", "/api/clients/list"), headers={"Authorization": "Basic " + auth})
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=5) as r:
                clients = json.loads(r.read().decode() or "{}").get("named_certs", [])
        except Exception as e:
            log("clients: %s" % e)
            return
        for c in clients:
            if isinstance(c, dict) and c.get("name") == phone_name and c.get("uuid"):
                body = json.dumps({"uuid": c["uuid"]}).encode()
                req = urllib.request.Request(url.replace("/api/pin", "/api/clients/unpair"), data=body, method="POST",
                                             headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"})
                try:
                    with urllib.request.urlopen(req, context=ctx, timeout=5) as r:
                        log("earlier pairing of %s (%s) removed: %s" % (phone_name, c["uuid"], r.read().decode()))
                except Exception as e:
                    log("unpair: %s" % e)

    last = ""
    cleared = False
    for _ in range(40):
        try:
            code, listed = call("GET")
            if code == 200 and isinstance(listed, dict) and "pairings" in listed:
                pairing = pick_pairing(listed["pairings"], phone_address)
                if pairing is None:
                    last = "no phone waiting to pair yet (%d waiting)" % len(listed["pairings"] or [])
                    time.sleep(1)
                    continue
                if not cleared:
                    cleared = True
                    clear_earlier(name)
                code, answer = call("POST", {"pairing_id": pairing, "pin": pin, "name": name})
            else:  # an older Sunshine, without the list
                code, answer = call("POST", {"pin": pin, "name": name})
            if answer.get("status") in (True, "true"):
                log("PIN typed for %s on port %d" % (name, port))
                return {"ok": True}
            last = "HTTP %d %s" % (code, answer.get("error", ""))
        except Exception as e:  # Sunshine is starting, or not there
            last = str(e)
        time.sleep(1)
    log("pin: %s" % last)
    return {"ok": False, "why": "Sunshine did not take the PIN (%s)" % last}


def answer(request, phone_address=None):
    op = request.get("op")
    if op == "pair":
        phone = request.get("phone") or "A phone"
        if not ask("%s wants to use this computer with Pocket XR (PC screens).\n\nAllow it?" % phone):
            return {"ok": False, "why": "not allowed on the PC"}
        log("paired " + phone)
        return {"ok": True, "token": add_phone(phone), "pc": NAME}
    if not known(request.get("token")):
        return {"ok": False, "why": "not paired"}
    if op == "apollo-pin":
        return sunshine_pin(request.get("pin"), request.get("name") or "Pocket XR", int(request.get("port") or 47989), phone_address)
    if op == "screens-setup":
        return {"ok": False, "why": "on Linux and macOS, run pocketxr-pc-screens.sh on the computer"}
    if op in ("status", "start"):
        return {"ok": True, "pc": NAME, "steam": False, "alvr": False, "steamvr": "off"}
    return {"ok": False, "why": "unknown request"}


def serve_tcp():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(8)
    while True:
        conn, peer = srv.accept()
        threading.Thread(target=client, args=(conn, peer[0]), daemon=True).start()


def client(conn, phone_address):
    with conn:
        try:
            conn.settimeout(130)
            line = conn.makefile("r", encoding="utf-8").readline()
            reply = answer(json.loads(line or "{}"), phone_address)
            conn.sendall((json.dumps(reply) + "\n").encode())
        except Exception as e:
            log("tcp: %s" % e)


def serve_udp():
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    udp.bind(("0.0.0.0", PORT))
    while True:
        data, sender = udp.recvfrom(256)
        if data.startswith(b"POCKETXR-FIND"):
            udp.sendto(("POCKETXR-HERE 1 %d %s" % (PORT, NAME)).encode(), sender)


def main():
    log("started on %s (%s)" % (NAME, platform.system()))
    threading.Thread(target=serve_udp, daemon=True).start()
    serve_tcp()


if __name__ == "__main__":
    if "--answer" in sys.argv:  # for tests: one request on stdin, the answer on stdout
        print(json.dumps(answer(json.loads(sys.stdin.read()))))
    else:
        main()
