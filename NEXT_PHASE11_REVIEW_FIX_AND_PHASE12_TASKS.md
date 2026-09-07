# NocoBase Java 后端 - Phase11 Review Fix 与 Phase12 开发任务

> 日期: 2026-09-04  
> 范围: 只修改 Java 后端、测试、后端文档；前端界面和前端代码保持不变。  
> Review 对象: `PHASE11_RELEASE_READINESS_COMPLETION_SUMMARY.md`  
> 本地核验: Surefire 汇总为 `799 tests, 0 failures, 0 errors, 0 skipped`，但 `PostgreSqlIntegrationTest` 实际为 `tests=0`。  
> 下一份完成总结: `PHASE12_RELEASE_GATE_AND_API_COVERAGE_COMPLETION_SUMMARY.md`

## 一、Review 结论

### 可以接受

- Phase11 已完成上一批任务的大部分目标，默认 `application.yml` 已移除硬编码的数据源加密 master key。
- `ProductionConfigGuard` 已检查 JWT、数据源加密 master key、H2 console、`ddl-auto`，并通过 `META-INF/spring.factories` 注册为早期监听器。
- 审计日志已具备 entity/repository/service/controller/migration，多个系统 service 已接入 `auditSuccess`。
- dataSources API 已增加 `maskedUrl`、`maskedUsername`、`hasPassword`，并支持 update 不传 password 时保留旧密码。
- `RELEASE_READINESS_CHECKLIST.md` 和 `SYSTEM_MODULE_API_GAP_ANALYSIS.md` 已生成，能支撑后续发布门禁和模块补齐。
- 默认测试报告当前为 `799 tests, 0 failures, 0 errors, 0 skipped`。

### 必须继续修正

- `application-dev.yml` 的 `nocobase.data-source-encryption.master-key` 解码后只有 31 字节，`DataSourcePasswordEncryptor` 要求 32 字节，dev profile 会启动失败。
- `ProductionConfigGuard.KNOWN_DEFAULT_MASTER_KEYS` 只包含旧 dev key，不包含当前 `application-dev.yml` 的 key；即使修成 32 字节，也要保证 dev 默认 key 不能在非 dev/test profile 使用。
- Phase11 总结声称 PostgreSQL 验收新增 8+ 测试，但 surefire 中 `TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml` 为 `tests="0"`；当前不能把 PostgreSQL 验收计入已通过。
- `DataSourceConfigService#loadFromDatabase` 对启动加载 catch 过宽，密钥错误、解密失败、配置非法可能被降级为 warning，生产会带病启动。
- `DataSourceConfigService#toResponseMap` 仍返回 raw `username`，`url` 也只遮蔽 embedded credentials，不遮蔽 host/db/path；这还没有真正完成敏感信息最小暴露。
- `AuditLogService#sanitizeDetails` 未处理 `List`/数组内嵌对象，也不会脱敏普通字符串值中的 JDBC URL、SQL、token；审计日志仍可能落敏感内容。
- 审计日志的 `requestId` 当前每条记录随机生成，不是来自同一个 HTTP 请求上下文，无法用于一次请求内的链路追踪。
- `SYSTEM_MODULE_API_GAP_ANALYSIS.md` 已明确 Data Source Main API 覆盖只有 14%，多个插件/collection/ui schema endpoint 仍是 `SVC_ONLY`。

## 二、下一批并行任务

### P0-A: 修复 dev master key 与密钥默认值测试

负责人: Claude-A  
可并行: 是  
依赖: 无  
目标:
- 修复 dev profile 启动失败风险。
- 确保所有配置文件中的 master key 都被真实校验，而不是只测硬编码常量。

修改范围:
- `src/main/resources/application-dev.yml`
- `src/main/resources/application.yml`
- `src/test/resources/application.yml`
- `src/main/java/com/nocobase/config/ProductionConfigGuard.java`
- `src/main/java/com/nocobase/service/DataSourcePasswordEncryptor.java`
- `src/test/java/com/nocobase/config/ProductionConfigGuardTest.java`

验收标准:
- `application-dev.yml` 的 master key 解码必须正好 32 bytes。
- `ProductionConfigGuard` 的 known dev master key 列表覆盖当前 dev 配置里的 key，非 dev/test profile 使用该 key 必须 fail-fast。
- 新增测试直接读取 `application-dev.yml` 中的 key 并验证长度、dev profile 可启动、非 dev profile 拒绝。
- `DataSourcePasswordEncryptor` 自身也应拒绝非 dev/test profile 使用 known dev key，不能只依赖 Guard。
- `mvn test -Dtest=ProductionConfigGuardTest,DataSourcePasswordEncryptorTest` 通过。

