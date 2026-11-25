# 公司公告：B 后端契约

公告是独立模块，小程序入口为“我的 → 公司公告”。管理后台路径为 `/platform-announcements`；本任务仅交付后端，管理界面另行提交。旧 A `/zs/announcements` 和个人消息中心均不是本模块的数据源。

## 接口

以下路径由现有请求客户端添加 `/admin-api` 前缀。所有接口均需要登录，业务账号携带当前任职 `X-Post-Id`；返回沿用 `{code,data,msg}`。所有响应设置 `Cache-Control: private, no-store`。

| 方法 | 路径 | 参数 / 行为 |
| --- | --- | --- |
| GET | `/platform/announcements/admin/options` | `{canWrite,brands:[{id,name}]}` |
| GET | `/platform/announcements/admin` | `pageNo=1,pageSize=20,keyword='',status='',brandId=''`，返回 `{list,total}` |
| POST | `/platform/announcements/admin` | 新建草稿，返回完整详情 |
| GET | `/platform/announcements/admin/{id}` | 后台详情，包括草稿及已撤回内容 |
| PUT | `/platform/announcements/admin/{id}` | 保存内容，必须提供 `expectedRevision` |
| POST | `/platform/announcements/admin/{id}/publish` | `{expectedRevision}`，发布 |
| POST | `/platform/announcements/admin/{id}/withdraw` | `{expectedRevision}`，撤回 |
| GET | `/platform/announcements/app` | `pageNo=1,pageSize=20,keyword=''`，仅当前身份可见的已发布公告 |
| GET | `/platform/announcements/app/{id}` | 与列表使用同一品牌可见性检查 |

`pageNo` 为 1–1000000，`pageSize` 为 1–100。关键词最长 100 字，按标题和摘要作不区分大小写的字面量搜索，`%` 和 `_` 不作为通配符。后台 `brandId` 筛选包括该品牌定向公告和 `ALL` 公告。默认置顶优先，然后最近发布时间、创建时间、ID 降序。

### 保存请求

```json
{
  "title": "公司通知",
  "summary": "列表摘要",
  "content": "公告正文\n支持换行，按纯文本展示",
  "visibility": "BRANDS",
  "brandIds": ["1", "2"],
  "pinned": false,
  "expectedRevision": 0
}
```

- 标题必填，最大 100 字；摘要可空，最大 200 字；正文必填，最大 20000 字。
- `content` 是纯文本；客户端应使用转义文本渲染，不使用 `v-html` 或 HTML 富文本解析。
- `visibility` 为 `ALL` 时 `brandIds` 必须为空；为 `BRANDS` 时至少选择一个品牌，最多 200 个，所有品牌必须属于当前租户。
- 创建时不需要 `expectedRevision`；更新和发布/撤回时必须传当前版本。
- ID、`brandIds`、选项中的品牌 ID 全部为字符串，`revision` 为数字。

完整详情字段：`id,title,summary,content,visibility,brandIds,pinned,status,revision,publishedAt,createdAt,updatedAt`。列表去掉 `content`，其余字段相同。

时间统一返回 **UTC ISO-8601 字符串**，例如 `2026-11-25T08:30:00Z`，前端可转换为北京时间显示。`publishedAt` 未发布时为 `null`；撤回和编辑后保留最近一次发布时间，新一次发布时更新。保存已撤回内容后状态恢复 `DRAFT`。

## 授权和范围

| 身份 | 小程序阅读 | 管理后台阅读 | 写入 |
| --- | --- | --- | --- |
| 总部 `hq-admin` | 当前租户全部品牌的已发布公告 | 需 `platform:announcement:query` | 还需 `platform:announcement:manage` |
| 总部 `finance/finance-lead` | 当前租户全部品牌的已发布公告 | 需 query 权限；可查草稿/撤回状态 | 拒绝 |
| 门店有效任职，包括财务 | `ALL` + 当前门店品牌 | 拒绝 | 拒绝 |
| 其他总部有效任职 | 仅 `ALL` | 拒绝 | 拒绝 |
| 总部后台 `super_admin`，无当前任职 | 当前总部租户全部已发布公告 | 允许 | 允许 |

总部身份必须同时满足 `orgType=headquarters`，不能只检查角色名。组织、用户、岗位和角色须有效且属于请求租户；门店品牌来自服务端 `pf_store_location`。缺少品牌或有多个品牌时只显示 `ALL`，不扩大到其他品牌。所有详情、编辑和发布操作再次以 `tenant_id` 限定。

后台 `super_admin` 兼容仅在未解析出当前任职且租户等于配置 `zhongshu.org-point.headquarters-tenant-id` 时生效。有无效/歧义的任职头时直接拒绝；已选门店身份时不会借用超级管理员权限。

业务错误码：401 未登录/登录租户不一致；403 权限或任职无效；404 对象不存在或不可见；400 参数/范围无效；409 旧版本、重复状态命令或直接编辑已发布公告。HTTP 外层沿用项目现有 `CommonResult` 错误处理，不另改全站状态码策略。

## 状态与审计

创建 → DRAFT；发布 → PUBLISHED；撤回 → WITHDRAWN；保存草稿/撤回内容 → DRAFT。已发布公告必须先撤回再修改（包括置顶和品牌范围）。

所有写操作在事务中持行锁，检查 `expectedRevision` 后递增版本，并保存同事务的 `pf_announcement_audit` 内容快照。没有物理删除接口，不群发个人消息，不自动产生业务完成记录。

## 迁移和发布范围

- `V20261125.1031`：公告、品牌关联、审计表；复用现有 `pf` Flyway 目录。
- `V20261125.1032`：新增仅用于总部的 `hq-admin` 岗位模板。
- `V20261125.1033`：新增总部管理员角色及 query/manage 权限种子；type=3 权限菜单不注册重复后台路由。
- 不为任何现有员工自动指派新岗位；不迁移、撤销或删除旧设计行政岗位及在途任务。
- 本任务仅新增公告权限及其跨品牌阅读范围，不意味着其他客户、工程、财务业务已改为总部全局查询。

`AnnouncementIntegrationTest` 覆盖真实 PostgreSQL 存取、发布/撤回、版本冲突、租户/品牌隔离、当前岗位读取、总部财务只读、无岗位 super_admin 兼容、无效岗位头拒绝和 HTTP 契约。新增测试以及岗位/权限种子测试纳入 Backend CI 的 release-regression 列表。

## 新建草稿回执重试

- 管理后台新建草稿通过原 `POST /platform/announcements/admin` 传 `createRequestId`（小写 UUID）。首次保存结果未知时，同一页面内冻结本次内容，并使用原请求编号确认保存结果；不因超时猜测是失败。关闭再打开新建对话框可继续确认。切换工作身份清空本页待确认内容；未将正文存入浏览器持久存储。
- 服务端先校验当前发布管理权限，再按租户、发起人及请求编号去重；以原稿的规范化内容校验重试请求。并发重试只提交一份草稿及一次新增审计；同一个编号改内容返回 409。若原公告已经发布或撤回，回执显示同一条公告的最新状态。
- 旧调用不传编号仍可创建草稿；后台编辑、发布和撤回沿用原有版本校验。上线时先运行后端迁移并启用新服务，再启用新版后台界面。
