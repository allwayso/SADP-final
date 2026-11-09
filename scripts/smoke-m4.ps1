<#
.SYNOPSIS
    check@collect M4 消息与交流端到端验收脚本。

.DESCRIPTION
    需要后端已启动（scripts/run-backend.ps1），数据库已由 Flyway 迁移到 V2，
    并使用 APP_SEED_DEMO=true 创建 member-a 到 member-d 四个演示账号。

    脚本依次验证：
      1. 角色群发：新数据通知发给全部核验者，未读和已读状态可持久化；
      2. 两个角色群聊：采集者、核验者按角色自动加入，非成员被拒绝；
      3. 点发与错误单私聊：通知只发给当事人，私聊只有当事双方可读写；
      4. 中间件中断后的失败重试：通知先记为待重试，恢复后补投成功；
      5. 重复投递去重：重复消费不新增收件记录、不改变状态。

.PARAMETER BaseUrl
    后端地址，默认 http://localhost:8080 。

.PARAMETER Password
    演示账号当前密码。省略时读取仓库根目录 .env 中的 DEMO_PASSWORD 。

.PARAMETER NewPassword
    仅在演示账号首次登录时使用：提供后脚本会自动完成改密，之后再用新密码运行。

.EXAMPLE
    pwsh -File scripts/smoke-m4.ps1 -Password Demo-Password-2026
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Password = '',
    [string]$NewPassword = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Read-EnvValue {
    param([string]$Name)
    $path = Join-Path $repo '.env'
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    foreach ($line in Get-Content -LiteralPath $path) {
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        $pair = $entry.Split('=', 2)
        if ($pair.Count -eq 2 -and $pair[0].Trim() -eq $Name) { return $pair[1] }
    }
    return $null
}

if (-not $Password) { $Password = Read-EnvValue 'DEMO_PASSWORD' }
if (-not $Password) {
    throw '请使用 -Password 提供演示账号密码，或在 .env 中设置 DEMO_PASSWORD。'
}

$script:checks = 0
function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "检查失败：$Message" }
    $script:checks++
    Write-Host "  [通过] $Message" -ForegroundColor Green
}

function New-Account {
    param([string]$Username, [string]$CurrentPassword)
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -WebSession $session -Method Get
    $loginBody = @{ username = $Username; password = $CurrentPassword } | ConvertTo-Json -Compress
    try {
        $me = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -Body $loginBody `
            -ContentType 'application/json' -WebSession $session -Headers @{ 'X-XSRF-TOKEN' = $csrf.token }
    } catch {
        throw "账号 $Username 登录失败，请确认后端已启动且密码正确。$($_.Exception.Message)"
    }
    $account = [pscustomobject]@{ Username = $Username; Session = $session; Me = $me }
    if ($me.mustChangePassword) {
        if (-not $NewPassword) {
            throw "账号 $Username 是首次登录，需要加 -NewPassword 完成改密后再运行。"
        }
        $csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -WebSession $session -Method Get
        $body = @{ oldPassword = $CurrentPassword; newPassword = $NewPassword } | ConvertTo-Json -Compress
        Invoke-RestMethod -Uri "$BaseUrl/api/me/password" -Method Post -Body $body `
            -ContentType 'application/json' -WebSession $session -Headers @{ 'X-XSRF-TOKEN' = $csrf.token } | Out-Null
        $account.Me = Invoke-RestMethod -Uri "$BaseUrl/api/me" -WebSession $session -Method Get
    }
    return $account
}

function Invoke-Api {
    param($Account, [string]$Method, [string]$Path, $Body = $null)
    $headers = @{}
    if ($Method -ne 'GET') {
        $csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -WebSession $Account.Session -Method Get
        $headers['X-XSRF-TOKEN'] = $csrf.token
    }
    $parameters = @{ Uri = "$BaseUrl$Path"; Method = $Method; WebSession = $Account.Session; Headers = $headers }
    if ($null -ne $Body) {
        $parameters.Body = ($Body | ConvertTo-Json -Depth 5 -Compress)
        $parameters.ContentType = 'application/json'
    }
    return Invoke-RestMethod @parameters
}

function Assert-Status {
    param([scriptblock]$Action, [int]$Expected, [string]$Message)
    try {
        & $Action | Out-Null
        throw "检查失败：$Message（请求成功，期望 HTTP $Expected）"
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        $code = [int]$response.StatusCode
        Assert-That ($code -eq $Expected) "$Message（HTTP $code）"
    }
}

