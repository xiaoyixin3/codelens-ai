# CodeLens AI：真正减轻 Review 工作量的技术重构规划

> 文档版本：v2.0 规划基线  
> 日期：2026-09-25  
> 状态：已批准，项目正式技术基线  
> 适用项目：CodeLens AI / AI Code Review Intelligence

## 1. 结论先行

当前版本已经具备可靠的 GitHub App、Webhook、PostgreSQL 队列、Java Worker、审计、模型接入和发布基础，但它还不是一个能够显著减轻程序员 Review 工作量的产品。

当前审查内核主要完成三件事：

1. 统计文件和增删行并生成 diff 摘要；
2. 通过正则表达式寻找少量显性风险模式；
3. 在 PR 变更文件范围内建立有限符号和调用关系。

这能帮助 Reviewer 更快浏览 PR，却不能替 Reviewer 完成可信的第一轮审查。程序员仍然需要重新理解需求、架构、调用链、异常路径和测试覆盖，因此节省的只是“翻文件时间”，不是“判断代码是否正确的时间”。

下一阶段的正式决策是：

> 保留现有 Java 生产基础设施，停止继续堆叠正则规则和表面化语言支持；重做 Review Intelligence 内核，把产品从“Diff 解说器”升级为“有证据、能执行验证、能给出修复方案的第一轮 Reviewer”。

项目是否成功，不再以“成功发布了一条评论”衡量，而以以下结果衡量：

- Reviewer 是否更快理解 PR；
- 系统是否发现了 Reviewer 认可的真实问题；
- Reviewer 是否可以直接采用建议的修复或测试；
- 使用系统后，人工第一轮 Review 时间是否显著下降；
- 系统是否明确说明未覆盖范围，而不是用泛化结论制造安全感。

## 2. 当前实现与目标之间的差距

### 2.1 已经完成且应当保留的能力

以下能力属于可复用的生产底座，不应推倒重来：

- GitHub App 安装认证和短期 Installation Token；
- Webhook 精确字节验签、delivery 去重和 SHA 幂等；
- Java 17 / Spring Boot API、Worker、迁移程序；
- PostgreSQL 持久化队列、租约恢复、重试和运行状态；
- stale SHA 检查和稳定更新的 Check Run / PR 评论；
- `.codelens.yml`、`CODELENS.md`、模型供应商和凭据管理；
- finding、证据、模型调用和反馈的审计数据；
- 数据保留、删除、安全扫描和 CI 基础。

这些模块解决的是“安全、可靠地运行一次 Review”，不是“Review 本身是否有价值”。它们约占完整产品工程量的 35%–45%。

### 2.2 必须重做的能力

| 当前能力 | 现实问题 | 下一代要求 |
|---|---|---|
| 按文件名、增删行生成摘要 | 只描述发生了什么，不解释行为如何改变 | 输出旧行为、新行为、业务意图、关键不变量和兼容性变化 |
| 正则表达式识别符号 | 无法可靠处理类型、重载、继承、泛型和跨模块调用 | 使用编译器/AST 语义模型，建立全仓库或可证明的局部语义图 |
| 只索引 changed files | 看不到未修改的调用方、接口实现、测试和配置消费者 | 建立 base SHA 仓库索引，并对 head SHA 做增量更新 |
| 只验证 finding 是否落在新增行 | 行号正确不等于结论正确 | 验证调用路径、类型关系、数据流、失败条件和反证 |
| LLM 阅读截断后的 diff | 缺少精确上下文，容易复述或猜测 | 通过工具按需获取定义、引用、调用方、测试、配置和历史 |
| 不执行仓库代码 | 无法证明构建失败、测试回归或边界行为 | 在隔离沙箱执行构建、目标测试和受限静态分析 |
| 只给建议，不验证修复 | Reviewer 仍需自行设计和验证修改 | 生成候选补丁与回归测试，并在沙箱验证后再展示 |
| 多语言表面覆盖 | 每种语言都只有浅层能力 | 首先把 Java 和 TypeScript 做深，再按同一验收标准扩展 |

