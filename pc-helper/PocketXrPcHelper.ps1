# Pocket XR PC helper, version 0 (private; docs/PCVR-PLAN.md step 6).
#
# A small tray program on the gaming PC. Pocket XR's PC VR tile finds it on the local network and asks it to start
# SteamVR, so nobody has to touch the PC. It does nothing else.
#
# Protocol (port 47610, UDP and TCP, local network only):
#   UDP   phone broadcasts "POCKETXR-FIND 1"; the helper answers "POCKETXR-HERE 1 <tcp port> <PC name>".
#   TCP   one JSON line in, one JSON line out, then the connection closes.
#         {"op":"pair","phone":"<name>"}       the PC asks its user; {"ok":true,"token":"..."} or {"ok":false,"why":"..."}
#         {"op":"status","token":"..."}        {"ok":true,"pc":"<name>","steamvr":"off|starting|ready"}
#         {"op":"start","token":"..."}         starts SteamVR if it is off, then answers as status does
# A phone must be allowed once on the PC (pair); after that its token is enough. Tokens live in
# %APPDATA%\PocketXR\pc-helper.json and "Forget phones" in the tray menu clears them.
#
# Run: powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File PocketXrPcHelper.ps1

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$Port = 47610
$AppName = 'Pocket XR PC helper'
$ConfigDir = Join-Path $env:APPDATA 'PocketXR'
$ConfigPath = Join-Path $ConfigDir 'pc-helper.json'
$LogPath = Join-Path $ConfigDir 'pc-helper.log'
$StartupLink = Join-Path ([Environment]::GetFolderPath('Startup')) 'Pocket XR PC helper.lnk'

# One helper at a time.
$created = $false
$mutex = New-Object System.Threading.Mutex($true, 'Global\PocketXrPcHelper', [ref]$created)
if (-not $created) { exit }

New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null

# Shared between the tray (this thread) and the network loops.
$shared = [hashtable]::Synchronized(@{
    Running      = $true
    ConfigPath   = $ConfigPath
    LogPath      = $LogPath
    Port         = $Port
    PcName       = $env:COMPUTERNAME
    PairRequest  = $null    # a phone's name, waiting for the PC's user
    PairAnswer   = $null    # $true or $false once answered
    StartedAt    = [DateTime]::MinValue
    Notice       = $null    # a balloon for the tray to show
})

# The network side, in its own runspace so the tray stays responsive.
$network = {
    param($shared)

    function Log($text) {
        try { Add-Content -Path $shared.LogPath -Value ("{0:HH:mm:ss} {1}" -f (Get-Date), $text) } catch {}
    }

    function Load-Config {
        if (Test-Path $shared.ConfigPath) {
            try { return (Get-Content $shared.ConfigPath -Raw | ConvertFrom-Json) } catch {}
        }
        return [pscustomobject]@{ phones = @() }
    }

    function Save-Config($config) {
        $config | ConvertTo-Json -Depth 4 | Set-Content -Path $shared.ConfigPath -Encoding UTF8
    }

    function Known($token) {
        if (-not $token) { return $false }
        foreach ($p in @((Load-Config).phones)) { if ($p.token -eq $token) { return $true } }
        return $false
    }

    # off: SteamVR isn't running. starting: it is, but ALVR's driver isn't answering yet. ready: ALVR answers.
    function SteamVrState {
        $server = Get-Process vrserver -ErrorAction SilentlyContinue
        if (-not $server) {
            if (((Get-Date) - $shared.StartedAt).TotalSeconds -lt 30) { return 'starting' }
            return 'off'
        }
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $wait = $client.BeginConnect('127.0.0.1', 8082, $null, $null)
            if ($wait.AsyncWaitHandle.WaitOne(300) -and $client.Connected) { return 'ready' }
        } catch {} finally { $client.Close() }
        return 'starting'
    }

    function Start-SteamVr {
        if (Get-Process vrserver -ErrorAction SilentlyContinue) { return }
        if (((Get-Date) - $shared.StartedAt).TotalSeconds -lt 30) { return }
        $shared.StartedAt = Get-Date
        Log 'starting SteamVR for the phone'
        Start-Process 'steam://rungameid/250820'
    }

    function Answer($request) {
        switch ($request.op) {
            'pair' {
                $name = [string]$request.phone
                if (-not $name) { $name = 'A phone' }
                $shared.PairAnswer = $null
                $shared.PairRequest = $name
                $deadline = (Get-Date).AddSeconds(60)
                while ($shared.PairAnswer -eq $null -and (Get-Date) -lt $deadline -and $shared.Running) { Start-Sleep -Milliseconds 200 }
                $allowed = $shared.PairAnswer -eq $true
                $shared.PairRequest = $null
                if (-not $allowed) {
                    Log "pairing refused for $name"
                    return @{ ok = $false; why = 'not allowed on the PC' }
                }
                $token = [Guid]::NewGuid().ToString('N')
                $config = Load-Config
                $config.phones = @(@($config.phones) + [pscustomobject]@{ name = $name; token = $token })
                Save-Config $config
                Log "paired $name"
                $shared.Notice = "$name can now start SteamVR on this PC."
                return @{ ok = $true; token = $token; pc = $shared.PcName }
            }
            'status' {
                if (-not (Known $request.token)) { return @{ ok = $false; why = 'not paired' } }
                return @{ ok = $true; pc = $shared.PcName; steamvr = (SteamVrState) }
            }
            'start' {
                if (-not (Known $request.token)) { return @{ ok = $false; why = 'not paired' } }
                Start-SteamVr
                return @{ ok = $true; pc = $shared.PcName; steamvr = (SteamVrState) }
            }
            default { return @{ ok = $false; why = 'unknown request' } }
        }
    }

    # UDP: answer phones looking for this PC.
    $udp = New-Object System.Net.Sockets.UdpClient($shared.Port)
    $udp.Client.ReceiveTimeout = 500
    # TCP: one request per connection.
    $tcp = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $shared.Port)
    $tcp.Start()
    Log "listening on port $($shared.Port)"

    while ($shared.Running) {
        try {
            $from = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            $data = $udp.Receive([ref]$from)
            $text = [Text.Encoding]::ASCII.GetString($data)
            if ($text -like 'POCKETXR-FIND*') {
                $reply = [Text.Encoding]::UTF8.GetBytes("POCKETXR-HERE 1 $($shared.Port) $($shared.PcName)")
                [void]$udp.Send($reply, $reply.Length, $from)
            }
        } catch [System.Net.Sockets.SocketException] {
        } catch { Log "udp: $($_.Exception.Message)" }

        while ($tcp.Pending()) {
            $client = $tcp.AcceptTcpClient()
            try {
                $client.ReceiveTimeout = 3000
                $stream = $client.GetStream()
                $reader = New-Object System.IO.StreamReader($stream)
                $writer = New-Object System.IO.StreamWriter($stream)
                $writer.AutoFlush = $true
                $line = $reader.ReadLine()
                $request = $line | ConvertFrom-Json
                $response = Answer $request
                $writer.WriteLine(($response | ConvertTo-Json -Compress))
            } catch {
                Log "tcp: $($_.Exception.Message)"
            } finally { $client.Close() }
        }
    }
    $udp.Close()
    $tcp.Stop()
}

