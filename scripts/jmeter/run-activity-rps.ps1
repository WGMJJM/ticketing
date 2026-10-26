param(
    [Parameter(Mandatory = $true)][int]$Threads,
    [int]$RampUpSeconds = 30,
    [int]$WarmupSeconds = 60,
    [int]$MeasureSeconds = 120,
    [switch]$DisableCache,
    [string]$JMeterHome = (Join-Path $env:TEMP 'apache-jmeter-5.6.3')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtime = Join-Path $repoRoot '.local\jmeter'
$envFile = Join-Path $runtime 'compose.env'
$activityData = Join-Path $runtime 'activity-data.json'
$composeFile = Join-Path $repoRoot 'deploy\compose.yml'
$plan = Join-Path $PSScriptRoot 'activity-detail-rps.jmx'
$summarizer = Join-Path $PSScriptRoot 'summarize-activity-rps.py'
$compressor = Join-Path $PSScriptRoot 'compress-jtl.py'
$jmeter = Join-Path $JMeterHome 'bin\jmeter.bat'

if ($Threads -lt 1 -or $Threads -gt 400) { throw 'Threads 须在 1 至 400 之间。' }
if ($RampUpSeconds -lt 0 -or $WarmupSeconds -lt 0 -or $MeasureSeconds -lt 30) { throw '测量至少 30 秒，爬坡和预热不得为负。' }
if (-not (Test-Path -LiteralPath $envFile) -or -not (Test-Path -LiteralPath $activityData)) {
    throw '缺少隔离环境或活动夹具；先按 ACTIVITY_RPS.md 准备。'
}
if (-not (Test-Path -LiteralPath $jmeter)) { throw "找不到 JMeter CLI：$jmeter" }

$settings = @{}
Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
$expectedCacheEnabled = if ($DisableCache) { 'false' } else { 'true' }
if ($settings['MYSQL_DATABASE'] -ne 'ticketingjmeter' -or $settings['MYSQL_PORT'] -ne '13307' -or
    $settings['SERVER_PORT'] -ne '18080' -or $settings['CATALOG_CACHE_ENABLED'] -ne $expectedCacheEnabled) {
    throw '隔离库、服务端口或活动目录缓存配置不匹配；拒绝启动负载。'
}

$composeArgs = @('compose', '--project-name', 'ticketing-jmeter', '--env-file', $envFile, '--file', $composeFile, 'ps', '--services', '--status', 'running')
$services = @(& docker @composeArgs)
if ($LASTEXITCODE -ne 0 -or @('mysql', 'redis', 'rabbitmq' | Where-Object { $services -notcontains $_ }).Count -ne 0) {
    throw 'ticketing-jmeter 隔离 Compose 的 MySQL、Redis、RabbitMQ 未全部运行。'
}

$activity = Get-Content -LiteralPath $activityData -Raw -Encoding utf8 | ConvertFrom-Json
$activityId = [string]$activity.activityId
if ($activity.environment -ne 'ticketing-jmeter-only' -or $activity.database -ne 'ticketingjmeter' -or $activityId -notmatch '^\d+$') {
    throw '活动元数据不属于隔离测试库。'
}
$health = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 5
if ($health.status -ne 'UP') { throw '隔离后端健康检查未返回 UP。' }
$detail = Invoke-RestMethod -Uri "http://127.0.0.1:18080/api/v1/activities/$activityId" -TimeoutSec 10
if ($detail.code -ne 'OK' -or [string]$detail.data.id -ne $activityId -or $detail.data.status -ne 'PUBLISHED') {
    throw '目标活动详情未通过 HTTP 业务预检。'
}

function Read-RedisCacheStats {
    $lines = @(& docker exec ticketing-jmeter-redis-1 redis-cli INFO stats)
    if ($LASTEXITCODE -ne 0) { throw '无法读取隔离 Redis 只读统计。' }
    $hitsLine = $lines | Where-Object { $_ -match '^keyspace_hits:\d+$' } | Select-Object -First 1
    $missesLine = $lines | Where-Object { $_ -match '^keyspace_misses:\d+$' } | Select-Object -First 1
    if (-not $hitsLine -or -not $missesLine) { throw '隔离 Redis 未返回 keyspace hit/miss 计数。' }
    return [ordered]@{ keyspaceHits = [long](($hitsLine -split ':')[1]); keyspaceMisses = [long](($missesLine -split ':')[1]) }
}
$redisBefore = Read-RedisCacheStats

$scenarioPrefix = if ($DisableCache) { 'activity-rps-nocache-' } else { 'activity-rps-' }
$batch = $scenarioPrefix + $Threads + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + (Get-Random -Minimum 1000 -Maximum 9999)
$resultsRoot = Join-Path $runtime 'rps-p99'
$resultDir = Join-Path $resultsRoot $batch
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null
$jtl = Join-Path $resultDir 'activity-detail.jtl'
$jtlArchive = Join-Path $resultDir 'activity-detail.jtl.gz'
$log = Join-Path $resultDir 'jmeter.log'
$summary = Join-Path $resultDir 'summary.json'
$metadataPath = Join-Path $resultDir 'run.json'
$durationSeconds = $RampUpSeconds + $WarmupSeconds + $MeasureSeconds
$os = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$dockerInfo = & docker info --format 'server={{.ServerVersion}} cpus={{.NCPU}} memoryBytes={{.MemTotal}}'
$version = (& $jmeter -v 2>&1 | Out-String).Trim()
$metadata = [ordered]@{
    batch = $batch
    activityId = $activityId
    title = [string]$activity.title
    threads = $Threads
    rampUpSeconds = $RampUpSeconds
    warmupSeconds = $WarmupSeconds
    measureSeconds = $MeasureSeconds
    durationSeconds = $durationSeconds
    server = 'http://127.0.0.1:18080'
    database = 'ticketingjmeter'
    cacheEnabled = $settings['CATALOG_CACHE_ENABLED']
    cacheNamespace = $settings['CATALOG_CACHE_NAMESPACE']
    os = $os.Caption
    logicalProcessors = $cpu.NumberOfLogicalProcessors
    totalMemoryKiB = $os.TotalVisibleMemorySize
    freeMemoryAtStartKiB = $os.FreePhysicalMemory
    processor = $cpu.Name
    docker = ($dockerInfo -join '').Trim()
    jmeterVersion = $version
    cacheScenario = if ($DisableCache) { "同一 PUBLISHED 活动；关闭活动目录 L1/L2 缓存，每次读取数据库；预热 $WarmupSeconds 秒。" } else { "同一 PUBLISHED 活动；预热 $WarmupSeconds 秒后测量；单后端实例，Caffeine L1 15 秒、Redis L2 120 秒。" }
    loadModel = 'JMeter 闭环并发线程，无节流 Timer；线程数不等于目标 RPS。'
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding utf8

$jmeterArgs = @(
    '-n', '-t', $plan,
    '-Jhost=127.0.0.1', '-Jport=18080', '-Jprotocol=http',
    "-JactivityId=$activityId", "-JbatchId=$batch", "-Jthreads=$Threads",
    "-JrampUpSeconds=$RampUpSeconds", "-JwarmupSeconds=$WarmupSeconds",
    "-JmeasureSeconds=$MeasureSeconds", "-JdurationSeconds=$durationSeconds",
    '-JconnectTimeoutMs=3000', '-JresponseTimeoutMs=10000',
    '-Jjmeter.save.saveservice.output_format=csv',
    '-Jjmeter.save.saveservice.print_field_names=true',
    '-Jjmeter.save.saveservice.timestamp_format=ms',
    '-Jsampleresult.timestamp.start=true',
    '-Jjmeter.save.saveservice.response_data=false',
    '-Jjmeter.save.saveservice.samplerData=false',
    '-Jjmeter.save.saveservice.response_message=false',
    '-Jjmeter.save.saveservice.response_code=true',
    '-Jjmeter.save.saveservice.success=true',
    '-Jjmeter.save.saveservice.label=true',
    '-Jjmeter.save.saveservice.time=true',
    '-Jjmeter.save.saveservice.thread_name=false',
    '-Jjmeter.save.saveservice.thread_counts=true',
    '-Jjmeter.save.saveservice.connect_time=true',
    '-Jjmeter.save.saveservice.latency=true',
    '-Jjmeter.save.saveservice.data_type=false',
    '-Jjmeter.save.saveservice.failure_message=false',
    '-Jjmeter.save.saveservice.bytes=false',
    '-Jjmeter.save.saveservice.sent_bytes=false',
    '-Jjmeter.save.saveservice.url=false',
    '-Jjmeter.save.saveservice.idle_time=false',
    '-Jjmeter.save.saveservice.assertion_results=false',
    '-Jsample_variables=batchId,activityId,samplePhase,businessOk,failureCode',
    '-l', $jtl, '-j', $log
)
Write-Output "开始批次 $batch，活动 ID $activityId，线程 $Threads；结果保存在隔离目录。"
& $jmeter @jmeterArgs
if ($LASTEXITCODE -ne 0) { throw "JMeter 退出码为 $LASTEXITCODE；原始日志保留在 $resultDir。" }

$summaryArgs = @(
    '-B', $summarizer, '--jtl', $jtl, '--output', $summary, '--batch', $batch,
    '--activity-id', $activityId, '--threads', [string]$Threads,
    '--ramp-up-seconds', [string]$RampUpSeconds, '--warmup-seconds', [string]$WarmupSeconds,
    '--measure-seconds', [string]$MeasureSeconds
)
python @summaryArgs
if ($LASTEXITCODE -ne 0) { throw "JTL 汇总失败；原始结果保留在 $resultDir。" }
$redisAfter = Read-RedisCacheStats
$metadata.redisKeyspaceBefore = $redisBefore
$metadata.redisKeyspaceAfter = $redisAfter
$metadata.redisKeyspaceDelta = [ordered]@{
    hits = $redisAfter.keyspaceHits - $redisBefore.keyspaceHits
    misses = $redisAfter.keyspaceMisses - $redisBefore.keyspaceMisses
}
$osAfter = Get-CimInstance Win32_OperatingSystem
$metadata.freeMemoryAtEndKiB = $osAfter.FreePhysicalMemory
try {
    $metadata.dockerStatsAtEnd = @(& docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' ticketing-jmeter-mysql-1 ticketing-jmeter-redis-1 ticketing-jmeter-rabbitmq-1)
} catch {
    $metadata.dockerStatsAtEnd = @('unavailable')
}
$metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $metadataPath -Encoding utf8
python -B $compressor $jtl $jtlArchive
if ($LASTEXITCODE -ne 0) { throw "JTL 压缩失败；原始结果保留在 $resultDir。" }
Write-Output "压缩原始 JTL、JMeter 日志和 JSON 摘要：$resultDir"
