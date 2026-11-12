#!/bin/bash
# 生产 PostgreSQL 每日备份脚本（gz 压缩 + 非空校验 + 保留期滚动清理）
# 部署（生产机）：
#   sudo mkdir -p /opt/zhongshu/ops /opt/zhongshu/backups
#   sudo cp deploy/ops/backup-postgres.sh /opt/zhongshu/ops/ && sudo chmod 700 /opt/zhongshu/ops/backup-postgres.sh
#   crontab -e 加入（每日 03:30，zhongshu 用户身份）：
#     30 3 * * * /opt/zhongshu/ops/backup-postgres.sh >> /opt/zhongshu/ops/backup.log 2>&1
# 恢复演练（上线前必做一次）：
#   gunzip < /opt/zhongshu/backups/zhongshu_design-<时间戳>.sql.gz | psql -h 127.0.0.1 -U zhongshu -d 恢复目标库
set -euo pipefail

ENV_FILE=${ENV_FILE:-/opt/zhongshu/.env}
BACKUP_DIR=${BACKUP_DIR:-/opt/zhongshu/backups}
RETAIN_DAYS=${RETAIN_DAYS:-14}

[ -r "$ENV_FILE" ] || { echo "[backup] 无法读取 $ENV_FILE"; exit 1; }

# 从 .env 拆连接信息（ZS_PG_URL 为 jdbc:postgresql://host:port/db 形态）
ZS_PG_USERNAME=$(grep -E '^ZS_PG_USERNAME=' "$ENV_FILE" | cut -d= -f2-)
ZS_PG_PASSWORD=$(grep -E '^ZS_PG_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
[ -n "$ZS_PG_USERNAME" ] && [ -n "$ZS_PG_PASSWORD" ] || { echo "[backup] .env 缺少 ZS_PG_USERNAME/PASSWORD"; exit 1; }
JDBC_URL=$(grep -E '^ZS_PG_URL=' "$ENV_FILE" | cut -d= -f2-)
HOST_PORT_DB=${JDBC_URL#jdbc:postgresql://}
HOST=${HOST_PORT_DB%%:*}
REST=${HOST_PORT_DB#*:}
PORT=${REST%%/*}
DB=${REST#*/}

mkdir -p "$BACKUP_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
FILE="$BACKUP_DIR/${DB}-${STAMP}.sql.gz"

PGPASSWORD="$ZS_PG_PASSWORD" pg_dump -h "$HOST" -p "$PORT" -U "$ZS_PG_USERNAME" -d "$DB" --no-owner | gzip > "$FILE"

# 产物非空校验：空文件视为失败，绝不留假备份
[ -s "$FILE" ] || { echo "[backup] 产物为空，疑似失败：$FILE"; exit 1; }
echo "[backup] $(date '+%F %T') 完成 $FILE ($(du -h "$FILE" | cut -f1))"

# 滚动清理保留期外的历史备份
find "$BACKUP_DIR" -name "${DB}-*.sql.gz" -mtime "+$RETAIN_DAYS" -delete
