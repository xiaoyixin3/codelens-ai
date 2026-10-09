# CodeLens AI：理解辅助与“复用优先”Review 改进技术方案

状态：提案，待纳入正式技术基线

版本：v1.0

日期：2026-09-28
适用范围：Phase 0 标注工作台、Phase 2 Semantic Change Model、Phase 4 Evidence-driven Review Agent

## 1. 决策摘要

CodeLens AI 不再把 Review 简化为“展示增删行 + 让人打标签”，也不把 AI 建议等同于“生成一段看似可用的新代码”。后续产品必须同时解决两个问题：

1. **帮助 Reviewer 建立理解**：先说明变更意图、行为变化、影响范围和需要验证的问题，再展示证据；
2. **约束 AI 复用现有设计**：在生成修改方案前，必须检索并评估仓库中已有的接口、工具类、组件、模式和测试夹具。没有完成复用调查，不允许生成可提交补丁。

目标产品形态从 `Diff Labeler` 调整为 `Review Reasoning Workspace`：系统提供可核查的理解路径、上下文和解决方案选项，人负责确认事实、判断风险和选择修复方向。

本方案不降低 `docs/technical-baseline-v2.md` 的证据门槛。正式金标仍然对 CodeLens 预测盲化；降低人工成本依靠更好的中立上下文、自动化银标和有边界的验证任务，而不是把机器答案伪装成人工真值。

## 2. 用户问题与当前实现诊断

### 2.1 体验问题

当前人工标注流程存在三个连续断点：

- **看见变化，但不知道为什么变化**：页面以文件和 patch 为主，没有从需求、行为和调用链角度组织信息；
- **发现可疑点，但不知道如何验证**：页面没有提供相关调用方、测试、契约、历史实现和需要回答的引导问题；
- **指出问题，但得不到修复路径**：标签只描述“哪里可能有问题”，不要求记录触发条件、影响和可接受修复。

这会把最困难的“理解陌生代码”完整转嫁给标注者。基础薄弱的用户只能猜，熟悉项目的专家也要重复搜索上下文，最终既费时，也难形成稳定反馈。

### 2.2 代码层面的根因

当前实现与上述体验一致：

- `apps/benchmark-labeler/src/main.js` 的主工作区只渲染 PR 标题、描述、文件标签和 patch；
- 待标注表单只采集类型、严重度、标题和新增行号；
- `scripts/benchmark-labeler.ts` 的 `ExpectedFinding` 不包含 claim、trigger、impact、evidence、solution 或 verification；
- 服务端要求每个问题必须锚定右侧新增行，无法表达跨文件根因、遗漏修改、错误复用或未修改调用方受影响；
- 冻结后只展示机器预测的标题和位置，没有解释、验证方法和修复方案，也不能形成学习闭环。

因此不能只增加一个“AI 总结”区域。必须升级标注契约、上下文构建和方案生成流程。

## 3. 外部项目参考与取舍

