# NEXT_PHASE14_REVIEW_FIX_AND_PHASE15_TASKS

> Date: 2026-09-09  
> Scope: Java backend only. Do not change the existing NocoBase frontend.  
> Input review: `PHASE14_RELEASE_BOOTSTRAP_AND_CONTRACT_COMPLETION_SUMMARY.md`

## 0. Architect Review Result

Phase14 大方向通过：默认 surefire 汇总确认为 `975 tests, 0 failures, 0 errors, 0 skipped`。首个 admin bootstrap、RequestIdFilter 生命周期、dataSources 响应脱敏、frontend trace replay、failure audit 都已有实现和测试落点。

但当前还不能把 release gate 定义为可信发布门禁。下一批必须先修“门禁自身可能误判”的问题，再进入部署和真实前端验收。

| ID | Severity | Finding | Required Follow-up |
|---|---|---|---|
| R1 | P0 | `scripts/release-gate.ps1` 中 `& mvn $Command` 把 `test -Ppostgresql-acceptance` 作为单个参数传给 Maven，PowerShell 下多参数命令不可靠。 | 改为 argv 数组调用，并补脚本级测试或 dry-run 验证。 |
| R2 | P0 | Gate 3 解析整个 `target/surefire-reports`，没有强制校验 `TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml`。如果 Gate 1 报告残留，PG gate 可能用 H2 报告误判。 | 每个 gate 隔离报告目录或清理后运行，并强制 PG-specific XML 存在且 tests > 0。 |
| R3 | P0 | Sensitive scan 使用 `-SimpleMatch` 搭配带转义的正则，例如 `System\.out\.println`，会漏报；同时正确修复后需要避免 Javadoc/test false positive。 | 重写 scan 规则，区分 main/test/comment，并将 false negative/false positive 都纳入测试。 |
| R4 | P1 | 当前没有看到 PostgreSQL acceptance 的 surefire 报告产物。总结只声明 profile，没有真实 PG 环境、报告文件和 tests 数。 | 需要真实 PG 验收证据，或者引入 Testcontainers/CI service postgres。 |
| R5 | P1 | `trace.json` 是模拟前端启动契约，不是从真实 NocoBase 前端浏览器行为采集的 HAR/trace。 | 下一步要接真实前端构建产物做 smoke/e2e contract。 |
| R6 | P1 | `POST /api/bootstrap:setup` 只覆盖 colon route；其他 controller 多数兼容 colon/slash 双路由。 | 补 `/api/bootstrap/setup` 兼容路由和安全配置。 |
| R7 | P1 | Bootstrap 由公开 endpoint 触发，虽然凭据来自 env，但仍缺少安装模式边界、限流/幂等并发测试、日志最小化策略。 | 加固 first-admin bootstrap 的生产安全边界。 |
| R8 | P1 | Failure audit 已用 `REQUIRES_NEW`，方向正确；但业务服务中大量手写 try/catch，后续维护风险高，且动态 CRUD/association 路径仍需完整矩阵。 | 建立统一审计覆盖矩阵和架构边界测试。 |

## 1. Parallel Assignment Overview

本批任务建议 7 个 Claude Agent 并行开发。Agent A/B/C 是 P0，必须先完成并通过；Agent D/E/F/G 可并行推进。

| Agent | Priority | Task | Can Run In Parallel | Blocks Release |
|---|---|---|---|---|
| Agent A | P0 | Fix release-gate Maven invocation and report isolation | Yes | Yes |
| Agent B | P0 | Fix sensitive scan correctness | Yes | Yes |
| Agent C | P0 | Make PostgreSQL acceptance executable in CI | Yes | Yes |
| Agent D | P1 | Harden bootstrap route/security/concurrency | Yes | No, but required before beta |
| Agent E | P1 | Real frontend smoke contract | Yes | No, but required before frontend handoff |
| Agent F | P1 | Audit coverage matrix and dynamic data-layer failures | Yes | No |
| Agent G | P1 | Packaging, health checks, and deployment docs | Yes | No |

## 2. Agent A - P0 Release Gate Invocation And Report Isolation

### Goal
让 `scripts/release-gate.ps1` 成为可信门禁，不能因为 PowerShell 参数、旧 surefire 报告或残留文件误判 PASS。

### Scope
- 修复 Maven 调用方式：
  - 不允许用 `& mvn $Command` 传整段字符串。
  - 使用参数数组，例如 `& mvn @Args`。
  - `Invoke-MavenCommand` 入参改为 `string[] Args`，显示命令时再 join。
- 每个 gate 运行前隔离/清理报告：
  - Gate 1 清理 `target/surefire-reports` 后运行 `mvn test`。
  - Gate 3 清理或输出到独立目录后运行 `mvn test -Ppostgresql-acceptance`。
- Gate 3 必须强制校验：
  - `TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml` 存在。
  - 该 XML 中 tests > 0。
  - failures = 0、errors = 0、skipped = 0。
