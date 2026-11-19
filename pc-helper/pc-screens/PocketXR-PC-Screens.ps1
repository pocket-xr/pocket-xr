# Pocket XR PC screens setup.
#
# Run once on the PC (double-click "Set up PC screens.cmd"). It asks Windows for admin rights, then:
#   1. finds Apollo (and offers to download and install it when it is missing);
#   2. makes two more Apollo instances for PC screens 2 and 3, each with its own port and its own virtual display;
#   3. adds a sign-in task, "Pocket XR PC screens", that starts them and keeps them knowing the same phones as the main
#      Apollo: a phone paired once with the main Apollo is copied, with its permissions, to the others. So Pocket XR's
#      PC screens 2 and 3 connect with no PIN of their own.
#
# Why copying works: every Apollo instance on a PC uses the same host certificate (config\credentials\cacert.pem), and
# keeps its paired phones in root.named_devices of its own state file. Instances must not share one state file (each
# save overwrites the others'), so this copies the list instead, while the extra instance is stopped.
#
# Modes:
#   (none)    set up, as above
#   -Run      what the sign-in task runs: start the instances and copy pairings, checking every 10 seconds
#   -Remove   stop the extra instances and remove the sign-in task (their config files stay)

param(
    [switch]$Run,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$TaskName = 'Pocket XR PC screens'
$InstallDir = Join-Path $env:ProgramFiles 'Pocket XR'
$InstalledScript = Join-Path $InstallDir 'PocketXR-PC-Screens.ps1'
$LogDir = Join-Path $env:ProgramData 'PocketXR'
$LogPath = Join-Path $LogDir 'pc-screens.log'
# PC screens 2 and 3: Apollo's base port for each. An instance uses its port - 5 to + 21, so 1000 apart is plenty.
$Screens = @(
    @{ Number = 2; Port = 48989 },
    @{ Number = 3; Port = 49989 }
)

function Log($text) {
    try {
        New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
        Add-Content -Path $LogPath -Value ('{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $text)
    } catch {}
}

function Say($text) {
    Write-Host $text
    Log $text
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# ---- Apollo ----

function Find-Apollo {
    $svc = Get-CimInstance Win32_Service -Filter "Name='ApolloService'" -ErrorAction SilentlyContinue
    if ($svc -and $svc.PathName) {
        $exe = $svc.PathName.Trim('"')
        $dir = Split-Path (Split-Path $exe -Parent) -Parent
        if (Test-Path (Join-Path $dir 'sunshine.exe')) { return $dir }
    }
    $default = Join-Path $env:ProgramFiles 'Apollo'
    if (Test-Path (Join-Path $default 'sunshine.exe')) { return $default }
    return $null
}

function Install-Apollo {
    Say 'Apollo is not on this PC. Pocket XR streams PC screens from Apollo (free, open source).'
    $answer = Read-Host 'Download and install Apollo now? (Y/N)'
    if ($answer -notmatch '^[Yy]') { return $false }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/ClassicOldSong/Apollo/releases/latest' -Headers @{ 'User-Agent' = 'PocketXR-PC-Screens' }
    $asset = $release.assets | Where-Object { $_.name -match '\.exe$' } | Select-Object -First 1
    if (-not $asset) { Say 'Could not find the Apollo installer to download.'; return $false }
    $file = Join-Path $env:TEMP $asset.name
    Say "Downloading $($asset.name)..."
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $file -UseBasicParsing
    Say 'Opening the Apollo installer. Click through it with the usual choices, then come back here.'
    Start-Process -FilePath $file -Wait
    return [bool](Find-Apollo)
}

# Reads "key = value" lines of an Apollo config file.
function Read-Conf($path) {
    $conf = @{}
    if (Test-Path $path) {
        foreach ($line in Get-Content $path) {
            if ($line -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') { $conf[$Matches[1]] = $Matches[2].Trim() }
        }
    }
    return $conf
}

# A state file path from a config value: relative paths are relative to Apollo's config folder.
function Resolve-ConfigPath($configDir, $value) {
    if ([IO.Path]::IsPathRooted($value)) { return $value }
    return Join-Path $configDir $value
}

function Get-MainStatePath($apollo) {
    $configDir = Join-Path $apollo 'config'
    $main = Read-Conf (Join-Path $configDir 'sunshine.conf')
    $name = if ($main.ContainsKey('file_state')) { $main['file_state'] } else { 'sunshine_state.json' }
    return Resolve-ConfigPath $configDir $name
}

function Screen-Conf($apollo, $n) { Join-Path (Join-Path $apollo 'config') "pocketxr_screen$n.conf" }
function Screen-State($apollo, $n) { Join-Path (Join-Path $apollo 'config') "pocketxr_screen${n}_state.json" }

function Read-Json($path) {
    if (-not (Test-Path $path)) { return $null }
    try { return (Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}

function Write-Json($path, $value) {
    $json = $value | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText($path, $json, (New-Object Text.UTF8Encoding($false)))
}

# The main Apollo's paired phones, as JSON text (for comparing) and as objects.
function Get-Devices($state) {
    if ($state -and $state.root -and $state.root.named_devices) { return @($state.root.named_devices) }
    return @()
}

function Devices-Key($devices) {
    if ($devices.Count -eq 0) { return '[]' }
    return (ConvertTo-Json -InputObject @($devices) -Depth 20 -Compress)
}

function Port-Busy($port) {
    $busy = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(($port - 5), $port, ($port + 1), ($port + 21)) }
    return [bool]$busy
}

# One extra instance's config and state, made once.
function New-Screen($apollo, $screen) {
    $n = $screen.Number
    $confPath = Screen-Conf $apollo $n
    if (Test-Path $confPath) { return }
    $port = $screen.Port
    while (Port-Busy $port) { $port += 100 }
    $lines = @(
        "# Made by Pocket XR's PC screens setup: the Apollo for Pocket XR's PC screen $n.",
        "# Started at sign-in by the task ""$TaskName"". Remove it by running the setup with -Remove.",
        "port = $port",
        "sunshine_name = $($env:COMPUTERNAME) screen $n",
        "file_state = pocketxr_screen${n}_state.json",
        "log_path = pocketxr_screen$n.log",
        'upnp = disabled'
    )
    [IO.File]::WriteAllLines($confPath, $lines, (New-Object Text.UTF8Encoding($false)))
    Say "Made PC screen $n's Apollo, on port $port."
}

function Screen-Port($apollo, $n) {
    $conf = Read-Conf (Screen-Conf $apollo $n)
    if ($conf.ContainsKey('port')) { return [int]$conf['port'] }
    return 0
}

function Get-ScreenProcess($apollo, $n) {
    $name = "pocketxr_screen$n.conf"
    Get-CimInstance Win32_Process -Filter "Name='sunshine.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$name*" }
}

function Start-Screen($apollo, $n) {
    $conf = Screen-Conf $apollo $n
    Start-Process -FilePath (Join-Path $apollo 'sunshine.exe') -ArgumentList "`"$conf`"" -WorkingDirectory $apollo -WindowStyle Hidden
    Log "started PC screen $n's Apollo"
}

function Stop-Screen($apollo, $n) {
    foreach ($p in @(Get-ScreenProcess $apollo $n)) {
        try { Stop-Process -Id $p.ProcessId -Force } catch {}
    }
}

# Copies the main Apollo's phones (and its web login) into an extra instance's state, if they differ. Returns $true
# when it changed the file (the instance must restart to read it).
#
# Each phone gets its own uuid in each extra instance: Apollo makes one virtual display per paired device's uuid, so
# with the main Apollo's uuids copied as they were, all three instances made the same display (\\.\DISPLAY6) and the
# three PC screens showed one desktop (Mike's PC, 19 Nov 2026). The uuid is made from the original and the screen's
# number, so it stays the same from one copy to the next.
function Screen-Uuid($uuid, $n) {
    $md5 = [Security.Cryptography.MD5]::Create()
    $bytes = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes("$uuid pocketxr screen $n"))
    return ([guid]::new($bytes)).ToString().ToUpper()
}

function Screen-Devices($devices, $n) {
    $out = @()
    foreach ($d in $devices) {
        $copy = $d | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        if ($copy.PSObject.Properties['uuid'] -and $copy.uuid) { $copy.uuid = Screen-Uuid $copy.uuid $n }
        $out += $copy
    }
    return $out
}

function Sync-Screen($apollo, $n, $mainState) {
    $path = Screen-State $apollo $n
    $state = Read-Json $path
    $devices = Screen-Devices (Get-Devices $mainState) $n
    if ($state -and (Devices-Key (Get-Devices $state)) -eq (Devices-Key $devices)) { return $false }
    $uniqueid = if ($state -and $state.root -and $state.root.uniqueid) { $state.root.uniqueid } else { ([guid]::NewGuid()).ToString().ToUpper() }
    $out = [ordered]@{}
    # The same web login as the main Apollo, so its page works for every screen's Apollo too.
    foreach ($k in 'username', 'salt', 'password') {
        if ($mainState -and $mainState.PSObject.Properties[$k]) { $out[$k] = $mainState.$k }
    }
    $out['root'] = [ordered]@{ uniqueid = $uniqueid; named_devices = @($devices) }
    $running = @(Get-ScreenProcess $apollo $n).Count -gt 0
    if ($running) { Stop-Screen $apollo $n; Start-Sleep -Seconds 1 }
    Write-Json $path $out
    Log "PC screen ${n}: copied $($devices.Count) paired phone(s) from the main Apollo"
    return $true
}

function Sync-All($apollo) {
    $mainState = Read-Json (Get-MainStatePath $apollo)
    if (-not $mainState) { return }
    foreach ($s in $Screens) {
        $n = $s.Number
        if (-not (Test-Path (Screen-Conf $apollo $n))) { continue }
        Sync-Screen $apollo $n $mainState | Out-Null
        if (@(Get-ScreenProcess $apollo $n).Count -eq 0) { Start-Screen $apollo $n }
    }
}

# ---- the sign-in task ----

function Stop-RunLoop {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like '*PocketXR-PC-Screens.ps1*-Run*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Install-Task {
    # Run again (an update): the running loop is the old script, so it stops before the new one starts.
    Stop-RunLoop
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    # Run from Program Files, where only admins can change it: the task runs it with admin rights.
    if ($PSCommandPath -ne $InstalledScript) { Copy-Item -Path $PSCommandPath -Destination $InstalledScript -Force }
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$InstalledScript`" -Run"
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
    $principal = New-ScheduledTaskPrincipal -UserId $user -RunLevel Highest -LogonType Interactive
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    Say "Added the sign-in task ""$TaskName""."
}

# ---- modes ----

if ($Run) {
    $apollo = Find-Apollo
    if (-not $apollo) { Log 'run: Apollo not found'; exit 1 }
    Log 'run: started'
    while ($true) {
        try { Sync-All $apollo } catch { Log "run: $($_.Exception.Message)" }
        Start-Sleep -Seconds 10
    }
}

if (-not (Test-Admin)) {
    $argsLine = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    if ($Remove) { $argsLine += ' -Remove' }
    Start-Process -FilePath 'powershell.exe' -ArgumentList $argsLine -Verb RunAs
    exit
}

try {
    $apollo = Find-Apollo
    if ($Remove) {
        Stop-RunLoop
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
        if ($apollo) { foreach ($s in $Screens) { Stop-Screen $apollo $s.Number } }
        Say 'PC screens 2 and 3 are stopped and will not start at sign-in. Their settings files are left in Apollo''s config folder.'
    } else {
        Say 'Pocket XR PC screens setup'
        Say ''
        if (-not $apollo) {
            if (-not (Install-Apollo)) { throw 'Apollo is needed for PC screens. Install it from github.com/ClassicOldSong/Apollo, then run this again.' }
            $apollo = Find-Apollo
        }
        Say "Apollo: $apollo"
        $svc = Get-Service -Name 'ApolloService' -ErrorAction SilentlyContinue
        if ($svc -and $svc.Status -ne 'Running') { Start-Service 'ApolloService'; Say 'Started Apollo.' }
        foreach ($s in $Screens) { New-Screen $apollo $s }
        Install-Task
        # Start now, without waiting for the next sign-in.
        Start-ScheduledTask -TaskName $TaskName
        Say ''
        Say 'Done. On your phone, open PC screen in Pocket XR and tap this PC.'
        Say 'The first time, Pocket XR shows a PIN: type it in Apollo''s web page (it opens next).'
        Say 'After that, PC screen 2 and PC screen 3 connect by themselves.'
        $main = Read-Conf (Join-Path (Join-Path $apollo 'config') 'sunshine.conf')
        $mainPort = if ($main.ContainsKey('port')) { [int]$main['port'] } else { 47989 }
        Start-Process "https://localhost:$($mainPort + 1)/pin"
    }
} catch {
    Say "Setup stopped: $($_.Exception.Message)"
}
Write-Host ''
Read-Host 'Press Enter to close'
