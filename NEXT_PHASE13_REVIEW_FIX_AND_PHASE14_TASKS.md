# NEXT_PHASE13_REVIEW_FIX_AND_PHASE14_TASKS

> Date: 2026-09-07  
> Scope: Java backend only. Do not change the existing NocoBase frontend.  
> Input review: `PHASE13_RELEASE_GATE_FIX_COMPLETION_SUMMARY.md`

## 0. Architect Review Result

Phase13 基本通过，但还不能直接定义为生产可发布。默认测试报告显示 `913 tests, 0 failures, 0 errors, 0 skipped`，`SYSTEM_MODULE_API_GAP_ANALYSIS.md` 已同步到 `113/113 = 100%`，`RequestIdFilter` 也已进入 Spring Security chain，JWT/生产 stdout 清理方向正确。

本轮抽查发现以下剩余风险，下一批必须优先处理：

| ID | Severity | Finding | Required Follow-up |
|---|---|---|---|
| R1 | P0 | 默认 `mvn test` 仍排除 PostgreSQL 验收测试，当前 `target/surefire-reports` 未看到 PG 测试报告产物。PG profile 已做 fail-fast，但发布证据还不完整。 | 发布门禁必须实际运行 PG acceptance，并生成可审计结果。 |
| R2 | P0 | `RequestIdFilter` 作为 `Filter` bean 被加入 Security chain，但未看到禁用 Servlet 自动注册的配置，也没有 RequestId 链路测试。存在重复执行或顺序回归风险。 | 明确 filter 生命周期，并补 MockMvc/API 测试。 |
| R3 | P0 | `sanitizeUrlForResponse()` 保留 query parameters；JDBC URL 的 query 中可能包含 `user/password/sslpassword` 等敏感字段。 | 对 URL query 敏感参数做响应级脱敏。 |
| R4 | P1 | `DataSourceConfigService.toResponseMap()` 仍返回原始 `username`，同时测试只检查 `maskedUsername` 存在，没有约束 raw username。 | 以真实前端契约为准决定是否移除或改为 masked。 |
| R5 | P0 | 生产 `DataInitializer` 不创建默认用户是正确方向，但当前只输出“无 admin 用户”告警，缺少首个管理员初始化链路。 | 实现安全的一次性 admin bootstrap，不改前端。 |
| R6 | P1 | Release checklist 仍是人工表格，未形成可执行 release gate 和结果归档。 | 增加 release gate 自动化脚本/校验器。 |

## 1. Parallel Assignment Overview

本批任务建议 6 个 Claude Agent 并行开发。P0-A/B/C 可同时启动；P0-D 依赖前端契约核验但可先做策略和测试草案；P1-E/F 独立推进。

| Agent | Priority | Task | Can Run In Parallel | Blocks Release |
|---|---|---|---|---|
| Agent A | P0 | Release gate automation + PG evidence | Yes | Yes |
| Agent B | P0 | RequestId filter lifecycle hardening | Yes | Yes |
| Agent C | P0 | DataSource sensitive response hardening | Yes | Yes |
| Agent D | P0 | First admin bootstrap flow | Yes | Yes |
| Agent E | P1 | Real frontend contract replay | Yes | No, but blocks frontend acceptance |
| Agent F | P1 | Audit failure coverage + transaction semantics | Yes | No, but required before beta |

## 2. Agent A - P0 Release Gate Automation And PG Evidence

### Goal
把 release gate 从文档要求变成可执行、可复核、不会假阳性的门禁。

### Scope
- 新增发布门禁脚本，优先支持 Windows：`scripts/release-gate.ps1`；如项目已有 Linux 脚本约定，再补 `scripts/release-gate.sh`。
- 执行并解析以下命令：
  - `mvn test`
  - `mvn flyway:validate`
  - `mvn test -Ppostgresql-acceptance`
  - sensitive scan
- 解析 `target/surefire-reports/TEST-*.xml`，输出 tests/failures/errors/skipped。
- PG acceptance 必须验证 `TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml` 存在且 tests > 0。
- 如果 `PG_URL`、`PG_USERNAME`、`PG_PASSWORD` 缺失，`mvn test -Ppostgresql-acceptance` 必须非 0 失败，并输出明确错误，不允许 skip 成功。
- 生成 `RELEASE_GATE_RESULT.md`，记录命令、退出码、测试数量、失败数、跳过数、执行时间、最终 PASS/FAIL。
- 更新 `RELEASE_READINESS_CHECKLIST.md`，让人工表格指向脚本结果，不再手填为主。