- `-SkipPgAcceptance` 只能用于本地开发，不允许生成 `ALL GATES PASSED`。
- `RELEASE_GATE_RESULT.md` 必须记录每个 gate 的实际报告文件名。
- 修复脚本输出中的乱码注释/分隔符，保持 UTF-8 或纯 ASCII。

### Acceptance Criteria
- 手工运行 `scripts/release-gate.ps1 -SkipPgAcceptance` 时 overall 必须 FAIL，且说明 PG 被跳过。
- 无 PG env 时 overall 必须 FAIL，且不会执行假阳性 surefire 解析。
- 有 PG env 时 Gate 3 只使用 PostgreSQL 测试报告判断。
- 删除旧 `target/surefire-reports` 后脚本仍能正确生成报告。

### Suggested Tests
- 为 `SurefireReportParser` 增加 PG-specific report lookup 测试。
- 如 PowerShell 逻辑难以单测，至少新增 `scripts/release-gate.dry-run.ps1` 或 Java parser 验证用例。

## 3. Agent B - P0 Sensitive Scan Correctness

### Goal
修复 sensitive scan 的漏报和误报，让它可以作为 release gate 的硬性检查。

### Scope
- 修复 `Select-String` 用法：
  - 正则匹配时不能使用 `-SimpleMatch`。
  - 字面量匹配时不要写转义正则。
- main source 中以下命中必须让 gate fail：
  - `System.out.println(`
  - `.printStackTrace()`
  - 记录完整 JWT/token/password/secret/Authorization header 的 log 语句
  - 响应或 audit details 中直接包含 password/token/secret/JDBC URL raw value 的明显模式
- test source 中的 `System.out.println` 只能作为 INFO，不影响 gate。
- Javadoc/example/comment 中的示例代码不能误判为 ERROR；如果保留 Javadoc 示例，scan 要识别注释上下文。
- 输出中不能打印真实 secret，只能打印文件、行号、规则名和脱敏片段。

### Acceptance Criteria
- 在临时 fixture 中放入 `System.out.println`、`printStackTrace`、secret log，scan 必须失败。
- 在 Javadoc/comment/test 中放入同样字符串，scan 不应阻塞 release。
- 当前 `src/main/java` 通过 scan，且没有乱码输出。
- `RELEASE_GATE_RESULT.md` 中列出 WARN/INFO 但 only ERROR blocks release。

### Suggested Tests
- 如果脚本逻辑保留在 PowerShell，新增 fixture 目录和 dry-run 参数。
- 如果迁移为 Java checker，新增 JUnit 测试覆盖正/反例。

## 4. Agent C - P0 PostgreSQL Acceptance In CI

### Goal
让 PostgreSQL acceptance 不依赖人工本机环境，形成可重复 CI 证据。

### Scope
选择一种方案实现，优先推荐 Testcontainers：

方案 A: Testcontainers
- 引入 testcontainers-postgresql，仅用于 test scope。
- 新增 `PostgreSqlTestContainerSupport` 或独立 profile，使 `mvn test -Ppostgresql-acceptance` 可自动启动 PostgreSQL。
- 保留外部 `PG_URL/PG_USERNAME/PG_PASSWORD` 覆盖能力。
- 确保 CI 环境支持 Docker 时可直接运行。

方案 B: GitHub Actions service postgres
- 新增 `.github/workflows/release-gate.yml`。
- 使用 postgres service container。
- 配置 PG env 后运行 `mvn test -Ppostgresql-acceptance`。

必须实现：
- `mvn test` 默认仍排除 PG acceptance。
- `mvn test -Ppostgresql-acceptance` 必须生成 PG-specific surefire XML。
- Release gate 必须上传/保留 `RELEASE_GATE_RESULT.md` 和 surefire reports。

### Acceptance Criteria
- 本地或 CI 至少一种方式能真实跑 PG acceptance。
- 完成总结中必须写明：PG 版本、连接方式、报告文件名、tests/failures/errors/skipped。
- 无 PG 环境且未启用 Testcontainers 时，不允许伪装成功。

## 5. Agent D - P1 Bootstrap Route And Production Security Hardening

### Goal
让 first-admin bootstrap 满足生产最小安全边界，并兼容 NocoBase 路由风格。

### Scope
- `BootstrapController` 同时支持：
  - `POST /api/bootstrap:setup`
  - `POST /api/bootstrap/setup`
- `SecurityConfig` 同时 permitAll 两个 bootstrap 路由。
- Bootstrap 只允许在 users 表为空时执行。
- 增加并发幂等保护：两个并发 bootstrap 请求最多创建一个 admin。
- 可选增强：安装模式配置项，例如 `nocobase.bootstrap.enabled`，默认仅 users 为空时可用。
- 日志不要输出 password/token；评估是否输出 admin email，生产默认建议脱敏或 DEBUG。
- Bootstrap 成功后绑定 admin 角色；如 root 语义需要，也明确是否绑定 root。

