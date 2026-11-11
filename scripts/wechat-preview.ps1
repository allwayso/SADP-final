<#
.SYNOPSIS
  用微信开发者工具生成带登录 ticket 的小程序预览码。

.DESCRIPTION
  网站的 /api/auth/wechat/mini/create 会生成一个三十位的十六进制 ticket，并把 `ticket=<id>`
  作为小程序确认页的启动参数。本脚本把该参数写入开发者工具的编译模式，再调用 CLI 预览，
  输出二维码图片并打开，供已获授权的开发者/体验成员扫码确认。

  前置条件：微信开发者工具已安装并登录，且“设置 → 安全设置 → 服务端口”已开启。
  个人主体未通过验证时无法调用生码接口（85412），本脚本用开发者工具预览绕过该限制；
  预览码只对已授权的开发者/体验成员有效，不等同于正式发布。
#>
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-f0-9]{32}$')][string]$Ticket,
    [string]$ProjectPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'miniapp'),
    [string]$OutputPath = (Join-Path $env:TEMP "wechat-preview-$Ticket.png")
)

$ErrorActionPreference = 'Stop'
$cli = Join-Path ${env:ProgramFiles(x86)} 'Tencent\微信web开发者工具\cli.bat'
if (-not (Test-Path -LiteralPath $cli)) {
    $candidate = 'D:\微信开发者工具\微信web开发者工具\cli.bat'
    if (Test-Path -LiteralPath $candidate) { $cli = $candidate }
    else { throw "未找到微信开发者工具 CLI，请确认安装位置后修改脚本中的路径。" }
}

$private = Join-Path $ProjectPath 'project.private.config.json'
$config = if (Test-Path -LiteralPath $private) { Get-Content -Raw -Encoding UTF8 $private | ConvertFrom-Json } else { [pscustomobject]@{} }
$condition = [pscustomobject]@{
    miniprogram = [pscustomobject]@{
        list = @(
            [pscustomobject]@{
                name     = 'checkcollect-login'
                pathName = 'pages/confirm/confirm'
                query    = "ticket=$Ticket"
            }
        )
    }
}
if ($config.PSObject.Properties.Name -contains 'condition') { $config.condition = $condition }
else { $config | Add-Member -NotePropertyName condition -NotePropertyValue $condition }
$config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $private -Encoding UTF8

Write-Host "已在编译模式中写入启动参数：ticket=$Ticket"
Write-Host "正在生成预览码……（首次调用可能需要启动开发者工具）"
& $cli preview --project $ProjectPath --qr-format image --qr-output $OutputPath --lang zh

if (Test-Path -LiteralPath $OutputPath) {
    Write-Host "预览码已生成：$OutputPath"
    Start-Process $OutputPath
} else {
    Write-Host '未生成预览码。请检查开发者工具是否已登录，以及“设置 → 安全设置 → 服务端口”是否开启。'
    Write-Host '也可以手动操作：开发者工具中选择编译模式 checkcollect-login，点击“预览”，用手机扫码确认。'
}
