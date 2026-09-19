# NEXT_PHASE15_REVIEW_FIX_AND_PHASE16_TASKS

> Date: 2026-09-09  
> Scope: Java backend only. Do not change the existing NocoBase frontend.  
> Input review: `PHASE15_RELEASE_GATE_CI_AND_DEPLOYMENT_COMPLETION_SUMMARY.md`

## 0. Architect Review Result

Phase15 功能方向基本正确：默认 surefire 汇总确认为 `1002 tests, 0 failures, 0 errors, 0 skipped`，并且已跨过 1000 测试数量。Bootstrap、RequestId、DataSource 脱敏、failure audit、health endpoint、trace replay、PG Testcontainers 都有实现落点。

但是，本轮不能判定为 release-ready。关键原因是 `RELEASE_GATE_RESULT.md` 当前实际结果为 **GATES FAILED**，并且 Gate 1/Gate 2 显示 Maven 收到空目标导致 `NoGoalSpecifiedException`。这说明 release gate 脚本仍不能作为可信发布门禁。

| ID | Severity | Finding | Required Follow-up |
|---|---|---|---|
| R1 | P0 | `RELEASE_GATE_RESULT.md` 当前为 FAIL：Gate 1 `mvn test` 和 Gate 2 `mvn flyway:validate` 实际执行成 Maven 空目标。 | 修复 `release-gate.ps1` 参数传递，必须产出 PASS 的 gate 结果。 |
| R2 | P0 | `Invoke-MavenCommand -Args` 可能与 PowerShell 自动变量 `$Args` 冲突，报告里命令显示正确但 Maven 实际没收到 goal。 | 参数名改为 `$MavenArgs`，并补真实脚本 smoke 测试。 |
| R3 | P0 | Gate 3 仍先检查 `PG_URL/PG_USERNAME/PG_PASSWORD`，导致无 env 时直接失败，无法使用 Testcontainers 自动启动。 | 明确策略：默认 Testcontainers，env 仅作 override；或文档明确 PG env 必填。 |
| R4 | P0 | 当前 `target/surefire-reports` 没有 PostgreSQL 专属 XML，完成总结也没有 PG version/report tests 证据。 | 必须真实运行 `mvn test -Ppostgresql-acceptance` 并保留 PG-specific surefire XML。 |
| R5 | P1 | `PostgreSqlTestContainerSupport` 使用 `System.out.println` 输出 JDBC URL，且 docs 仍写 env-only；敏感扫描把它当 INFO。 | 改成 logger + sanitized URL，并同步 README/release docs。 |
| R6 | P1 | Health endpoint 只返回 body，不按 DOWN 返回 HTTP 503；overall status 也没有纳入 runtime/external datasource 策略。 | 拆分 liveness/readiness 或明确 `/api/health` 的 HTTP 状态语义。 |
| R7 | P1 | Audit matrix 自己标出 3 个 gap：`updateUserRoles`、`dataSource.testConnection success`、`bootstrap failure`。 | 下一批补齐到 100% 或明确豁免理由。 |
| R8 | P1 | Trace replay 已到 73 步，但仍未看到真实 HAR 原始样本/脱敏样本，不能证明来自真实前端。 | 产出 sanitized HAR fixture + converter 验收。 |
| R9 | P1 | 当前本地 git worktree 仍有未提交 Phase14/15 变更。 | 建立提交/发布清单，避免 review 结果和提交内容不一致。 |

## 1. Parallel Assignment Overview

本批任务建议 6 个 Claude Agent 并行。Agent A/B/C 是发布阻塞项，必须先完成；Agent D/E/F 可并行推进。

| Agent | Priority | Task | Can Run In Parallel | Blocks Release |
|---|---|---|---|---|
| Agent A | P0 | Fix release-gate script execution | Yes | Yes |
| Agent B | P0 | Close PostgreSQL acceptance evidence | Yes | Yes |
| Agent C | P0 | Release gate result and CI workflow | Yes | Yes |
| Agent D | P1 | Health/readiness production semantics | Yes | No, but required before production |
| Agent E | P1 | Close audit matrix gaps | Yes | No |
| Agent F | P1 | Real frontend HAR evidence and repo hygiene | Yes | No |

## 2. Agent A - P0 Fix Release Gate Script Execution

### Goal
让 `scripts/release-gate.ps1` 实际执行它显示的 Maven 命令，消除 `NoGoalSpecifiedException`。

### Scope
- 修改 `Invoke-MavenCommand`：
  - 参数名从 `$Args` 改为 `$MavenArgs`，避免 PowerShell 自动变量冲突。
  - 使用 `& mvn @MavenArgs`。
  - 输出命令用 `$MavenArgs -join ' '`。
