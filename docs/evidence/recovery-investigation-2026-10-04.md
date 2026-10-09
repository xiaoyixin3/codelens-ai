# 恢复尝试调查与提交边界演练：2026-10-04

## 本轮交付

内部恢复服务新增受 principal 限制的只读 investigate，审批对象提供非秘密
attemptId 作为调查句柄。复用已有审计表、身份校验与成功审计的本地结构 SQL
谓词，不新增迁移或发布逻辑，不开放 API/CLI，不配置真实凭据、不升级公网服务。

单条数据库语句在独立只读事务内查询，设置 5 秒超时，避免多次读取拼出不一致
的状态。仅允许查询当前已验证主体的尝试；错误主体在访问审计之前被拒绝。
不存在、其他主体或保留删除的记录统一 NOT_FOUND。结果不包含主体/证据哈希、
审批 bearer、代码正文、冻结密文或自由文本错误；时间与已有报告一样使用 UTC。

## 结果与操作边界

- PENDING_INVESTIGATION：requested 已持久化，没有匹配终态。可能在执行、
  已失败、进程退出或结果尚不明确；不能据此断言没有完成。
- DENIED：存在匹配的明确拒绝记录，只输出限定理由。
- COMMITTED_LOCAL_STRUCTURE_MATCHES：存在匹配 committed，当前 run/job/
  日志、目标引用、租约清除和占用释放满足成功审计的结构谓词。
- COMMITTED_LOCAL_STRUCTURE_CHANGED：曾提交，但当前结构不满足谓词。
- NOT_FOUND：无当前主体可见的记录，不代表审批可以重新使用。

所有结果的 automaticRecoveryAllowed 和 approvalReusable 都为 false。调查不
确认、重排、解锁、签发审批、删除/补写审计或调用 GitHub。结构一致不表示原
绑定、原冻结内容或当前 GitHub 输出重新通过核验；数据库管理员篡改也不在保证
范围内。原 committed 历史不能被调查改写为失败。数据库不可用时抛出异常，
不编造终态。

## 真实数据库提交边界测试

测试使用独立 PostgreSQL 17 容器与原有恢复 fixture，GitHub 为模拟对象。
仅测试代码装饰 JDBC 连接，正式代码没有故障注入开关：

1. 审批 requested 独立登记后，业务事务实际提交，再让 commit 向调用方抛出
   SQLException：调用方收到 TransactionSystemException，但数据库已保存
   completed 状态与 committed 审计；调查识别已提交，不追加 denied、不重发。
2. 在业务 commit 前实际 rollback，再向调用方报错：requested 保留，业务
   状态、日志、目标引用、代次和占用回滚；调查为 pending，旧审批不能复用。
3. 成功事务已追加审计但尚未 commit：另一连接调查读到 pending，事务提交后
   再调查才看到 committed；调查不取写锁、不读取未提交结果。

其余测试覆盖：pending 不能误判失败且不泄露内容、denied/NOT_FOUND、错误主体
与跨主体隔离、提交后本地引用变化、审计随 run 保留删除。已有 14 项恢复集成
测试继续保留。新增输入校验单测确认不合格身份参数在数据库访问前拒绝。

初次测试发现默认 ObjectMapper 无法序列化 Instant，已改为与现有 Inspector
一致的 UTC 文本格式并重跑。验收计数在下方记录。

最终验收：7 项新增 PostgreSQL 调查集成测试全部通过；完整 Java 测试和 JAR
打包退出码 0，共 266 项、262 通过、4 外部语义评测跳过、0 失败/错误。
完整 release:check 已在新增调查测试为 6 项时通过，包含 TS 类型检查、18 套
92 项 TS 测试、JAR/旧构建与发布结构检查；追加未提交事务读取测试后重新运行
完整 Java 测试与打包。最终密钥扫描 351 文件、0 发现，git diff --check 通过。
自动检查不等于阶段效果评测或正式上线门槛通过。

## 仍未完成

这是 JDBC 提交边界故障注入，不是实际进程 kill、网络丢包或数据库 failover。
受保护运维入口、前置失败审计、pending 调查后的人工终结策略、密钥隔离/轮换、
真实备份和密钥恢复、实际崩溃演练仍是恢复开放门槛。该调查基础不能自动终结
pending，也不能通过删除审计使审批重新可用。

Java 仍是当前语言，源候选 java.15/001–021；公网仍旧 java.13/001–019。
本地 readyz=200，测试未使用 trial 数据库、未修改真实 PR。Phase 0/1 独立
gold、精度与实际 Review 时间收益门槛没有因为可靠性测试通过而关闭。