function Wait-NotificationStatus {
    param($Account, [string]$NotificationId, [string]$Expected, [int]$TimeoutSeconds = 10)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $status = Invoke-Api $Account 'GET' '/api/messaging/stub/status'
        $row = @($status.recent | Where-Object { $_.id -eq $NotificationId }) | Select-Object -First 1
        if ($null -ne $row -and $row.status -eq $Expected) { return $row }
        $last = $row
        Start-Sleep -Milliseconds 400
    }
    $current = if ($null -eq $last) { '未出现在最近通知中' } else { $last.status }
    throw "通知 $NotificationId 未在 $TimeoutSeconds 秒内变为 $Expected（当前：$current）"
}

Write-Host ''
Write-Host '=== check@collect M4 消息与交流 端到端验收 ===' -ForegroundColor Cyan

Write-Host ''
Write-Host '1. 登录四个演示账号' -ForegroundColor Cyan
$a = New-Account 'member-a' $Password
$b = New-Account 'member-b' $Password
$c = New-Account 'member-c' $Password
$d = New-Account 'member-d' $Password
Assert-That ($a.Me.id -ne $b.Me.id -and $b.Me.id -ne $c.Me.id -and $c.Me.id -ne $d.Me.id) '四个演示账号拥有不同用户标识'

Write-Host ''
Write-Host '2. 角色群发与已读状态（新数据待检 → 全部核验者）' -ForegroundColor Cyan
$status = Invoke-Api $a 'GET' '/api/messaging/stub/status'
Assert-That ($null -ne $status.transport) "消息桩可用，投递通道为 $($status.transport)"
if (-not $status.brokerAvailable) {
    Invoke-Api $a 'POST' '/api/messaging/stub/fault' @{ down = $false } | Out-Null
    Write-Host '  已恢复模拟中间件' -ForegroundColor Yellow
}
$newData = Invoke-Api $a 'POST' '/api/messaging/stub/scenario' @{ scenario = 'NEW_DATA' }
$newDataId = $newData.notificationIds[0]
Wait-NotificationStatus $a $newDataId 'DELIVERED' | Out-Null
$cInbox = Invoke-Api $c 'GET' '/api/notifications?page=0&size=50'
$cItem = @($cInbox.items | Where-Object { $_.id -eq $newDataId }) | Select-Object -First 1
Assert-That ($null -ne $cItem) '核验者 member-c 收到群发通知'
Assert-That ($cItem.read -eq $false) '新通知初始为未读'
$unreadBefore = $cInbox.unread
Invoke-Api $c 'POST' "/api/notifications/$newDataId/read" | Out-Null
$cInboxAfter = Invoke-Api $c 'GET' '/api/notifications?page=0&size=50'
$cItemAfter = @($cInboxAfter.items | Where-Object { $_.id -eq $newDataId }) | Select-Object -First 1
Assert-That ($cItemAfter.read -eq $true) '已读状态在重新查询后保持'
Assert-That ($cInboxAfter.unread -eq ($unreadBefore - 1)) '未读数量减一'

Write-Host ''
Write-Host '3. 两个角色群聊与成员权限' -ForegroundColor Cyan
$collectorGroup = @((Invoke-Api $b 'GET' '/api/conversations') | Where-Object { $_.kind -eq 'ROLE_GROUP' -and $_.role -eq 'COLLECTOR' }) | Select-Object -First 1
Assert-That ($null -ne $collectorGroup) '采集者 member-b 自动加入“采集者交流”群'
$groupMessage = '采集技巧：拍摄前先确认定位精度。（自动验收消息）'
Invoke-Api $b 'POST' "/api/conversations/$($collectorGroup.id)/messages" @{ body = $groupMessage } | Out-Null
$groupMessages = Invoke-Api $b 'GET' "/api/conversations/$($collectorGroup.id)/messages"
Assert-That ((@($groupMessages | Where-Object { $_.body -eq $groupMessage })).Count -ge 1) '群聊消息持久化后可以读取'
$reviewerGroup = @((Invoke-Api $c 'GET' '/api/conversations') | Where-Object { $_.kind -eq 'ROLE_GROUP' -and $_.role -eq 'REVIEWER' }) | Select-Object -First 1
Assert-That ($null -ne $reviewerGroup) '核验者 member-c 自动加入“核验者交流”群'
$dConversations = Invoke-Api $d 'GET' '/api/conversations'
Assert-That ((@($dConversations | Where-Object { $_.id -eq $reviewerGroup.id })).Count -eq 0) '第三方成员看不到核验者群聊'
Assert-Status { Invoke-Api $d 'POST' "/api/conversations/$($reviewerGroup.id)/messages" @{ body = '越权消息' } } 403 '非群成员发消息被拒绝'

