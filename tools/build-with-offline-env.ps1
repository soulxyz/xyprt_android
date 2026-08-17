param(
    [Parameter(Mandatory=$true)][string]$EnvRoot,
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleArgs
)
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$EnvRoot = (Resolve-Path $EnvRoot).Path
$env:JAVA_HOME = Join-Path $EnvRoot "jdk17"
$env:ANDROID_HOME = Join-Path $EnvRoot "android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_OFFLINE_MAVEN_REPO = Join-Path $EnvRoot "maven-repo"
$env:GRADLE_USER_HOME = Join-Path $EnvRoot ".gradle-user-home"
$Gradle = Join-Path $EnvRoot "gradle\bin\gradle.bat"
$Aapt2 = Join-Path $env:ANDROID_HOME "build-tools\36.1.0\aapt2.exe"

if (!(Test-Path $Gradle)) { throw "Missing Gradle: $Gradle" }
if (!(Test-Path $Aapt2)) { throw "Missing aapt2: $Aapt2" }

Push-Location $RepoRoot
try {
    python tools/verify-local-build.py
    if (!$GradleArgs -or $GradleArgs.Count -eq 0) {
        $GradleArgs = @(':app:compileDebugKotlin', ':app:testDebugUnitTest')
    }
    & $Gradle --offline --no-daemon "-Pandroid.aapt2FromMavenOverride=$Aapt2" @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
