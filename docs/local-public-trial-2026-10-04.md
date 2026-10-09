# 本机公网测试运行说明

状态：Java API、Worker、新 PostgreSQL 数据库已在本机运行；用户手动启动公网隧道后，
公网就绪检查已返回 200，带正确签名的公网测试请求已返回 202。
真实 Java 验收 PR #2 已完成审查，识别未修改调用方并发布摘要与非阻塞 Check；
只读查证工具已核对三个发布操作。详情见同日 Java PR 验收证据。
这不是正式生产上线，也不代表独立质量或节省 Review 时间的评测通过。

## 目前可用的服务

- 本地就绪检查：`http://127.0.0.1:3000/readyz`，已实测返回 200。
- Compose 项目：`codelens-java12-public-trial`，数据存放在独立持久卷中。
- 仅允许 `codelens-ai-lab/codelens-beta-test`；Java 全仓库语义分析已启用。
- 所有审查结果只作提示；仓库设置不能在此次测试中启用阻塞结论。
- 未连接模型服务。当前测试是确定性分析与 Java 语义索引，不是完整模型效果验收。
- 旧数据库、旧队列和 GitHub App 回调地址没有修改。

## 你需要启动公网隧道

在本机终端运行下面一条命令，并保持该进程运行：

```powershell
ngrok http 3000 --url=https://cupbearer-handclasp-diffusion.ngrok-free.dev
```

该机器已有 ngrok 和历史认证配置；用户已成功手动启动并完成公网连通验证。
如果报告代理错误，可按本机网络实际情况修正该终端的代理配置，不要把认证
令牌粘贴到聊天中。执行环境禁止代理代为启动此进程，因此没有绕过限制。

本次已检查公网 `/readyz` 返回 200，并验证带签名的合成 ping 请求返回 202。
GitHub 实际重新审查事件与测试仓库 Java PR #2 的审查记录也已验证。
GitHub App 当前回调已经指向此固定域名的
`/webhooks/github`，无须先改地址。根路径不是产品首页；结果显示在 GitHub PR。

电脑关闭、Docker Desktop 退出或 ngrok 退出都会导致服务不可用。

## 重启本地服务

先确保 Docker Desktop 已运行，在项目目录执行。下面复用旧配置文件中的密钥，
不会复制密钥到仓库，也不会连接旧数据库；不要输出完整 Compose 配置。

```powershell
Set-Location 'C:/Users/PC/Documents/Codex/2026-09-25/codelens-ai/work/codelens-ai'
$env:CODELENS_ENV_FILE='C:/Users/PC/Documents/Codex/2026-09-18/referenced-chatgpt-conversation-this-is-an-2/outputs/codelens-ai/.env'
$env:CODELENS_BIND_ADDRESS='127.0.0.1'
$env:PORT='3000'
docker compose --env-file $env:CODELENS_ENV_FILE -f infra/compose.production.yml -f infra/compose.local-public-trial.yml up -d --no-build
```

此步骤依赖已经构建的 `java13-public-trial-inspect1-20261004` 镜像。项目名称保留
`codelens-java12-public-trial`，避免误建新数据卷。不要用旧版镜像
回滚新 schema，也不要用默认生产项目替代这个隔离项目。

## 停止测试

先停止 ngrok，再执行以下明确的容器停止命令；不删除持久数据：

```powershell
docker stop codelens-java12-public-trial-worker-1 codelens-java12-public-trial-api-1 codelens-java12-public-trial-postgres-1
```

冻结发布结果、受控恢复演练、独立评测和 Java 语义精度校准仍未完成，禁止将
本机试运行当作生产发布或节省 Review 时间的证据。详见同日部署证据文档。