Write-Host ''
Write-Host '4. 点发与错误单私聊权限' -ForegroundColor Cyan
$issue = Invoke-Api $a 'POST' '/api/messaging/stub/scenario' @{ scenario = 'ISSUE_RAISED' }
$issueNotificationId = $issue.notificationIds[0]
$issueConversationId = $issue.conversationId
Wait-NotificationStatus $a $issueNotificationId 'DELIVERED' | Out-Null
$cInboxIssue = Invoke-Api $c 'GET' '/api/notifications?page=0&size=50'
Assert-That ((@($cInboxIssue.items | Where-Object { $_.id -eq $issueNotificationId })).Count -eq 0) '错误点发通知只发给当事人，核验者未收到'
$bConversations = Invoke-Api $b 'GET' '/api/conversations'
Assert-That ((@($bConversations | Where-Object { $_.id -eq $issueConversationId })).Count -eq 1) '采集者 member-b 看到错误单私聊'
Invoke-Api $b 'POST' "/api/conversations/$issueConversationId/messages" @{ body = '我已核对，稍后重新采集并上传。' } | Out-Null
$issueMessages = Invoke-Api $a 'GET' "/api/conversations/$issueConversationId/messages"
Assert-That ((@($issueMessages | Where-Object { $_.body -like '*稍后重新采集*' })).Count -ge 1) '当事双方可以互相看到私聊消息'
Assert-Status { Invoke-Api $d 'GET' "/api/conversations/$issueConversationId/messages" } 403 '非当事第三方无法读取错误单私聊'

Write-Host ''
Write-Host '5. 中间件中断后的失败重试' -ForegroundColor Cyan
Invoke-Api $a 'POST' '/api/messaging/stub/fault' @{ down = $true } | Out-Null
$announcement = Invoke-Api $a 'POST' '/api/messaging/stub/scenario' @{ scenario = 'ANNOUNCEMENT' }
$announcementId = $announcement.notificationIds[0]
$failed = Wait-NotificationStatus $a $announcementId 'FAILED' 8
Assert-That ($failed.attempts -ge 1) '中断期间通知记录为待重试并累计尝试次数'
Invoke-Api $a 'POST' '/api/messaging/stub/fault' @{ down = $false } | Out-Null
$retry = Invoke-Api $a 'POST' '/api/messaging/stub/retry'
Assert-That ($retry.retried -ge 1) '立即重试处理了待投递通知'
$delivered = Wait-NotificationStatus $a $announcementId 'DELIVERED' 12
Assert-That ($delivered.delivered -eq $delivered.recipients) '重试后全部收件人确认投递'
$bInbox = Invoke-Api $b 'GET' '/api/notifications?page=0&size=50'
Assert-That ((@($bInbox.items | Where-Object { $_.id -eq $announcementId })).Count -eq 1) '补投成功后采集者可以读到公告'

Write-Host ''
Write-Host '6. 重复投递去重' -ForegroundColor Cyan
$first = Invoke-Api $c 'POST' "/api/messaging/stub/redeliver/$newDataId"
$second = Invoke-Api $c 'POST' "/api/messaging/stub/redeliver/$newDataId"
Assert-That ($first.statusChanged -eq $false -and $second.statusChanged -eq $false) '重复投递不改变已完成状态'
Assert-That ($second.newlyDeliveredRecipients -eq 0) '重复消费不新增收件确认'
Assert-That ($second.recipients -eq $first.recipients) '收件人数量保持不变'
$cInboxFinal = Invoke-Api $c 'GET' '/api/notifications?page=0&size=50'
Assert-That ((@($cInboxFinal.items | Where-Object { $_.id -eq $newDataId })).Count -eq 1) '同一通知对同一用户只有一条收件记录'

Write-Host ''
Write-Host "全部 $($script:checks) 项检查通过。" -ForegroundColor Green
