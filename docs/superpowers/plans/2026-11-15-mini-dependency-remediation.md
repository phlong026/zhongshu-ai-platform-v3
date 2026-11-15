# 小程序依赖与 Excel 运行库修复计划

1. 新增离线专项：锁定安全依赖树、SheetJS 官方来源/版本/hash，并覆盖 automator/Jimp/Node XLSX 与真实 `schedule-import#importNodes` 行为；先在旧依赖和 0.18.5 vendor 上确认安全门禁失败。
2. 精确锁定 `miniprogram-automator@0.13.0` 和 SheetJS 官方 0.20.3 tarball，仅覆盖已验证兼容的叶子依赖；从同一 tarball 替换实际运行的 mini vendor。
3. 用全新 `npm ci --ignore-scripts` 运行专项、完整 `npm run check`、锁树与在线审计，记录唯一残余 phin moderate 链及不可达边界。
4. 检查 JS/JSON、包体、敏感信息、完整 diff 和可回滚性，生成证据报告后按 Lore 协议提交，交主代理独立复审。
