param([ValidateSet('Debug','Preview')][string]$Variant='Preview')
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if(!$env:JAVA_HOME){throw 'Set JAVA_HOME to JDK 21.'}
if(!$env:GRADLE_USER_HOME){$env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle'}
# Recreate the APK so incremental ZIP holes do not inflate distributable packages.
# Only this generated APK is removed; source, databases, and previous delivered APKs are retained.
$apk=Join-Path $PSScriptRoot "app\build\outputs\apk\$($Variant.ToLower())\app-$($Variant.ToLower()).apk"
if(Test-Path -LiteralPath $apk){Remove-Item -LiteralPath $apk -Force}
& (Join-Path $PSScriptRoot 'gradlew.bat') :core:compileTestKotlin :app:compileDebugUnitTestKotlin ":app:assemble$Variant" :app:lintDebug --console=plain
if($LASTEXITCODE -ne 0){throw 'Android构建或检查失败。'}
# Direct JUnit invocation avoids the Gradle worker argument-file encoding issue on this Windows Chinese path.
$libRoot=Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1'
$jarFolders=@('org.jetbrains.kotlin\kotlin-stdlib\2.2.10','junit\junit\4.13.2','org.hamcrest\hamcrest-core\1.3','org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2','com.squareup.okhttp3\okhttp\4.12.0','com.squareup.okio\okio-jvm\3.6.0')
$testJars=$jarFolders | ForEach-Object{Get-ChildItem (Join-Path $libRoot $_) -Recurse -Filter *.jar | Select-Object -ExpandProperty FullName}
$testRoot=(Resolve-Path 'core\build\classes\kotlin\test').Path
$testClasses=@(Get-ChildItem $testRoot -Recurse -Filter '*Test.class' | Where-Object {$_.Name -notmatch '\$'} | ForEach-Object{[System.IO.Path]::GetRelativePath($testRoot,$_.FullName).Replace('\','.').Replace('.class','')})
$testClasspath=(@($testRoot,(Resolve-Path 'core\build\classes\kotlin\main').Path)+$testJars) -join ';'
& "$env:JAVA_HOME\bin\java.exe" -cp $testClasspath org.junit.runner.JUnitCore @testClasses
if($LASTEXITCODE -ne 0){throw '统计核心测试失败。'}
# Pure JVM app tests exercise backup metadata and report-selection decisions without Android data.
$backupClass=Get-ChildItem 'app/build/intermediates/built_in_kotlinc/debugUnitTest' -Recurse -Filter 'BackupProvenanceTest.class' | Select-Object -First 1
if(!$backupClass){throw '未找到备份回归测试编译结果。'}
$backupTestRoot=$backupClass.FullName.Substring(0,$backupClass.FullName.Length-'dev\usagetime\app\BackupProvenanceTest.class'.Length)
$appMainRoot=(Resolve-Path 'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes').Path
$jsonJar=Get-ChildItem (Join-Path $libRoot 'org.json/json/20250517') -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$backupClasspath=(@($backupTestRoot,$appMainRoot,(Resolve-Path 'core/build/classes/kotlin/main').Path,$jsonJar)+$testJars)-join ';'
$appTestClasses=@(Get-ChildItem $backupTestRoot -Recurse -Filter '*Test.class' | Where-Object {$_.Name -notmatch '\$'} | ForEach-Object{[System.IO.Path]::GetRelativePath($backupTestRoot,$_.FullName).Replace('\','.').Replace('.class','')})
& "$env:JAVA_HOME/bin/java.exe" -cp $backupClasspath org.junit.runner.JUnitCore @appTestClasses
if($LASTEXITCODE -ne 0){throw '备份回归测试失败。'}
$apk=Join-Path $PSScriptRoot "app\build\outputs\apk\$($Variant.ToLower())\app-$($Variant.ToLower()).apk"
Write-Output "APK: $apk"
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
