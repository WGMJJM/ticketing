param(
    [string]$Version = 'v2.3.0',
    [string]$Sha256 = '112276d495e5741c968e2bc09ea6196099c1275bd6db9ee0875d173c7148ce43'
)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+$' -or $Sha256 -notmatch '^[a-fA-F0-9]{64}$') {
    throw '请提供正式版本号及官方SHA256校验值'
}
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$toolsDir = Join-Path $projectRoot '.local/performance/tools'
New-Item -ItemType Directory -Force $toolsDir | Out-Null
$archiveName = "k6-$Version-windows-amd64.zip"
$archivePath = Join-Path $toolsDir $archiveName
Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/grafana/k6/releases/download/$Version/$archiveName" -OutFile $archivePath
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $Sha256) {
    throw 'k6校验和不匹配，禁止执行下载文件'
}
Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsDir -Force
& (Join-Path $toolsDir "k6-$Version-windows-amd64/k6.exe") version
