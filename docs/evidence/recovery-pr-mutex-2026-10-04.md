# 入队与未来恢复的 PR 级互斥（2026-10-04）

## 完成范围

源码 pipeline 变为 java.15，无新增迁移。Java 入队复用现有事务，先取 repository ID
和 PR number 范围的事务级 advisory mutex，再写安装/仓库元数据、run 和 job。内部
`withReviewPrLock` 提供未来恢复可复用的相同范围；不另写队列，不改变输出分析逻辑。

`requireLatestReviewRunLocked` 校验同数据源事务和实际持有的对应锁，然后检查目标
run 是否仍最新。数据库 READ COMMITTED 是前置条件；REPEATABLE READ 等旧快照
隔离直接拒绝。新 run 的 created_at/updated_at 用实际插入的 clock_timestamp，
避免事务较早开始、稍后才插入的 run 被排为旧任务。历史行不改，重复入队身份不改。

遵守 PostgreSQL 最佳实践的短事务要求，不把 GitHub、模型或分析放入互斥事务。
5 秒 lock timeout /10 秒 statement timeout 沿用已有保护，不宣称全流程硬截止时间。
哈希碰撞可能增加串行等待，不会使互斥失效。

## 新增真实 PostgreSQL 测试

1. 持有 PR mutex 时，同 PR 入队实际出现在 pg_locks 的等待记录中；不是仅靠线程
   延迟推断。释放后才提交新 run/job。
2. 不同 PR 的 mutex 能取得；不承诺现有共享元数据行锁也完全不阻塞。
3. 无事务、错误 PR 锁、被新 run 取代的目标拒绝最新性复核；持正确锁的新 run 通过。
4. 事务回滚撤销嵌套入队，释放 mutex，后续事务可继续。
5. 较早启动的事务在另一个 run 已提交后才插入，仍按实际入队时间成为最新。
6. 不安全快照隔离、无效仓库/PR 范围被拒绝。

完整 `release:check` 通过：Java 233 项中 229 通过、4 项外部语义验收跳过，
0 失败/错误；上述 6 项新增真实 PostgreSQL 集成测试全部通过。辅助工具 18 套件、
92 项通过，类型检查和打包通过；最终密钥扫描 342 个文件、0 发现，diff 检查通过。
本机公网 readiness 返回 200。测试只使用一次性隔离数据库，未升级公网库。

## 尚未完成

可写恢复执行器、可信运维身份、短时一次性审批及绑定消费仍未实现。未来执行器必须
使用同 PR 互斥，再锁定/比较队列、原文、日志、占用和审批；仅本基础不能保证所有
状态或跨 GitHub 的原子性。没有自动重排、重新分析、模型调用或远端写入功能。

只有参与协议的 Java 写入路径受保护；原始 SQL、旧 Java、Go/TypeScript 旧写入器
不能与新流程混跑。不能从已锁 job 的 Worker 事务反向申请 PR 锁，以免倒序死锁。
没有把旧暂停任务伪装成新 pipeline。

当前公网保持 java.13/schema 019，未升级镜像或数据库。新候选仍需 001–021、
受保护真实备份与密钥恢复演练、授权/审计执行验收。Phase 0/1 独立产品门槛仍开放。