本方案主要参考活跃的开源项目 [The-PR-Agent/pr-agent](https://github.com/The-PR-Agent/pr-agent)。其公开文档将能力拆为四个清晰任务：

- [`/describe`](https://github.com/The-PR-Agent/pr-agent/blob/main/docs/docs/tools/describe.md)：生成 PR 类型、摘要、文件 walkthrough 和交互图；
- [`/review`](https://github.com/The-PR-Agent/pr-agent/blob/main/docs/docs/tools/review.md)：输出问题、安全、测试、风险和 Review 工作量信息；
- [`/improve`](https://github.com/The-PR-Agent/pr-agent/blob/main/docs/docs/tools/improve.md)：输出带描述和前后 diff 的可行动建议，并支持表格或可提交评论；
- [`/ask`](https://github.com/The-PR-Agent/pr-agent/blob/main/docs/docs/tools/ask.md)：允许针对 PR 或具体代码行提问。

值得吸收的设计：

1. 把“理解、审查、改进、问答”拆开，避免一个模型输出承担所有职责；
2. 用 walkthrough、风险、测试和优先文件降低 Reviewer 的启动成本；
3. 建议同时包含摘要、详细解释和代码修改示例；
4. 对大 PR 显示覆盖范围，无法完整分析时不假装全覆盖；
5. 使用持久化结果和增量运行，减少重复噪声。

不能直接照搬的部分：

- 公开实现的核心交互仍以“生成评论或建议”为中心，不等于形成 Reviewer 的可核查理解过程；
- `extra_instructions` 和 `best_practices.md` 可以提示模型遵守项目习惯，但提示词本身不是“优先复用”的强制证据；
- 公开文档没有定义“找到哪些已有实现、为何复用/扩展/新建、改动面是否异常”的结构化门槛；
- 文档也明确提醒代码建议可能只是示例，仍需人工判断。因此 CodeLens 不能把可应用 diff 当作正确性的证明。

CodeLens 的差异化方向应是：**参考其任务拆分和结果呈现，但用全仓库语义索引、复用决策契约和确定性校验器补上证据约束。**

## 4. 产品原则

### 4.1 先理解，后判断，再给方案

界面默认顺序固定为：

1. 变更想解决什么；
2. 行为从什么变成什么；
3. 哪些入口、调用方、数据和测试受到影响；
4. Reviewer 需要确认哪些事实；
5. 存在哪些风险及其证据；
6. 有哪些修改方案，各自代价是什么。

原始 diff 仍可随时查看，但不再是唯一入口。

### 4.2 建议必须能被验证

每个问题至少包含：

- 根因陈述；
- 触发条件；
- 可观察影响；
- 最小代码证据；
- 相关未修改代码或“未找到”的明确记录；
- 验证方法；
- 至少一个修复方向。

缺少关键证据的内容只能显示为“待调查问题”，不能发布为确定性缺陷。

### 4.3 复用是生成补丁前的硬门槛

“请尽量复用”不能只写在 Prompt 中。系统必须先产出可审计的 `ReuseDecision`，再决定是复用、扩展、抽取还是新建。

### 4.4 正式评测与学习辅助严格分离

- **辅助 Review / 开发银标模式**：允许显示解释、候选风险、复用候选和修复方案，用于日常使用和快速反馈；
- **正式金标模式**：隐藏 CodeLens 的风险结论与答案，但允许显示同一份中立仓库上下文、结构导航、术语解释和不带方向性的检查问题；提交并冻结后，才显示机器结论和解决方案。

这解决“盲标不是无上下文”的问题，同时避免用机器答案证明机器正确。

## 5. 目标交互：Review Reasoning Workspace

### 5.1 第一层：30 秒变更地图

每个 PR 先显示一张 `Change Brief`：

| 区块 | 内容 | 证据要求 |
| --- | --- | --- |
| 意图 | PR/Issue/commit 声称解决的问题 | 原始文本链接；缺失时标记“意图未知” |
| 行为变化 | before → after，而不是增删行复述 | base/head 符号、控制流或契约差异 |
| 影响范围 | 入口、调用方、实现类、数据写入、配置、测试 | 语义索引关系和覆盖级别 |
| 优先检查 | 最值得先看的 1–5 个问题 | 风险依据与不确定性 |
| 覆盖情况 | 已分析、未分析、降级部分 | `semantic / diff-only / executed` |

### 5.2 第二层：按“行为”组织，而不是按文件组织

将多个文件的变化聚合成 `Behavior Change Card`。每张卡包含：

- 变更意图；
- 相关入口和核心符号；
- base/head 行为差异；
- 未修改但受影响的调用方；
- 相关测试和缺失测试；
- 数据、权限、异常、事务或并发边界；
- Reviewer 检查清单；
- “查看证据”后才展开原始 diff。

文件视图保留为证据浏览方式，不再承担全部理解任务。

### 5.3 第三层：引导思考而不是替人下结论

系统根据行为类型生成中立问题，例如：

- 新的异常是否能穿过现有调用链，还是会被某层吞掉？
- 这个写操作失败后，前一步副作用能否回滚或重试？
- 新增分支是否有调用方仍依赖旧默认行为？
- 仓库中是否已经有相同的校验器、重试器或转换器？
- 测试覆盖的是新增实现，还是实际生产入口？

问题必须绑定可展开的上下文，不能只给抽象 checklist。

### 5.4 第四层：方案卡

确认风险后展示 1–3 个 `Solution Option`：

- 推荐方案及原因；
- 可复用的现有符号和位置；
- 最小改动范围；
- 兼容性和迁移代价；
- 应新增或修改的测试；
- 替代方案及不采用原因；
- 仅在定位和上下文已验证时提供 patch preview。

Reviewer 可以选择“接受方向”“方向正确但实现不对”“应复用其他组件”“不是问题”“证据不足”，这些反馈比简单点赞更可训练。

## 6. 标注契约升级

### 6.1 新标注单位

从“文件某新增行的问题标题”升级为“根因 Claim Packet”：

```ts
interface RootCauseLabel {
  id: string;
  category: RiskCategory;
  severity: Severity;
  claim: string;
  trigger: string;
  impact: string;
  evidence: EvidenceRef[];
  affectedSymbols: SymbolRef[];
  acceptableFix?: string;
  verification: string;
  confidence: 'certain' | 'likely' | 'uncertain';
  reviewerUncertainty?: string;
}
```

`EvidenceRef` 可引用 diff 行、base/head 符号、调用关系、测试、配置、构建或执行结果。问题可以锚定未修改代码，也可以表达“应有但缺失”的修改，不再强制钉在新增行。

### 6.2 降低人工负担

人工不再从空白页发现所有问题。采用以下分流：

1. 编译器、静态分析、历史修复和两个独立分析器先生成银标候选；
2. 系统自动合并证据和上下文；
3. 人只处理工具分歧、低置信度、高影响项和随机一致样本；
4. 每个任务限制到一个明确问题，例如“调用 A 是否可能在 B 状态下遗漏取消？”；
5. 允许选择“能力范围外/上下文不足”，不得强迫猜测；
6. 需要产品级对外结论的金标仍由通过校准的独立 Reviewer 完成。

基础薄弱的项目所有者可参与辅助模式、可用性评价和问题复现，不被要求充当陌生 Java 项目的专家金标员。

### 6.3 盲化规则

正式金标阶段允许显示：

- 完整冻结仓库；
- PR/Issue 意图；
- 结构化文件和符号导航；
- 中立术语解释；
- 与答案无关的构建结果；
- 预先冻结的通用检查问题。

正式金标阶段禁止显示：

- CodeLens 风险结论、严重度和修复建议；
- 由同一待评模型选择的“可疑调用方”；
- 其他 Reviewer 的答案；
- 能反推出预测的排序或高亮。

如果导航或问题由待评模型生成，该样本只能进入辅助/银标集，不能进入当次金标。

## 7. “复用优先”生成协议

### 7.1 强制流水线

任何修改建议必须依次通过：

1. `Understand`：只建立意图、行为和约束，不生成代码；
2. `Retrieve`：从全仓库索引检索同名、同类型、同调用角色和语义相近的实现；
3. `Reuse Decision`：明确复用、扩展、抽取或新建；
4. `Plan`：生成最小改动方案和备选方案；
5. `Patch`：只对已批准方案生成局部补丁；
6. `Validate`：检查编译、测试、契约、重复实现和改动面；
7. `Challenge`：主动寻找反证，失败则降级为建议而非可提交 patch。

阶段 1–3 未完成时，阶段 5 必须 fail closed。

### 7.2 ReuseDecision 契约

```ts
interface ReuseDecision {
  goal: string;
  candidates: Array<{
    symbol: SymbolRef;
    relationship: 'same_contract' | 'same_role' | 'caller_pattern' | 'test_fixture' | 'similar_logic';
    fit: 'direct' | 'extendable' | 'partial' | 'rejected';
    evidence: EvidenceRef[];
    rejectionReason?: string;
  }>;
  decision: 'reuse' | 'extend' | 'extract' | 'new';
  selectedCandidate?: SymbolRef;
  justification: string;
  changeBudget: {
    maxFiles: number;
    maxChangedSymbols: number;
    publicContractChangeAllowed: boolean;
  };
}
```

选择 `new` 时必须记录：检索范围、候选、逐项不适用原因以及新抽象的职责边界。只写“没有合适实现”视为不合格。

### 7.3 硬性校验器

新增 `ReusePolicyVerifier` 和 `RewriteRiskVerifier`，至少执行：

- 未提供 `ReuseDecision`：拒绝发布代码补丁；
- 删除并重建整个函数/类，但行为目标只涉及局部：标记高风险；
- 单文件重写率、符号 churn 或修改文件数超过 change budget：要求解释并转人工确认；
- 新增类/函数与现有候选高度相似：阻止自动应用；
- 绕过已有接口直接访问底层依赖：阻止自动应用；
- 改变公共 API、异常、事务或数据契约但计划未声明：阻止自动应用；
- 复制现有测试夹具或工具逻辑：要求复用或解释；
- patch 无对应验证方法：只能展示方案，不能标记“可提交”。

重写不是绝对禁止。生成文件、明确迁移、废弃架构替换或有证据的安全重构可以重写，但必须扩大审查级别并显式列出保留的外部契约。

### 7.4 复用检索信号

按可信度从高到低使用：

1. 类型/接口实现与调用关系；
2. 注解、继承、override、构造注入和工厂注册；
3. 相同输入输出类型与异常契约；
4. 同一调用方中的既有模式；
5. 测试夹具和测试基类；
6. 词法/向量相似度。

向量相似只能召回候选，不能单独证明应该复用。

## 8. 系统架构改动

```text
PR Materializer
  -> Java Repository Semantic Index
  -> Change Narrative Builder
  -> Neutral Context Pack Builder
  -> Reuse Candidate Retriever
  -> Review Reasoning Workspace
       -> Guided Questions
       -> Root-cause Labels
       -> Solution Options
  -> Minimal Patch Planner
  -> Reuse/Rewrite/Contract Verifiers
  -> GitHub Publisher + Audit Store
```

新增组件职责：

| 组件 | 职责 | 失败行为 |
| --- | --- | --- |
| Change Narrative Builder | 从 base/head 生成行为变化，不复述 patch | 降级显示事实清单并标记缺失 |
| Neutral Context Pack Builder | 为盲标生成不泄露预测的冻结上下文 | hash 或来源不一致则拒绝金标 |
| Reuse Candidate Retriever | 基于语义索引找已有实现和模式 | 覆盖不足时禁止声称“无可复用项” |
| Solution Planner | 产出方案和权衡，不直接写整文件 | 缺少 ReuseDecision 时拒绝运行 |
| RewriteRiskVerifier | 测量 churn、符号连续性和重复逻辑 | 超阈值转人工确认 |
| Contract Verifier | 检查 API、异常、事务和数据契约 | 未声明变化时拒绝自动应用 |

所有输出保存输入 SHA、索引版本、模型与 Prompt 版本、工具调用、覆盖率、候选和拒绝原因，支持重放与审计。

## 9. 实施顺序

### R1：标注契约与中立上下文（先做）

交付：

- `RootCauseLabel`、`EvidenceRef` 和新决策存储格式；
- 可浏览 base/head 全文、符号定义、调用方和测试；
- “辅助模式 / 正式金标模式”隔离；
- 从 `blind-v1` 只读迁移，原始数据不覆盖；
- 去除“只能标新增行”的限制。

退出条件：Reviewer 可以在不离开工作台的情况下，为一个根因记录触发条件、影响、跨文件证据、验证方法和可接受修复；金标 API 响应中不存在预测字段。

### R2：Change Brief 与引导式 Review

交付：

- 30 秒变更地图；
- Behavior Change Card；
- 调用方、测试、契约和覆盖范围标签；
- 中立引导问题与证据展开；
- 冻结后解释“机器为什么这样判断”。

退出条件：在校准样本中，Reviewer 无需自行全文搜索即可回答预定义事实问题；所有摘要句可追溯到证据或明确标记推断。

### R3：复用优先方案生成

交付：

- `Reuse Candidate Retriever`；
- `ReuseDecision`；
- 1–3 个 Solution Option；
- change budget；
- 局部 patch preview。

退出条件：每条代码修改建议都含复用候选或可审计的“无法复用”证明；没有 `ReuseDecision` 的补丁无法进入发布层。

### R4：重写风险与执行验证

交付：

- churn、全函数/类重写、重复实现和绕过接口检测；
- 公共契约差异检查；
- 编译和定向测试；
- 自动应用权限分级。

退出条件：高重写风险、未声明契约变化或验证失败的建议不能自动应用；每次阻止都有可读原因和证据。

## 10. 衡量指标与发布门槛

以下为新增候选门槛，试点后可按冻结评测协议调整，但不得静默降低：

| 指标 | 首次上线门槛 |
| --- | --- |
| Reviewer 理解耗时 | 相对原始 diff 对照组中位数降低 ≥ 25% |
| 事实问题正确率 | 不低于原始 diff 对照组，且 95% 置信区间不显示实质退化 |
| “仅复述 diff”摘要占比 | < 10%，沿用 Phase 2 门槛 |
| 摘要证据可追溯率 | 100% 或明确标注为推断/未知 |
| 可行动建议完整率 | ≥ 90% 含 trigger、impact、verification 和 solution option |
| 复用调查覆盖率 | 100% 的代码补丁含 `ReuseDecision` |
| 不必要整函数/整类重写率 | < 2% |
| 重复实现引入率 | 阻断级缺陷为 0 |
| 建议接受率 | 仅作产品指标，不作为正确率证据 |
| 金标泄漏 | 0；发现一次即使当批样本失效 |

理解耗时实验采用同类 PR 交叉对照；除总耗时外，记录首次形成正确心智模型、首次定位证据和最终决定的时间。不能只以“点了接受”证明系统有帮助。

## 11. 安全与隐私约束

- 延续 S0/S1/S2 沙箱分级，不因增加问答或方案生成扩大默认执行权限；
- 仓库内容、Issue、日志和模型输出都视为不可信输入，禁止其覆盖系统策略；
- 发送给外部模型前执行密钥和个人信息脱敏，并记录提供方、区域和保留策略；
- 复用检索不得跨租户或跨未授权仓库；
- patch 生成默认只读，自动应用需要独立权限和通过全部 verifier；
- 大 PR 或索引覆盖不足时显示明确 coverage，不得用局部分析声称全仓库结论；
- 正式金标材料使用内容寻址、冻结 SHA 和独立存储，辅助模式反馈不得混入金标。

## 12. 不做什么

- 不继续堆叠浅层正则规则来假装“理解代码”；
- 不让基础薄弱的项目所有者承担专家金标责任；
- 不把更长的 AI 摘要当作更好的 Review；
- 不以 Prompt 中的“优先复用”替代结构化检索和校验；
- 不默认生成或应用整文件重写；
- 不把 mutation、机器建议或建议接受率当作正式产品精确率。

## 13. 与正式基线的关系

本文件是对 `docs/technical-baseline-v2.md` 的增量改进提案，不自动替换现有基线。采纳时应：

1. 将 R1 纳入 Phase 0 评测工具改造；
2. 将 Change Brief 和 Behavior Change Card 纳入 Phase 2；
3. 将 ReuseDecision、Solution Planner 和各 verifier 纳入 Phase 4；
4. 保留 Phase 0 真实样本、Phase 1 Java 语义精度、双人盲标、冲突裁决和沙箱退出条件；
5. 新增 ADR，冻结“无 ReuseDecision 不得发布补丁”的架构决策。

最先实施的不是模型调参，而是 R1 的数据契约和工作台信息架构。只有系统能保存“为什么、影响什么、如何验证、如何修复”，后续模型和评测才有真实反馈可用。
