# 发布重试止损：工程证据与未完成边界

日期：2026-10-04。生产语言保持 Java。复用现有 GitHub 客户端、ReviewEngine、
Worker 和 PostgreSQL 租约，不另建发布系统；正式技术基线的退出条件不变。

## 已实现

- GitHub GET/HEAD 的临时网络异常、429 和 5xx 最多尝试三次；永久错误立即返回。
- POST/PATCH 每次客户端调用只发送一次，不自动重发；错误信息不包含远端响应正文。
- 写入网络异常、线程中断、5xx、2xx 后无法解码及创建响应缺少有效 ID，抛出专用
  `PublicationUncertainException`，表示可能已经提交，而非断言失败。
- Check 创建后本地保存失败，以及 Check 完成后评论/本地确认失败，同样停止常规重试。
  这避免在这些可捕获故障后重跑整个审查并再次追加 annotations。
- 当前持有者将任务及审查一起标为 failed，记录 `PUBLICATION_UNCERTAIN`，清除租约。
  不增加 attempts、不再认领、不发送另一个失败 Check。终止失败通知发生不确定写入时
  也使用这个停止分支。失去租约时保留原有 fencing 行为，不越权写状态。
- 停止状态使用短事务，含锁/语句超时；外部 HTTP 不在事务内。无新 schema 迁移。

## 验证

使用一次性 PostgreSQL 17 容器，迁移 001–017 成功；未连接生产数据库。
GitHub 调用通过模拟 HTTP 验证，未向实际 GitHub 创建 Check 或评论。

- HTTP 故障测试覆盖创建超时、注解更新 503、2xx 损坏 JSON/缺少 ID、403、429、
  临时读取错误三次上限、线程中断及中断标志保留。
- ReviewEngine 注入“远端创建成功但本地保存失败”和“Check 成功但评论失败”。
- Worker 测试覆盖普通发布及终止失败通知的不确定写入，不调用常规 retry/complete。
- 实际 PostgreSQL 测试覆盖队列/审查原子停止、attempts 不增加、不重复认领，
  以及过期/被替换持有者不能停止另一持有者的任务。
- 完整 `npm run release:check` 通过：Java 测试、兼容工具类型检查及 86 项测试、
  Java 打包、历史工具打包、安全扫描 303 文件无发现、发布清单 automatedReady=true。
  此清单只表示工程检查通过，不代表原基线质量门槛或上线批准。
- 补充终止失败通知故障测试后，再跑完整 Java 测试：38 套件、135 项，
  131 通过、4 跳过、0 失败/错误；其中实际数据库测试未跳过。
  一次性容器及测试数据库已销毁，仅保留测试报告。

## 明确未完成

目前只是可捕获异常的止损，不是完整发布恢复，更不是 exactly-once：

1. 未实现发送前持久化 intent，也没有稳定的每次 run 远端 external_id。
2. 请求发出后进程崩溃，或停止记录因数据库不可用写入失败，租约回收仍可能重跑。
3. 已完成 Check 在崩溃后重跑，仍可能重复追加 annotations。
4. 评论发现仍有单页与通用 marker 问题；App 作者、PR 归属校验和分页查证待完成。
5. 尚无自动查证/恢复入口；不得盲目重新入队或点击 rerun。
6. 此轮没有测量人工 Review 时间，也没有替代独立 gold 或语义关系校准。

下一项必须先落地持久化发布意图和稳定身份，再用只读接口查证 Check/评论，
验证作者、仓库、PR、版本及内容指纹。只有证据确认可安全继续时恢复；
冲突、缺失或分页预算耗尽均停止，而不推测远端没有写入。
在这些缺口与原基线门槛关闭前，保持未上线，不开启 blocking。

## API 依据

已核对 [GitHub Check Runs 官方文档](https://docs.github.com/en/rest/checks/runs?apiVersion=2022-11-28)
与 [Issue Comments 官方文档](https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28)。
Check 更新涉及追加 annotations，评论查找必须处理分页；不能把简单重发当作安全幂等。
