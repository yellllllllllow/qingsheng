# S-master Android 16 Emulator Setup Script
# Usage: .\setup-android16-test.ps1
# Downloads system image -> Creates AVD -> Launches emulator -> Installs APK

param(
    [switch]$NoInstall,
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
$ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\H\.android-sdk" }
$AVD_NAME = "S_Master_Android16"
$APK_PATH = "$PSScriptRoot\app\build\outputs\apk\release\S-master.apk"
$SYSTEM_IMAGE = "system-images;android-36;google_apis_playstore;x86_64"
$SDKMANAGER = "$ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat"
$AVDMANAGER = "$ANDROID_HOME\cmdline-tools\latest\bin\avdmanager.bat"
$EMULATOR = "$ANDROID_HOME\emulator\emulator.exe"
$ADB = "$ANDROID_HOME\platform-tools\adb.exe"

function Write-Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-OK($msg) { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  [!] $msg" -ForegroundColor Yellow }
function Write-Fail($msg) { Write-Host "  [X] $msg" -ForegroundColor Red }

$env:JAVA_HOME = "C:\Users\H\AppData\Local\Temp\jdk\jdk-17.0.12+7"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Clear-Host
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  S-master Android 16 Test Setup" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ---------- Step 1: Check environment ----------
Write-Step "1/5 Checking environment"
$errors = @()

if (-not (Test-Path $SDKMANAGER)) { $errors += "sdkmanager not found: $SDKMANAGER" }
if (-not (Test-Path $AVDMANAGER)) { $errors += "avdmanager not found: $AVDMANAGER" }
if (-not (Test-Path $EMULATOR))   { $errors += "emulator not found: $EMULATOR" }
if (-not (Test-Path $ADB))        { $errors += "adb not found: $ADB" }
if (-not (Test-Path $APK_PATH))   { Write-Warn "APK not found: $APK_PATH`n  Build it first with .\build-release.ps1" }

if ($errors.Count -gt 0) {
    foreach ($e in $errors) { Write-Fail $e }
    Write-Fail "Environment check failed"
    exit 1
}
Write-OK "Environment OK"

# ---------- Step 2: Download/check system image ----------
Write-Step "2/5 Checking Android 16 system image"

if (Test-Path "$ANDROID_HOME\system-images\android-36\google_apis_playstore\x86_64\") {
    Write-OK "Android 16 system image already installed"
} else {
    Write-Warn "Downloading Android 16 system image (~1-2 GB, may take 5-15 min)..."
    & $SDKMANAGER --sdk_root="$ANDROID_HOME" $SYSTEM_IMAGE 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-OK "Android 16 system image downloaded"
    } else {
        Write-Fail "Download failed, check network and retry"
        exit 1
    }
}

# ---------- Step 3: Create AVD ----------
Write-Step "3/5 Creating AVD"

$existingAVD = & $AVDMANAGER list avd 2>$null | Select-String $AVD_NAME
if ($existingAVD) {
    Write-Warn "AVD $AVD_NAME already exists, skipping"
} else {
    Write-Host "  Creating AVD: $AVD_NAME"
    & $AVDMANAGER create avd -n $AVD_NAME -k "$SYSTEM_IMAGE" -d pixel_8_pro -f 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0 -or $LASTEXITCODE -eq 1) {
        Write-OK "AVD created: $AVD_NAME"
    } else {
        Write-Fail "AVD creation failed (exit code: $LASTEXITCODE)"
        exit 1
    }
}

# ---------- Step 4: Configure AVD ----------
Write-Step "4/5 Configuring AVD"
$AVD_CONFIG_DIR = "$env:USERPROFILE\.android\avd\$AVD_NAME.avd"
$CONFIG_FILE = "$AVD_CONFIG_DIR\config.ini"

if (Test-Path $CONFIG_FILE) {
    $additions = @"
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.ramSize=4096
hw.accelerometer=yes
vm.heapSize=256
"@
    Set-Content -Path $CONFIG_FILE -Value ((Get-Content $CONFIG_FILE -Raw) + $additions)
    Write-OK "AVD optimized (4GB RAM, GPU acceleration)"
}

# ---------- Step 5: Launch emulator and install APK ----------
if (-not $NoLaunch) {
    Write-Step "5/5 Launching emulator"
    Write-Host "  Waiting for Android 16 emulator to start..."
    Write-Host "  First boot may take 3-5 minutes"
    
    Start-Process -FilePath $EMULATOR -ArgumentList "-avd $AVD_NAME -wipe-data -netdelay none -netspeed full" -NoNewWindow
    
    Write-Host "  Waiting for device (timeout: 15 min)..."
    $booted = $false
    for ($i = 0; $i -lt 180; $i++) {
        $state = & $ADB shell getprop sys.boot_completed 2>$null
        if ($state -eq "1") { $booted = $true; break }
        Start-Sleep -Seconds 5
        if ($i % 12 -eq 0) { Write-Host "  Waiting... ($($i*5)s)" }
    }
    
    if ($booted) {
        Write-OK "Android 16 emulator is ready"
        
        if (-not $NoInstall -and (Test-Path $APK_PATH)) {
            Write-Host "  Installing S-master.apk..."
            $installResult = & $ADB install -r $APK_PATH 2>&1
            if ($LASTEXITCODE -eq 0) {
                Write-OK "APK installed successfully"
                & $ADB shell am start -n com.example.s_master/.MainActivity
                Write-OK "App launched"
            } else {
                Write-Fail "APK install failed: $installResult"
            }
        }
        
        Write-Host "`n========================================" -ForegroundColor Green
        Write-Host "  Android 16 test environment ready!" -ForegroundColor Green
        Write-Host "========================================" -ForegroundColor Green
        Write-Host ""
        Write-Host "  Emulator console: http://localhost:5554"
        Write-Host "  AVD name:        $AVD_NAME"
        Write-Host ""
        Write-Host "  Commands:"
        Write-Host "    adb shell input keyevent 82         # Unlock screen"
        Write-Host "    adb logcat -s ChatMonitorService    # View logs"
        Write-Host "    $ANDROID_HOME\emulator\emulator -avd $AVD_NAME  # Manual launch"
    } else {
        Write-Fail "Emulator boot timeout (15 min)"
        Write-Host "  Try manually: $ANDROID_HOME\emulator\emulator -avd $AVD_NAME"
    }
} else {
    Write-Step "5/5 Skipped (-NoLaunch)"
    Write-Host "  Manual launch: $ANDROID_HOME\emulator\emulator -avd $AVD_NAME"
}
