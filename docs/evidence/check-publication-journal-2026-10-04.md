# Check 发布记录与恢复：工程证据

日期：2026-10-04。仍采用 Java，pipeline 升为 `java.9`。本轮仅关闭 Check
创建与结果发布的盲目重发路径，不宣称整个 GitHub Publisher 已完整恢复。

## 实现与安全边界

- 迁移 018 新增 `check_publication_effects`：每个 run 的创建和结果各一条记录，
  保存请求哈希、sent/confirmed 状态及远端 ID；不保存源码或渲染正文，删除 run 时级联删除。
- GitHub 外部标识绑定 run、installation、仓库、PR、Base/Head。输入采用结构化序列化
  后 SHA-256，不拼接未经转义的原始标识。它用于查证，不是 GitHub 的原子幂等键。
- 发送前使用租约保护的短事务保存 sent，提交后才能调用 HTTP；确认再用短事务。
  sent 的意思是“可能已经发送”，包含提交意图后、尚未发 HTTP 的崩溃窗口。
- 恢复创建时按完整 external_id 查找，并核对 App ID、名称和 Head；使用 `filter=all`，
  每页 100，最多 20 页，每页前检查租约。重复匹配、缺失字段、分页预算耗尽均停止。
- 结果正文携带该 run 和完整请求哈希。恢复核对 completed 状态、conclusion、标题、
  正文和注解数量，确认同一结果后只更新本地确认，不再 PATCH/追加 annotations。
- 网络异常后可以立即做一次只读查证；查证失败或结果不符则停止，不能再发送同一请求。
- 重审验证旧 Check 的 App/Head，但创建新 Check，不改写旧 Check，不把旧注解混入新结果。
- 已提交 completed/stale 的本地 run，在队列确认丢失后只确认队列，不重新分析或发布。
- 内容重算产生不同指纹时停止；不以新的模型结果覆盖已经发送的请求。
- 确认记录不能改换请求哈希或远端 ID；过期或替换持有者不能记录发送或确认。

## 故障验证范围

1. 创建前意图已提交；远端创建成功、本地确认失败后，恢复找到同一 ID，POST 只发生一次。
2. 远端结果成功、本地确认失败后，只读查证恢复，结果 PATCH 只发生一次。
3. 网络超时但远端已提交时，通过只读查证继续；没有证据时停止，不盲重发。
4. 内容指纹、远端状态或注解数量不符时停止。
5. Check 超过第一页时可发现；其他 App 复制标识不会被接管；重复和分页耗尽停止。
6. 实际 PostgreSQL 验证意图持久化、确认冲突、级联删除与旧租约持有者隔离。
7. 实际 PostgreSQL + 模拟 GitHub 注入远端成功后租约丢失：新持有者通过另一 Store/
   Publisher 实例恢复；创建与结果均不重复发送。没有向实际 GitHub 写入。

## 迁移升级检查

一次性 PostgreSQL 17 先执行 001–017，插入历史 completed run 及 Check ID=9 的
publication，再执行 018。历史状态/ID 保持原样，新 journal 为空，没有虚构历史意图。
测试数据库与生产隔离；旧 Worker 必须停止，历史未解决发布必须先查证后再安排升级。

## 最终检查结果

- 完整 `npm run release:check` 通过：Java 测试、兼容工具类型检查/86 项测试、
  Java 与历史工具打包、敏感信息扫描、迁移 001–018 和发布清单检查。
- 增补跨持有者恢复测试后再跑完整 Java 测试：39 套件、159 项，155 通过、
  4 跳过、0 失败/错误；实际 PostgreSQL 用例启用并通过。
- `git diff --check` 通过。未推送、未部署、未创建实际 GitHub Check/评论。
- 临时 PostgreSQL 容器及数据库已清理。automatedReady 只说明工程检查通过，
  不表示原基线的上线、准确率或节省人工时间门槛达成。

## 仍未完成

- PR 摘要评论没有持久化发布意图；作者/PR 归属校验、完整分页与内容查证待完成。
  评论写入与本地确认之间崩溃仍有风险，不能据此上线。
- uncertain failed run 没有运营查证/恢复命令；目前只恢复现有租约协议允许重认领的任务。
- sent 已提交但尚未发送的窗口无法凭“没找到”自动判断，保守停止可能要求人工运维。
- GitHub 列表不是一致性快照；最终版本检查与远端写入也不是原子操作。
- 已记录内容没有冻结为可重发 artifact；非确定性模型重算变化会停止，不自动恢复。
- 不保证 exactly-once，不开启 blocking；Phase 0 独立 gold/时间研究与 Phase 1
  语义关系校准退出条件保持开放。工程故障测试不证明 Review 质量或节省时间。

## 设计依据

使用 PostgreSQL 最佳实践的短事务原则：本地记录持有权和意图，远端网络调用在事务外。
接口行为核对 [GitHub Check Runs 官方文档](https://docs.github.com/en/rest/checks/runs?apiVersion=2022-11-28)：
external_id 是关联标识，Check 列表需要分页，更新 annotations 不是替换旧批注。
