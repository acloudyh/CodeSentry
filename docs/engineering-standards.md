# 团队前后端开发规范

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0.0（遵循 SemVer） |
| 生效日期 | 2026-11-14 |
| 适用范围 | 全部 Web 前端、后端服务、对内与对外 HTTP API |
| 维护责任 | 架构组；变更须提交 PR 并经 2 名 Owner 批准 |
| 复核周期 | 每季度一次 |
| 上位依据 | RFC 9110、RFC 9457、OpenAPI 3.1、WCAG 2.2、Conventional Commits 1.0.0、SemVer 2.0.0 |
| 配套文件 | 见「附录 C」 |

**效力优先级**：法律法规与行业强制标准 > 项目级约定（须在 PR 中显式声明） > 本规范 > 团队口头约定。当前后端约定冲突时，以 `openapi/openapi.json` 为准。

---

## 0. 如何使用本规范

### 0.1 级别语义（采用 RFC 2119 定义）

| 标记 | 含义 | 违反后果 |
| --- | --- | --- |
| **MUST** | 强制要求 | CI 阻断或评审驳回，不得合并 |
| **SHOULD** | 推荐要求 | 须在 PR 描述中说明理由，无正当理由则要求修改 |
| **MAY** | 可选实践 | 不作约束 |

每条规则有稳定编号（如 `API-07`），用于 CI 报错、PR 评论与豁免登记引用。**编号一经发布不复用、不重排**，废止的规则保留编号并标注「已废止」。

### 0.2 落地顺序（建议 2–4 周，按投入产出比排序）

| 阶段 | 目标 | 交付物 | 验收标准 |
| --- | --- | --- | --- |
| **P0** | 契约与最低门禁 | OpenAPI 3.1 模板、统一错误结构、CI 五步检查 | 任一 PR 未过门禁无法合并 |
| **P1** | 工程基线 | TypeScript strict、主干开发、约定式提交、traceId 日志 | 全仓 `tsc --noEmit` 零错误；提交信息 100% 合规 |
| **P2** | 增强项 | 契约测试、PR 预览环境、性能与无障碍预算 | 关键路径 E2E 稳定通过；PR 自动生成预览 URL |

### 0.3 例外与豁免

填写 `docs/waivers/<编号>.md`，须包含：背景、影响范围、补偿措施、失效日期（最长 90 天）、审批人。**`SEC-*` 与 `API-04`（禁止 HTTP 200 承载错误）不接受豁免。**

---

## 1. 接口契约规范（最高优先级）

前后端唯一的强制交汇点。契约未定，双方不得开始实现。

### 1.1 契约先行流程（MUST）

1. 后端在 `openapi/openapi.json` 提交 spec（初期可只写路径与 schema，不要求实现）。
2. spec PR 需**前端代表 + 后端 Owner** 共同批准，CI 运行 `check-contract.mjs`。
3. spec 合并后立即生成 Mock 服务与 TypeScript 类型，前后端据此并行开发。
4. 实现 PR 必须通过契约一致性校验；响应结构与 spec 不符视为失败。
5. **禁止**先改实现再补 spec，禁止手写长期与实现漂移的接口文档。

### 1.2 URL 与方法

| 规则 | 正例 | 反例 |
| --- | --- | --- |
| 复数名词、全小写 kebab-case | `/v1/user-profiles` | `/v1/getUserProfiles`、`/v1/User_Profiles` |
| 路径不含动词 | `/v1/orders` | `/v1/createOrder` |
| 嵌套不超过 2 层 | `/v1/users/{id}/orders` | `/v1/users/{id}/orders/{oid}/items/{iid}` |
| 非 CRUD 动作使用动作子资源 | `POST /v1/orders/{id}/cancel` | `GET /v1/cancelOrder` |
| 版本前缀必需 | `/v1/orders` | `/orders` |

| 方法 | 语义 | 幂等 | 成功码 |
| --- | --- | --- | --- |
| GET | 读取，**绝不改变状态** | 是 | 200 |
| POST | 创建或触发动作 | 否（需 `Idempotency-Key`） | 201 / 202 |
| PUT | 整体替换 | 是 | 200 / 204 |
| PATCH | 部分更新 | 建议是 | 200 |
| DELETE | 删除 | 是 | 204 |

