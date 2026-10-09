# Java 局部补丁隔离验证原型

状态：2026-10-05 内部工程原型；未开放 S2 生产执行，未通过 Phase 3/5 或 R4 验收。

## 能做什么

在原只读补丁预览之后，复用同一套批准方案和局部编辑校验，得到原文/补丁后正文，
不再实现第二套补丁应用逻辑。只支持标准库 Java 17 源码和两个固定 `main` 探针，
不支持 Maven、Gradle、JUnit、第三方依赖、仓库脚本或任意命令。

预先保存不含源码的计划清单：run、审批 ID/版本、Base/Head、索引适配器和构建模型
hash、diff hash、候选证据、镜像完整 ID、全部源文件/测试 hash、固定测试入口、
三步执行顺序、资源策略版本和每步执行上限。计划和每步开始记录以独占创建、同步
文件写入保存，再启动容器；同一计划/阶段不能自动重用。缺少前一步完成记录拒绝。

1. `BASELINE`：原 Head + 原有目标测试，必须编译并通过。
2. `HEAD_REGRESSION`：原 Head + 同一原有测试 + 回归测试；原有测试仍须通过，
   且失败必须发生在回归测试位置。编译失败、原有测试失败、超时不算问题复现。
3. `PATCHED_REGRESSION`：补丁后源码 + 完全同一份原有/回归测试，必须全部通过。

满足上述模式仅报告 `beforeFailAfterPassObserved=true`。它不是根因因果证明或
`verified fix`；所有 verified/apply/publication 标志仍为 false。

## 隔离方式

独立镜像 `infra/sandbox/Dockerfile` 只装 JDK 和受信任、无第三方依赖的监督程序。
官方 JDK 基础镜像按 digest 固定；执行使用完整本地 image ID，不拉取镜像。
协议输入经 stdin 传输，既不挂载宿主目录，也不让容器访问 Docker socket。
Docker 客户端在本机执行，不能交给处理用户 PR 的普通 API/Worker 容器。

固定配置：无网络、非 root 10001:10001、只读根目录、capabilities 全撤销、禁止
提权、1 CPU、384 MiB 内存且不额外使用 swap、64 个进程、128 个文件描述符、
单文件写入上限 1 MiB、工作区/临时目录 tmpfs 分别 16/8 MiB、共享内存 1 MiB。
每计划最多 24 个 Java 文件、每文件 256 KiB、每步源码总量 1 MiB；每步容器执行
最多 15 秒，测试子 JVM 单项最多 5 秒。允许调用方进一步缩短，禁止超出计划。
启动前读取实际容器配置核对上述关键限制；发现 daemon 配置不一致则拒绝启动。

只允许本地 Unix socket/Windows named pipe Docker context，拒绝远端 daemon 和
`DOCKER_HOST` 环境覆盖。无 host PID/IPC/UTS、宿主设备、额外挂载、自动重启或
健康检查；镜像声明 volume 也拒绝。每次运行生成随机名称和专用 plan 标签，
清理前再次验证完整容器 ID、名称、标签，不删除无法确认属于本计划的容器。

编译禁用 annotation processor，classpath 仅临时 classes 和 JDK，不执行项目构建
插件。测试在单独子 JVM 运行，清空环境，stdout/stderr 丢弃；编译诊断不保存正文。
Docker 日志驱动设为 none。只保存有限阶段结果和失败测试序号，不保存源码、diff、
凭据或任意工具输出。协议和客户端输出均有字节限制，异常只报有限通用错误。

实现依据：[Docker create 参数](https://docs.docker.com/reference/cli/docker/container/create/)、
[JDK 17 编译器 API](https://docs.oracle.com/en/java/javase/17/docs/api/java.compiler/javax/tools/JavaCompiler.html)。
参数存在不等于完成了生产威胁模型/隔离安全评审。

## 如何验证原型

仅在本机隔离环境构建单独镜像、取得其完整 image ID，再显式开启
`CODELENS_SANDBOX_TESTS=true` 和 `CODELENS_SANDBOX_FIXTURE_IMAGE`。
运行 `DockerJavaSandboxIntegrationTest`。这两个变量是测试开关，不是运行时 S2
授权；不要加入试用部署或 API/Worker 的默认配置。

测试只使用本仓库内明确定义的合成源码与测试，不接收真实仓库、外部命令、私钥、
收费模型或任意 Docker 目标。测试临时日志目录由测试框架管理；容器按身份清理。
完整回归仍需另外启用数据库、恢复崩溃和备份测试，不能把跳过当作通过。

## 生产启用前尚缺什么

- 独立的每仓库 S2 执行授权、fork 默认禁止、受保护执行身份和专用 runner 主机；
  原有方案批准只是修复方向批准，**不是执行仓库代码的权限**。
- 服务端可信材料化：support 文件/探针目前由内部调用者提供并校验声明 hash，
  并未自动查数据库证明它们都来自当前授权 Head。需冻结全文、依赖与测试的来源，
  重新核对当前审批/远端 SHA，并阻止计划到执行期间的版本变更。
- 生产持久化工具计划、每 run 的总预算、全局并发/容量控制、跨进程审计和取消；
  本地文件记录不是可信审批、分布式队列或数据库保留/备份抗回滚机制。
- 外部独立监督的总期限、实际 runner 进程崩溃/主机故障演练、孤儿调查/清理。
  当前超时依赖存活的宿主执行器；子测试 5 秒限制也不等于抵抗同容器恶意进程
  干扰监督者，不能据此声称跨崩溃仍有不可绕过的执行期限。
- 文件/目录 fsync、存储权限、磁盘压力、保留删除及断电持久性验收；CREATE_NEW
  和文件 force 不证明目录项断电安全，不允许自动清空旧记录后强制重跑。
- 完整供应链/内核逃逸威胁评审，按需更强隔离；当前容器原型不是安全认证。
- Maven/Gradle 离线依赖、JUnit 定向适配、测试选择、反例/重复逻辑/公共行为合同
  和根因因果映射。非零测试退出可能来自配置或无关失败，不能直接当作 finding。
- 真实项目覆盖、Phase 0 时间研究/独立标签、Phase 1 精度、Phase 3/5 真实验收。

因此原型没有 Spring bean、HTTP 路由、Worker 调用、仓库自动修改或 GitHub 发布。
API 的补丁预览仍然只读，公网镜像和 schema 不变。
