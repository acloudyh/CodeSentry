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
- [本地代码安全审核技术调研与实施流程](docs/代码安全审核技术调研与实施流程.md)
- [产品页面规划](docs/product-pages.md)
- [开发规划](docs/development-plan.md)
- [工具复核流程](docs/工具复核与人工审核流程.md)

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

人工复核记录写入 `scan_triage`，不会删除扫描证据。问题台账保留全部候选结果。正式报告保留人工已确认的风险，或初审、复审均完成、各项可信度分别大于 65% 且非误报的工具结果。人工误报不进入报告。报告列表和三种导出格式共用筛选、去重与统计逻辑，导出过程不修改原始记录。
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

## 工具复核与人工审核

在问题台账中执行“批量工具审核”，通过“执行详情”查看进度、初审与复审建议及真实失败原因。整批结束后会自动重试失败项一次，也支持手动重试。

工具服务由后端配置启用，所需凭据由运行环境提供。启用外部工具时，必要真实源码证据会提交至配置接口，可能包含命中凭据。工具结论独立保存，不自动覆盖人工审核状态。

审核人员可在“人工复核”中确认风险、标记误报、接受风险或登记整改待验证。完整流程见[工具复核与人工审核流程](docs/工具复核与人工审核流程.md)。