### 2.3 停止错误方向

在以下条件满足前，不再新增语言、不再增加低价值正则规则，也不再用评论数量证明进展：

- Java 与 TypeScript 至少一种语言完成全仓库语义索引；
- 存在真实人工标注的缺陷与干净 PR 对照集；
- 高风险 finding 精确率达到发布门槛；
- 使用组的人工 Review 时间相对对照组显著下降；
- 至少一种“发现问题 → 生成测试/补丁 → 沙箱验证”的闭环可用。

## 3. 产品目标与非目标

### 3.1 一句话产品定义

CodeLens AI 是一个证据驱动的第一轮代码审查系统：它理解 PR 意图和仓库上下文，验证高风险行为，给出少量可操作结论，让程序员把时间集中在架构取舍和少数真正需要人判断的问题上。

### 3.2 用户完成一次 Review 后应得到什么

输出必须回答六个问题：

1. 这次 PR 试图改变什么用户或系统行为？
2. 实际实现与声明意图是否一致？
3. 哪些入口、调用方、数据和测试会被影响？
4. 哪些问题已经被代码证据或执行结果证明？
5. 哪些风险仍需要人判断，原因是什么？
6. 当前是否可以合并；若不能，最小阻塞项是什么？

### 3.3 非目标

- 不承诺替代最终 Reviewer 或自动批准所有 PR；
- 不对缺乏证据的猜测发布 inline comment；
- 不默认运行不受信任代码时开放网络、宿主文件系统或生产凭据；
- 不以“支持语言数量”作为近期目标；
- 不建立无法恢复、不可审计的自由循环多 Agent 系统；
- 不在未验证前自动向用户分支提交代码。

## 4. 可量化的成功标准

### 4.1 北极星指标

北极星指标是“人工第一轮 Review 时间下降比例”，而不是评论数量。

采用同类 PR 交叉对照：一组 Reviewer 使用 CodeLens 结果，另一组只使用 GitHub 原生 diff；交换人员和样本以降低个人能力偏差。

| 指标 | Beta 门槛 | 正式可用门槛 |
|---|---:|---:|
| 人工第一轮 Review 中位时间下降 | ≥ 20% | ≥ 35% |
| 高风险 finding 人工确认精确率 | ≥ 80% | ≥ 90% |
| 所有已发布 finding 明确误报率 | ≤ 15% | ≤ 8% |
| 基准集中高风险缺陷召回率 | ≥ 60% | ≥ 75% |
| “仅复述 diff”的评论占比 | ≤ 15% | ≤ 5% |
| finding 根因证据完整率 | ≥ 90% | ≥ 98% |
| 经验证候选补丁可直接采用或轻改采用 | ≥ 35% | ≥ 55% |
| 中位 PR 完成时延（30 文件/2,000 行内） | ≤ 8 分钟 | ≤ 5 分钟 |
| 运行成功率 | ≥ 95% | ≥ 99% |

### 4.2 指标定义

- **精确率**：发布的 finding 中，被两名 Reviewer 独立确认确实需要修改的比例。
- **召回率**：人工金标缺陷中，系统成功识别同一根因的比例；不能用行号完全一致作为必要条件。
- **误报**：结论错误、没有现实失败路径、与本次变更无因果关系，或严重度明显夸大的 finding。
- **根因证据完整**：包含触发条件、相关符号、调用或数据路径、失败结果和建议验证方式。
- **Review 时间**：从 Reviewer 首次打开 PR 到给出 approve/request changes/正式评论的有效操作时间。
- **补丁采用率**：系统生成且通过沙箱验证的补丁，被 Maintainer 原样或轻微修改后采用的比例。

### 4.3 发布红线

出现以下任一情况不得开放 blocking 模式：

- 高风险精确率低于 90%；
- 样本少于 300 个真实 PR 或正例少于 100 个根因；
- 对私有仓库的沙箱、数据保留或凭据隔离没有通过安全评审；
- 系统无法明确报告未索引文件、未运行测试和工具失败；
- 同一 SHA 可能重复发布或新 SHA 到达后仍发布旧结论。

