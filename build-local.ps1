param(
    [ValidateSet('Debug', 'Preview')][string]$Variant = 'Preview',
    [switch]$Offline,
    [switch]$SkipBuild,
    [switch]$CheckRelease,
    [switch]$RunPythonChecks
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Initialize-LocalToolchain([string]$RepoRoot) {
    $parentDir = Split-Path -Parent $RepoRoot
    $envScript = Join-Path $parentDir '启用开发工具.ps1'
    if ((Test-Path -LiteralPath $envScript) -and $PSVersionTable.PSVersion.Major -ge 6) {
        . $envScript
        return
    }
    $toolsRoot = Join-Path $parentDir '.tools'
    if (Test-Path -LiteralPath $toolsRoot) {
        if (-not $env:JAVA_HOME -and $env:ProgramFiles) {
            $msJdkDir = Join-Path $env:ProgramFiles 'Microsoft'
            $jdkCandidate = if (Test-Path -LiteralPath $msJdkDir) {
                Get-ChildItem -LiteralPath $msJdkDir -Directory -Filter 'jdk-21*' | Sort-Object Name -Descending | Select-Object -First 1
            }
            if ($jdkCandidate -and (Test-Path -LiteralPath (Join-Path $jdkCandidate.FullName 'bin\java.exe'))) {
                $env:JAVA_HOME = $jdkCandidate.FullName
            }
        }
        if (-not $env:JAVA_HOME) {
            $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($javaCmd) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaCmd.Source) }
        }
        $sdkDir = Join-Path $toolsRoot 'android-sdk'
        if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath $sdkDir)) {
            $env:ANDROID_HOME = $sdkDir
        }
        if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) {
            $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
        }
        $gradleHome = Join-Path $toolsRoot 'gradle-home'
        if (-not $env:GRADLE_USER_HOME -and (Test-Path -LiteralPath $gradleHome)) {
            $env:GRADLE_USER_HOME = $gradleHome
        }
        $candidates = @()
        if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin') }
        $candidates += (Join-Path $toolsRoot 'gradle-9.3.1\bin')
        $candidates += (Join-Path $toolsRoot 'android-sdk\platform-tools')
        $extraBins = @($candidates | Where-Object { Test-Path -LiteralPath $_ })
        $existingParts = @($env:Path -split ';' | Where-Object { $_ })
        $missingBins = @($extraBins | Where-Object { $existingParts -notcontains $_ })
        if ($missingBins.Count -gt 0) {
            $env:Path = ($missingBins + $existingParts) -join ';'
        }
    }
}

Initialize-LocalToolchain -RepoRoot $PSScriptRoot

