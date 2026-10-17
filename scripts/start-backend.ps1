param([switch]$VerifyDatabase)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        if ($entry -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            throw '环境文件仅支持 KEY=value 格式，不支持命令、export 或多行值。'
        }
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key, 'Process'))) {
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
}
foreach ($key in 'MYSQL_PASSWORD', 'RABBITMQ_PASSWORD') {
    if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key, 'Process'))) {
        throw "缺少 $key，请复制 .env.example 为 .env 并填写，或通过环境变量注入。"
    }
}
$mavenArgs = @('-f', (Join-Path $projectRoot 'backend/pom.xml'))
if ($VerifyDatabase) { $mavenArgs += @('clean', 'verify', '-Pmysql-it') }
else { $mavenArgs += 'spring-boot:run' }
& mvn @mavenArgs
exit $LASTEXITCODE
