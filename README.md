# CodeSentry

CodeSentry 是一个本地化代码审查产品。它读取已有本地 Git 仓库，或通过标准 Git 协议克隆远程仓库，执行全量扫描及基于指定分支的增量扫描，并提供人工复核与整改报告。

## 技术栈

- 后端：JDK 11、Spring Boot、MyBatis、Flyway
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router
- 数据库：MySQL 5.7+
- 扫描引擎：Semgrep、Gitleaks、Trivy，以及可扩展的 Java 扫描器适配器

## 项目结构

```text
CodeSentry/
├── codesentry-server/   Java 后端
├── codesentry-web/      Vue 前端
├── docs/                架构和开发文档
└── rules/               本地扫描规则
```

## 设计文档

- [整体架构](docs/architecture.md)
- [技术调研方案总结](docs/CodeSentry-技术调研方案总结.md)
- [代码安全审核技术调研与实施流程](docs/代码安全审核技术调研与实施流程.md)
- [产品页面规划](docs/product-pages.md)
- [开发规划](docs/development-plan.md)
- [AI 双轮复核流程](docs/AI双轮复核流程.md)

## 本地启动

### 1. 初始化数据库

```sql
CREATE DATABASE codesentry DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'codesentry'@'localhost' IDENTIFIED BY 'change_me';
GRANT ALL PRIVILEGES ON codesentry.* TO 'codesentry'@'localhost';
FLUSH PRIVILEGES;
```

不要提交真实数据库密码。通过环境变量传入：

```bash
export CODESENTRY_DB_URL='jdbc:mysql://127.0.0.1:3306/codesentry?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export CODESENTRY_DB_USERNAME='codesentry'
export CODESENTRY_DB_PASSWORD='change_me'
```

### 2. 启动后端

```bash
cd codesentry-server
mvn spring-boot:run
```

后端默认监听 `http://127.0.0.1:18080`。

### 3. 启动前端

```bash
cd codesentry-web
npm install
npm run dev
```

前端默认监听 `http://127.0.0.1:5173`。

## 仓库扫描约束

- 本地目录模式不会执行 `git pull`，也不会切换用户当前分支。
- 当前工作区扫描可以包含未提交内容。
- 指定分支扫描通过 `git archive` 导出 Commit 快照，不创建 Git worktree。
- 远程仓库只使用标准 `git clone/fetch`，不调用 GitLab API、CI 或安全功能。
- 密钥、访问令牌和私钥不得写入项目配置；本地报告如包含真实代码证据，必须限制访问，并在对外交付前按约定处理敏感值。

## 误报复核与报告过滤

人工复核记录写入 `scan_triage`，不会删除扫描证据。问题台账、首页问题信号和
HTML、Word、PDF 报告均不展示人工确认的 `FALSE_POSITIVE`；报告还会排除满足严格条件的
AI 双轮高可信误报。报告列表和三种导出格式共用同一套筛选、去重与统计逻辑，导出过程不修改原始记录。
批量复核使用精确的扫描器、规则、路径和起始行选择器：

```bash
python3 scripts/triage-findings.py \
  --scan-run-id 7 \
  --selectors-file scripts/examples/codesentry-report-7-false-positives.json

# 确认 dry-run 输出无误后才写入复核记录
python3 scripts/triage-findings.py \
  --scan-run-id 7 \
  --selectors-file scripts/examples/codesentry-report-7-false-positives.json \
  --apply
```

脚本不会在参数文件中接收令牌；如接口开启认证，可通过
`CODESENTRY_API_TOKEN` 环境变量提供 Bearer token。

## AI 双轮辅助复核

AI 功能默认关闭。启用后，CodeSentry 会在本地构建脱敏审核包，分别执行第一轮判断和第二轮独立复核；AI 结果写入独立的 `scan_ai_review` 表，不会自动修改人工结论或删除扫描证据。

DeepSeek V4 Pro 已适配为 OpenAI-compatible 服务。macOS 上建议把密钥保存在
Keychain，不写入仓库、Shell 历史或 `.env` 文件：

```bash
# -w 放在最后且不跟参数，security 会交互式提示输入，不会把密钥写进命令历史
security add-generic-password -U -a "$(id -un)" -s 'CodeSentry-DeepSeek' -w

# 本机配置文件为 .codesentry/deepseek.env（已被 Git 忽略）
./scripts/start-server-deepseek.sh
```

DeepSeek 配置使用 `https://api.deepseek.com` 和 `deepseek-v4-pro`。启动脚本从
Keychain 读取密钥，并可通过 `CODESENTRY_MAVEN_SETTINGS` 指定 Maven settings。当前
本机配置通过 `CODESENTRY_AI_THINKING_MODE=disabled` 关闭思考模式，避免有限输出额度
被推理内容占用而截断结构化 JSON；CodeSentry 自身仍执行两轮相互校验。
使用外部模型意味着脱敏后的最小代码证据仍会离开本机，必须获得代码所有方授权。

模型服务需提供 OpenAI-compatible 的 `/chat/completions` 接口和 JSON Object 输出模式。发送内容仅包括规则元数据、仓库相对路径和经过脱敏、限长的代码片段；Gitleaks、Trivy 和密钥类问题的命中值始终替换为 `<REDACTED>`。

主要接口：

- `POST /api/v1/ai-reviews/findings/{findingId}`：单条双轮审核。
- `POST /api/v1/ai-reviews/scan-runs/{scanRunId}`：异步批量审核未人工复核的问题。
- `GET /api/v1/ai-reviews/scan-runs/{scanRunId}`：读取批次最新 AI 建议。
- `GET /api/v1/ai-reviews/jobs/{jobId}`：读取批量审核进度。
- `GET /api/v1/ai-reviews/jobs/{jobId}/records`：读取逐问题的初审、复审和失败记录。

问题台账可查看批量审核执行详情。调用或响应解析失败时会显示真实原因、不显示虚假的 `0%` 可信度，并允许重新审核。报告会自动显示已有的 AI 初审、AI 复审、各轮可信度和综合建议；正式问题状态仍以 `scan_triage` 为准。
