# ============================================================================
#  Solo 数据库备份（Windows 开发机 / 无 bash 环境用；Linux 服务器请用 backup.sh）
#
#  依赖 pg_dump / mysqldump 在 PATH 中（PostgreSQL / MySQL 客户端工具即可）。
#  与 backup.sh 同口径：PostgreSQL 用 -Fc，MySQL 用 mysqldump + gzip，备份后自校验。
#
#  用法
#    $env:PGPASSWORD='xxx'; .\scripts\backup.ps1
#    .\scripts\backup.ps1 -Kind mysql -HostName 192.168.1.10 -User root -Password xxx
# ============================================================================
param(
    [ValidateSet('pg', 'mysql')][string]$Kind = 'pg',
    [string]$HostName = '127.0.0.1',
    [string]$Port = '',
    [string]$Database = 'solo_db',
    [string]$User = 'postgres',
    [string]$Password = '',
    [string]$BackupDir = './backups',
    [int]$KeepDays = 14
)

$ErrorActionPreference = 'Stop'

if (-not $Port) { $Port = if ($Kind -eq 'pg') { '5432' } else { '3306' } }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null

if ($Kind -eq 'pg') {
    if (-not $env:PGPASSWORD -and -not $Password) {
        throw 'PostgreSQL 备份需要 PGPASSWORD 或 -Password（推荐用 %APPDATA%\postgresql\pgpass.conf）'
    }
    if ($Password) { $env:PGPASSWORD = $Password }
    $file = Join-Path $BackupDir "$Database-$stamp.dump"
    Write-Host "[备份] pg_dump -> $file"
    & pg_dump -h $HostName -p $Port -U $User -d $Database -Fc --no-owner --no-privileges -f $file
    if ($LASTEXITCODE -ne 0) { throw "pg_dump 失败（exit=$LASTEXITCODE）" }
    & pg_restore --list $file | Out-Null
    if ($LASTEXITCODE -ne 0) { Remove-Item -Force $file; throw '备份自校验失败，已删除半成品' }
} else {
    if (-not $Password) { throw 'MySQL 备份需要 -Password' }
    $env:MYSQL_PWD = $Password
    $gz = Join-Path $BackupDir "$Database-$stamp.sql.gz"
    Write-Host "[备份] mysqldump -> $gz"
    # PowerShell 的 > 重定向会把输出按文本再编码，SQL 里的中文/二进制会坏；
    # 交给 cmd 在操作系统层直接落盘，保证字节一致
    $sql = Join-Path $env:TEMP "$Database-$stamp.sql"
    $dumpArgs = 'mysqldump -h {0} -P {1} -u {2} --single-transaction --quick --routines ' +
                '--triggers --events --default-character-set=utf8mb4 {3} > "{4}"'
    $dumpArgs = $dumpArgs -f $HostName, $Port, $User, $Database, $sql
    & cmd /c $dumpArgs
    if ($LASTEXITCODE -ne 0) { Remove-Item -Force $sql -ErrorAction SilentlyContinue; throw "mysqldump 失败（exit=$LASTEXITCODE）" }
    $in = [System.IO.File]::OpenRead($sql)
    $out = [System.IO.File]::Create($gz)
    $gzip = New-Object System.IO.Compression.GZipStream($out, [System.IO.Compression.CompressionLevel]::Optimal)
    try { $in.CopyTo($gzip) } finally { $gzip.Dispose(); $out.Dispose(); $in.Dispose() }
    Remove-Item -Force $sql
    $file = $gz
}

$sizeMb = [math]::Round((Get-Item $file).Length / 1MB, 1)
Write-Host "[备份] 完成 $file（${sizeMb} MB，自校验通过）"

Get-ChildItem $BackupDir -File |
    Where-Object { $_.Name -like "$Database-*" -and $_.LastWriteTime -lt (Get-Date).AddDays(-$KeepDays) } |
    ForEach-Object {
        Write-Host "[备份] 清理过期 $($_.Name)"
        Remove-Item -Force $_.FullName
    }

Write-Host "[备份] 保留策略：$KeepDays 天；目录 $BackupDir"