function Get-JavaTestClasses([string]$RootDir) {
    $base = (Resolve-Path -LiteralPath $RootDir).Path.TrimEnd('\', '/')
    @(Get-ChildItem -LiteralPath $base -Recurse -Filter '*Test.class' |
        Where-Object { $_.Name -notmatch '\$' } |
        ForEach-Object {
            $_.FullName.Substring($base.Length).TrimStart('\', '/').Replace('\', '.').Replace('/', '.').Replace('.class', '')
        })
}

function Resolve-CachedJars([string]$LibRoot, [string[]]$ArtifactSubpaths) {
    $resolved = @()
    foreach ($subpath in $ArtifactSubpaths) {
        $dir = Join-Path $LibRoot $subpath
        if (!(Test-Path -LiteralPath $dir)) {
            throw "缺少本地 Gradle 缓存依赖目录: $dir"
        }
        $jars = @(Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Select-Object -ExpandProperty FullName)
        if ($jars.Count -eq 0) {
            throw "依赖目录中未找到 JAR 文件: $dir"
        }
        $resolved += $jars
    }
    $resolved
}

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\$($Variant.ToLower())\app-$($Variant.ToLower()).apk"
if (!$SkipBuild) {
    # Recreate the APK so incremental ZIP holes do not inflate distributable packages.
    # Only this generated APK is removed; source, databases, and previous delivered APKs are retained.
    if (Test-Path -LiteralPath $apk) { Remove-Item -LiteralPath $apk -Force }
    $tasks = @(':core:compileTestKotlin', ':app:compileDebugUnitTestKotlin', ":app:assemble$Variant", ':app:lintDebug')
    if ($CheckRelease) {
        $tasks += @(':app:compileDebugAndroidTestKotlin', ':app:assembleRelease')
    }
    $gradleArgs = @($tasks) + @('--console=plain')
    if ($Offline) { $gradleArgs += '--offline' }
    & gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Android 构建或检查失败。' }
}

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw '未配置有效 JAVA_HOME（需包含 bin\java.exe）。'
}
if (-not $env:GRADLE_USER_HOME) {
    throw '未配置 GRADLE_USER_HOME，无法定位本地 Gradle 依赖缓存。'
}

# Direct JUnit invocation avoids the Gradle worker @argfile encoding issue on Windows non-ASCII paths.
$libRoot = Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1'
$coreJarPaths = @(
    'org.jetbrains.kotlin\kotlin-stdlib\2.2.10',
    'junit\junit\4.13.2',
    'org.hamcrest\hamcrest-core\1.3',
    'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2',
    'com.squareup.okhttp3\okhttp\4.12.0',
    'com.squareup.okio\okio-jvm\3.6.0'
)
$testJars = Resolve-CachedJars -LibRoot $libRoot -ArtifactSubpaths $coreJarPaths
$jsonJars = Resolve-CachedJars -LibRoot $libRoot -ArtifactSubpaths @('org.json\json\20250517')

$coreTestRoot = (Resolve-Path 'core\build\classes\kotlin\test').Path
$coreMainRoot = (Resolve-Path 'core\build\classes\kotlin\main').Path
$testClasses = Get-JavaTestClasses -RootDir $coreTestRoot
if ($testClasses.Count -eq 0) { throw '未找到 :core 测试类。' }
$testClasspath = (@($coreTestRoot, $coreMainRoot) + $testJars) -join ';'
Write-Output "Running :core JVM tests ($($testClasses.Count) classes)..."
& "$env:JAVA_HOME\bin\java.exe" -cp $testClasspath org.junit.runner.JUnitCore @testClasses
if ($LASTEXITCODE -ne 0) { throw '统计核心测试失败。' }

$backupClass = Get-ChildItem 'app\build\intermediates\built_in_kotlinc\debugUnitTest' -Recurse -Filter 'BackupProvenanceTest.class' | Select-Object -First 1
if (!$backupClass) { throw '未找到 :app JVM 测试编译结果。' }
$appTestRoot = $backupClass.FullName.Substring(0, $backupClass.FullName.Length - 'dev\usagetime\app\BackupProvenanceTest.class'.Length)
$appMainRoot = (Resolve-Path 'app\build\intermediates\built_in_kotlinc\debug\compileDebugKotlin\classes').Path
$appClasspath = (@($appTestRoot, $appMainRoot, $coreMainRoot) + $jsonJars + $testJars) -join ';'
$appTestClasses = Get-JavaTestClasses -RootDir $appTestRoot
if ($appTestClasses.Count -eq 0) { throw '未找到 :app 测试类。' }
Write-Output "Running :app JVM tests ($($appTestClasses.Count) classes)..."
& "$env:JAVA_HOME\bin\java.exe" -cp $appClasspath org.junit.runner.JUnitCore @appTestClasses
if ($LASTEXITCODE -ne 0) { throw '应用层 JVM 回归测试失败。' }

if ($RunPythonChecks) {
    Write-Output 'Running Python verification scripts...'
    & python -m unittest discover -s tools -p 'test_*.py'
    if ($LASTEXITCODE -ne 0) { throw 'Python 工具测试失败。' }
    & python tools\verify_dual_storage.py
    if ($LASTEXITCODE -ne 0) { throw '双层存储 SQL 校验失败。' }
}

if (Test-Path -LiteralPath $apk) {
    Write-Output "APK: $apk"
    Get-FileHash -Algorithm SHA256 -LiteralPath $apk
}