### 1.3 状态码映射（MUST）

| 码 | 使用场景 | 附加要求 |
| --- | --- | --- |
| 200 | 读取 / 更新成功 | 带响应体 |
| 201 | 资源创建 | `Location` 头指向新资源 |
| 202 | 异步任务已受理 | 返回任务 ID 与状态查询地址 |
| 204 | 成功且无响应体 | 不得携带 body |
| 400 | 请求格式 / 参数校验失败 | `errors[]` 一次返回全部字段级错误 |
| 401 | 未认证或凭证失效 | 附 `WWW-Authenticate` |
| 403 | 已认证但无权限 | 涉密资源可改用 404 |
| 404 | 资源不存在 | — |
| 409 | 状态冲突 / 重复资源 | — |
| 422 | 格式合法但违反业务规则 | 与 400 明确区分 |
| 429 | 触发限流 | `Retry-After` + `X-RateLimit-*` 头 |
| 500 | 未预期服务端错误 | 统一错误体，禁止堆栈 |
| 503 | 依赖不可用 / 维护 | `Retry-After` |

### 1.4 统一错误结构（MUST）

对齐 RFC 9457（Problem Details），并补充团队字段：

```json
{
  "type": "https://errors.example.com/validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "email is not a valid address",
  "code": "VALIDATION_FAILED",
  "traceId": "req_7x8k2m",
  "errors": [
    { "field": "email", "code": "INVALID_FORMAT", "message": "Must be a valid email address" }
  ]
}
```

| 字段 | 约束 |
| --- | --- |
| `code` | `SCREAMING_SNAKE_CASE` 常量，**发布后不可变更**，供客户端做程序分支 |
| `message` / `detail` | 面向人的可展示文案，可随时优化；不得含内部信息 |
| `traceId` | **必填**，与服务端结构化日志一一对应 |
| `errors[]` | 校验类错误须一次返回全部字段，禁止逐字段多次往返 |
| 禁止项 | 堆栈、SQL、内部 IP、主机名、密钥、用户敏感数据 |

### 1.5 分页、排序与过滤

```json
{
  "data": [ { "id": "ord_123", "status": "pending" } ],
  "pagination": { "nextCursor": "eyJpZCI6MTB9", "hasMore": true, "limit": 20 }
}
```

- 默认游标分页（`cursor` + `limit`）；仅小型静态集合可使用 offset 分页并额外返回 `total`。
- `limit` 默认 20、上限 100，超限返回 400。
- 排序：`?sort=createdAt:desc,name:asc`；过滤：`?filter[status]=active`；裁剪字段：`?fields=id,name`。三者均走**参数白名单**，禁止把客户端参数拼接进 SQL。

### 1.6 版本与废弃

- 路径版本 `/v1`，**仅破坏性变更**升主版本；新增可选字段属兼容变更，不升版本。
- 旧主版本至少并行保留 6 个月（对外 API 建议 12 个月）。
- 废弃响应头：`Deprecation: true`、`Sunset: <HTTP-date>`、`Link: </v2/orders>; rel="successor-version"`。
- 移动端需保证 N-2 版本兼容，因为旧版 App 会长期驻留。

### 1.7 幂等、限流与超时

- 支付、下单、资源创建等有副作用的 POST **MUST** 支持 `Idempotency-Key`；24 小时内同键返回首次结果，不得重复执行。
- 限流按 `API Key + IP` 维度，429 必须带 `Retry-After` 与 `X-RateLimit-Limit/Remaining/Reset`。
- 所有下游调用设置连接与读取超时；重试仅用于幂等操作，采用指数退避 + 抖动。

### 1.8 认证与授权

| 场景 | 方案 |
| --- | --- |
| 浏览器 Web | `HttpOnly + Secure + SameSite` 会话 Cookie + CSRF 防护 |
| 服务间 / 第三方 | OAuth 2.0 Client Credentials / OIDC；JWT 用 RS256、≤15 分钟，每次校验 `iss/aud/exp` |
| 第三方用户授权 | Authorization Code + PKCE |

**禁止**将 JWT 存于 `localStorage`。所有路由默认要求鉴权，公开路由须用 `@Public()` 之类显式豁免；授权判定（RBAC/ABAC）位于 Service 层，不得散落在 Controller。