### P0-B: 修复 PostgreSQL 验收假阳性

负责人: Claude-B  
可并行: 是  
依赖: 无  
目标:
- 不能再出现“总结写 8+ PostgreSQL 测试通过，但 surefire 实际 tests=0”的情况。
- 区分默认单元测试与发布 PostgreSQL 验收，两者都要有清晰门禁。

修改范围:
- `src/test/java/com/nocobase/postgresql/PostgreSqlIntegrationTest.java`
- `pom.xml`
- `RELEASE_READINESS_CHECKLIST.md`
- 完成总结模板或说明

验收标准:
- 默认 `mvn test` 可以不跑真实 PostgreSQL，但完成总结不得把 `tests=0` 说成 PostgreSQL 已通过。
- 新增发布验收命令，例如 `mvn test -Ppostgresql-acceptance` 或等价 profile；该 profile 下缺少 `PG_URL/PG_USERNAME/PG_PASSWORD` 必须失败，而不是 skip/0 tests。
- PostgreSQL 环境存在时，必须实际运行 metadata migration、唯一索引、FK、SQL collection、错误脱敏验收。
- surefire/failsafe 报告中 PostgreSQL 测试数量必须大于 0 才能标记 P1-F 通过。
- `RELEASE_READINESS_CHECKLIST.md` 明确 PostgreSQL 发布验收为 release 必跑项。

### P0-C: 收紧启动加载异常与数据源密文生命周期

负责人: Claude-C  
可并行: 是  
依赖: P0-A 合并后对齐 master key 规则  
目标:
- 外部数据源加载不能吞掉生产级错误。
- 密文解密失败、非法配置、迁移失败必须有明确策略。

修改范围:
- `src/main/java/com/nocobase/service/DataSourceConfigService.java`
- `src/main/java/com/nocobase/service/DataSourcePasswordEncryptor.java`
- data source 启动加载相关测试

验收标准:
- 仅“表不存在/首次启动 migration 前”可以降级 warning；其他异常要 fail-fast 或标记 datasource unavailable，并有测试证明。
- 加密密码解密失败不能被当作“表未创建”吞掉。
- plaintext 迁移时日志只记录 datasource key，不输出密码或密文。
- `repository.saveAll(configs)` 的迁移数量日志必须准确，不应把所有 configs 数量当作 migrated 数量。
- 增加 legacy plaintext、bad ciphertext、wrong master key、invalid config 的启动加载测试。

### P0-D: 审计日志脱敏与请求链路修正

负责人: Claude-D  
可并行: 是  
依赖: 无  
目标:
- 审计日志可以进入生产排查链路，且不能成为敏感信息落库入口。

修改范围:
- `src/main/java/com/nocobase/service/AuditLogService.java`
- `src/main/java/com/nocobase/controller/AuditLogController.java`
- 必要时新增 request id filter/MDC/context helper
- 审计日志测试

验收标准:
- `sanitizeDetails` 递归处理 `Map`、`List`、数组、字符串值；字符串中的 JDBC URL、SQL 片段、token、password、secret 都要脱敏。
- `auditSuccess`/`auditFailure` 日志中的异常信息必须脱敏，不能直接输出 raw `e.getMessage()`。
- `requestId` 来自 HTTP header 或 request context，同一请求内多条审计记录使用同一个 requestId；没有请求上下文时再生成 fallback。
- `AuditLogController` 增加 API 级 admin/root 允许、普通用户拒绝、未登录拒绝测试。
- 审计写失败导致主业务回滚的策略要有集成测试证明。

### P1-E: dataSources API 级覆盖与响应最小暴露

负责人: Claude-E  
可并行: 是  
依赖: P0-C 合并后同步异常策略  
目标:
- 补齐 Data Source Main 只有 14% API 覆盖的问题。
- 明确 dataSources 响应里 raw url/raw username 的兼容和脱敏策略。

修改范围:
- `src/main/java/com/nocobase/service/DataSourceConfigService.java`
- `src/main/java/com/nocobase/controller/DataSourceController.java`
- `src/test/java/com/nocobase/ApiCompatibilityTest.java`
- `src/test/resources/frontend-traces/trace.json`
- `SYSTEM_MODULE_API_GAP_ANALYSIS.md`

