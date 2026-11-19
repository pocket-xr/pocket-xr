@echo off
rem Pocket XR PC screens setup: runs PocketXR-PC-Screens.ps1 next to this file (it asks Windows for admin rights).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0PocketXR-PC-Screens.ps1"