### Acceptance Criteria
- colon/slash 两种路由测试通过。
- 并发测试证明只创建一个用户和一个 userRole。
- 已有任何用户时返回 409。
- 未配置 env 时返回 503，且不泄露配置值。
- 创建用户后可以 signIn 并访问 admin API。

## 6. Agent E - P1 Real Frontend Smoke Contract

### Goal
把模拟 trace 推进到真实 NocoBase 前端兼容验证，但仍不修改前端源码。

### Scope
- 使用现有前端构建产物或本地 NocoBase 前端 dev server，对 Java 后端执行 smoke 流程。
- 记录真实浏览器网络请求，生成 HAR/trace fixture。
- 将真实 HAR 转换为当前 replay 框架支持的 contract JSON。
- 至少覆盖：登录、auth check、插件加载、系统设置、UI schema、collection manager、data source list、基础 CRUD list/get。
- 对比模拟 trace 和真实 trace，更新 `SYSTEM_MODULE_API_GAP_ANALYSIS.md`：
  - API-level coverage
  - synthetic trace coverage
  - real frontend trace coverage

### Acceptance Criteria
- 新增真实 trace fixture，不包含 token/password 等敏感值。
- Replay 测试可在无浏览器 CI 中执行。
- 任何响应 envelope/字段名不兼容会失败。
- 不修改前端文件。

## 7. Agent F - P1 Audit Coverage Matrix And Data-Layer Failure Paths

### Goal
把 failure audit 从“部分 service 手写 try/catch”推进到可维护、可证明的覆盖矩阵。

### Scope
- 新增 `AUDIT_COVERAGE_MATRIX.md`，列出所有写入口：
  - users、roles、acl resources/actions/scopes
  - collections、fields、indexes/runtime reload
  - uiSchemas、plugins、systemSettings、dataSources
  - generic CRUD create/update/destroy
  - association add/remove/set
  - SQL collection write rejection
- 对每个入口标记：success audit、failure audit、requestId、actorUserId、sanitization、transaction behavior。
- 补齐动态数据层失败审计：
  - ACL denied
  - field permission denied
  - scope denied
  - validation error
  - SQL collection write rejection
  - association cross-datasource rejection
- 避免继续散落复制 try/catch；如必须保留，增加架构边界测试确保写入口都有 audit failure。

### Acceptance Criteria
- `AuditFailureIntegrationTest` 覆盖动态 CRUD 和 association failure。
- 失败审计 details 不包含 password/token/JDBC URL/raw SQL。
- `auditFailure()` 使用 `REQUIRES_NEW` 的语义有测试证明。
- 成功审计在业务回滚时不留下脏记录。

## 8. Agent G - P1 Packaging, Health Checks, And Deployment Docs

### Goal
让后端可以被部署、探活、回滚和排障，而不仅是本地测试通过。

### Scope
- 增加 Spring Boot Actuator 或等价健康检查：
  - `/actuator/health` 或 `/api/health`
  - main datasource health
  - Flyway migration state
  - runtime collection registry loaded state
  - external datasource optional health summary，不泄露 URL/username/password
- 新增 Dockerfile 或更新部署文档，明确：
  - Java 17 runtime
  - required env vars
  - H2 dev vs PostgreSQL production
  - data volume
  - log level
  - release gate command
- 增加 `BACKEND_OPERATION_GUIDE.md` 的生产启动、升级、回滚、排障章节。
- 确保生产 profile 下 H2 console disabled、Hibernate ddl-auto unsafe values blocked。

### Acceptance Criteria
- health endpoint 不需要认证或按部署约定明确认证策略。
- health response 不泄露 secret。
- Docker build 或至少 Maven package 可通过。
- 运维文档包含最小生产环境变量清单。

## 9. Completion Summary Requirements

Claude 完成后输出：`PHASE15_RELEASE_GATE_CI_AND_DEPLOYMENT_COMPLETION_SUMMARY.md`

必须包含：

- 每个 Agent 的完成状态：Done / Partial / Blocked。
- 修改文件列表。
- 实际执行命令和结果。
- `mvn test` surefire 汇总：tests/failures/errors/skipped。
- `mvn test -Ppostgresql-acceptance` 是否真实运行：PG 版本、报告文件、tests/failures/errors/skipped。
- `scripts/release-gate.ps1` 输出摘要和 `RELEASE_GATE_RESULT.md` 路径。
- 是否修改前端：必须为 No。
- 仍存在的风险和下一批建议。

## 10. Do Not Do

- 不修改 NocoBase 前端源码。
- 不用旧 surefire 报告判断新 gate。
- 不把 skipped test 当作 release gate pass。
- 不输出或提交真实 password、token、JDBC URL、PG env。
- 不绕过 `DynamicRepository` 做数据权限相关读写。
- 不把 bootstrap 做成默认 admin/admin。
