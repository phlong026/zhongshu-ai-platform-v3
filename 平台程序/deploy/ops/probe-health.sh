#!/bin/bash
# 后端健康拨测：非 200 即记录并（可选）推送企业微信群机器人，30 分钟告警冷却防刷屏
# 部署（生产机）：
#   sudo cp deploy/ops/probe-health.sh /opt/zhongshu/ops/ && sudo chmod 700 /opt/zhongshu/ops/probe-health.sh
#   在 /opt/zhongshu/.env 追加（可选）ZS_OPS_WEBHOOK=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx
#   crontab -e 加入（每 5 分钟）：
#     */5 * * * * /opt/zhongshu/ops/probe-health.sh >> /opt/zhongshu/ops/probe.log 2>&1
# 说明：只负责"发现与通知"；进程拉起交给 systemd Restart=always，不做自动重启
set -uo pipefail

OPS_DIR=$(dirname "$0")
HEALTH_URL=${ZS_PROBE_URL:-http://127.0.0.1:48080/actuator/health}
WEBHOOK=""
[ -r /opt/zhongshu/.env ] && WEBHOOK=$(grep -E '^ZS_OPS_WEBHOOK=' /opt/zhongshu/.env | cut -d= -f2-)
COOLDOWN_FILE="$OPS_DIR/.probe-alert-state"

HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$HEALTH_URL" 2>/dev/null || echo 000)
[ "$HTTP_CODE" = "200" ] && { rm -f "$COOLDOWN_FILE" 2>/dev/null; exit 0; }

# 冷却窗口内不重复告警
NOW=$(date +%s)
LAST=$(cat "$COOLDOWN_FILE" 2>/dev/null || echo 0)
[ $((NOW - LAST)) -lt 1800 ] && exit 0
echo "$NOW" > "$COOLDOWN_FILE"

echo "[probe] $(date '+%F %T') 健康检查失败 HTTP=$HTTP_CODE（systemd 会自动拉起，若持续失败需人工介入）"
[ -n "$WEBHOOK" ] && curl -s --max-time 5 -H 'Content-Type: application/json' \
  -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[众墅之家] 后端健康检查失败 HTTP=$HTTP_CODE @ $(date '+%F %T')\"}}" \
  "$WEBHOOK" > /dev/null
exit 0