## 5. 下一代总体架构

```text
GitHub Webhook
  → Review Orchestrator（现有 Java 基础）
  → Ephemeral Workspace（新增）
  → Base Repository Index（重做）
  → Head Incremental Index（重做）
  → Semantic Change Model（新增）
  → Validation Planner（新增）
  → Sandboxed Build/Test/Analysis（新增）
  → Evidence-driven Review Pipeline（重做）
  → Finding Challenger & Verifier（重做）
  → Patch/Test Synthesizer（新增）
  → GitHub Publisher（复用并升级）
```

### 5.1 模块保留、重写与新增

| 模块 | 决策 | 说明 |
|---|---|---|
| Webhook/API、队列、状态机 | 保留 | 当前 Java 基础继续使用 |
| GitHub 客户端、发布器 | 保留并扩展 | 增加 review decision、artifact 链接和 patch 展示 |
| PostgreSQL、审计、凭据 | 保留 | 扩展 artifact、tool run、patch validation 表 |
| `ReviewEngine.summarize` | 重写 | 当前统计式摘要不再作为正式结果 |
| 正则多语言 Indexer | 降级为 fallback | 不再作为“语义分析完成”的依据 |
| Context Builder | 重写 | 从截断 diff 改为可查询的证据包 |
| LLM Risk Reviewer | 重写 | 从一次性生成改为受控调查流程 |
| Evidence Verifier | 重写 | 从行号校验升级为语义、执行和反证验证 |
| Repo Materializer | 新增 | base/head 临时工作区、缓存和销毁 |
| Semantic Adapters | 新增 | Java、TypeScript 深度解析 |
| Sandbox Runner | 新增 | 构建、测试、静态分析和补丁验证 |
| Patch Synthesizer | 新增 | 只对高置信问题生成候选修复和测试 |

### 5.2 为什么不采用自由循环 Agent

系统可以使用模型进行规划和调查，但外层必须是确定性的状态机：

- 每个阶段有固定输入、输出 Schema 和预算；
- 工具调用有白名单、次数、时间和数据范围限制；
- 所有调用可审计和重放；
- 模型不能直接发布评论、修改数据库状态或获得 GitHub 写权限；
- 最终 publication 只能接受已通过 Verifier 的结构化结果。

这既保留 Agent 的上下文探索能力，也避免不可控循环、成本失控和无法复现。

## 6. Repository Materializer 与安全沙箱

### 6.1 工作区模型

每个 Review Run 创建一次隔离工作区：

```text
workspace/{review_run_id}/
  base/       base SHA 只读工作树
  head/       head SHA 可写临时工作树
  artifacts/  构建日志、测试结果、分析报告
  patches/    候选修复，不直接推送
```

流程结束后销毁源码工作区，只保留按策略允许的结构化证据和工件摘要。

### 6.2 沙箱强制约束

- Linux 容器或等效隔离运行时；
- 默认无外网，仅允许配置的依赖镜像/代理；
- 不挂载 Docker socket、宿主 SSH、云凭据和 GitHub 私钥；
- 文件系统限制、非 root 用户、只读 base、临时 head；
- CPU、内存、进程数、磁盘和最长执行时间硬限制；
- 构建日志先做凭据检测和脱敏再持久化；
- fork PR 和不可信仓库默认不执行脚本，除非仓库显式允许；
- `postinstall`、Gradle/Maven 插件和测试均视为不可信代码。

### 6.3 可信执行等级

| 等级 | 允许能力 | 典型场景 |
|---|---|---|
| S0 | 不 checkout，只读 GitHub diff | 外部 fork、未知仓库 |
| S1 | checkout + AST，不执行代码 | 默认私有/公开仓库 |
| S2 | 无网络构建和目标测试 | 已批准仓库 |
| S3 | 受控依赖代理和完整验证 | Design Partner 仓库 |

Review 结果必须展示本次使用的等级。

## 7. 语义索引与变化模型

