# 第三方依赖与源码溯源

本目录于 2026-11-14 从只读目录
`/Users/honor.pei/Documents/企业FDE项目/众墅之家设计小程序/ai-runtime`
复制独立基线。B 的差异只包括独立包/镜像名称、取消每日业务次数限制、B 专属测试和部署边界说明；A 源目录未被修改。

测试依赖及许可证以 `package-lock.json` 为准；生产 Runtime 不携带这些包：

- `sharp`: Apache-2.0。
- `@img/sharp-*`: Apache-2.0；部分平台包同时包含 LGPL-3.0-or-later 组件。
- `@img/sharp-libvips-*`: LGPL-3.0-or-later。
- `@emnapi/runtime`, `@img/colour`: MIT。
- `detect-libc`: Apache-2.0。
- `semver`: ISC。
- `tslib`: 0BSD。

测试环境通过锁文件安装依赖，依赖包自带 LICENSE/NOTICE。生产镜像不复制 `node_modules`。升级 lockfile 前必须重新核对许可证清单。
