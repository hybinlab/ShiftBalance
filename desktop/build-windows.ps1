param(
    [Parameter(Mandatory = $true)][string]$PostgresZip,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$PostgresSha256,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'Build Windows installers on Windows x64 with PowerShell 7.' }
$repo = Split-Path $PSScriptRoot -Parent
$payload = Join-Path $PSScriptRoot 'src-tauri/payload'
$build = Join-Path $PSScriptRoot 'build'
function Run([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "$program failed ($LASTEXITCODE)" }
}
if (-not $JavaHome -or -not (Test-Path "$JavaHome/bin/jlink.exe")) { throw 'Set JAVA_HOME to a Windows x64 JDK 25.' }
$version = & "$JavaHome/bin/java.exe" -version 2>&1 | Out-String
if ($version -notmatch 'version "25\.') { throw 'JDK 25 is required.' }
$PostgresZip = (Resolve-Path $PostgresZip).Path
if ((Get-FileHash $PostgresZip -Algorithm SHA256).Hash -ne $PostgresSha256) { throw 'PostgreSQL archive SHA256 mismatch.' }
New-Item -ItemType Directory -Force $build | Out-Null
$unpacked = Join-Path $build 'postgres'
if (Test-Path $unpacked) { Remove-Item -Recurse -Force $unpacked }
Expand-Archive -Path $PostgresZip -DestinationPath $unpacked
if (-not (Test-Path "$unpacked/pgsql/bin/pg_ctl.exe")) { throw 'Expected the official Windows x64 binary archive with pgsql/bin/pg_ctl.exe.' }

# Bundle Microsoft-signed VC++ prerequisites for a completely offline installation.
$redist = Join-Path $build 'vc_redist.x64.exe'
Invoke-WebRequest 'https://aka.ms/vs/17/release/vc_redist.x64.exe' -OutFile $redist
$signature = Get-AuthenticodeSignature $redist
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') {
    throw 'VC++ redistributable signature validation failed.'
}
if (Test-Path $payload) { Remove-Item -Recurse -Force $payload }
New-Item -ItemType Directory -Force $payload | Out-Null
Copy-Item $redist "$payload/vc_redist.x64.exe"
# Build tools also need the VC runtime to run PostgreSQL smoke checks.
$installation = Start-Process $redist -ArgumentList '/install', '/quiet', '/norestart' -Wait -PassThru
if ($installation.ExitCode -notin @(0, 1638, 3010)) { throw 'Cannot install the PostgreSQL VC++ prerequisite.' }
New-Item -ItemType Directory -Force "$payload/pgsql" | Out-Null
foreach ($folder in @('bin', 'lib', 'share', 'doc')) {
    if (Test-Path "$unpacked/pgsql/$folder") { Copy-Item -Recurse "$unpacked/pgsql/$folder" "$payload/pgsql/$folder" }
}
Get-ChildItem "$unpacked/pgsql" -File | Copy-Item -Destination "$payload/pgsql"
Get-ChildItem "$payload/pgsql" -Recurse -Filter '*.pdb' | Remove-Item -Force
# PostgreSQL's narrow Win32/CRT argv and file APIs need a UTF-8 process code page for Chinese paths.
$manifestTool = Get-ChildItem "${env:ProgramFiles(x86)}/Windows Kits/10/bin/*/x64/mt.exe" | Sort-Object FullName | Select-Object -Last 1
if (-not $manifestTool) { throw 'Install the Windows SDK manifest tool (mt.exe) with C++ Build Tools.' }
foreach ($executable in Get-ChildItem "$payload/pgsql/bin" -Filter '*.exe') {
    Run $manifestTool.FullName @('-nologo', '-manifest', "$PSScriptRoot/postgres-utf8.manifest", "-outputresource:$($executable.FullName);#1")
}

Run "$JavaHome/bin/jlink.exe" @('--add-modules', 'java.se,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.localedata,jdk.naming.dns,jdk.zipfs', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', "$payload/runtime")
Copy-Item "$JavaHome/legal" "$payload/jdk-legal" -Recurse
Push-Location "$repo/frontend"
try {
    Run 'npm.cmd' @('ci')
    $env:VITE_DESKTOP = 'true'
    try { Run 'npm.cmd' @('run', 'build') } finally { Remove-Item Env:VITE_DESKTOP }
    Copy-Item 'dist' "$payload/web" -Recurse
} finally { Pop-Location }
Push-Location "$repo/backend"
try {
    Run 'mvn.cmd' @('-B', '-Pdesktop-windows', 'package', 'dependency:build-classpath', '-Dmdep.outputFile=target/desktop.classpath')
    Copy-Item 'target/backend-0.1.0-SNAPSHOT.jar' "$payload/backend.jar"
    Copy-Item 'src/main/resources/db' "$payload/db" -Recurse
    $classpath = (Get-Content 'target/desktop.classpath' -Raw).Trim()
    Run "$payload/runtime/bin/java.exe" @('-ea', '-cp', "target/test-classes;target/classes;$classpath", 'com.shiftbalance.desktop.DesktopSelfCheck', '--postgres-bin', "$payload/pgsql/bin")
    Run "$payload/runtime/bin/java.exe" @('--enable-native-access=ALL-UNNAMED', '-ea', '-cp', "target/test-classes;target/classes;$classpath", 'com.shiftbalance.schedule.ConfigurableRulesSelfCheck')
} finally { Pop-Location }
Copy-Item "$PSScriptRoot/README.md" "$payload/DESKTOP-README.md"
Copy-Item "$PSScriptRoot/THIRD-PARTY-NOTICES.md" "$payload/THIRD-PARTY-NOTICES.md"
if (-not $PrepareOnly) {
    Push-Location $PSScriptRoot
    try {
        Run 'npm.cmd' @('ci')
        Run 'npm.cmd' @('run', 'build:windows')
    } finally { Pop-Location }
}
