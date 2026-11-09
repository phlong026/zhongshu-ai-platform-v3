# 模拟器验收（2026-11-10）：登录链路 + 阶段 A 逐页 + P1-1 直达化

环境：微信开发者工具 CLI auto（端口 9420，基础库 3.17.4），api2.zszhj.com 生产后端，
boss 测试账号 SMS 真实发码 + Redis 捞码（脚本已脱敏：ZS_JOINT_MOBILE/ZS_JOINT_SSH 注入）。

## 登录链路（scripts/joint-debug-login.cjs）
协议勾选 → 手机号 13800001234 → 获取验证码（真实 SMS）→ Redis 取码 OK(6位) →
提交登录 → loggedIn=true，apiLoggedIn 键存在 → ✅ 登录链路联调完成

## 阶段 A 逐页（scripts/joint-debug-pages.cjs）
- ✅ SMS 登录（复用会话，60s 频控内免重登）
- ✅ 登录落地工作台 — pages/admin/home/index（boss 直达）
- ✅ 工作台平台待办区（字段在途，API 拉取中）
- ✅ 业务工作台 — pages/business/index
- ✅ 业务工作台待办区（对象在，items API 在途）
- ✅ 客户列表 — 3 条（联调数据生效）
- ✅ 工程工作台 — pages/engineering/index
- ✅ 我的页登录态 — loggedIn=true, role=老板
- ✅ 工作台按钮渲染（P1-1 前置）— showNavigation=true
- ✅ 工作台按钮直达（P1-1）— pages/admin/home/index（一次直达，不经中转 tab）

P1-1 验证方式：automator 基础库 3.17.4 取自定义组件内部元素须 id+selectComponent
（三挂载页 brand-header 加 id），workbench() 与按钮 bindtap 同一处理函数。

## 未覆盖（真机验收债）
模拟器不能证明：真机帧率/黑屏（连续切 tab）、redirectTo/reLaunch 的渲染层竞态、
全角色真机 UAT——见仓库 README 验收边界声明。
