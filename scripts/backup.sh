#!/usr/bin/env bash
# ============================================================================
#  Solo 数据库备份（M7 / S6，对应架构文档 5.2.1 备份预算）
#
#  设计取舍
#    · PostgreSQL 用 -Fc（自定义格式）：自带压缩、可 pg_restore 只挑表恢复、可并行；
#    · MySQL 用 mysqldump + gzip：--single-transaction 保证一致性快照且不锁业务表；
#    · 备份完立刻自校验（pg_restore --list / gzip -t）：坏包比没包更危险；
#    · 失败自动删除半成品，避免"看起来有备份，其实恢复不了"。
#
#  用法
#    PGPASSWORD=xxx ./scripts/backup.sh
#    DB_KIND=mysql MYSQL_PWD=xxx DB_HOST=192.168.1.10 DB_USER=root ./scripts/backup.sh
#
#  可覆盖变量
#    DB_KIND=pg|mysql   DB_HOST   DB_PORT   DB_NAME   DB_USER
#    BACKUP_DIR（默认 ./backups）  KEEP_DAYS（默认 14）
#
#  定时任务示例（cron，每天 02:30）
#    30 2 * * * cd /opt/solo-platform && PGPASSWORD=xxx ./scripts/backup.sh >> /var/log/solo/backup.log 2>&1
#
#  注意：本脚本只备份**关系库**。对象存储里的录像/文件走存储侧的生命周期与版本控制，
#  不要把几十 GB 的录像塞进数据库备份里。
# ============================================================================
set -euo pipefail

DB_KIND="${DB_KIND:-pg}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_NAME="${DB_NAME:-solo_db}"
DB_USER="${DB_USER:-postgres}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
TS="$(date +%Y%m%d-%H%M%S)"

case "$DB_KIND" in
  pg)
    DB_PORT="${DB_PORT:-5432}"
    FILE="$BACKUP_DIR/${DB_NAME}-${TS}.dump"
    ;;
  mysql)
    DB_PORT="${DB_PORT:-3306}"
    FILE="$BACKUP_DIR/${DB_NAME}-${TS}.sql.gz"
    ;;
  *)
    echo "[备份] 不支持的 DB_KIND=$DB_KIND（可选 pg|mysql）" >&2
    exit 2
    ;;
esac

mkdir -p "$BACKUP_DIR"

# 失败时删掉半成品：留下一个能列目录却恢复不了的文件，比没有备份更糟
cleanup_on_error() {
  rc=$?
  if [ "$rc" -ne 0 ] && [ -f "$FILE" ]; then
    rm -f "$FILE"
    echo "[备份] 失败（rc=$rc），已删除半成品 $FILE" >&2
  fi
}
trap cleanup_on_error EXIT

echo "[备份] 开始 kind=$DB_KIND target=$DB_HOST:$DB_PORT/$DB_NAME -> $FILE"

if [ "$DB_KIND" = "pg" ]; then
  : "${PGPASSWORD:?PostgreSQL 备份需要 PGPASSWORD（推荐用 ~/.pgpass 而不是明文变量）}"
  pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
      -Fc --no-owner --no-privileges -f "$FILE"
  # 自校验：能列出归档目录 = 文件结构与压缩头都完整
  pg_restore --list "$FILE" >/dev/null
else
  : "${MYSQL_PWD:?MySQL 备份需要 MYSQL_PWD}"
  mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
      --single-transaction --quick --routines --triggers --events \
      --default-character-set=utf8mb4 \
      "$DB_NAME" | gzip -c > "$FILE"
  gzip -t "$FILE"
fi

SIZE="$(du -h "$FILE" | cut -f1)"
echo "[备份] 完成 $FILE（$SIZE，自校验通过）"

# 清理过期备份：按修改时间算，不依赖文件名排序
find "$BACKUP_DIR" -maxdepth 1 -type f -name "${DB_NAME}-*" \
    \( -name '*.dump' -o -name '*.sql.gz' \) -mtime +"$KEEP_DAYS" \
    -print -delete | sed 's/^/[备份] 清理过期 /'

echo "[备份] 保留策略：${KEEP_DAYS} 天；目录 $BACKUP_DIR"
