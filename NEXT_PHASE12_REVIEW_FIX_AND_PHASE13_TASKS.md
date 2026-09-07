# NocoBase Java 后端 - Phase12 Review Fix 与 Phase13 开发任务

> 日期: 2026-09-07  
> 范围: 只修改 Java 后端、测试、后端文档；前端界面和前端代码保持不变。  
> Review 对象: `PHASE12_RELEASE_GATE_AND_API_COVERAGE_COMPLETION_SUMMARY.md`  
> 本地核验: 默认 surefire 汇总为 `900 tests, 0 failures, 0 errors, 0 skipped`；默认测试中没有 PostgreSQL 报告文件。  
> 下一份完成总结: `PHASE13_RELEASE_GATE_FIX_COMPLETION_SUMMARY.md`

## 一、Review 结论

### 可以接受

- Phase12 默认测试基线继续全绿，当前 surefire 汇总为 `900 tests, 0 failures, 0 errors, 0 skipped`。
- `application-dev.yml` 的数据源加密 master key 已修复为 32 bytes。
- `ProductionConfigGuard` 和 `DataSourcePasswordEncryptor` 都已加入 known dev master key 拒绝逻辑，非 dev/test profile 会拒绝开发默认 key。
- `DataSourceConfigService#loadFromDatabase` 已从宽泛吞异常改为只允许“表不存在/首次启动”降级，其余异常 fail-fast。
- 审计日志脱敏已扩展到 `Map`、`List`、数组和字符串值，`AuditLogControllerTest` 也补了 admin/member/unauthenticated 权限测试。
- Data Source Main API 覆盖明显提升，`SYSTEM_MODULE_API_GAP_ANALYSIS.md` 从 85% 更新到 99%。

### 必须继续修正

- PostgreSQL 发布验收仍未真正闭环：默认 surefire 中没有 `PostgreSqlIntegrationTest` 报告文件；`@EnabledIfEnvironmentVariable(named = "PG_URL")` 会让缺少 `PG_URL` 时禁用整类，即使在 acceptance profile 下也可能出现“0 tests/disabled 但构建成功”的假阳性。
- `pom.xml` 中全局 surefire exclude `PostgreSqlIntegrationTest`，profile 中只配置 include，存在 include/exclude 合并后仍被排除的风险；必须用实际 profile 报告证明 PostgreSQL 测试数大于 0。
- `RequestIdFilter` 作为 bean 存在，但 `FilterRegistrationBean<RequestIdFilter>` 被 `setEnabled(false)`，并且没有加入 Spring Security filter chain；实际 HTTP 请求可能不会设置 `RequestIdContext`。
- 生产代码仍有 `System.out.println`：`SecurityConfig` 和 `JwtAuthenticationFilter`。其中 JWT filter 还输出 auth 状态，不能进入发布代码。
- `JwtAuthenticationFilter` 把 token 校验和 authorities 日志打在 `WARN`，会污染生产日志；应降到 `DEBUG` 并避免输出完整 authentication 对象。
- `DataSourceConfigService#toResponseMap` 仍返回 raw `username`，`url` 只遮蔽 embedded credentials，不遮蔽 host/db/path；这需要明确兼容策略和最小暴露边界。
- `SYSTEM_MODULE_API_GAP_ANALYSIS.md` 仍把 `uiSchemaTemplates:list/get` 标为 `SVC_ONLY (stub)`，但代码已有实体和 service 方法，文档和 API/trace 测试需要同步收口。
- `RELEASE_READINESS_CHECKLIST.md` 中 PostgreSQL 命令仍写 `mvn test -Dtest=PostgreSqlIntegrationTest`，没有使用 Phase12 新增的 `postgresql-acceptance` profile，发布门禁文档不一致。

## 二、下一批并行任务

### P0-A: 修复 PostgreSQL acceptance profile 假阳性

负责人: Claude-A  
可并行: 是  
依赖: 无  
目标:
- acceptance profile 下缺少 PostgreSQL 环境必须失败，不能 skip、disabled 或 0 tests 成功。
- 有 PostgreSQL 环境时必须真实运行验收测试并生成报告。

修改范围:
- `pom.xml`
- `src/test/java/com/nocobase/postgresql/PostgreSqlIntegrationTest.java`
- `RELEASE_READINESS_CHECKLIST.md`
- 必要时新增 release gate verifier 测试或脚本