### 7.1 首期语言范围

首期只把以下两条路径做到生产质量：

- **Java**：类型、方法、构造器、继承/实现、注解、调用、字段引用、异常、事务边界和测试映射；
- **TypeScript/JavaScript**：module、import/export、类型、函数、类、调用、Promise/async、框架路由和测试映射。

其他语言继续允许 diff-only 降级，但 UI 必须明确标注“未完成语义分析”，不得输出伪精确的影响结论。

### 7.2 索引策略

1. 首次接入仓库时为默认分支或 base SHA 建立全仓库索引；
2. 索引以 `repository + commit SHA + adapter version + build model hash` 唯一；
3. head SHA 只重新解析 changed files 和受影响的依赖单元；
4. 通过稳定符号键匹配 base/head，生成行为变化模型；
5. 未变化文件沿用 base 索引，因此可以发现未修改的调用方和测试；
6. 每条关系保存来源位置、解析器、置信度和是否经类型解析。

### 7.3 必须支持的关系

- `DECLARES`、`IMPORTS`、`EXPORTS`；
- `CALLS`、`READS`、`WRITES`；
- `IMPLEMENTS`、`EXTENDS`、`OVERRIDES`；
- `THROWS`、`CATCHES`；
- `ANNOTATED_WITH`；
- `HANDLES_ROUTE`、`ACCESSES_DATABASE`、`CALLS_REMOTE`；
- `TESTS`、`MOCKS`、`CONFIGURES`。

### 7.4 Semantic Change Model

不能只记录“方法内容哈希改变”。每个变化至少识别：

- API 签名、可见性、返回值和异常合同变化；
- 条件分支和默认值变化；
- 数据写入、事务范围和幂等语义变化；
- 权限检查增加、删除或顺序变化；
- 同步/异步、锁、线程和资源生命周期变化；
- 依赖、配置、数据库 schema 和序列化合同变化；
- 测试增加、删除以及与变化行为的映射。

输出示例：

```json
{
  "symbol": "PaymentService.charge",
  "behaviorChanges": [
    "remote_call_moved_inside_transaction",
    "timeout_removed",
    "new_retry_without_idempotency_key"
  ],
  "affectedEntrypoints": ["PaymentController.pay"],
  "affectedTests": ["PaymentServiceTest.timeout"],
  "confidence": 0.94
}
```

## 8. 受控 Review Agent

### 8.1 阶段划分

Review Agent 不是一个 Prompt，而是五个受控阶段：

1. **Intent Extractor**：从 PR 描述、Issue、commit 和代码变化提取目标、不变量与验收条件；
2. **Change Investigator**：查询变化符号、调用方、被调用方、接口、数据和测试；
3. **Risk Investigator**：针对 correctness、security、data integrity、concurrency、compatibility、performance 和 test gap 建立候选问题；
4. **Finding Challenger**：主动寻找反证、既有保护、上游校验和测试覆盖，淘汰猜测；
5. **Fix Synthesizer**：仅为通过验证的 finding 生成最小修复和回归测试。

每阶段最多使用固定次数的工具调用，不允许模型自行无限扩展任务。

### 8.2 工具接口

模型只能使用结构化只读工具：

- `get_symbol_definition(symbol)`；
- `find_references(symbol, direction, depth)`；
- `get_call_path(from, to)`；
- `get_base_head_diff(symbol)`；
- `find_tests_for(symbol)`；
- `read_file_range(path, start, end, sha)`；
- `get_build_model()`；
- `get_validation_result(tool_run_id)`；
- `search_repository(query, scope)`；
- `propose_patch(finding_id)`。

工具返回内容必须带 SHA、路径、行号、截断信息和来源，模型不得引用工具未返回的代码事实。

### 8.3 Finding 证据等级

| 等级 | 含义 | 是否允许 inline |
|---|---|---|
| E0 | 仅启发式或模型猜测 | 否 |
| E1 | 当前 diff 行支持，但缺少跨文件语义 | 默认否 |
| E2 | 类型/调用/数据关系支持，失败条件明确 | 可以 |
| E3 | 构建、测试或静态工具复现 | 可以，优先 |
| E4 | 问题复现且候选修复通过回归验证 | 可以，最高优先级 |