### Acceptance Criteria
- 默认测试仍为 `0 failures, 0 errors, 0 skipped`。
- PG 环境变量缺失时，PG gate 失败，且失败原因清晰。
- PG 环境变量存在时，PG gate 必须产生 PostgreSQL surefire XML，tests > 0。
- sensitive scan 至少覆盖：`System.out.println`、`printStackTrace`、JWT/token 明文日志、password/secret 明文响应断言。

### Suggested Tests
- 如果脚本逻辑可拆出 parser，增加 surefire XML parser 单元测试。
- 至少在完成总结中贴出 release gate 的四行命令结果。

## 3. Agent B - P0 RequestId Filter Lifecycle Hardening

### Goal
确保每个请求只有一个稳定 `requestId`，并且审计日志、响应 header、MDC/ThreadLocal 使用同一个值。

### Scope
- 明确 `RequestIdFilter` 只由 Spring Security chain 管理，或只由 Servlet container 管理，二选一。推荐与 `JwtAuthenticationFilter` 一致：加入 Security chain，同时用 `FilterRegistrationBean<RequestIdFilter>` 禁用自动 Servlet 注册，避免重复执行。
- 保持顺序：`RequestIdFilter` 必须在 `JwtAuthenticationFilter` 之前执行。
- `X-Request-Id` 为空时生成 UUID-like id；传入时原样复用，但要限制长度和非法字符，避免日志注入。
- 响应必须返回 `X-Request-Id`。
- 请求结束后清理 `RequestIdContext` 和 MDC，避免线程复用污染。

### Acceptance Criteria
- `GET /api/auth:check` 带 `X-Request-Id` 时，响应 header 相同。
- 未带 `X-Request-Id` 时，响应 header 非空。
- 执行一次写操作后，`audit_logs.request_id` 等于请求 header。
- 连续两个请求的 ThreadLocal/MDC 不串值。
- 有测试证明 filter 不会被执行两次。

### Suggested Tests
- 新增 `RequestIdFilterIntegrationTest` 或合并到 API 兼容测试。
- 用 MockMvc 覆盖 public endpoint、authenticated endpoint、write audit endpoint。

## 4. Agent C - P0 DataSource Sensitive Response Hardening

### Goal
让外部数据源配置 API 不泄露 JDBC URL 中的任何敏感信息，同时保持前端不改。

### Scope
- 修复 `DataSourceConfigService.sanitizeUrlForResponse()`：
  - host、port、database/path 必须脱敏。
  - query parameters 中敏感 key 必须脱敏，包括但不限于 `user`、`username`、`password`、`pass`、`pwd`、`sslpassword`、`secret`、`token`、`accessKey`、`accessKeyId`、`accessKeySecret`。
  - 非敏感 query parameters 可保留，例如 `sslmode=require`、`connectTimeout=10`。
- 重新定义 `username` 响应策略：
  - 先用现有前端契约/trace 判断是否必须返回 raw `username`。
  - 如果前端不依赖 raw `username`，从 list/get/create/update 响应中移除 `username`，只保留 `maskedUsername`。
  - 如果前端确实依赖字段名 `username`，则 `username` 返回 masked 值，并保留 `maskedUsername`；禁止返回 raw 值。
- 错误消息继续使用脱敏 URL，不能泄露 username/password/query secret。

### Acceptance Criteria
- dataSources 所有响应不包含 raw password、raw username、raw host、raw database、raw sensitive query value。
- `maskedUsername` 存在且稳定。
- 不改变前端已有调用路径和响应 envelope。
- API 测试必须新增对 query secret 的断言。

### Suggested Tests
- `jdbc:postgresql://user:pass@db.example.com:5432/prod?sslmode=require&password=p1&user=u1`
- `jdbc:h2:file:./storage/db/nocobase;USER=sa;PASSWORD=secret`
- list/get/create/update/testConnection 失败路径的错误消息脱敏。

## 5. Agent D - P0 First Admin Bootstrap Flow

### Goal
解决生产首次启动无管理员时系统不可用的问题，同时不引入默认账号密码。

### Scope
- 调研现有 NocoBase 前端是否已有 setup/install/bootstrap 调用契约；如果有，Java 后端必须兼容该契约。
- 如果前端没有可复用契约，实现后端安全 bootstrap 能力，优先级如下：
  - 环境变量/CLI bootstrap：`NOCOBASE_ADMIN_EMAIL`、`NOCOBASE_ADMIN_PASSWORD`、`NOCOBASE_ADMIN_NICKNAME`。
  - 一次性 setup token endpoint：只在 users 表为空时可用，token 来自环境变量或启动日志中的一次性 token；不得输出密码。
