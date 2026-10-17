# SWAR Multilingual Voice Walkie-Talkie - All Phases Verification Script
$ErrorActionPreference = 'Stop'
$mobileDir = Split-Path -Parent $PSScriptRoot

$javaBin = "C:\Users\ROHIT\.antigravity-ide\extensions\redhat.java-1.56.0-win32-x64\jre\21.0.12.1-win32-x86_64\bin"
$env:JAVA_HOME = "C:\Users\ROHIT\.antigravity-ide\extensions\redhat.java-1.56.0-win32-x64\jre\21.0.12.1-win32-x86_64"
$kotlinc = "C:\Users\ROHIT\.kotlinc\kotlinc\bin\kotlinc.bat"
$coroutinesJar = "C:\Users\ROHIT\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0\9beade4c1c1569e4f36cbd2c37e02e3e41502601\kotlinx-coroutines-core-jvm-1.9.0.jar"

Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "SWAR: Offline Multilingual Voice Walkie-Talkie - Full Build & Test Suite" -ForegroundColor Cyan
Write-Host "==========================================================================" -ForegroundColor Cyan

Push-Location $mobileDir
try {
    $srcFiles = Get-ChildItem -Path "app/src/main/java/in/itantra/mobile" -Recurse -Filter "*.kt" | Select-Object -ExpandProperty FullName
    
    $phases = @(
        @{ Name = "Phase 0 - Transport Skeleton (BLE + Wi-Fi Direct)"; TestDir = "tests/phase0"; MainClass = "in.itantra.mobile.tests.phase0.Phase0AcceptanceTest" },
        @{ Name = "Phase 1 - Wi-Fi Aware Opportunistic Upgrade"; TestDir = "tests/phase1"; MainClass = "in.itantra.mobile.tests.phase1.Phase1AcceptanceTest" },
        @{ Name = "Phase 2 - Payload Protocol, ACK/Retransmit, Floor Control & AEAD"; TestDir = "tests/phase2"; MainClass = "in.itantra.mobile.tests.phase2.Phase2AcceptanceTest" },
        @{ Name = "Phase 3 - STT Pipeline (Silero VAD + IndicConformer + Stability Buffer)"; TestDir = "tests/phase3"; MainClass = "in.itantra.mobile.tests.phase3.Phase3AcceptanceTest" },
        @{ Name = "Phase 4 - Indic-TTS Pipeline (FastPitch -> HiFi-GAN via ONNX Runtime)"; TestDir = "tests/phase4"; MainClass = "in.itantra.mobile.tests.phase4.Phase4AcceptanceTest" },
        @{ Name = "Phase 5 - 1-to-1 Full Duplex Conversation Mode (STT->Transport->TTS)"; TestDir = "tests/phase5"; MainClass = "in.itantra.mobile.tests.phase5.Phase5AcceptanceTest" },
        @{ Name = "Phase 6 - Walkie-Talkie Mode (1-to-Many Broadcast & Floor Control)"; TestDir = "tests/phase6"; MainClass = "in.itantra.mobile.tests.phase6.Phase6AcceptanceTest" },
        @{ Name = "Phase 7 - Beta English <-> Hindi Translation (Linear KV-Cache)"; TestDir = "tests/phase7"; MainClass = "in.itantra.mobile.tests.phase7.Phase7AcceptanceTest" },
        @{ Name = "Phase 8 - §8 Validation Pass & Checklist"; TestDir = "tests/phase8"; MainClass = "in.itantra.mobile.tests.phase8.Phase8AcceptanceTest" }
    )

    foreach ($phase in $phases) {
        Write-Host "`n>>> Running $($phase.Name) <<<" -ForegroundColor Yellow
        $testFiles = Get-ChildItem -Path $phase.TestDir -Recurse -Filter "*.kt" | Select-Object -ExpandProperty FullName
        $outJar = "build/$($phase.TestDir)/test.jar"
        $outDir = Split-Path -Parent $outJar
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
        
        & $kotlinc -cp $coroutinesJar ($srcFiles + $testFiles) -include-runtime -d $outJar
        if ($LASTEXITCODE -ne 0) { throw "Compilation failed for $($phase.Name)" }
        
        & "$javaBin\java.exe" -cp "$outJar;$coroutinesJar" $phase.MainClass
        if ($LASTEXITCODE -ne 0) { throw "Tests failed for $($phase.Name)" }
        Write-Host "PASSED: $($phase.Name)" -ForegroundColor Green
    }

    Write-Host "`n==========================================================================" -ForegroundColor Green
    Write-Host "ALL PHASES (PHASE 0 THROUGH PHASE 8) COMPILED AND PASSED ACCEPTANCE CRITERIA!" -ForegroundColor Green
    Write-Host "==========================================================================" -ForegroundColor Green
} finally {
    Pop-Location
}