验收标准:
- `mvn test` 默认不运行真实 PostgreSQL 测试，可以保持全绿。
- `mvn test -Ppostgresql-acceptance` 在缺少 `PG_URL/PG_USERNAME/PG_PASSWORD` 时必须失败，并明确提示缺少哪个变量。
- `mvn test -Ppostgresql-acceptance` 在提供 PostgreSQL 环境时，报告中 `PostgreSqlIntegrationTest` 的 tests 数必须大于 0。
- 不再使用 `@EnabledIfEnvironmentVariable` 让 acceptance profile 静默禁用整类；可改为 `@BeforeAll` 中按 `postgresql.acceptance` 做 fail-fast。
- 确认 profile 下 surefire include 能覆盖全局 exclude；必要时在 profile 中清空 excludes 或改用 failsafe plugin。
- 完成总结必须分别列出默认测试结果和 PostgreSQL acceptance 测试结果，不得把未运行的 PG 测试写成通过。

### P0-B: 修复 RequestIdFilter 实际执行链路

负责人: Claude-B  
可并行: 是  
依赖: 无  
目标:
- HTTP 请求进入后端时必须稳定设置 `RequestIdContext` 和 MDC，审计日志应复用同一个 requestId。

修改范围:
- `src/main/java/com/nocobase/config/SecurityConfig.java`
- `src/main/java/com/nocobase/web/RequestIdFilter.java`
- `src/main/java/com/nocobase/web/RequestIdContext.java`
- 审计日志/requestId API 测试

验收标准:
- `RequestIdFilter` 必须实际加入 servlet filter chain 或 Spring Security filter chain，不能只声明 disabled registration。
- 带 `X-Request-Id` 的请求产生审计日志时，audit_logs.request_id 必须等于 header 值。
- 同一个 HTTP 请求内多条审计记录使用同一个 requestId。
- 未带 header 时自动生成非空 requestId，并写入响应 header `X-Request-Id`。
- 请求结束后 ThreadLocal/MDC 必须清理，增加并发或连续请求测试证明不会串号。

### P0-C: 清理生产 stdout 与 JWT 日志泄露

负责人: Claude-C  
可并行: 是  
依赖: 无  
目标:
- 移除生产代码中的 stdout 调试输出，收紧认证日志级别和内容。

