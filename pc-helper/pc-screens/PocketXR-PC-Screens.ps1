# Pocket XR PC screens setup.
#
# Run once on the PC (double-click "Set up PC screens.cmd"). It asks Windows for admin rights, then:
#   1. finds Apollo, or downloads and installs it with no questions; an Apollo already there is kept as it is, and a
#      Sunshine that is on (same ports) is turned off after one question;
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
#
# Setup also offers "away from home": it installs Tailscale, signs it in (a browser page), names the PC on Tailscale
# after its Windows name, turns on waking by network for a wired card, and offers to stop the PC sleeping on mains.

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

# Apollo installed with no questions: running this setup is the user's yes. Its installer (NSIS) takes /S; it installs
# the virtual display driver, the firewall rules and ApolloService, which starts with Windows.
function Install-Apollo {
    Say 'Installing Apollo (free, open source), which streams PC screens to Pocket XR...'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/ClassicOldSong/Apollo/releases/latest' -Headers @{ 'User-Agent' = 'PocketXR-PC-Screens' }
    $asset = $release.assets | Where-Object { $_.name -match '\.exe$' } | Select-Object -First 1
    if (-not $asset) { Say 'Could not find the Apollo installer to download.'; return $false }
    $file = Join-Path $env:TEMP $asset.name
    Say "Downloading $($asset.name)..."
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $file -UseBasicParsing
    Say 'Installing Apollo. Windows may ask once to allow its virtual display driver: click Install.'
    $p = Start-Process -FilePath $file -ArgumentList '/S' -Wait -PassThru
    Log "Apollo installer $($asset.name) exited $($p.ExitCode)"
    # The service can take a moment to appear and start.
    for ($i = 0; $i -lt 60; $i++) {
        $svc = Get-Service -Name 'ApolloService' -ErrorAction SilentlyContinue
        if ($svc -and (Find-Apollo)) {
            if ($svc.Status -ne 'Running') { try { Start-Service 'ApolloService' } catch {} }
            Say "Apollo $($release.tag_name) is installed."
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return [bool](Find-Apollo)
}

# Sunshine (LizardByte's, which Apollo is made from) on the same ports would stop Apollo answering, and it cannot make
# virtual screens. Left installed; turned off and kept from starting with Windows, after one question.
function Resolve-Sunshine {
    $svc = Get-Service -Name 'SunshineService' -ErrorAction SilentlyContinue
    $procs = @(Get-Process -Name 'sunshine' -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path -notlike '*\Apollo\*' })
    $active = ($svc -and ($svc.Status -eq 'Running' -or $svc.StartType -eq 'Automatic')) -or $procs.Count -gt 0
    if (-not $active) {
        if ($svc) { Say 'Sunshine is installed but off: left as it is.' }
        return $true
    }
    Say 'Sunshine is on this PC. Pocket XR''s PC screens use Apollo, which makes a virtual screen for each one; the two use the same network ports, so Sunshine has to be off.'
    $answer = Read-Host 'Turn Sunshine off (it stays installed, and you can turn it back on in Windows Services)? (Y/N)'
    if ($answer -notmatch '^[Yy]') { return $false }
    if ($svc) {
        try { Stop-Service 'SunshineService' -Force -ErrorAction Stop } catch {}
        Set-Service 'SunshineService' -StartupType Disabled
    }
    foreach ($p in $procs) { try { Stop-Process -Id $p.Id -Force } catch {} }
    Say 'Sunshine is off.'
    return $true
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

# ---- Apollo's web login, for the Pocket XR PC helper ----
#
# The PC helper types a phone's pairing PIN into Apollo for the user (its "apollo-pin" request), signed in to Apollo's web
# page. This saves that login for it, the password protected by Windows for this user only (DPAPI). A fresh Apollo with
# no login yet gets one made here; an Apollo that has one asks for it once.

$LoginFile = Join-Path $env:APPDATA 'PocketXR\apollo-login.json'

function Test-ApolloLogin($port, $user, $password) {
    Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    [Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
    try {
        $body = @{ username = $user; password = $password } | ConvertTo-Json -Compress
        $r = Invoke-WebRequest -Uri "https://localhost:$($port + 1)/api/login" -Method Post -Body $body -ContentType 'application/json' -UseBasicParsing -TimeoutSec 5
        return $r.StatusCode -eq 200
    } catch { return $false } finally { [Net.ServicePointManager]::ServerCertificateValidationCallback = $null }
}

function Save-ApolloLogin($user, [Security.SecureString]$secure) {
    New-Item -ItemType Directory -Force -Path (Split-Path $LoginFile) | Out-Null
    $out = [ordered]@{ username = $user; password = (ConvertFrom-SecureString -SecureString $secure) }
    [IO.File]::WriteAllText($LoginFile, ($out | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
}

function Ensure-ApolloLogin($apollo) {
    $configDir = Join-Path $apollo 'config'
    $main = Read-Conf (Join-Path $configDir 'sunshine.conf')
    $port = if ($main.ContainsKey('port')) { [int]$main['port'] } else { 47989 }
    $state = Read-Json (Get-MainStatePath $apollo)
    if (Test-Path $LoginFile) {
        try {
            $saved = Get-Content $LoginFile -Raw | ConvertFrom-Json
            $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR((ConvertTo-SecureString $saved.password)))
            if (Test-ApolloLogin $port $saved.username $plain) { Say 'Apollo login: saved for the PC helper already.'; return }
        } catch {}
    }
    if (-not ($state -and $state.username)) {
        # No login yet (a fresh Apollo): make one, so nobody has to.
        $user = 'pocketxr'
        $plain = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ })
        & (Join-Path $apollo 'sunshine.exe') (Join-Path $configDir 'sunshine.conf') --creds $user $plain | Out-Null
        Restart-Service 'ApolloService' -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 3
        Save-ApolloLogin $user (ConvertTo-SecureString $plain -AsPlainText -Force)
        Say "Made Apollo's web login (user ""$user"", password ""$plain""). Pocket XR's PC helper uses it to pair phones for you."
        return
    }
    Say 'Pocket XR''s PC helper can pair your phone with Apollo for you (no PIN to type) if it has Apollo''s web login.'
    for ($try = 0; $try -lt 3; $try++) {
        $user = Read-Host 'Apollo web login user name (leave empty to skip)'
        if (-not $user) { Say 'Skipped: pairing will show a PIN to type in Apollo''s web page.'; return }
        $secure = Read-Host 'Apollo web login password' -AsSecureString
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
        if (Test-ApolloLogin $port $user $plain) {
            Save-ApolloLogin $user $secure
            Say 'Apollo login saved for the PC helper (only this Windows user can read it).'
            return
        }
        Say 'That login did not work. Try again.'
    }
}

# ---- away from home (Tailscale) ----
#
# Pocket XR reaches the PC away from home over Tailscale, by the PC's Tailscale name, which this makes the Windows
# name (lower case): the phone learns that name when it finds the PC at home, so nothing is typed on the phone. Apollo
# stays closed to the internet; only the user's own Tailscale devices can reach it.

$TailscaleExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'

function Install-Tailscale {
    if (Test-Path $TailscaleExe) { return $true }
    Say 'Installing Tailscale (free, from tailscale.com)...'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $msi = Join-Path $env:TEMP 'tailscale-setup-latest-amd64.msi'
    Invoke-WebRequest -Uri 'https://pkgs.tailscale.com/stable/tailscale-setup-latest-amd64.msi' -OutFile $msi -UseBasicParsing
    # Unattended: Tailscale stays connected when nobody is signed in to Windows, so the sign-in screen is reachable.
    $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList "/i `"$msi`" /quiet /norestart TS_UNATTENDEDMODE=always" -Wait -PassThru
    if ($p.ExitCode -ne 0) { Say "The Tailscale installer stopped ($($p.ExitCode))."; return $false }
    for ($i = 0; $i -lt 30 -and -not (Test-Path $TailscaleExe); $i++) { Start-Sleep -Seconds 1 }
    return (Test-Path $TailscaleExe)
}

function Get-TailscaleState {
    try { return (& $TailscaleExe status --json 2>$null | Out-String | ConvertFrom-Json) } catch { return $null }
}

function Connect-Tailscale {
    $name = ($env:COMPUTERNAME.ToLower() -replace '[^a-z0-9-]+', '-').Trim('-')
    $state = Get-TailscaleState
    if ($state -and $state.BackendState -eq 'Running') {
        & $TailscaleExe set --hostname=$name --unattended 2>&1 | Out-Null
        return $state
    }
    # "tailscale up" prints a sign-in link and waits for it: run it aside, open the link in the browser.
    $out = Join-Path $env:TEMP 'pocketxr-tailscale-up.txt'
    $err = Join-Path $env:TEMP 'pocketxr-tailscale-up-err.txt'
    Remove-Item $out, $err -ErrorAction SilentlyContinue
    Start-Process -FilePath $TailscaleExe -ArgumentList "up --unattended --hostname=$name" -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError $err | Out-Null
    $opened = $false
    for ($i = 0; $i -lt 600; $i++) {
        Start-Sleep -Seconds 1
        if (-not $opened) {
            $link = Select-String -Path @($out, $err) -Pattern 'https://login\.tailscale\.com/\S+' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($link) {
                Say 'Sign in to Tailscale in the browser page that opens. Use the same account you use (or will use) in the Tailscale app on your phone.'
                Start-Process $link.Matches[0].Value
                $opened = $true
            }
        }
        $state = Get-TailscaleState
        if ($state -and $state.BackendState -eq 'Running') { return $state }
    }
    return $null
}

# The network card wakes the PC for a "magic packet" (Wake-on-LAN), and may wake it from sleep.
function Enable-Wake {
    $wired = @(Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' -and $_.MediaType -eq '802.3' })
    if ($wired.Count -eq 0) {
        Say 'No wired network cable: waking the PC over the network needs one (Wi-Fi rarely wakes a PC).'
        return
    }
    foreach ($a in $wired) {
        try { Set-NetAdapterPowerManagement -Name $a.Name -WakeOnMagicPacket Enabled -ErrorAction Stop } catch {}
        try { Set-NetAdapterAdvancedProperty -Name $a.Name -RegistryKeyword '*WakeOnMagicPacket' -RegistryValue 1 -ErrorAction Stop } catch {}
        & powercfg /deviceenablewake "$($a.InterfaceDescription)" 2>$null | Out-Null
        Say "Waking by network turned on for $($a.Name) ($($a.MacAddress))."
    }
}

function Enable-Away {
    if (-not (Install-Tailscale)) { Say 'Tailscale is not installed, so PC screens away from home are not set up.'; return }
    $state = Connect-Tailscale
    if (-not $state) { Say 'Tailscale did not finish signing in. Run this setup again to try once more.'; return }
    $ip = @($state.Self.TailscaleIPs | Where-Object { $_ -match '^100\.' }) | Select-Object -First 1
    Say "Tailscale is on. This PC is ""$($state.Self.HostName)"" ($ip) on your Tailscale."
    Enable-Wake
    $answer = Read-Host 'Keep this PC from sleeping while it is plugged in, so you can always reach it away from home? (Y/N)'
    if ($answer -match '^[Yy]') {
        & powercfg /change standby-timeout-ac 0
        & powercfg /change hibernate-timeout-ac 0
        Say 'This PC no longer sleeps by itself while plugged in. The screen can still turn off.'
    } else {
        Say 'Sleep left as it is. Away from home, a sleeping PC can only be woken by something at home that stays on (some routers can do it).'
    }
    Say 'On your phone: install Tailscale from the Play Store and sign in with the same account. Pocket XR finds the PC through it when you are away.'
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
        if (-not (Resolve-Sunshine)) { throw 'Sunshine is still on, so Apollo cannot use its ports. Run this again when you want PC screens.' }
        if (-not $apollo) {
            if (-not (Install-Apollo)) { throw 'Apollo did not install. Install it from github.com/ClassicOldSong/Apollo, then run this again.' }
            $apollo = Find-Apollo
        } else {
            Say 'Apollo is already installed: kept as it is, with its login and paired devices.'
        }
        Say "Apollo: $apollo"
        $svc = Get-Service -Name 'ApolloService' -ErrorAction SilentlyContinue
        if ($svc -and $svc.Status -ne 'Running') { Start-Service 'ApolloService'; Say 'Started Apollo.' }
        Ensure-ApolloLogin $apollo
        foreach ($s in $Screens) { New-Screen $apollo $s }
        Install-Task
        # Start now, without waiting for the next sign-in.
        Start-ScheduledTask -TaskName $TaskName
        Say ''
        $away = Read-Host 'Use PC screens away from home too, for example from a cafe? This adds Tailscale (free). (Y/N)'
        if ($away -match '^[Yy]') { Enable-Away }
        Say ''
        Say 'Done. On your phone, open PC screen in Pocket XR and tap this PC, then click Yes on this PC.'
        Say 'After that, PC screen 2 and PC screen 3 connect by themselves.'
        if (-not (Test-Path $LoginFile)) {
            # No saved login: pairing shows a PIN to type in Apollo's web page, so open it.
            $main = Read-Conf (Join-Path (Join-Path $apollo 'config') 'sunshine.conf')
            $mainPort = if ($main.ContainsKey('port')) { [int]$main['port'] } else { 47989 }
            Start-Process "https://localhost:$($mainPort + 1)/pin"
        }
    }
} catch {
    Say "Setup stopped: $($_.Exception.Message)"
}
Write-Host ''
Read-Host 'Press Enter to close'
