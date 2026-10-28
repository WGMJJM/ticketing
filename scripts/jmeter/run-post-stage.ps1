param(
    [Parameter(Mandatory=$true)][string]$SessionsFile,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$BatchId,
    [Parameter(Mandatory=$true)][int]$Threads,
    [Parameter(Mandatory=$true)][double]$TargetRps,
    [Parameter(Mandatory=$true)][int]$BackendPid,
    [int]$RampSeconds=60,
    [int]$MeasureSeconds=30
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
if ($BatchId -notmatch '^[A-Za-z0-9_-]{8,60}$') { throw '批次标识无效' }
$listener = Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue
$backend = Get-CimInstance Win32_Process -Filter "ProcessId=$BackendPid"
if (-not $listener -or $listener.OwningProcess -ne $BackendPid -or -not $backend -or $backend.CommandLine -notmatch 'ticketing-backend-0.1.0-SNAPSHOT.jar' -or $backend.CommandLine -notmatch 'ticketing.purchase.capacity=1000') { throw '隔离后端进程、端口或容量不匹配' }
$sessions = (Resolve-Path -LiteralPath $SessionsFile).Path
$jmeterRoot = (Resolve-Path -LiteralPath '.local/jmeter').Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $output.StartsWith($jmeterRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '输出必须位于隔离目录' }
if (Test-Path -LiteralPath $output) { throw '本轮输出目录已存在' }
python -X utf8 -B scripts/jmeter/check-post-supply.py --sessions $sessions --threads $Threads --rate $TargetRps --seconds $MeasureSeconds
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Path $output | Out-Null
$monitor = Start-Process python -ArgumentList '-B','scripts/jmeter/monitor-separated.py',$output,$BackendPid -RedirectStandardOutput "$output/monitor.out" -RedirectStandardError "$output/monitor.err" -PassThru -WindowStyle Hidden
$monitor.Id | Set-Content "$output/monitor.pid"
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$start = $now + ($RampSeconds + 20) * 1000
$end = $start + $MeasureSeconds * 1000
@{scheduledAtMs=$now; windowStartMs=$start; windowEndMs=$end; threads=$Threads; targetRps=$TargetRps; rampSeconds=$RampSeconds; monotonicTicks=[Diagnostics.Stopwatch]::GetTimestamp(); stopwatchFrequency=[Diagnostics.Stopwatch]::Frequency} | ConvertTo-Json | Set-Content -Encoding UTF8 "$output/window.json"
$jmeter = Join-Path $env:TEMP 'apache-jmeter-5.6.3/bin/jmeter.bat'
if (-not (Test-Path -LiteralPath $jmeter)) { throw '未找到 JMeter 5.6.3' }
$env:JVM_ARGS = "-Xlog:gc:file=$($output.Replace('\','/'))/jmeter-gc.log"
Write-Output "固定 UTC 毫秒窗口 $start - $end，线程 $Threads，目标 $TargetRps RPS"
& $jmeter -n -t scripts/jmeter/purchase-session-post-only.jmx "-JsessionsCsv=$sessions" '-JskuId=1' "-JbatchId=$BatchId" "-Jthreads=$Threads" "-JrampUpSeconds=$RampSeconds" "-JtargetRps=$TargetRps" "-JstartMs=$start" "-JendMs=$end" "-JstopFile=$output/stop.flag" '-Jsample_variables=testBatch,idempotencyKey,requestId,failureCode,postOutcome' '-Jjmeter.save.saveservice.output_format=csv' '-Jjmeter.save.saveservice.print_field_names=true' '-Jjmeter.save.saveservice.response_data=false' '-Jjmeter.save.saveservice.samplerData=false' -l "$output/measure.jtl" -j "$output/jmeter.log"
$jmeterExit = $LASTEXITCODE
python -X utf8 -B scripts/jmeter/wait-for-drain.py --output "$output/drain.json" --window-end-ms $end --timeout-seconds 150
$drainExit = $LASTEXITCODE
New-Item -ItemType File -Force "$output/monitor-finished.flag" | Out-Null
Wait-Process -Id $monitor.Id -Timeout 30 -ErrorAction SilentlyContinue
python -X utf8 -B scripts/jmeter/summarize-resources.py $output --backend-pid $BackendPid
if ($LASTEXITCODE -ne 0) { throw '资源统计失败；不得继续升档' }
python -X utf8 -B scripts/jmeter/summarize-separated.py --jtl "$output/measure.jtl" --start-ms $start --end-ms $end --batch $BatchId --output $output
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($jmeterExit -ne 0 -or $drainExit -ne 0 -or (Test-Path -LiteralPath "$output/stop.flag")) { throw '本档发生保护中止、运行失败或未排空；不得继续升档' }
$result = Get-Content -Raw -Encoding UTF8 "$output/admission.json" | ConvertFrom-Json
$order = Get-Content -Raw -Encoding UTF8 "$output/order-creation.json" | ConvertFrom-Json
$drain = Get-Content -Raw -Encoding UTF8 "$output/drain.json" | ConvertFrom-Json
$last = $drain.samples[-1]
$stock = $order.databaseStock
if ($result.accepted -ne $result.postCount -or $result.acceptedRps -lt $TargetRps * 0.95 -or $result.postsAfterWindow -ne 0 -or $order.successfulLatencySamples -ne $result.accepted -or $order.unknownAccepted -ne 0 -or $order.associationAnomalies -ne 0 -or $order.duplicateOrders -ne 0 -or $order.duplicateRequestOrders -ne 0 -or $stock.total_stock - $stock.locked_stock - $stock.sold_stock -ne $last.skuAvailable) { throw '本档速率、受理、窗口、订单或库存对账异常；不得继续升档' }