- 创建首个用户后必须绑定 `admin` 和必要系统角色，确保可登录并访问系统管理 API。
- bootstrap 必须幂等：已有任何用户后再次调用返回 409/403，不得覆盖现有管理员。
- 密码必须走现有 `PasswordEncoder`/认证链路，不允许明文落库。
- bootstrap 操作写入 audit log，details 不含密码/token。

### Acceptance Criteria
- fresh database 启动后，可以通过 bootstrap 创建首个 admin。
- 已有用户时 bootstrap 被拒绝。
- 创建后的 admin 可以 `signIn` 并访问 collection manager、systemSettings、plugins API。
- 不创建默认固定账号，不输出默认密码。
- 不修改前端文件。

### Suggested Tests
- `BootstrapAdminTest`：空库创建、重复创建拒绝、弱输入校验、登录成功、权限可用。
- 生产 profile 相关配置测试：缺少 bootstrap 配置时行为明确。

## 6. Agent E - P1 Real Frontend Contract Replay

### Goal
把“API 覆盖率 100%”从手工端点覆盖推进到真实前端调用契约验证。

### Scope
- 新增 trace/HAR replay 测试框架，输入为 JSON/HAR，不修改前端。
- 至少覆盖以下前端首屏和常用流程：
  - auth check/signIn/currentUser/refresh/logout
  - applicationPlugins/plugins/systemSettings
  - uiSchemas/uiSchemaTemplates
  - collections/fields/indexes
  - generic CRUD list/get/create/update/destroy
  - association actions add/remove/set/listLinks
  - dataSources list/get/create/update/testConnection/destroy
- 校验响应 envelope、状态码、关键字段名、权限错误格式。
- 更新 `SYSTEM_MODULE_API_GAP_ANALYSIS.md`，新增 “Trace Replay Coverage” 区块，区分 API-level tests 和 frontend-trace tests。

### Acceptance Criteria
- trace replay 可以在 CI 中跑，不依赖真实浏览器。
- 每个 trace case 能声明 auth user、method、path、query/body、expected status、expected response shape。
- 响应结构变化会让测试失败。
- 不要求修改前端。

## 7. Agent F - P1 Audit Failure Coverage And Transaction Semantics

### Goal
补齐失败操作审计，并明确成功/失败审计的事务边界。

### Scope
- 梳理当前所有写操作：users、roles、acl、collections、fields、uiSchemas、plugins、dataSources、generic CRUD、association actions。
- 成功审计应与业务事务一致；业务回滚时成功审计不能单独留下。
- 失败审计应能在业务失败后保留，建议使用独立事务，但必须避免记录密码、token、完整 SQL、完整 JDBC URL。
- 统一失败审计入口，避免 controller/service 各自散落 try/catch。
- `auditFailure()` details 必须通过现有 sanitizer。
- requestId 必须贯穿成功和失败审计。

### Acceptance Criteria
- 权限拒绝、字段校验失败、SQL collection 写入拒绝、dataSource testConnection 失败、plugin 无效操作都有失败审计。
- 失败审计不泄露敏感字段。
- 成功写操作失败回滚时不留下成功审计。
- audit log API 只允许 admin/root 查询。

### Suggested Tests
- 新增审计失败集成测试，优先使用 MockMvc 触发真实 Controller 链路。
- 覆盖 requestId、actorUserId、resource/action/resourceKey、status、details sanitization。

## 8. Completion Summary Requirements

Claude 完成后输出 `PHASE14_RELEASE_BOOTSTRAP_AND_CONTRACT_COMPLETION_SUMMARY.md`，必须包含：

- 每个 Agent 的任务状态：Done / Partial / Blocked。
- 实际修改文件列表。
- 实际执行命令和结果，不只写 “mvn test passed”。
- `mvn test` 的 surefire 汇总：tests/failures/errors/skipped。
- PG acceptance 是否真实运行：环境、报告文件名、tests/failures/errors/skipped。
- 是否修改前端：必须为 No。
- 仍然存在的风险和下一批建议。

## 9. Do Not Do

- 不改 NocoBase 前端。
- 不把默认 admin/admin 密码写入生产初始化。
- 不绕过 `DynamicRepository` 做数据权限相关查询。
- 不把 SQL、JDBC URL、password、token 明文写进日志、响应或 audit details。
- 不用 skipped test 伪装 release gate 通过。