Critical/High finding 至少达到 E2；作为 blocking 条件时原则上要求 E3，安全类明确 source-to-sink 证据可例外。

### 8.4 下一代 Finding 合同

```json
{
  "rootCauseId": "transaction.remote-call.idempotency",
  "category": "data_integrity",
  "severity": "high",
  "confidence": 0.93,
  "evidenceLevel": "E3",
  "claim": "支付重试在数据库事务内调用远端且没有幂等键，超时重试可能产生重复扣款。",
  "trigger": "远端完成扣款但本地请求在提交前超时",
  "path": [
    "PaymentController.pay",
    "PaymentService.charge",
    "PaymentProvider.createCharge"
  ],
  "evidence": [
    {"path": "...", "line": 84, "sha": "...", "fact": "remote call occurs before transaction commit"}
  ],
  "counterEvidenceChecked": [
    "no upstream idempotency key",
    "no unique charge constraint",
    "existing test does not cover timeout-after-provider-success"
  ],
  "reproduction": "targeted test paymentTimeoutAfterProviderSuccess failed",
  "suggestedFix": "persist idempotency key and separate remote call from transaction",
  "patchValidation": "candidate patch passed 18 targeted tests"
}
```

## 9. Validation Planner 与执行验证

### 9.1 自动选择最小验证集

系统不应默认运行整个仓库。Validation Planner 根据变化符号和构建模型选择：

- 编译受影响 module/package；
- 运行直接测试和一跳受影响测试；
- 运行仓库已有 lint/static analysis；
- 对高风险路径生成一个隔离的候选回归测试；
- 仅在预算允许或风险较高时扩大测试范围。

计划必须在执行前持久化，避免模型根据结果无限试错。

### 9.2 首期验证适配器

**Java**

- Maven/Gradle 项目识别；
- compile/testCompile；
- JUnit 定向测试；
- 仓库已有的 Checkstyle、SpotBugs、PMD 等任务；
- 数据库迁移文件的顺序和兼容性检查；
- Spring 路由、事务和安全注解的静态语义检查。

**TypeScript/JavaScript**

- npm/pnpm/yarn 锁文件识别；
- TypeScript typecheck；
- Vitest/Jest 定向测试；
- ESLint 和仓库已有检查；
- async、Promise、资源生命周期和 API schema 变化检查。

### 9.3 工具结果不是 finding

编译器或静态工具输出先作为 Candidate Evidence，经过以下处理后才发布：

- 与本次 diff 建立因果关系；
- 合并同一根因的多个错误；
- 排除基线已存在的问题；
- 映射到 Reviewer 可理解的行为影响；
- 给出复现命令和实际覆盖范围。

## 10. 候选补丁和回归测试

### 10.1 生成条件

仅对 E2 以上、根因清晰、修改范围可控的问题生成补丁。以下情况只给建议：

- 涉及产品取舍或公共 API 设计；
- 需要数据库迁移策略选择；
- 跨多个服务或仓库；
- 缺少可运行测试环境；
- 多种修复方案代价差异明显。

### 10.2 验证流程

```text
基线目标测试通过
  → 注入候选回归测试
  → 证明原 head 失败或暴露问题
  → 应用候选补丁
  → 回归测试通过
  → 原有受影响测试通过
  → 输出补丁和验证记录
```

无法完成“先失败、后通过”的补丁不得标记为 verified fix。

### 10.3 GitHub 展示

默认不自动提交。PR Summary 展示：

- 修复目的和修改范围；
- 可复制或展开查看的 patch；
- 新增回归测试；
- 运行的命令、通过/失败数量和环境；
- “创建修复分支”属于后续显式操作，必须由用户授权。

## 11. 输出重新设计

最终评论必须短、可决策，并控制在以下结构：

