$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$projectPath = Join-Path $repositoryRoot "artifacts\linkdroid-android\native-kotlin"

$gradle = $null
if ($env:GRADLE_HOME) {
    $candidate = Join-Path $env:GRADLE_HOME "bin\gradle.bat"
    if (Test-Path $candidate) { $gradle = $candidate }
}
if (-not $gradle) {
    $command = Get-Command gradle -ErrorAction SilentlyContinue
    if ($command) { $gradle = $command.Source }
}
if (-not $gradle) {
    $wrapperRoot = Join-Path $env:USERPROFILE ".gradle\wrapper\dists"
    $gradle = Get-ChildItem $wrapperRoot -Recurse -Filter gradle.bat -File -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $gradle) {
    throw "Gradle tidak ditemukan. Set GRADLE_HOME atau instal Gradle/Android Studio terlebih dahulu."
}

if ($env:JAVA_HOME -and -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    Write-Warning "JAVA_HOME tidak valid, Java yang ditemukan akan digunakan otomatis."
    $env:JAVA_HOME = $null
}

if (-not $env:JAVA_HOME) {
    $java = Get-ChildItem (Join-Path $env:LOCALAPPDATA "Temp") -Recurse -Filter java.exe -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "temurin|jdk-17" } |
        Select-Object -First 1
    if ($java) { $env:JAVA_HOME = Split-Path (Split-Path $java.FullName -Parent) -Parent }
}

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JDK 17 tidak ditemukan. Instal JDK 17 lalu set JAVA_HOME."
}

Write-Host "Gradle: $gradle"
Write-Host "Java:   $env:JAVA_HOME"
& $gradle -p $projectPath assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $projectPath "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "APK:    $apk"