修改范围:
- `src/main/java/com/nocobase/config/SecurityConfig.java`
- `src/main/java/com/nocobase/security/JwtAuthenticationFilter.java`
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java`

验收标准:
- `src/main/java` 下不允许出现 `System.out.println`、`printStackTrace`。
- JWT filter 不记录 token 原文、不记录完整 authentication 对象、不在 `WARN` 打印每次请求。
- 认证成功/失败日志最多使用 `DEBUG`，且只记录 path、userId、角色数量等低敏信息。
- 增加架构测试扫描生产代码 stdout、printStackTrace、raw token 日志关键字。

### P0-D: 修正 Release Readiness 门禁文档和可执行命令

负责人: Claude-D  
可并行: 是  
依赖: P0-A 最终命令名  
目标:
- 发布清单中的命令必须和 `pom.xml` 实际 profile 一致，并且能防止“测试未运行但通过”的误判。

修改范围:
- `RELEASE_READINESS_CHECKLIST.md`
- 可选新增 `scripts/verify-release-gate.*`
- 完成总结模板或说明

验收标准:
- PostgreSQL 验收命令统一为 `mvn test -Ppostgresql-acceptance` 或最终确定的等价命令。
- 清单要求检查 surefire/failsafe 报告中 PG tests 数量大于 0。
- 敏感配置扫描命令在 Windows/PowerShell 和 Linux shell 至少给出一种可执行版本；不要只写不可直接运行的 grep 管道。
- `mvn flyway:validate` 的数据库目标和凭据来源要写清楚，不能默认误连开发 H2 当生产验证。
- 完成总结必须包含 release gate 表格: command、exit code、tests、failures、errors、skipped、是否必跑。

### P1-E: dataSources 响应最小暴露策略收口

负责人: Claude-E  
可并行: 是  
依赖: 无  
目标:
- 在不改前端的前提下，明确 raw `url`/`username` 的兼容边界，减少敏感信息暴露。

修改范围:
- `src/main/java/com/nocobase/service/DataSourceConfigService.java`
- `src/main/java/com/nocobase/controller/DataSourceController.java`
- dataSources API 测试
- `BACKEND_OPERATION_GUIDE.md`

验收标准:
- 响应中继续不返回 password、密文、master key、embedded credentials。
- `username` 是否保留 raw 值必须有明确兼容说明；若可以移除，改为只返回 `maskedUsername`。
- `url` 至少要支持更强脱敏模式，避免暴露内网 host、port、database name；如保留 raw `url`，必须标注为 admin-only 兼容字段。
- update/create/testConnection 的错误响应不得包含完整 URL、username、password、driver stack。
- trace/API 测试覆盖 `maskedUrl`、`maskedUsername`、`hasPassword` 和敏感字段不存在。

### P1-F: UI Schema Templates API 与 Gap 文档同步

负责人: Claude-F  
可并行: 是  
依赖: 无  
目标:
- 把 UI Schema Templates 从“文档里仍是 stub”同步为真实实现和 API 覆盖。

修改范围:
- `src/main/java/com/nocobase/controller/UiSchemaController.java`
- `src/main/java/com/nocobase/service/UiSchemaService.java`
- `src/main/java/com/nocobase/entity/UiSchemaTemplate.java`
- `src/test/java/com/nocobase/ApiCompatibilityTest.java`
- `src/test/resources/frontend-traces/trace.json`
- `SYSTEM_MODULE_API_GAP_ANALYSIS.md`

验收标准:
- `/api/uiSchemaTemplates:list` 和 `/api/uiSchemaTemplates:get` 有 API 级测试。
- list 支持空列表；get 不存在返回稳定 404；存在模板返回 `id/name/schema/createdAt/updatedAt`。
- 如果 NocoBase 前端需要 create/update/destroy/import/export，先在 Gap 文档列为后续任务，不要改前端。
- `SYSTEM_MODULE_API_GAP_ANALYSIS.md` 重新计算覆盖率，不得继续把已实现 endpoint 标为 stub。

### P1-G: 前端 Trace 从手写样例升级为可导入录制格式

负责人: Claude-G  
可并行: 是  
依赖: 无  
目标:
- 当前 trace 仍是手写 JSON；下一步要支持导入真实 HAR/recorded API trace，保持前端不改。

修改范围:
- `src/test/java/com/nocobase/ApiCompatibilityTest.java`
- `src/test/resources/frontend-traces/`
- trace schema 文档

验收标准:
- 保留现有 JSON trace，同时新增 HAR 或 recorded trace 转换/加载能力。
- 支持 header、query、body、response shape、变量提取、负向断言。
- 失败报告必须定位到 trace 文件、step name、method/path、缺失字段。
- 不依赖前端构建，不修改前端代码。

### P1-H: 完成总结准确性与证据约束

负责人: Claude-H  
可并行: 是  
依赖: 无  
目标:
- 防止后续总结继续出现“未运行验收被写成通过”的问题。

修改范围:
- 新增或更新完成总结模板
- `RELEASE_READINESS_CHECKLIST.md`
- 可选新增测试报告解析脚本

验收标准:
- 完成总结必须区分默认 `mvn test`、release gate、PostgreSQL acceptance、手工验证。
- 每个测试声明必须对应 surefire/failsafe 报告或明确的人工执行记录。
- 如果某类测试被 exclude/disabled/skipped，必须写入“未执行”，不能写入“通过”。
- 总结中必须列出未完成项和下一步风险。

## 三、统一开发约束

- 前端保持不变，所有兼容问题由 Java 后端解决。
- 公共 CRUD 继续统一走 `DynamicRepository`，ACL、scope、字段权限不能被 relation/association/data source 旁路绕过。
- SQL collection 保持只读，filter/sort/page/count/scope 必须在外层安全拼装并参数化。
- 生产代码不能包含 stdout 调试输出、token 原文日志、完整连接串日志、完整 SQL 错误回显。
- 发布验收不能只看测试总数；必须确认关键测试是否真实执行。
- 完成总结必须报告真实测试命令和真实报告，不允许把 skipped/disabled/0 tests 写成通过。

## 四、建议开发顺序

1. Claude-A 先修 PostgreSQL acceptance profile，因为这是发布门禁最大假阳性。
2. Claude-B 和 Claude-C 并行修 requestId 链路和生产日志清理。
3. Claude-D 对齐 Release Readiness 文档和可执行命令。
4. Claude-E、Claude-F 分别收口 dataSources 最小暴露和 UI Schema Templates gap。
5. Claude-G、Claude-H 做 trace 导入和总结证据约束，为下一轮真实联调做准备。
