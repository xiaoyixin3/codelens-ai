# PR 摘要发布：持久化、身份查证与并发保护

日期：2026-10-04。生产语言 Java，pipeline `java.10`。复用既有发布器、GitHub
客户端、租约及 effect 表；未重写审查内核，未新增语言或规则。

## 已实现

- 迁移 019 允许 effect 记录 `summary_comment`，发送前保存哈希和已选定的更新目标 ID。
  新增 PR 发布占用表，以 installation/repository/PR 唯一，绑定 run；HTTP 仍在事务外。
- 占用与意图写入受当前租约保护。确认和释放占用同一事务提交；不确定/失败保留占用。
  新任务不能抢占旧的待确认写入。发现更晚创建的 run 时保守阻止旧摘要发布。
- 用 App JWT 查询 `/app`，核对 App ID/slug；另查该 App 的 `[bot]` 用户，核对 Bot
  类型、登录名及用户数字 ID。随后只接管该机器人、目标仓库和 PR 的摘要评论。
  不把 App ID 当作用户 ID；身份缓存十分钟到期。
- Marker 必须在正文开头，不能只是引用/包含。其他人复制 marker 不会被覆盖。
- 评论最多查 20 页、每页 100；每次读取前检查租约。多条机器人摘要、错误 PR、
  无效数据或分页耗尽停止，而不是猜测不存在。
- 正文绑定 run、installation、仓库、PR、Base/Head 与内容哈希；恢复须精确匹配正文，
  并符合已记录目标 ID。远端成功但本地确认失败、或网络超时后结果已可见时，只读恢复。
- 更新前再校验目标的作者/PR/marker。写入后读回正文，确认后才推进最终完成状态。
- 冲突、正文变更、记录缺失或租约丢失，不盲目重发、不覆盖新结果。

## 验证范围

- 模拟 HTTP：外部作者复制 marker、已知外部评论不可 PATCH、错误 PR、第二页发现、
  引用 marker 不接管、多条摘要冲突、分页上限。
- 发布器故障：意图先于写入、确认后释放、本地确认失败和超时后远端成功、
  结果缺失/改变停止、内容重算改变停止、PR 占用冲突时不调用 GitHub。
- PostgreSQL：远端摘要成功后失去租约，另一 Store/Publisher 持有者通过持久化记录
  恢复，不创建第二条评论；确认后占用释放。并发 run 不共享同一 PR 占用；
  旧 run 不覆盖更新 run；记录目标 ID 不允许变更。
- 升级：一次性 PostgreSQL 17 先到 018，保存已确认 Check ID=9 的历史记录，再升级
  019，记录保持不变，占用表为空；没有虚构历史评论意图。

GitHub 完全通过模拟调用验证，没有向实际仓库写入。测试数据库独立于生产。

## 最终检查

- 完整 `npm run release:check` 通过：39 个 Java 套件、175 项测试，171 通过、
  4 跳过、0 失败/错误；数据库集成测试已启用。
- 兼容工具 86 项测试、类型检查、Java 和历史工具打包、迁移 001–019、发布清单
  automatedReady=true，均通过。该清单不是上线批准或产品退出条件。
- 敏感信息扫描无发现，`git diff --check` 通过。未推送、未部署。
- 临时测试 PostgreSQL 容器和数据库已清理。

## 未完成与运营约束

- 仍没有失败 uncertain run 的运营查证/恢复入口。不能盲目删除占用或重新入队。
- 新 run 出现时，旧的待确认占用可能形成保守停止；需要查证，不自动抢占或释放。
- 外部列表不是一致性快照，最终版本检查与 GitHub 写入不是原子操作；不保证 exactly-once。
- sent 记录提交但 HTTP 尚未发出时，恢复不能靠“查不到”决定安全重发。
- 非确定性模型重算若改变内容指纹，仍暂停；尚未冻结完整可恢复输出 artifact。
- 历史无意图记录的待确认发布不得自动当成已完成恢复；升级必须停旧服务并先查证。
- Phase 0 独立评测/时间研究、Phase 1 语义校准及后续补丁预览门槛保持开放。
  工程回归通过不证明节省 Review 时间；本轮不部署、不开放 blocking。

## 来源与设计

按 PostgreSQL 最佳实践保持事务短小，远端网络调用不占用数据库事务锁。
已核对 [GitHub 评论 API](https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28)、
[App API](https://docs.github.com/en/rest/apps/apps) 和
[GitHub 官方 App Token 示例](https://github.com/actions/create-github-app-token/blob/main/README.md)
中的 App slug 与 bot 用户查询方法。
