# 加盟商名录与地图维护说明

## 部署与迁移顺序

保留现有 Flyway 迁移链，新增 `平台程序/platform/platform-org/src/main/resources/db/migration/pf/V20261124.991__franchise_directory.sql`。它依赖既有加盟账号表，以及 `V20261118.988__franchise_store_map.sql` 的品牌、位置投影和 `V20261118.989__franchise_district_centers.sql` 的行政区中心数据。不要跳过前置迁移或修改已执行的旧迁移。

先部署包含迁移和新接口的后端，确认地图 V2 与完整地区目录均可读取，再发布管理端与小程序。新迁移建立名录及地区关联表，并从既有老板账号生成兼容投影；不导入客户工作簿，不插入演示加盟商。尚未升级的后端缺少地区目录接口时，新小程序显示加载错误，不回退到假数据。

## 数据登记与来源

| 表 | 职责 |
| --- | --- |
| `pf_franchise_directory` | 每条登记资料的品牌、名称、类型、原始地区、匹配状态、来源编号及指纹、开户关联、版本 |
| `pf_franchise_directory_region` | 同一资料关联的多个行政区；一条多县来源仍只有一个名录 ID |
| `pf_map_brand` | 租户内品牌字典；可见范围内零记录品牌也返回 |
| `pf_map_region` | 已有行政区与县中心字典；完整选项不依赖加盟记录数量 |
| `pf_store_location` | 既有账号位置投影和门店品牌归属；V2 不使用其中的精确地址坐标展示门店 |

`COVERAGE` 表示品牌地区资料，按“条”统计；没有真实加盟商名称时允许生成品牌与地区的展示名。`FRANCHISE` 表示加盟商档案，须有名称，按名录 ID 去重统计“家”。资料只有城市、大区或多个县，均不能据文字拆成多家加盟商。

`RESOLVED` 表示关联已明确到县级，`PARTIAL` 保留明确的上级地区或尚不完整的覆盖关系，`UNRESOLVED` 保留原文及可识别的省市等待修订。地区代码可包含省、市、县；只有明确县级关联生成点位。直辖市及省直辖县允许省直接关联县，`cityCode` 为空，不创建虚拟城市代码。

## API 契约

