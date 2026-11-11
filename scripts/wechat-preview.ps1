<#
.SYNOPSIS
  用微信开发者工具生成小程序确认页预览码。

.DESCRIPTION
  生成确认页预览码。CLI preview 不保证携带编译模式 query；手机进入后输入网站显示的配对码。
  Ticket 参数仅用于输出文件命名，不能据文件名认为二维码携带了该参数。
  工具需登录且 CLI 服务端口可用，预览范围以微信平台实际权限为准。
#>
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-f0-9]{32}$')][string]$Ticket,
    [string]$ProjectPath,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
if (-not $ProjectPath) {
    $repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
    $ProjectPath = Join-Path $repo 'miniapp'
}
if (-not $OutputPath) { $OutputPath = Join-Path $env:TEMP "wechat-preview-$Ticket.png" }
$cli = Join-Path ${env:ProgramFiles(x86)} 'Tencent\微信web开发者工具\cli.bat'
if (-not (Test-Path -LiteralPath $cli)) {
    $candidate = 'D:\微信开发者工具\微信web开发者工具\cli.bat'
    if (Test-Path -LiteralPath $candidate) { $cli = $candidate }
    else { throw "未找到微信开发者工具 CLI，请确认安装位置后修改脚本中的路径。" }
}

# CLI preview 没有启动参数选项；不再覆盖编译模式并声称 ticket 一定随码传递。
# 预览页通过网站显示的配对码定位本次请求，Ticket 只用于输出文件命名。
Write-Host '预览码只打开确认页，请在手机上输入原网站当前显示的配对码。'
Write-Host "正在生成预览码……（首次调用可能需要启动开发者工具）"
& $cli preview --project $ProjectPath --qr-format image --qr-output $OutputPath --lang zh

if (Test-Path -LiteralPath $OutputPath) {
    Write-Host "预览码已生成：$OutputPath"
    Start-Process $OutputPath
} else {
    Write-Host '未生成预览码。请检查开发者工具是否已登录，以及“设置 → 安全设置 → 服务端口”是否开启。'
    Write-Host '也可以手动操作：开发者工具中打开确认页并点击“预览”，手机输入网站当前显示的配对码。'
}
