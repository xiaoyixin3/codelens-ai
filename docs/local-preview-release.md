# CodeLens AI 本地预览测试版

版本标签：`v1.0.0-local-preview.1`。这是 GitHub prerelease 和本机只读试用包，不是生产 Beta、Design Partner 上线或正式可用版本。

## 下载与启动

在 GitHub Releases 下载 `CodeLens-AI-v1.0.0-local-preview.1.zip`，核对同页 `SHA256SUMS.txt`，解压到普通可写目录。Windows 双击包内 `CodeLens-Local.cmd`；启动后打开 `http://127.0.0.1:4310`。保持启动进程运行，Ctrl+C 停止。已有同协议本地入口会被复用。

要求 Java 17+、Node.js 24+、Git。首次启动会按锁文件安装本工具依赖，需要联网；安装使用 `npm ci --ignore-scripts`，不运行依赖安装脚本。预览 ZIP 包含已构建的 `target/codelens-ai.jar`，不修改源码时无需 Maven。若改动工具源码或使用 GitHub 自动生成的纯源码压缩包，需要 Maven 3.9+ 重新构建。

macOS/Linux 或手动启动：在解压后的根目录执行 `npm ci --ignore-scripts`，再执行 `npm run review:local`。预览包不包含 Java、Node.js、Git 或已安装的 npm 依赖。

页面填写本地 Git 仓库路径与 Base/Head 提交。程序只读取已提交的 Java 与构建描述文件，不包含未提交变更。详见 [本地使用说明](local-review.md)。报告可能包含仓库源码，请勿未经授权外发。

## 当前能力

- 固定 Base/Head 源码证据、Java 全仓库语义关系和显式覆盖缺口。
- 行为变化、未修改调用方、证据导航和复用候选。
- 结构化修改方案、范围与验证步骤；不声称方案已修复真实缺陷。
- 本机回环绑定、Host/Origin 校验、有界请求与只读接口限制。

## 本次发布边界

本地入口不加载 `.env`，不调用模型，不向 GitHub 写入，不执行输入仓库构建/测试，不自动应用补丁，不启用 blocking。依赖下载是本工具的首次安装步骤，不是对输入仓库开放联网执行。

两个版本合计最多 2000 文件/25 MiB，单文件 1 MB，最多 100 个相关变更文件；Java 分析子进程超时 120 秒。同一入口只允许一个活动分析，额外并发请求返回 409，不代表多任务并行能力。

Phase 0 的独立真值与人工 Review 时间、Phase 1 的独立直接调用精度门槛仍未关闭。不宣称高风险 finding 精确率、生产高可用、自动正确修复或人工 Review 提效百分比。工程回归和受控压力测量不能替代这些评测。

`v1.0.0-beta.*` 的既有生产镜像发布规则与生产 checklist 不变。本地预览标签不触发该规则，不创建生产 GHCR 镜像、不运行线上迁移，也不更改公网 GitHub App 或原有数据库。

包内 `RELEASE-MANIFEST.json` 记录标签、源码提交、构建 Java 版本和 JAR/锁文件哈希。`SHA256SUMS.txt` 用于下载完整性检查，不是签名、独立评测或供应链安全认证。