```text
Review decision
  建议：需要修改 / 可合并但需确认 / 未发现阻塞项

Behavior change
  旧行为 → 新行为 → 受影响入口

Must fix（0–3 项）
  每项包含根因、触发条件、影响、证据等级、修复和验证

Needs human judgment（0–3 项）
  明确说明为什么工具无法替人判断

Validation performed
  构建、测试、静态工具、失败和未运行项

Coverage and limitations
  已索引范围、沙箱等级、未分析模块、模型/工具失败
```

禁止发布以下内容：

- “该文件发生了修改，请注意检查”；
- 单纯罗列文件名、增删行或 commit message；
- 没有失败路径的“可能存在风险”；
- 把测试缺失直接等价为生产缺陷；
- 重复静态工具原始输出；
- 超过 8 条、没有优先级的批注列表。

## 12. 数据模型扩展

在保留现有表的基础上新增或扩展：

| 表/实体 | 主要内容 |
|---|---|
| `repository_snapshots` | 完整 base 索引、build model、adapter version |
| `semantic_changes` | 行为变化、合同变化、影响入口和测试 |
| `review_artifacts` | diff model、context pack 摘要、构建/测试报告 |
| `validation_plans` | 计划运行的工具、理由、预算和状态 |
| `tool_runs` | 命令类型、隔离等级、退出码、耗时、脱敏日志摘要 |
| `finding_candidates` | 发布前的候选、淘汰原因和 Challenger 结果 |
| `finding_evidence` | 代码、调用路径、执行结果、反证和证据等级 |
| `candidate_patches` | patch、测试、验证状态和失败原因 |
| `reviewer_sessions` | 可匿名化的 Review 耗时和决策指标 |

原始仓库源码不长期写入数据库。大体积日志和报告进入有生命周期策略的对象存储；数据库只保存哈希、摘要、位置和访问控制信息。

## 13. 评测体系

### 13.1 四类数据集

1. **真实缺陷修复集**：历史上先合并、后通过修复 PR 纠正的问题，反向构造待审变化；
2. **干净 PR 集**：已充分 Review 且长期没有相关回归的变更，用于测量误报；
3. **受控 mutation 集**：注入资源泄漏、权限绕过、事务、并发、空值和兼容性缺陷，用于稳定回归；
4. **Design Partner 在线集**：系统结果在真实 Review 中被隐藏标注，再逐步开放给 Reviewer。

mutation 集只能用于回归，不能单独用于对外宣称产品精确率。

### 13.2 标注规范

- 标注单位是“根因”，不是评论或代码行；
- 每个样本至少两名 Reviewer 独立标注，冲突由第三人裁决；
- Reviewer 在标注真值前不能看到系统预测；
- 记录严重度、触发条件、最小证据和可接受修复；
- 同时标注系统是否节省时间，即使系统没有发现新缺陷；
- 评测报告必须按语言、仓库、风险类别和 PR 大小分层。

### 13.3 每次合并必须通过的回归门槛

- 既有高风险金标不得无解释丢失；
- 新增规则/Prompt 不得让 clean set 误报率增加超过 2 个百分点；
- 相同输入、相同模型配置下结构化阶段可重放；
- 非确定性模型输出必须通过确定性 Verifier；
- 评测差异按 pipeline、adapter、prompt 和 model 版本归档。

## 14. 分阶段实施计划

时间是工程量估算，不是发布日期承诺。每阶段只有达到退出条件才能进入下一阶段。

### Phase 0：重新建立真实基线（1–2 周）

交付：

- 冻结当前 broad multi-language 宣传；
- 收集最近真实批注样本，分类“复述、误报、有效、漏报”；
- 完成至少 50 个 PR、20 个正例根因的盲标小集；
- 建立 Review 耗时采集方法；
- 保存当前版本的精确率、召回率、耗时和成本基线。

退出条件：可以用数据说明当前版本节省了什么、没有节省什么。

### Phase 1：Repository Materializer 与全仓库索引（3–4 周）

交付：