### 1.9 条文表

| 编号 | 级别 | 要求 | 验证方式 |
| --- | --- | --- | --- |
| API-01 | MUST | 先提交并评审 spec，再写实现 | CI 契约检查 + PR 评审 |
| API-02 | MUST | 所有 REST 接口有 OpenAPI 3.1 描述且由 spec 生成文档/类型 | `check-contract.mjs` |
| API-03 | MUST | URL 符合 1.2 节命名与嵌套规则 | Spectral / 脚本校验 |
| API-04 | MUST | 禁止用 HTTP 200 承载 `{success:false}`；禁止 500 外泄堆栈 | 契约测试 + 代码评审 |
| API-05 | MUST | 错误响应符合 1.4 节结构，`traceId` 必填 | 契约测试 |
| API-06 | MUST | GET 幂等且不改变状态 | 集成测试 |
| API-07 | MUST | 201 带 `Location`，204 无 body，429 带 `Retry-After` | 契约测试 |
| API-08 | MUST | 列表接口一律分页，默认 20、上限 100 | 契约测试 |
| API-09 | MUST | 破坏性变更升主版本，旧版保留 ≥6 个月 | `check-contract.mjs` + 发布评审 |
| API-10 | MUST | 有副作用的 POST 支持 `Idempotency-Key` | 集成测试 |
| API-11 | MUST | 所有入参在边界用 schema 校验，拒绝未知字段 | 单测 + 评审 |
| API-12 | MUST | 默认鉴权，公开路由显式豁免 | SAST + 评审 |
| API-13 | SHOULD | 非 CRUD 动作建模为动作子资源 | 接口评审 |
| API-14 | SHOULD | 提供 `?fields=` 字段裁剪与 ETag / `Cache-Control` | 接口评审 |
| API-15 | SHOULD | 为 AI Agent 消费者补全 `description`、`example`、`operationId` | Spectral |
| API-16 | MAY | 内部 TypeScript 全栈服务可选 tRPC；对外 API 保持 REST | 架构评审 |

---

## 2. 前端规范

| 编号 | 级别 | 要求 | 验证方式 |
| --- | --- | --- | --- |
| FE-01 | MUST | 全仓 `strict: true`，禁止逐文件豁免；不得使用 `any`（用 `unknown` + 收窄） | `tsc --noEmit` + Biome |
| FE-02 | MUST | 接口类型由 OpenAPI 生成或 Zod 定义，**禁止手写 DTO** | 代码评审 |
| FE-03 | MUST | 按 feature 组织目录，组件单一职责，props 显式类型 | 代码评审 |
| FE-04 | MUST | 服务端状态（请求库缓存）与客户端 UI 状态分离管理 | 代码评审 |
| FE-05 | MUST | 每个路由具备错误边界与加载/空/错误三态 | E2E 抽检 |
| FE-06 | MUST | 格式化与 lint 由工具执行（Biome 或 ESLint flat config + Prettier），pre-commit 用 lint-staged | CI |
| FE-07 | MUST | axe 在 PR 阻断新增 critical/serious 无障碍问题 | CI |
| FE-08 | SHOULD | 性能预算：首屏 JS ≤ 200KB gzip、LCP ≤ 2.5s、CLS ≤ 0.1、INP ≤ 200ms | CI 预算检查 |
| FE-09 | SHOULD | WCAG 2.2：目标尺寸 ≥24×24 CSS px、焦点可见、键盘可达 | 自动化 + 人工走查 |
| FE-10 | SHOULD | 按路由代码分割，图片使用 WebP/AVIF + 响应式尺寸 | 代码评审 |
| FE-11 | SHOULD | E2E 用 `getByRole` 定位，不依赖 CSS class；测试数据经 API 构造 | 代码评审 |
| FE-12 | MAY | 新项目优先 Biome；存量项目保留 ESLint 9 flat config 亦可 | — |

---

## 3. 后端规范

