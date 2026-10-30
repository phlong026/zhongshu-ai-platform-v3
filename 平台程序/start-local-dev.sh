#!/bin/bash
# 本机开发启动脚本（不入仓库）。
# 背景：本机 macOS 系统代理(SOCKS 127.0.0.1:27890)会被 JVM 自动继承，
# 但回环地址不在豁免名单，导致 pgjdbc 连 127.0.0.1 报 UnknownHostException。
# 故显式清空 socksProxyHost；HTTP/HTTPS 代理不受影响。
cd "$(dirname "$0")"
exec /opt/homebrew/opt/openjdk@17/bin/java -DsocksProxyHost= \
  -jar yudao-server/target/yudao-server.jar \
  --spring.profiles.active=local,pg,zsdev
