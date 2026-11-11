<#
.SYNOPSIS
  用微信开发者工具生成小程序确认页预览码。

.DESCRIPTION
  打包期间将一次性 ticket 和同一后端 HTTPS 地址写入预览上下文，完成后恢复源码。
  手机进入预览确认页后无需手输配对码；仍须本人点击确认。
  工具需登录且 CLI 服务端口可用，预览范围以微信平台实际权限为准。
#>
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-f0-9]{32}$')][string]$Ticket,
    [Parameter(Mandatory = $true)][string]$ApiBase,
    [string]$ProjectPath,
    [string]$OutputPath,
    [string]$CliPath
)

$ErrorActionPreference = 'Stop'
if (-not $ProjectPath) {
    $repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
    $ProjectPath = Join-Path $repo 'miniapp'
}
if (-not $OutputPath) { $OutputPath = Join-Path $env:TEMP "wechat-preview-$Ticket.png" }
$cli = if ($CliPath) { $CliPath } else { Join-Path ${env:ProgramFiles(x86)} 'Tencent\微信web开发者工具\cli.bat' }
if (-not (Test-Path -LiteralPath $cli)) {
    $candidate = 'D:\微信开发者工具\微信web开发者工具\cli.bat'
    if (Test-Path -LiteralPath $candidate) { $cli = $candidate }
    else { throw "未找到微信开发者工具 CLI，请确认安装位置后修改脚本中的路径。" }
}

$uri = [Uri]$ApiBase
if ($uri.Scheme -ne 'https' -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) {
    throw '手机预览必须指定同一后端实例的 HTTPS 根地址。'
}
$context = Join-Path $ProjectPath 'preview-context.js'
$original = [IO.File]::ReadAllBytes($context)
try {
    $payload = @{ ticket = $Ticket; apiBase = $ApiBase.TrimEnd('/') } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($context, "module.exports = $payload", [Text.UTF8Encoding]::new($false))
    & $cli preview --project $ProjectPath --qr-format image --qr-output $OutputPath --lang zh
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $OutputPath)) { throw '预览生成失败，不能将旧图片作为本次扫码入口。' }
    Write-Host "已生成携带一次性请求的预览码：$OutputPath"
} finally {
    [IO.File]::WriteAllBytes($context, $original)
}
