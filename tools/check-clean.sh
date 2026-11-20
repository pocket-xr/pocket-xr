#!/usr/bin/env bash
# Fails when the source names another product as the origin of code, values or text, or carries private details.
# Pocket XR is written from scratch (CONTRIBUTING.md, "Our own work only"); this catches the obvious slips. Run from the
# repository root; CI runs it on every pull request.
set -euo pipefail

# Patterns that must not appear in app/, core/ or docs/. Case-insensitive, extended regex.
banned='nebula|decompil|il2cpp|\bvtable|\bPDB\b|agent read|\.java:[0-9]|xreal.?s runtime|\bnrsdk\b|NRGlasses|docs/(research|diagnostics|findings|logs|emulator-shots|audit)|DESIGN\.md|HAND_INPUT_RESEARCH|OPEN-SOURCE-PLAN|CHANGES-0\.|C:\\Users\\'

# Allowed: the saved preference key kept so existing settings carry over, and captured glasses log lines used as test
# data (they are the glasses' own output, not our code).
allowed='"warp_nebula_0532"|NRFlingerInterface|FlingerCtrl::'

# Moonlight's own source, included unchanged and credited (docs/LICENSES.md), is third-party code, not ours: its
# controller list names a "Nebula" gamepad. Our code that uses it is still checked.
hits=$(grep -rnIiE "$banned" app core docs --exclude-dir=moonlight 2>/dev/null | grep -viE "$allowed" || true)
if [ -n "$hits" ]; then
  echo "check-clean: these lines name another product as a source, or carry private details:"
  echo "$hits"
  echo
  echo "Rewrite them in the project's own words (see CONTRIBUTING.md, Code style)."
  exit 1
fi
echo "check-clean: OK"
