# 前后端配对验收

`main` 是小程序，`backend-main` 是 Java 服务与管理台。两个分支各自 CI 通过，只证明各自检查通过；拟交付的组合还要通过 `Paired release rehearsal`。

## 如何选择组合

- 后端 PR / push：检查该后端提交，并在运行开始时将 `main` 解析为一个固定提交。
- 小程序 PR / push：`main` 分支的薄工作流传入本次小程序的完整 SHA，调用这里的可复用工作流；后端在 checkout 时从 `backend-main` 冻结为一个提交。
- 手动演练：在 Actions 输入两个完整提交 SHA，可复验包含本版演练脚本的历史后端组合（REL-01 及以后）；更早版本请查它原有运行的验收证据，不支持直接套用本工作流。默认 `main` / `backend-main` 只用于开始时选择版本。

`freeze_pair.py` 写入 `FRONTEND_REV` / `BACKEND_REV` 后，其余步骤只使用这两个固定 checkout。运行中分支继续前进，不会替换本次候选代码。小程序调用方必须显式传入 `backend_sha: backend-main`，不能把调用方的 `github.sha` 当成后端版本。

## 通过意味着什么

本次小程序的完整 `npm run check`、真实 Spring 控制器与小程序交接握手、管理台构建、不可变交付包、失败迁移恢复、健康回滚和离线镜像验证都必须通过。`paired-release` artifact 中保留包、SHA256SUMS 和 `acceptance.json`；包内 `manifest.json` 绑定两个提交与各文件校验值。步骤摘要先标明候选组合，全部演练成功后才追加通过说明。

演练使用 GitHub 托管的临时运行器和测试数据，不部署生产，也不证明真实微信、短信、COS 或 AI 供应商联调通过。历史 `PREVIOUS_REV` 是迁移故障用例的固定旧版样本，不是当前前端版本。

## 工作流维护

小程序的调用文件引用可复用工作流的完整提交 SHA；修改这里的工作流接口时，应先合并后端，再在独立小程序 PR 更新该引用并跑通配对检查。调用文件不设置相同的 concurrency group，避免与被调用工作流互相取消。所有触发均为只读仓库权限，不继承生产密钥。
