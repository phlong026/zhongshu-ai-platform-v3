# 固定回归夹具

`legacy-policy-same-person.json.gz.b64` 与 `legacy-finance-hq-staff.json.gz.b64`
只用于复跑 2026-10-19 至 2026-10-21 的旧链路回归。它们不是当前业务通过结果，
也不能替代 `verify-sales-offline-flow.cjs` 和 `verify-engineering-records.cjs`
根据当前源码生成的页面场景。

`prepare-full-flow-fixtures.cjs` 会校验固定夹具的 SHA-256，按需恢复到被忽略的
`qa/` 路径，再执行上述两个当前链路生成器。财务夹具仅保留旧可见文案检查实际读取的
`hq-staff` 场景，避免提交整份约 1 MB 的历史运行快照。
