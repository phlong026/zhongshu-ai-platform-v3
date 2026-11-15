# 2026-11-11 历史制品核验脚本归档

清理旧工作区时发现，此脚本只存在于旧 platform-split 工作树的 `.omc/artifacts/platform-rebuild-20261111/`，没有对应的主线 Git blob。为保留独有核验源码，逐字节归档于 [verify-rebuild-round7.py.txt](verify-rebuild-round7.py.txt)。

- 原始 SHA256：`5106e92e697d3a89cee12632a45d65bc52109f1579fe771f684873fa360ad90f`。
- 已用 Python 内存编译验证语法，没有执行脚本或其中的旧发布检查。
- 脚本固定使用当时的本机路径、旧/新 JAR、69 项迁移及历史制品对照；这些条件不适用于当前独立 B 环境。
- `.py.txt` 仅作带日期的历史源码留档，不是可执行发布入口，不进入现有构建、测试或部署门禁。
- 当前制品检查继续使用 `平台程序/script/platform/verify-platform-artifact.py` 和既有相关行为测试。本次没有修改运行时代码或服务器配置。

完整旧工作树工作区、暂存区、stash、忽略文件与恢复校验另有本地私有备份；私密配置、原稿中的测试手机号、完整体验二维码和工具笔记不进入此归档。