下表使用 Controller 路径，客户端按现有配置补 `/admin-api` 前缀。成功数据仍使用 `CommonResult` 包装。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /franchise/store-map` | V2 地图快照及旧 `stores` 兼容数据 |
| `GET /franchise/store-map/regions` | 完整地区目录；可选 `parentCode` 返回直接子级 |
| `GET /platform/org/franchise-directory` | 管理列表；`keyword`、`brandId`、`matchStatus`、`pageNo`、`pageSize` |
| `GET /platform/org/franchise-directory/candidates` | 未开户候选；可选 `keyword`，返回记录仍需检查匹配状态 |
| `GET /platform/org/franchise-directory/regions` | 管理端地区目录，不要求选择小程序当前岗位 |
| `POST /platform/org/franchise-directory/import/precheck` | multipart `file`；只预检，不写库 |
| `POST /platform/org/franchise-directory/import` | multipart `file`；重新解析校验，逐行事务提交 |
| `POST /platform/org/franchise-directory` | 手工登记，字段见下文 |
| `PUT /platform/org/franchise-directory/{id}` | 受控修订，必须提供 `expectedVersion` |
| `POST /platform/org/franchise-onboard/from-directory` | 从明确的名录记录开通账号并绑定 |

地图返回 `schemaVersion: 2`、`scope`、当前品牌、`brands`、`records`、`points`。地图中的所有 ID 均为字符串。`records` 包含 `recordType`、`originalRegionText`、`matchStatus`、`regionCodes`、省市代码及 `linkedOrgId`；`points` 通过 `recordId` 关联记录，包含县代码、经纬度和固定的 `locationPrecision: 'district'`。兼容 `stores` 只包含 `FRANCHISE`，每条最多取一个县中心，不把覆盖资料伪装成门店。

手工登记/修订字段为 `name`、`brand`、`recordType`、`originalRegionText`、`regionCodes`、可选 `provinceCode`、`cityCode`，修订另带 `expectedVersion`。从名录开户提交 `recordId`、`expectedVersion`、`adminName`、`adminMobile`，可带 `areaPinyin`、`ownerPinyin`、`remark`、`districtCode`；多县记录必须明确本次主区县。服务器重验版本、县归属和未绑定状态，不能仅依赖前端已选择的资料。

## 表格导入与幂等

仅支持不超过 5 MB 的 XLSX，单次最多 2,000 条来源记录；兼容横排品牌分块表及标准逐行表。标准表可使用 `来源编号、品牌、名称、省、市、地区原文、地区代码、记录类型`。地区代码可用分隔符填写多个，默认类型为 `COVERAGE`。公式、宏、外链、异常压缩结构会拒绝；普通资料行错误进入逐行结果，不静默丢弃。

未提供稳定来源编号时，系统以工作簿完整 SHA-256、工作表序号和来源单元格构成 `sourceKey`，因此同一 Excel 行的不同品牌不会冲突。该默认键只保证同字节文件重传幂等：编辑、重新保存或换文件会改变指纹。需要长期更新同一资料时，应在标准表填写并持续保留唯一的 `来源编号`；不要用地区文字自动合并不同来源。

预检和提交均返回 `CREATED / UPDATED / SKIPPED / FAILED` 行结果、当前匹配状态及汇总。实际提交重新校验并按租户和来源编号加锁。已人工修订或已开户的记录遇到未变化的来源重传时保留现有资料，`SKIPPED` 回显当前档案；同来源内容确有变化且已开户时拒绝覆盖，转入受控维护。

## 名录、账号和权限边界

名录导入不自动创建组织、用户或登录账号。原手工开户流程继续可用，通过账号触发器生成 `account:` 来源的加盟商投影；它不会按相似品牌名称或地区文字自动吞并已有覆盖资料。

从名录真实开户时，选中资料变为 `FRANCHISE` 并使用开户组织名称，同时保留来源编号、指纹、原始地区文字和多县关系。开户、临时自动投影删除及原记录绑定属于同一事务。导入/手工来源的覆盖范围由名录管理：普通组织档案的名称、品牌、主区县或省市文字变化不会重建这些覆盖关系，只有受控名录修订明确替换。自动 `account:` 投影则跟随原手工开户资料的地区和原文变化。

受控修改已开户多县记录时，沿用目标范围内有效的原主区县；目标只有一县时取唯一县。目标多县没有有效主区县时返回明确错误，不擅自取第一个。管理 UI 本轮不开放已绑定记录的覆盖编辑，日常账号资料仍从组织目录维护。

所有查询按租户隔离。小程序地图要求有效当前岗位：总部岗位只读查看全部品牌，门店岗位只读查看本品牌；此范围由后端决定，客户端传 `brandId` 或 `scope` 不能扩大权限。名录管理、导入和开户均另受 `platform:org:onboard` 控制；地图可见不授予写权限。管理 Web 的地区接口不依赖小程序 `CurrentPost`。

组织停用或删除后退出地图；撤销/删除不自动把关联名录恢复成待开户，不自动重新呈现为有效加盟商。以后如需“只撤销账号、保留加盟关系”，应另行定义受控解绑动作。

## 地图与统计口径

行政区域统计按所选地区包含的名录 ID 去重，拖动地图不改变该统计。地图视野统计只使用当前可见的真实点位，同一加盟商跨县仍只算一家。两种模式均分别统计加盟商“家”、覆盖资料“条”和去重区县数。

城市、大区及无法完整确认县级的资料不展开为所有下属县，不创建猜测点位；相关区域有待确认资料时，页面提示待确认，不能直接推断没有品牌。名称查询可以在当前可见品牌范围内跨地区查找，行政区统计仍属于明确选中的行政区域。

所有展示点都是县中心，不代表门店地址。省市目录中心可以为空；前端可用真实下属县中心调整视野，但不能由此生成加盟商标记。当前实现不需要调用地址解析 API，也不会把原表地区文字当作门牌地址地理编码。

## 验证与发布状态

本轮精确源码已通过 65 项后端专项，以及单独启用的 `WalletAccountingIntegrationTest#miniProgramToSpringControllersOverHttp`，合计 66 项，0 失败、0 错误、0 跳过。HTTP 专项使用合成资料、临时 PostgreSQL、真实 Controller 和小程序 Page 运行器，另需提供小程序仓库的 `franchise.ui.runner` 绝对路径；它不是原生地图渲染验收。

`backend-ci.yml` 的 `release-regression` 在原测试列表上加入名录、工作簿、地区、管理权限、开户、MyPost 和 CurrentPost 专项。客户工作簿、实际来源行、私有验收 JSON 均不进入仓库或 CI fixture。

**Native QA: BLOCKED（尚未完成微信开发者工具或真机验收）。** 发布验收仍需覆盖原生地图同县多品牌气泡、覆盖层点击、缩放与拖动后的视野回调、县中心定位和身份切换。Node Page 模拟、接口联调及静态构建通过不能替代这些结果；本说明不表示已经部署或完成生产验收。
