param([string]$Jdk = $env:JAVA_HOME, [switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if ($Jdk) {
    $compiler = Join-Path $Jdk 'bin/javac.exe'
    $java = Join-Path $Jdk 'bin/java.exe'
} else {
    $compiler = (Get-Command javac -ErrorAction Stop).Source
    $java = Join-Path (Split-Path $compiler) 'java.exe'
}
New-Item -ItemType Directory -Force build/classes | Out-Null
$sources = @(Get-ChildItem src/main/java,src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
& $compiler -encoding UTF-8 -cp 'lib/gson-2.13.2.jar' -d build/classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Ошибка компиляции' }
if ($Test) {
    & $java -ea -cp 'build/classes;lib/gson-2.13.2.jar' ru.museum.ComponentTest demo-python/manifest.json
    if ($LASTEXITCODE -ne 0) { throw 'Проверка завершилась ошибкой' }
} else {
    & $java -cp 'build/classes;lib/gson-2.13.2.jar' ru.museum.Main
}

