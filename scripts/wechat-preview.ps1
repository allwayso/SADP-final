<#
.SYNOPSIS
  用微信开发者工具生成小程序确认页预览码。

.DESCRIPTION
  网站的 /api/auth/wechat/mini/create 会生成一个三十二位的十六进制 ticket，并把 `ticket=<id>`
  作为小程序确认页的启动参数。CLI 不保证把编译模式参数带入预览码；使用网页配对码完成请求关联，
  输出二维码图片并打开，供已获授权的开发者/体验成员扫码确认。

  前置条件：微信开发者工具已安装并登录，且“设置 → 安全设置 → 服务端口”已开启。
  个人主体未通过验证时无法调用生码接口（85412），本脚本用开发者工具预览绕过该限制；
  预览码只对已授权的开发者/体验成员有效，不等同于正式发布。
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