- 临时 base/head 工作区和安全销毁；
- Java build model 识别；
- Java 全仓库符号、类型、调用、继承和测试关系；
- base snapshot 缓存与 head 增量更新；
- 索引覆盖率和降级原因可见。

退出条件：在选定 Java 仓库中，静态可解析的直接调用关系精确率 ≥ 90%，且能找到未修改文件中的直接调用方和测试。

### Phase 2：Semantic Change Model 与意图理解（2–3 周）

交付：

- API、条件、异常、数据写入和事务行为变化识别；
- PR/Issue/commit 意图抽取；
- “声明意图 vs 实际实现”差异；
- 新版 Behavior Change Summary。

退出条件：人工抽查中，“仅复述 diff”的摘要低于 10%，Reviewer 对摘要有用率 ≥ 75%。

### Phase 3：沙箱与执行验证（3–4 周）

交付：

- S1/S2 沙箱等级；
- Maven/Gradle 编译和 JUnit 定向测试；
- Validation Planner；
- 基线问题过滤；
- 工具结果与 PR 因果映射。

退出条件：至少 80% 的目标 Design Partner Java PR 能完成编译或给出明确、可行动的失败原因；测试结果不泄露凭据。

### Phase 4：Evidence-driven Review Agent（3–4 周）

交付：

- Intent、Investigator、Challenger、Verifier 流水线；
- E0–E4 证据等级；
- 根因去重和反证检查；
- 决策式 PR Summary；
- 不满足 E2 的高风险候选不发布。

退出条件：盲评集中高风险精确率 ≥ 85%，误报率 ≤ 12%，真实 Reviewer 有用率 ≥ 70%。

### Phase 5：补丁与回归测试闭环（2–3 周）

交付：

- 对选定风险类型生成回归测试和最小 patch；
- before-fail / after-pass 验证；
- GitHub 中展示 patch 和验证报告；
- 无用户授权不提交分支。

退出条件：至少 30 个真实或高质量历史缺陷中，≥ 40% 的候选补丁可直接或轻改采用，且不存在错误声称“已验证”的案例。

### Phase 6：Design Partner Beta（至少 4 周）

交付：

- 5–10 个经过授权的 Java/TypeScript 仓库；
- 对照 Review 时间实验；
- 每周误报、漏报、节省时间和成本报告；
- 按仓库逐步开启 blocking；
- 安全、保留、回滚和事故演练。

退出条件：达到正式可用门槛，并由真实数据证明第一轮 Review 时间下降 ≥ 35%。

## 15. 首个迭代的具体 Backlog

### P0：必须先做

1. 新增 `repository_workspace` 接口和本地安全实现；
2. 定义 `SemanticAdapter`、`BuildModel`、`SemanticChange` 数据合同；
3. 为 Java 仓库建立全量 base snapshot；
4. 提取类、方法、字段、继承、实现和类型解析后的调用关系；
5. 关联 JUnit 测试与被测符号；
6. 将现有 changed-file regex graph 标记为 `fallback`，禁止作为完整影响分析；
7. 建立 50 PR 盲标集和当前版本基线报告；
8. 修改 GitHub 输出，明确标识 `diff-only / semantic / executed` 覆盖级别。

### P1：紧随其后

1. Maven/Gradle module 和目标测试识别；
2. 隔离沙箱原型；
3. Semantic Change Model 第一版；
4. Intent Extractor 和新版 Summary Schema；
5. 工具查询 API 与预算；
6. Finding Challenger；
7. tool run、artifact、candidate finding 数据表；
8. 评测报告按类别和仓库分层。

### 暂缓

- 新增更多语言；
- Web Dashboard 美化；
- 自动创建修复 PR；
- 跨仓库调用图；
- 深度自主 Agent；
- Neo4j 或向量数据库迁移；
- 低价值风格类批注。

## 16. 工程质量与安全门槛