| 编号 | 级别 | 要求 | 验证方式 |
| --- | --- | --- | --- |
| BE-01 | MUST | Controller（解析/校验/响应）→ Service（业务）→ Repository（数据访问）三层；DTO 与领域实体隔离 | 代码评审 |
| BE-02 | MUST | Service 层业务逻辑可脱离 HTTP 测试；授权策略位于 Service 层 | 单测 |
| BE-03 | MUST | 数据库迁移可回滚且向前兼容（先加列、双写、后删列） | 发布评审 + 迁移预演 |
| BE-04 | MUST | 事务边界定义在 Service 层，禁止跨外部调用持有长事务 | 代码评审 |
| BE-05 | MUST | 所有下游调用配置超时、熔断、限流；重试仅限幂等操作 | 集成测试 |
| BE-06 | MUST | 禁止在 API 层直接暴露数据库表结构或自增主键 | 契约检查 |
| BE-07 | SHOULD | 密钥、配置走环境变量/密钥管理，配置与代码分离（12-Factor） | secrets 扫描 |
| BE-08 | SHOULD | 集成测试使用真实依赖（Testcontainers），不大量 mock 数据库 | 代码评审 |
| BE-09 | SHOULD | 提供 `/health`（存活）与 `/ready`（依赖就绪）分离端点 | 部署检查 |
| BE-10 | MAY | 服务间调用可选 gRPC；边缘接口保持 REST/JSON | 架构评审 |

---

## 4. 数据交换通用约定（前后端共同遵守）

| 项目 | 约定 |
| --- | --- |
| 字段名 | `camelCase`，禁止下划线或匈牙利命名 |
| 枚举值 | `UPPER_SNAKE_CASE` 字符串，禁止用魔法数字 |
| 时间 | RFC 3339 UTC，如 `2026-11-14T04:22:11Z`；禁止时间戳数字或本地时间字符串 |
| 金额 | 最小货币单位**整数** + `currency`（ISO 4217），禁止浮点数 |
| ID | 不可枚举的字符串（如 `ord_123`），禁止自增整数 |
| 布尔 | 严格 `true/false`，禁止 `0/1`、`"yes"` |
| 空值 | 空集合返回 `[]` 而非 `null`；字段缺失与 `null` 语义需在 spec 中说明 |
| 未知字段 | 客户端 MUST 忽略响应中的新增未知字段，以保证向后兼容 |
| 内容类型 | 请求与响应显式 `application/json; charset=utf-8` |

---

## 5. 协作与交付规范

| 编号 | 级别 | 要求 | 验证方式 |
| --- | --- | --- | --- |
| COL-01 | MUST | 主干开发（trunk-based），分支生命周期以小时/天计，长功能用特性开关 | 分支保护规则 |
| COL-02 | MUST | Conventional Commits：`<type>(<scope>): <subject>`，`type` 取 `feat/fix/chore/docs/refactor/test/perf/ci/build/revert` | CI 提交检查 |
| COL-03 | MUST | PR 描述含：变更说明、关联 issue、**契约影响**、测试证据、回滚方案 | PR 模板 |
| COL-04 | MUST | 至少 1 名评审人批准；涉及鉴权、支付、CI 配置的变更需第 2 名评审人 | 分支保护规则 |
| COL-05 | MUST | AI 生成代码与人工代码适用完全相同的 lint、覆盖率与人工签核，不因效率放宽 | 评审 |
| COL-06 | MUST | 发布遵循 SemVer，changelog 由提交记录自动生成 | 发布流水线 |
| COL-07 | MUST | 生产发布采用灰度/金丝雀，具备 5 分钟内回滚能力 | 发布评审 |
| COL-08 | SHOULD | 每个 PR 生成独立预览环境，使用合成数据，禁止复制生产数据 | CI |
| COL-09 | SHOULD | 文档变更与 API 变更在同一 PR 内完成 | 评审 |

提交示例：

```
feat(orders): add cursor-based pagination to list endpoint

BREAKING CHANGE: offset/page parameters removed, use cursor/limit.
Refs: PROJ-142
```

---

## 6. CI 质量门禁

顺序固定，前一步失败即终止；全部为 `MUST`，除非另有标注。

