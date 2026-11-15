> 历史归档：以下内容记录截至 2026-11-14 的阶段状态，仅用于分支审查追溯，不代表 2026-11-15 集成候选的当前发布、部署或验收结论。

# 01-org-onboard · 测试加盟商创建记录（任务 01 证据）

- **执行**：主会话代理。**时间**：2026-11-13（首次）/ 2026-11-14 更正。**关联任务**：任务书 01；下游 11（UAT 账号）、12C（正式环境开通复用同一接口）。

> **⚠️ 2026-11-14 更正**：首次开通执行在**旧融合后端**（`yudao-server.jar` local,pg,zsdev，写旧融合本地库）——该进程是拆分切流时 **FROZEN 停写对象**，其上的数据不代表 B 候选态，**orgId 1430 作废**。正确形态 = B 独立栈（见 §三），组织开通需在 B 栈重新执行。

## 一、（作废）旧融合库上的首次开通

- orgId 1430 / adminUserId 1061 / boss postId 2121283041014874113 / 赠点 1000（池核验 ACTIVE）——以上全部落在旧融合本地库，**随切流 FROZEN 边界作废**，不进入任何验收矩阵。
- 保留价值：验证了 onboard 接口契约（`orgType=store`、幂等 `reusedOrg`、建池赠点联动）在真实后端可用。

## 二、接口契约（两栈通用）

`POST /admin-api/platform/org/onboard`，权限 `platform:org:onboard`（超管角色）：
`{orgType:'store'|'headquarters', orgName, adminName, adminMobile(1\d{10}), designEnabled?, initialCredits?}` → `{orgId, adminUserId, postId, reusedOrg}`。幂等键 = orgType+orgName。

## 三、B 独立栈（正确形态，2026-11-14 已拉起）

| 项 | 值 |
| --- | --- |
| 后端 | `yudao-platform-server.jar`（SHA `e1666eab…`，镜像 `zs-platform-b-local:e1666eab`），容器 `zs-final-b-api`，**127.0.0.1:48081** |
| 独立库 | `zhongshu_platform_b`（PG 容器 postgres-b，159 表，切流演练 159/159 全等）+ 独立 Redis（redis-b） |
| 边界 | B 不依赖旧 identity/design/commerce/server（B35 闭包核验）；旧融合进程 B 侧写 = FROZEN 停写对象；A 设计运营 59 入口显式排除保护 |
| 已有账号 | 管理员 userId 145（用户名 `itcd1b71f28444`，密码见测试密钥文件 `admin-password`，**非生产凭据**）+ 测试手机号账号 139****5105 / 139****4300 / 139****9999 / 188****1681 |
| 历史冒烟 | login code 0、onboard orgId=120 幂等复用、ORG_TYPE 须用注册 code（store/headquarters）——见 `/tmp/zs-final-b-container/run-evidence.json` |

## 四、用户操作（更新）

1. 后台 `http://localhost:5173`（**拆分版 admin-ui**——含卡片式运营工作台「经营看板」、identity/org-staff/wallet 等新页，代理已指向 B 独立栈 48081；2026-11-14 恢复演示实例并更正品牌标题）→ 用 userId 145 账号登录（滑块验证码）。
2. **/zs → 组织开通**：开通正式测试加盟商（orgType=store）——在 B 独立库落账，替代作废的 1430。
3. **老板小程序端**配员工（登录后管理 → 组织管理：部门加人/岗位指派）——员工配置主战场在小程序（2026-11-10 产品边界），Web /fr 员工任职配置未闭环（P1-9 已登记）。
4. 配置完成回填 [02-coverage.md](./02-coverage.md) 与任务 11 UAT 矩阵。

## 五、限制

- B 栈当前为**本地测试容器**（合成 g2s-* 渠道值，NON_PRODUCTION_FIXTURE 标记）；生产部署/切流仍待授权（12B），切流按交付说明 §六要点（关闭旧充值→排空→DRAINING→FROZEN）。
- 旧融合本地进程已停止（A 本地演示数据不受影响；A 生产侧独立运行）。
