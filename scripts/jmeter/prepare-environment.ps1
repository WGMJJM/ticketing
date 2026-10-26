param(
    [string]$RuntimeDirectory = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.local\jmeter')
)

$ErrorActionPreference = 'Stop'

if (Test-Path -LiteralPath $RuntimeDirectory) {
    $existingItems = @(Get-ChildItem -LiteralPath $RuntimeDirectory -Force)
    $unexpectedItems = @($existingItems | Where-Object { $_.Name -ne 'rps-p99' -or -not $_.PSIsContainer })
    if ($unexpectedItems.Count -gt 0) {
        throw '隔离 JMeter 运行目录含有现存凭据或其他数据；为避免覆盖，请先检查并手动处理。'
    }
}

foreach ($port in 13307, 16379, 15673, 15674, 18080) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "端口 $port 已被占用；未创建本地测试凭据。"
    }
}

function New-UrlSafeSecret([int]$ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

if (-not (Test-Path -LiteralPath $RuntimeDirectory)) {
    New-Item -ItemType Directory -Path $RuntimeDirectory | Out-Null
}
$seedName = 'jmeter_seed_' + (New-UrlSafeSecret 6).Substring(0, 8)
$rateLimitSecret = (New-UrlSafeSecret 32).Replace('-', '+').Replace('_', '/') + '='
$rateLimitNamespace = 'jmeter-' + (New-UrlSafeSecret 9).Substring(0, 10)
$catalogNamespace = 'jmeter-' + (New-UrlSafeSecret 9).Substring(0, 10)
$ticketKey = (New-UrlSafeSecret 32).Replace('-', '+').Replace('_', '/')
switch ($ticketKey.Length % 4) {
    2 { $ticketKey += '==' }
    3 { $ticketKey += '=' }
    1 { throw '随机签名密钥编码长度异常。' }
}
$ticketKeysJson = '{"1":"' + $ticketKey + '"}'

$lines = @(
    'MYSQL_IMAGE=mysql:8.4'
    'REDIS_IMAGE=redis:7-alpine'
    'RABBITMQ_IMAGE=rabbitmq:4.1-management-alpine'
    'IMAGE_PULL_POLICY=never'
    'MYSQL_DATABASE=ticketingjmeter'
    'MYSQL_USER=ticketing_jmeter'
    ('MYSQL_PASSWORD=' + (New-UrlSafeSecret 32))
    ('MYSQL_ROOT_PASSWORD=' + (New-UrlSafeSecret 32))
    'MYSQL_HOST=127.0.0.1'
    'MYSQL_PORT=13307'
    'REDIS_HOST=127.0.0.1'
    'REDIS_PORT=16379'
    'RABBITMQ_USER=ticketing_jmeter'
    ('RABBITMQ_PASSWORD=' + (New-UrlSafeSecret 32))
    'RABBITMQ_PORT=15673'
    'RABBITMQ_MANAGEMENT_PORT=15674'
    ('JMETER_GUARD_TOKEN=' + (New-UrlSafeSecret 32))
    'SERVER_PORT=18080'
    'SESSION_COOKIE_SECURE=false'
    'REGISTRATION_EMAIL_ENABLED=false'
    'RATE_LIMIT_ENABLED=true'
    ('RATE_LIMIT_KEY_SECRET=' + $rateLimitSecret)
    ('RATE_LIMIT_NAMESPACE=' + $rateLimitNamespace)
    'RATE_LIMIT_CSRF_SESSION_IP_LIMIT=10000'
    'RATE_LIMIT_LOGIN_IP_LIMIT=10000'
    'RATE_LIMIT_LOGIN_ACCOUNT_IP_LIMIT=10'
    'RATE_LIMIT_ORDER_USER_LIMIT=30'
    ('CATALOG_CACHE_NAMESPACE=' + $catalogNamespace)
    'CATALOG_CACHE_ENABLED=true'
    'PURCHASE_ADMISSION_LIMIT_ENABLED=false'
    'PURCHASE_CAPACITY=200'
    'PURCHASE_CONSUMER_CONCURRENCY=2'
    'PURCHASE_CONSUMER_PREFETCH=1'
    'ORDER_SESSION_LIMIT=4'
    'TICKET_CREDENTIAL_CURRENT_VERSION=1'
    ('TICKET_CREDENTIAL_KEYS=' + $ticketKeysJson)
    'ADMIN_BOOTSTRAP_ENABLED=true'
    ('ADMIN_BOOTSTRAP_USERNAME=' + $seedName)
    ('ADMIN_BOOTSTRAP_PASSWORD=' + (New-UrlSafeSecret 32))
)

$envPath = Join-Path $RuntimeDirectory 'compose.env'
[System.IO.File]::WriteAllLines($envPath, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Output "已生成隔离运行配置：$envPath"
Write-Output '凭据未打印；该文件只供本地测试使用，不要提交或复制到业务 .env。'