| # | 阶段 | 命令 / 工具 | 阻断策略 | 目标耗时 |
| --- | --- | --- | --- | --- |
| 1 | 格式与 lint | `pnpm exec biome ci .` | 任何错误 | < 30s |
| 2 | 类型检查 | `pnpm exec tsc --noEmit` | 任何错误 | < 60s |
| 3 | 单元 / 集成测试 | `pnpm exec vitest run --coverage` | 失败或**变更行覆盖率 < 80%** | < 5min |
| 4 | 契约校验 | `node scripts/check-contract.mjs origin/main` | 破坏性变更未升版本、spec 不合规 | < 10s |
| 5 | 密钥扫描 | `gitleaks` | 任何命中 | < 30s |
| 6 | 依赖与 SAST | `dependency-review-action`、CodeQL | 仅阻断**高置信高危 / 已利用漏洞**；其余进 backlog | < 3min |
| 7 | 构建 | `pnpm build` | 任何错误 | < 2min |
| 8 | 预览环境（SHOULD） | 平台预览部署 | 失败告警，不阻断 | — |

**门禁设计原则**：只阻断高置信高危问题。若所有中危告警都阻断合并，团队会形成批量抑制告警的习惯，反而降低实际安全性。

---

## 7. 测试策略

| 层级 | 占比 | 要求 |
| --- | --- | --- |
| 单元测试 | 最多 | 快速、确定、无 I/O，边界条件集中在此层 |
| 集成测试 | 中等 | 使用真实数据库/队列/缓存容器，测试 Repository、API handler |
| 契约测试 | 边界必备 | Pact（消费者驱动）或 OpenAPI spec diff，替代庞大跨服务 E2E |
| E2E | 最少 | 仅覆盖注册、登录、下单、支付等核心路径 |

- 覆盖率门槛按**变更行**计算（80%），不按全仓计算。
- 测试行为而非实现细节；禁止为覆盖率写无断言测试。
- 反模式「冰淇淋筒」：大量缓慢易碎的 UI E2E + 几乎没有单测。发现时应先删除与低层重复的 E2E。
- AI 相关功能除确定性测试外，还需评估数据集与评分阈值，作为非阻断的独立 CI 任务。

---

## 8. 安全基线

| 编号 | 级别 | 要求 |
| --- | --- | --- |
| SEC-01 | MUST | 全站 HTTPS，含开发/预发环境；生产启用 HSTS |
| SEC-02 | MUST | 密钥、令牌、凭证禁止进入代码库；CI 强制 secrets 扫描 |
| SEC-03 | MUST | 所有输入在服务端边界校验并转义输出；禁止拼接 SQL |
| SEC-04 | MUST | 鉴权默认拒绝（deny by default）；越权用例纳入集成测试 |
| SEC-05 | MUST | 设置 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、CSP；CORS 使用白名单 |
| SEC-06 | MUST | 日志脱敏：不记录密码、令牌、身份证号、完整卡号 |
| SEC-07 | SHOULD | 依赖漏洞扫描与自动升级（Dependabot / Renovate），生产镜像扫描 CVE |
| SEC-08 | SHOULD | 数据库层启用行级安全（RLS），作为应用层鉴权之外的第二道防线 |

---

## 9. 可观测性

| 编号 | 级别 | 要求 |
| --- | --- | --- |
| OBS-01 | MUST | 结构化 JSON 日志，字段：`ts`、`level`、`traceId`、`spanId`、`service`、`method`、`path`、`status`、`latencyMs`、`userId` |
| OBS-02 | MUST | `traceId` 由网关生成，贯穿前端 → 后端 → 下游，并回写至错误响应体 |
| OBS-03 | MUST | 前端错误上报携带 `traceId`，保证一次用户投诉可串联全链路 |
| OBS-04 | SHOULD | OpenTelemetry 链路追踪；关键接口定义 SLO 与告警阈值 |
| OBS-05 | SHOULD | 错误追踪（Sentry 等）与性能监控从项目首日接入 |
| OBS-06 | SHOULD | 生产环境定期合成监控（多地域、5 分钟粒度） |

---

## 10. 规范演进流程

提案 → 团队评议（≥3 个工作日） → 提交 PR 修改本文 → 试运行 2 周（此期间违规只告警不阻断）→ 正式生效 → 版本号按 SemVer 递增 → 同步更新 CI 规则与附录 C 配置。

---

## 附录 A：PR 检查清单（可直接复制到 PR 模板）

