$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location (Join-Path $repo 'frontend')
try { npm ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci 失败' }; npm run build; if ($LASTEXITCODE -ne 0) { throw '前端构建失败' } }
finally { Pop-Location }
$static = Join-Path $repo 'backend/src/main/resources/static'
New-Item -ItemType Directory -Force $static | Out-Null
Copy-Item -Path (Join-Path $repo 'frontend/dist/*') -Destination $static -Recurse -Force
Push-Location (Join-Path $repo 'backend')
try { & .\mvnw.cmd package; if ($LASTEXITCODE -ne 0) { throw '后端构建失败' } }
finally { Pop-Location }
