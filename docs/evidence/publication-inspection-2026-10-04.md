# 只读发布查证：范围、证据与限制

日期：2026-10-04。Java pipeline `java.11`。无新增数据库迁移。

## 已实现

- 单独的 `publication-inspect` 非 Web 模式，只接收一个规范 run UUID，报告后退出。
- 启动前拒绝额外参数、重复 ID 和写入类参数；组件初始化前拒绝混入其他 profile。
  不启动 API、Worker、模型、语义工作区或迁移器。
- 只读取本地任务、意图、占用与版本信息，以及已授权 GitHub 安装的 PR/Check/评论。
  不确认 journal、不释放占用、不重入队、不发布 Check 或评论。
- 验证持久化 job 与 run 的 Base/Head/PR 身份。复用既有 App/作者/PR 校验、分页上限
  和身份标识，不增加平行查找路径。
- Check 结果重新计算完整请求指纹，包括远端批注内容，而不只检查注解数或隐藏标记。
  超过 50 条或包含现有发布器不支持的列定位时停止核实。
- 摘要正文去掉本 run 的固定标记后重新计算指纹；伪造标记不能使改动正文通过查证。
- 报告区分 verified_remote、not_visible、not_recorded、local_fingerprint_conflict、
  content_conflict、remote_id_conflict 和 lookup_failed。不把查询失败当成没有发布。
- 输出不含源码、渲染正文、批注正文或远端错误响应。明确 automaticRecoveryAllowed=false。
- 查询按 PostgreSQL 最佳实践复用 run/job 唯一键和已有占用/PR 索引；无需新 schema。

## 同轮修复

GitHub 官方 Check API 规定，只有 GitHub 能设置结论 `stale`，App 不可以主动设置。
原实现会在版本过期时请求这个不允许的结论。本轮改为远端 `cancelled`，保留本地 stale，
沿既有 journal 和租约确认完成，不重新设计审查内核。

## 验证范围

- 单元测试验证完整正文/批注重新计量、内容被改但保留 marker 时不通过、
  网络错误与缺失区分、报告不泄漏正文及 job 身份冲突前不调用 GitHub。
- 检查不调用任何发布/状态更新/占用解除操作。
- 参数测试拒绝 force、requeue、额外及重复输入；profile 检查拒绝 Worker/API/迁移混跑。
- 实际 PostgreSQL 测试验证读取待确认占用/任务信息，前后 queue 行和 journal 状态不变。
- 原有 Base/Head 过期回归测试继续验证不发布正常评论，并检查远端 cancelled、本地 stale。

## 尚未完成

最终工程检查：完整 `npm run release:check` 通过；Java 41 套件、185 项测试，181 通过、
4 跳过、0 失败/错误，实际 PostgreSQL 测试启用。86 项兼容测试、类型检查、打包、
迁移与发布清单检查通过。已对打包后的 CLI 实测混入 Worker profile 时，在数据库和
Worker 启动前拒绝运行；没有进行真实 GitHub 查证或发布。敏感信息扫描和 diff 检查通过。
临时测试数据库及容器已清理。automatedReady 不表示产品证据门槛或上线批准。

只读报告不是恢复许可，远端状态与本地多次读取不是同一个原子快照。
尚无写入恢复入口、完整冻结输出 artifact 或授权审计；不能强行重发或释放占用。
120 秒预算在调用之间检查，单次 HTTP 仍使用现有超时与有限读取重试，不是硬性全程截止。
接口鉴权会获取短期 installation token，但不更改仓库中的审查结果。

Phase 0/1 产品退出条件及准确率/人工 Review 时间证据仍开放。本轮未部署，不开放 blocking。

来源：[GitHub Check Runs 官方文档](https://docs.github.com/en/rest/checks/runs?apiVersion=2022-11-28)。