- 每次 Maven 执行前 `Set-Location $ProjectRoot`，执行后恢复原工作目录。
- Gate 1/Gate 2/Gate 3 调用都改为：
  - `Invoke-MavenCommand -MavenArgs @('test')`
  - `Invoke-MavenCommand -MavenArgs @('flyway:validate')`
  - `Invoke-MavenCommand -MavenArgs @('test', '-Ppostgresql-acceptance')`
- 修复脚本中的乱码分隔注释，保持 ASCII 或正确 UTF-8。
- `RELEASE_GATE_RESULT.md` 的 `Branch/Commit` 必须显示当前 HEAD，不允许停留在旧提交 `47cfe95`。

### Acceptance Criteria
- 手工执行 `powershell -NoProfile -File scripts/release-gate.ps1 -SkipPgAcceptance`：
  - Gate 1 真正运行 `mvn test`，tests > 0。
  - Gate 2 真正运行 `mvn flyway:validate`。
  - Gate 3 因 skip 标记 FAIL，overall FAIL。
  - 不再出现 `NoGoalSpecifiedException`。
- release gate 的命令显示和 Maven 实际收到的参数一致。
- 生成新的 `RELEASE_GATE_RESULT.md`，不能复用旧结果。

### Suggested Tests
- 新增脚本 dry-run 模式：只打印 argv，不执行 Maven，用于验证参数拆分。
- `ReleaseGateVerifierTest` 保留，但不能替代真实脚本 smoke。

## 3. Agent B - P0 Close PostgreSQL Acceptance Evidence

### Goal
让 `mvn test -Ppostgresql-acceptance` 能真实跑 PostgreSQL，并留下可审计报告。

### Scope
- 明确 PG acceptance 策略，推荐：
  - 无 `PG_URL/PG_USERNAME/PG_PASSWORD` 时默认使用 Testcontainers。
  - 有 env 时使用外部 PostgreSQL。
- 修改 `scripts/release-gate.ps1` Gate 3：
  - 不要因为 env 缺失就直接失败，除非显式配置 `-RequireExternalPg`。
  - 默认运行 `mvn test -Ppostgresql-acceptance`，由 Testcontainers 提供 PG。
- 修改 `PostgreSqlTestContainerSupport`：
  - 不使用 `System.out.println` 输出 JDBC URL。
  - 使用 logger，并通过 sanitizer 只输出 db type/version 或 sanitized host。
  - 不把 password 写入 system property 名称以外的日志/报告。
- 修正 `PostgreSqlIntegrationTest` Javadoc：说明 Testcontainers 默认策略和 env override。
- 确保 PG profile 下 surefire 只包含 PG acceptance，或至少 Gate 3 只根据 PG-specific XML 判断。

### Acceptance Criteria
- `mvn test -Ppostgresql-acceptance` 生成：
  - `target/surefire-reports/TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml`
- PG XML 中 tests > 0、failures = 0、errors = 0、skipped = 0。
- 完成总结必须写明：PG 来源（Testcontainers 或 external）、PostgreSQL version、报告文件、tests/failures/errors/skipped。
- 无 Docker 且无 PG env 时必须 fail fast，并给出清晰原因，不允许 skip pass。

## 4. Agent C - P0 Release Gate PASS Result And CI Workflow

### Goal
把 release gate 从“脚本存在”推进到“CI 和本地都能产出可信 PASS/FAIL”。

### Scope
- 新增或修复 `.github/workflows/release-gate.yml`：
  - Java 17。
  - Maven cache。
  - Docker/Testcontainers 可用。
  - 执行 `mvn test`。
  - 执行 `mvn flyway:validate`。
  - 执行 `mvn test -Ppostgresql-acceptance`。
  - 执行 `scripts/release-gate.ps1` 或等价 Linux gate。
  - 上传 `target/surefire-reports` 和 `RELEASE_GATE_RESULT.md` artifact。
- 如果 GitHub Actions 不适合 PowerShell gate，补 `scripts/release-gate.sh`，逻辑必须与 PowerShell gate 等价。
- 更新 `RELEASE_READINESS_CHECKLIST.md`：以 CI artifact 为准，不再只靠人工复制测试数量。
- 更新 `CHANGELOG.md` 和 `README.md` 的 release gate 说明。

### Acceptance Criteria
- 本地 release gate 至少在 `-SkipPgAcceptance` 模式下证明 Gate1/Gate2 可执行。
- CI release gate 完整 PASS 或在无 Docker 环境明确 FAIL。
- `RELEASE_GATE_RESULT.md` overall PASS 时必须包含 PG-specific report evidence。
- 完成总结不能只写 `mvn test passed`，必须贴四个 gate 的结果表。

## 5. Agent D - P1 Health And Readiness Production Semantics

### Goal
让健康检查可以真正用于部署平台探活和发布回滚判断。