验收标准:
- API 级测试覆盖 `/api/dataSources:list|get|create|update|destroy|testConnection`，冒号和斜杠路径至少各覆盖关键路径。
- 响应不返回 password、密文、master key、embedded credentials。
- raw `username` 是否保留必须有明确兼容说明；若保留，至少新增 `maskedUsername` 并在 trace 中断言敏感字段不存在。
- `url` 至少不能包含 embedded credentials；建议新增 `maskedUrl` 并优先在文档中声明 raw `url` 为兼容字段。
- 非 admin/root 对 dataSources 写接口返回 403。

### P1-F: 补齐 SVC_ONLY endpoint 的 API/Trace 覆盖

负责人: Claude-F  
可并行: 是  
依赖: 无  
目标:
- 把 `SYSTEM_MODULE_API_GAP_ANALYSIS.md` 中的 `SVC_ONLY` 端点推进到 API 级覆盖。

优先范围:
- Auth: `/api/auth:logout`
- Collection Manager: `/api/collections:dryRun`、`/api/fields:destroy`
- UI Schema: `/api/uiSchemas:getParentJsonSchema`
- Application Plugins: `/api/applicationPlugins:uninstall`、`/api/applicationPlugins:remove`
- Plugins: `/api/plugins:enable`、`/api/plugins:disable`、`/api/plugins:uninstall`

验收标准:
- 每个 endpoint 至少有一个成功 API 测试和一个权限/错误路径测试。
- 能进入前端启动路径或管理路径的 endpoint 加入 `frontend-traces/trace.json`。
- 更新 `SYSTEM_MODULE_API_GAP_ANALYSIS.md`，降低 `SVC_ONLY` 数量并重新计算覆盖率。
- 不修改前端。

### P1-G: UI Schema Templates 从 stub 走向兼容实现

负责人: Claude-G  
可并行: 是  
依赖: P1-F 可并行  
目标:
- 处理 Gap 文档中 UI schema templates 仍是 stub 的问题，避免前端后续使用时报假兼容。

修改范围:
- UI schema template controller/service/entity/repository/migration，如当前已有 stub 则在原模块内补齐
- API compatibility tests
- `SYSTEM_MODULE_API_GAP_ANALYSIS.md`

验收标准:
- `/api/uiSchemaTemplates:list` 返回真实模板列表，支持空列表。
- `/api/uiSchemaTemplates:get` 按 name/key 获取，不存在返回稳定 404。
- 如 NocoBase 前端还需要 create/update/destroy/import/export，先在 gap 文档列出，不要改前端。
- 模板 schema 内容不得绕过已有 UI schema 安全校验。

### P1-H: 发布门禁命令可执行化

负责人: Claude-H  
可并行: 是  
依赖: P0-B 最终命令命名  
目标:
- `RELEASE_READINESS_CHECKLIST.md` 不只是人工清单，要有可以执行和复用的命令。

修改范围:
- `pom.xml`
- `RELEASE_READINESS_CHECKLIST.md`
- 可选新增 `scripts/` 下后端检查脚本

验收标准:
- 清单里的每条命令在当前项目中真实可执行；例如如果写 `mvn flyway:migrate`，必须配置 Flyway Maven plugin，否则改成实际可用命令。
- 提供默认测试、PostgreSQL 验收、前端 trace replay、敏感配置扫描的命令。
- CI/人工执行时能清楚看到 pass/fail，不依赖阅读长日志。
- 完成总结必须列出每条门禁命令的执行结果。

## 三、统一开发约束

- 前端保持不变，所有兼容问题由 Java 后端解决。
- 公共 CRUD 继续统一走 `DynamicRepository`，ACL、scope、字段权限不能被 relation/association/data source 旁路绕过。
- SQL collection 保持只读，filter/sort/page/count/scope 必须在外层安全拼装并参数化。
- 生产默认配置必须安全，开发便利只能放在 dev/test profile。
- 所有敏感信息处理要覆盖响应、日志、审计、测试失败信息四个出口。
- 发布验收不能只看测试总数；需要确认关键验收项是否真实执行。

## 四、建议开发顺序

1. Claude-A 先修 dev key，因为它会影响本地启动和后续验证。
2. Claude-B 同步修 PostgreSQL 验收假阳性，这是发布门禁的最大偏差。
3. Claude-C、Claude-D 并行处理数据源启动加载和审计日志硬化。
4. Claude-E、Claude-F 补 API 级覆盖，把 gap 文档中最弱模块先收口。
5. Claude-G、Claude-H 处理 UI schema templates 和 release gate 可执行化，作为 Phase12 收口。