```markdown
- [ ] 提交信息符合 Conventional Commits
- [ ] 本 PR 是否改动接口契约？ 是 / 否
      - 若为「是」：`openapi/openapi.json` 已同步更新，且 spec PR 已先行合并
      - 若为「是」：破坏性变更已升主版本，或已在描述中登记豁免
- [ ] 新增/修改接口包含：正确状态码、统一错误体、traceId、分页（列表类）
- [ ] 新增入参在边界做了 schema 校验，拒绝未知字段
- [ ] 全仓 `tsc --noEmit` 通过，无新增 `any` / `@ts-ignore`
- [ ] 补充了单元或集成测试，变更行覆盖率 ≥ 80%
- [ ] 涉及鉴权 / 支付 / CI 配置：已获得第 2 名评审人批准
- [ ] AI 生成代码已逐行审阅，与人工代码同等门禁
- [ ] 日志已脱敏，无密钥、无堆栈外泄
- [ ] 描述中包含测试证据与回滚方案
```

## 附录 B：接口评审检查清单

```markdown
- [ ] 路径为复数 kebab-case 名词，无动词，嵌套 ≤ 2 层，带 /vN 前缀
- [ ] HTTP 方法与幂等语义正确，GET 无副作用
- [ ] 状态码使用精确（尤其 201/204、401 vs 403、400 vs 422、429 带 Retry-After）
- [ ] 错误体含稳定 code、可展示 message、必填 traceId，无内部信息
- [ ] 列表接口默认游标分页，limit 上限 100
- [ ] 时间、金额、ID、枚举符合第 4 节通用约定
- [ ] 新增字段均为可选（兼容变更），删除/改名/改类型已升主版本
- [ ] 认证方式明确，公开路由有显式豁免标记
- [ ] spec 含 operationId、description、example、每个状态码与错误类型
- [ ] 已生成 Mock 与类型，前端可据此并行开发
```

## 附录 C：配套文件与接入步骤

| 路径 | 用途 | 接入方式 |
| --- | --- | --- |
| `docs/engineering-standards.md` | 本规范正文 | 复制到项目 `docs/` |
| `openapi/openapi.json` | OpenAPI 3.1 契约模板，含错误体、分页、幂等、限流头定义 | 复制后按业务扩展 `paths` |
| `scripts/check-contract.mjs` | 纯 Node、零依赖的 spec 静态校验 + 破坏性变更检测 | CI 中调用 `node scripts/check-contract.mjs origin/main` |
| `biome.json` | 统一的格式化与 lint 基线 | 复制到仓库根目录，`pnpm add -D @biomejs/biome` |
| `.github/workflows/pr-checks.yml` | 可直接运行的 CI 门禁 | 复制后按项目调整 Node / pnpm 版本 |

接入步骤：

1. 复制上述文件到目标仓库对应路径。
2. `pnpm add -D @biomejs/biome typescript vitest @vitest/coverage-v8`，并在 `package.json` 增加 `typecheck`、`test` 脚本。
3. 在 `openapi/openapi.json` 中替换 `info`、`servers` 与示例 `paths`，保留 `components`。
4. 启用分支保护：要求 `pr-checks` 全部通过、至少 1 名评审人批准、禁止直接 push 到 `main`。
5. 配置 pre-commit：`pnpm add -D husky lint-staged`，钩子中执行 `pnpm exec biome check --write --staged`。
6. 本地验证：`node scripts/check-contract.mjs` 应输出 `[contract] OK`。

**工具选型说明**：`check-contract.mjs` 覆盖静态合规与常见破坏性变更，零外部依赖即可运行。若需更细粒度的规则（命名、描述完整性、示例），可追加 Spectral：`npx --yes @stoplight/spectral-cli@6 lint openapi/openapi.json --ruleset .spectral.yaml`，其中 `.spectral.yaml` 使用 `extends: ["spectral:oas"]`。

## 附录 D：参考标准

- [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110) — HTTP Semantics（方法、状态码语义）
- [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) — Problem Details for HTTP APIs（取代 RFC 7807）
- [OpenAPI Specification 3.1](https://spec.openapis.org/oas/v3.1.0) — 接口契约格式
- [WCAG 2.2](https://www.w3.org/TR/WCAG22/) — Web 内容无障碍指南
- [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) — 提交信息约定
- [Semantic Versioning 2.0.0](https://semver.org/) — 版本号约定
- [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) — 规范中级别用语的定义
- [The Twelve-Factor App](https://12factor.net/) — 配置、日志与进程基线