### Scope
- 将 health 拆为或明确支持：
  - `GET /api/health/live`：进程存活，轻量，不依赖 DB。
  - `GET /api/health/ready`：DB、Flyway、runtime registry 必须可用。
  - 保留 `GET /api/health` 兼容现有调用。
- readiness DOWN 时返回 HTTP 503，而不是只在 body 中写 `DOWN`。
- Flyway `PENDING/UNAVAILABLE` 是否算 DOWN 要明确。
- Runtime registry `EMPTY` 在首次安装和生产运行中的语义要区分。
- External datasource 默认不应拖垮主 readiness，除非 collection 依赖它；至少返回独立 component 状态。
- health response 不允许返回 URL、username、password、token、exception stack。

### Acceptance Criteria
- MockMvc 测试覆盖 200/503 状态码。
- 无认证访问策略明确，并有 SecurityConfig 测试。
- `BACKEND_OPERATION_GUIDE.md` 更新 Kubernetes/Docker health check 示例。

## 6. Agent E - P1 Close Audit Matrix Gaps

### Goal
把 `AUDIT_COVERAGE_MATRIX.md` 中的剩余 gap 收到 100%，或写出架构豁免理由。

### Scope
- 补齐或豁免以下 gap：
  - `UserManagementService.updateUserRoles` success/failure audit。
  - `DataSourceConfigService.testConnection` success audit。
  - `BootstrapController.setup` failure audit。
- 对 dynamic CRUD/association failure audit 增加更强测试：
  - ACL denied。
  - field permission denied。
  - scope denied。
  - SQL collection write rejection。
  - association cross-datasource rejection。
- 确保 failure audit details 不包含 raw SQL、record values、JDBC URL、username、password、token。
- 更新 `AUDIT_COVERAGE_MATRIX.md` summary。

### Acceptance Criteria
- Matrix 中 success/failure audit 覆盖达到 100%，或每个非 100% 项都有明确 “N/A reason”。
- `AuditFailureIntegrationTest` 或新增测试覆盖新增路径。
- `mvn test` 仍 0 skipped。

## 7. Agent F - P1 Real Frontend HAR Evidence And Repo Hygiene

### Goal
证明 trace replay 来自真实前端行为，并整理仓库提交状态，避免本地 review 和远端代码不一致。

### Scope
- 产出真实前端 HAR 证据：
  - 保存脱敏后的 HAR 或转换后的 trace fixture。
  - 文档说明采集环境、前端版本、后端 base URL、脱敏规则。
  - 不提交 token/password/cookie 等敏感值。
- `HarToContractConverter` 增加脱敏规则测试：Authorization、Cookie、password、token、secret。
- 更新 `SYSTEM_MODULE_API_GAP_ANALYSIS.md`：区分 synthetic trace 和 real frontend trace。
- Repo hygiene：
  - 确认 `.gitignore` 排除 `target/`、`storage/`、本地 token、临时 trace 原始文件。
  - 将 Phase14/15/16 文档和代码变更整理为清晰 commit。
  - 如果网络可用，推送到 `https://github.com/seadogwang/nocobase-java`。
  - 如果网络不可用，在完成总结中给出可复制的 push 命令和当前 commit hash。

### Acceptance Criteria
- 无敏感 HAR 原始文件进入 git。
- `git status --short` 只剩预期文件或为空。
- `README.md`、`CHANGELOG.md` 同步到 Phase15/Phase16 状态。
- 完成总结说明是否已推送远端。

## 8. Completion Summary Requirements

Claude 完成后输出：`PHASE16_RELEASE_GATE_FINALIZATION_COMPLETION_SUMMARY.md`

必须包含：

- 每个 Agent 的完成状态：Done / Partial / Blocked。
- 修改文件列表。
- 实际执行命令和结果。
- `mvn test` surefire 汇总：tests/failures/errors/skipped。
- `mvn test -Ppostgresql-acceptance` 真实运行证据：PG 来源、PG version、报告文件、tests/failures/errors/skipped。
- `scripts/release-gate.ps1` 或 `.sh` 的完整 gate 表。
- `RELEASE_GATE_RESULT.md` 最终 overall 状态。
- health endpoint 状态码语义。
- audit matrix 是否 100%。
- 是否修改前端：必须为 No。
- 是否已提交/推送 GitHub。
- 仍存在的风险和下一批建议。

## 9. Do Not Do

- 不修改 NocoBase 前端源码。
- 不用旧 `target/surefire-reports` 证明新 gate 通过。
- 不把 `RELEASE_GATE_RESULT.md` 的 FAIL 当作发布通过。
- 不输出或提交真实 password、token、Cookie、Authorization header、JDBC URL。
- 不让 Testcontainers 在默认 `mvn test` 中运行；默认测试仍应排除 PG acceptance。
- 不绕过 `DynamicRepository` 做数据权限相关读写。
