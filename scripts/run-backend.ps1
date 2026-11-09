$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$config = Join-Path $repo '.env'
if (-not (Test-Path -LiteralPath $config)) {
    throw '请先复制 .env.example 为 .env 并填写本地配置。'
}
foreach ($line in Get-Content -LiteralPath $config) {
    $entry = $line.Trim()
    if (-not $entry -or $entry.StartsWith('#')) { continue }
    $pair = $entry.Split('=', 2)
    if ($pair.Count -eq 2) {
        [Environment]::SetEnvironmentVariable($pair[0].Trim(), $pair[1], 'Process')
    }
}
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { throw '请安装 Java 17 或更高版本，并将 java 加入 PATH。' }
Push-Location (Join-Path $repo 'backend')
try { & .\mvnw.cmd spring-boot:run }
finally { Pop-Location }
