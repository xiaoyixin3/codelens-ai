# Java 隔离编译/回归验证原型证据（2026-10-05）

## 本轮交付

新增 `JavaValidationPlan`、`ValidationJournal`、`DockerJavaSandbox` 和单独的
`JavaSandboxHarness` 镜像。复用 `LocalPatchPreview.prepare` 的审批、索引原文
hash、局部编辑和真实变更符号校验，仅抽出原有已校验的前/后正文，不重写 diff
算法。公开 API 仍只返回原 Preview，不返回新的源码材料；内部源码对象的 JSON
正文和 toString 均抑制泄露。

已实际运行标准库 Java 17 合成样例：基线通过、原 Head 回归失败、补丁后原有及
同一回归测试通过。结果仅是受限执行证据，所有自动应用/发布/verifiedFix 标志
仍 false。没有部署到公网或执行用户/外部仓库脚本。

完整安全设计和限制见 [原型说明](../java-sandbox-prototype.md)。方案批准不替代
S2 执行授权；本轮计划里的审批是明确合成 fixture，不是伪造真实用户批准。

## 实际测试

- JavaValidationPlanTest：6 项，绑定审批、Base/Head、适配器/构建模型/diff/hash、
  固定步骤/测试和执行上限；payload 前后字节；拒绝伪 hash、错误原文、危险路径、
  可变镜像 tag、敏感源码和大小限制；测试路径不能覆盖生产文件或大小写冲突。
- ValidationJournalTest：4 项，仅元数据落盘、先预占后完成、篡改拒绝、未知阶段
  禁止重试、重新构造日志对象不能重用、并发预占恰好一个成功。
- DockerJavaSandboxTest：8 项，正常模式仍不放行，基线/无复现/错误测试失败的
  正确停止顺序，超时、异常输出、日志失败零调用、实际配置弱化先拒绝执行、
  远端 daemon/错误 image 拒绝、错误所有权绝不清理其他容器；不能扩大计划期限。
- DockerJavaSandboxIntegrationTest：7 项真实 Linux 容器，成功模式、基线失败、
  回归原本通过、回归编译失败、补丁后仍失败、无限循环超时、实际隔离检查。
  隔离样例核对 UID 10001、没有宿主数据库/GitHub 环境、没有 socket、根目录不可写、
  外部网络不可达，tmpfs 可写；子测试输出不进入保存记录。每次检查无本计划容器残留。

首次 7 项实际容器测试因 Windows 进程参数传递中的嵌套引号使 image label 的
Go template 无法解析，在启动任何容器前全部拒绝。改用完整 image JSON 的本地
解析，不放宽 identity/policy 约束；后续 7 项实际测试通过，增加实际配置的
no-new-privileges/文件大小限制反例后又完整针对性通过。失败首次运行不计为通过。

## 固定材料

单独原型镜像的官方 JDK 基础 digest：
`sha256:83bb7084d2cdfa954fc34616f91cf971012e2a50d6cfd1edacad06f2e1fff496`。
本机原型 image ID：
`sha256:c730c36f767de7bcd8cbc0bf2dff7857b398c9ab6f961c53850ba72bad68dfb6`。
镜像构建的 RUN 使用无网络；运行 `--pull never`，没有依赖/模型请求。
下载官方 JDK 基础镜像是开发构建准备，不是允许被审查代码联网。

源码仍为 java.16/schema 001–022，无新数据库迁移。API/Worker 原试用镜像未更换。
只创建临时隔离 PostgreSQL 做完整工程回归，正式数据和凭据不参与。

最终 `release:check` 退出码 0：Java 339 项，335 通过、4 项外部语义评测/验收
跳过、0 失败/错误；TypeScript 18 套/92 项通过。数据库、恢复进程崩溃、备份及
本轮实际沙箱测试均显式开启；未将 4 项跳过计作通过。类型检查、正式 JAR、
旧兼容构建与发布结构检查通过。原沙箱镜像与最终源码的监督程序一致。

第一次完整回归之前的密钥扫描 377 文件、0 发现；最终含新增文档扫描 379 文件、
0 发现。
`git diff --check` 通过。JAR 包含内部原型实现而不含测试 helper；没有通过组件
扫描或配置开关启用执行器。构建程序从未把用户提供的 Java 源码交给宿主编译器。

最终确认没有沙箱 plan 标签容器残留；临时回归库的 run 为 0，核对完整容器 ID、
名称和专用标签后停止并自动移除，仅清理合成数据。原试用 API/Worker/PostgreSQL
三容器仍健康，readyz 为 200。单独原型镜像保留用于明确开启的本机复跑，未替换
原试用镜像，也没有删除用户文件、正式数据、提交代码或修改 GitHub 内容。

## 未完成，不得等同上线能力

这是标准库探针原型，不是完整 Maven/Gradle/JUnit 项目验证。没有生产授权入口、
可信 support/test 材料来源复核、远端版本确认、生产工具审计/预算、抗主机崩溃
独立期限或通过安全评审的 S2 环境。当前本地日志不承诺跨断电/备份抗回滚。
没有安全适用的真实验证环境就不允许运行实际 PR，更不允许标为 verified fix。
上述合成执行证据不替代 Phase 0/1、R3/R4、Phase 3/5 真实退出门槛。
