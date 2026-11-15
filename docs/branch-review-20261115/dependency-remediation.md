# 小程序依赖与 Excel 运行库修复（2026-11-15）

## 处置结果

- 基线：`97f92ba8a3c02ef926b4d49b60b2531d6e2e8e10`，修复提交为本文件所在提交。
- `miniprogram-automator` 精确保留 `0.13.0`；只覆盖 `debug@4.4.3`、`follow-redirects@1.16.3`、`jpeg-js@0.4.4`、`minimist@0.2.4`。
- Node `xlsx` 与小程序真实运行的 `miniprogram/pages/build/vendor/xlsx.mini.min.js` 同步到 SheetJS 官方 CDN `0.20.3`。未只修改开发依赖掩盖运行时风险。
- Excel 导入入口增加 xlsx ZIP 头校验，损坏文件和伪 xlsx 统一返回格式错误；合法三列、日期单元格/文本日期、无表头、封顶补齐、20 行上限与日期倒序规则不变。

### 导入内存边界

进度计划只接受 20 行三列，入口在 `readFileSync` 前按微信文件元数据拒绝超过 2 MiB 的文件，`importNodes` 对实际 `ArrayBuffer` / `Uint8Array.byteLength` 再做同一检查。解析前按 PKWARE ZIP 中央目录及本地文件头限制为最多 128 个条目、声明展开总量最多 8 MiB、单条目压缩比最多 200；拒绝 ZIP64、分卷、加密及非 Store/Deflate 条目。该预算远高于正常 20 行三列表格，同时在 SheetJS 解压前拒绝携带巨大无关工作表、图片或高压缩内容的文件。

SheetJS 0.20.3 官方解析选项确认 `sheets` 可限定工作表、`sheetRows` 可限定行窗口，且截断时 `!fullref` 保存原范围。导入使用 `sheets: 0`、`sheetRows: 22`（20 行 + 可选表头 + 超限哨兵）；发现 `!fullref` 时显式拒绝，避免把窗口后的数据静默当成成功导入。窗口内空白行继续兼容。文件选择取消保持静默，权限或系统失败则显示“选择进度表失败，请重试”。

格式依据：SheetJS [Parsing Options](https://docs.sheetjs.com/docs/api/parse-options/)；PKWARE `APPNOTE.TXT` 4.3.12（中央目录）与 4.3.16（目录结束记录）。

## SheetJS 来源与完整性

| 项 | 固定值 |
| --- | --- |
| 官方 tarball | `https://cdn.sheetjs.com/xlsx-0.20.3/xlsx-0.20.3.tgz` |
| tarball SHA-256 | `8dc73fc3b00203e72d176e85b50938627c7b086e607c682e8d3c22c02bb99fe8` |
| npm lock SRI | `sha512-oLDq3jw7AcLqKWH2AhCpVTZl8mf6X2YReP+Neh0SJUzV/BdZYjth94tG5toiMB1PPrYtxOCfaoUCkvtuH+3AJA==` |
| vendor member | `package/dist/xlsx.mini.min.js` |
| vendor SHA-256 | `0cb353f830d7288385492c83d277b058ddeac664ca51cf1393aa1fd3e2b70939` |
| 版本 / 许可证 | `0.20.3` / `Apache-2.0` |

vendor 从上述固定 tarball 解包复制，保留 SheetJS 文件头。文件由 250,826 字节增至 279,523 字节（增加 28,697 字节）；完整工程检查继续执行现有包体预算门禁。

## 审计终态与唯一残余风险

在线 `npm audit --json` 为：`critical=0`、`high=0`、`moderate=5`、`low=0`。5 个 moderate 包节点是同一公告链：

`miniprogram-automator@0.13.0 -> jimp@0.6.8 -> @jimp/custom@0.6.8 -> @jimp/core@0.6.8 -> phin@2.9.3`

唯一底层公告为 `GHSA-x565-32qp-m3vf`。它要求 Jimp 通过 URL 请求、跟随重定向并携带敏感 header。当前 automator 的真实调用是 `Tool.enableRemoteDebug` 返回二维码 base64，转为本地 PNG `Buffer` 后调用 `Jimp.read(Buffer)`；截图则直接把 base64 写盘，均不走 phin URL 路径。离线专项实际覆盖 PNG/JPEG Buffer 解码、bitmap、重新编码和再次解码。

没有覆盖 `phin@3`：Jimp 0.6 仍调用 `p(options, callback)`，而 phin 3 是 Promise API，直接覆盖会破坏回调。也没有升级 Jimp 0.22/1.x，避免引入新的 `file-type` 公告或 default export/API 破坏。`npm audit --omit=dev` 为 0；不能把完整审计表述为 audit 0。

## 验证口径

- `npm run check:dependencies`：覆盖 automator 导出、Jimp PNG/JPEG Buffer、Node/mini XLSX roundtrip、固定来源/hash，以及真实 `schedule-import#importNodes` 正反例与读取前内存边界。
- `npm ls ... --all`：锁树与上述精确版本一致。
- `npm audit --json`：仅残余上述 phin 单一链；`npm audit --omit=dev`：0。
- 原始命令输出、退出码、最终文件 hash 与机器报告保存在 `/tmp/zhongshu-mini-dependency-fix-20261115/`；该目录不提交。

本次是本地候选修复，不代表已推送、合并 `main`、上传开发/体验版或完成真机验收。