$runspace = [runspacefactory]::CreateRunspace()
$runspace.Open()
$worker = [powershell]::Create()
$worker.Runspace = $runspace
[void]$worker.AddScript($network).AddArgument($shared)
$async = $worker.BeginInvoke()

# The tray.
$icon = New-Object System.Windows.Forms.NotifyIcon
$icon.Icon = [System.Drawing.SystemIcons]::Application
$icon.Text = $AppName
$icon.Visible = $true

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$title = $menu.Items.Add($AppName)
$title.Enabled = $false
$state = $menu.Items.Add('SteamVR: checking')
$state.Enabled = $false
[void]$menu.Items.Add('-')
$startNow = $menu.Items.Add('Start SteamVR now')
$startNow.add_Click({ Start-Process 'steam://rungameid/250820' })
$autostart = New-Object System.Windows.Forms.ToolStripMenuItem('Start with Windows')
$autostart.Checked = Test-Path $StartupLink
$autostart.add_Click({
    if (Test-Path $StartupLink) {
        Remove-Item $StartupLink -Force
    } else {
        $shell = New-Object -ComObject WScript.Shell
        $link = $shell.CreateShortcut($StartupLink)
        $link.TargetPath = 'powershell.exe'
        $link.Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`""
        $link.WindowStyle = 7
        $link.Save()
    }
    $autostart.Checked = Test-Path $StartupLink
})
[void]$menu.Items.Add($autostart)
$forget = $menu.Items.Add('Forget phones')
$forget.add_Click({
    if (Test-Path $ConfigPath) { Remove-Item $ConfigPath -Force }
    $icon.ShowBalloonTip(3000, $AppName, 'Phones forgotten. Each will ask again next time.', 'Info')
})
[void]$menu.Items.Add('-')
$quit = $menu.Items.Add('Quit')
$quit.add_Click({ $shared.Running = $false; [System.Windows.Forms.Application]::Exit() })
$icon.ContextMenuStrip = $menu

# Asking the PC's user about a new phone, and the menu's SteamVR line, on the tray's own thread.
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 300
$lastStateCheck = [DateTime]::MinValue
$script:asking = $false
$timer.add_Tick({
    # The question box runs its own message loop, which keeps this timer ticking: without the stop and the flag,
    # every tick while it is open would open another (8 November 2026: endless boxes).
    if ($shared.PairRequest -ne $null -and $shared.PairAnswer -eq $null -and -not $script:asking) {
        $script:asking = $true
        $timer.Stop()
        try {
            $name = $shared.PairRequest
            $form = New-Object System.Windows.Forms.Form
            $form.TopMost = $true
            $answer = [System.Windows.Forms.MessageBox]::Show($form,
                "$name wants to start SteamVR on this PC for Pocket XR.`n`nAllow it?",
                $AppName, 'YesNo', 'Question')
            $form.Dispose()
            $shared.PairAnswer = ($answer -eq 'Yes')
        } finally {
            $script:asking = $false
            $timer.Start()
        }
    }
    if ($shared.Notice) {
        $icon.ShowBalloonTip(4000, $AppName, $shared.Notice, 'Info')
        $shared.Notice = $null
    }
    if (((Get-Date) - $script:lastStateCheck).TotalSeconds -ge 3) {
        $script:lastStateCheck = Get-Date
        $running = [bool](Get-Process vrserver -ErrorAction SilentlyContinue)
        $state.Text = if ($running) { 'SteamVR: running' } else { 'SteamVR: off' }
    }
})
$timer.Start()

$icon.ShowBalloonTip(3000, $AppName, 'Ready. Pocket XR can start SteamVR from your phone.', 'Info')
[System.Windows.Forms.Application]::Run()

$timer.Stop()
$icon.Visible = $false
$icon.Dispose()
$shared.Running = $false
[void]$worker.EndInvoke($async)
$runspace.Close()
$mutex.ReleaseMutex()