- 所有新增数据库变更使用前向 migration，不改历史 migration；
- 所有工具执行必须有 timeout、资源上限、取消和审计；
- base/head、工具版本、模型版本、prompt 版本和策略哈希全部可追踪；
- Finding 必须可重放到相同证据，无法重放则标记失效；
- 外部仓库内容永远视为不可信数据和潜在 Prompt Injection；
- 仓库文本不能改变系统权限、工具白名单或发布阈值；
- 模型看不到 GitHub App 私钥、供应商密钥和数据库凭据；
- 日志、artifact 和模型输入在外发前执行凭据检测与脱敏；
- 任何 blocking 结论都有人工可查看的证据链和明确关闭方式。

## 17. 主要风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| 全仓库索引成本过高 | 时延和费用失控 | base 缓存、增量 head、按 module 分片、明确预算 |
| 构建执行不安全 | 供应链或宿主机风险 | 分级沙箱、无网默认、非 root、资源限制、批准名单 |
| 模型仍然产生合理但错误的结论 | 失去 Reviewer 信任 | Challenger、反证、E2 门槛、执行验证、少发评论 |
| Java/TS 工程差异大 | 适配器碎片化 | 稳定 SemanticAdapter 合同，语言能力单独验收 |
| 测试执行时间过长 | PR 等待时间不可接受 | 目标测试、风险驱动扩展、后台完整验证 |
| Benchmark 被规则污染 | 指标虚高 | 盲标、隔离测试集、按时间切分、第三人裁决 |
| 用户只看结论不看覆盖范围 | 形成错误安全感 | Summary 首屏展示 coverage、execution level 和未分析项 |
| 补丁修复局部但破坏架构 | 产生新债务 | 仅输出候选、验证受影响测试、复杂设计交给人工 |

## 18. 项目治理和决策机制

每两周只审查四类事实：

1. 哪些 finding 被真实 Reviewer 接受或否定；
2. 哪些漏报造成 Reviewer 仍需完整重审；
3. Review 时间是否下降；
4. 哪些成本来自索引、工具或模型，是否换来价值。

以下内容不能作为阶段完成证明：

- 新增代码行数；
- 新增规则数量；
- 支持语言数量；
- 模型生成评论数量；
- Demo 中看起来合理的单个案例；
- 没有经过盲标的数据集得分。

每个阶段必须产出一份简短决策记录：继续、调整范围或停止。如果某能力连续两个阶段无法提高精确率、召回率或 Review 时间，应当删除或降级，而不是继续包装。

## 19. 最终 Definition of Done

CodeLens AI 只有同时满足以下条件，才可以称为“真正减轻程序员 Review 工作量”：

- 能说明 PR 的行为意图和实际实现，而不是罗列文件变化；
- 能发现未修改代码中的受影响调用方、接口和测试；
- 高风险结论至少具有语义证据，blocking 结论通常具有执行证据；
- 能主动寻找反证并淘汰看似合理的误报；
- 能运行最小构建/测试并明确说明没有运行什么；
- 对适合自动修复的问题，能够生成并验证候选测试和 patch；
- 输出少量、按根因合并、可直接行动的结论；
- 在真实对照实验中，将人工第一轮 Review 中位时间降低至少 35%；
- 高风险精确率达到 90%，总体明确误报率低于 8%；
- 用户可以追踪每条结论的代码、工具、模型、版本和证据；
- 系统失败或覆盖不足时诚实降级，不伪装成已完成深度 Review。

## 20. 下一步决策

建议立即批准以下方向：

1. 现有 Java 版本定义为“可靠运行底座”，不再宣称已完成深度智能 Review；
2. 下一迭代只做 Phase 0 和 Phase 1，不并行扩语言或做 UI；
3. Java 作为第一个深度语义适配器，TypeScript 紧随其后；
4. 在编写新 Review Agent 前，先建立真实盲标基线和全仓库索引；
5. Phase 1 退出条件未达到前，不继续增加模型 Prompt 或规则数量；
6. 每项功能必须证明它提高了精确率、召回率、修复采用率或降低了 Review 时间。

这份规划的核心不是让系统“说得更像 Reviewer”，而是让每条结论都拥有 Reviewer 可以检查、工具可以复现、工程可以验证的事实基础。


