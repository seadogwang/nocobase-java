# NocoBase Java 后端 - Phase9 Review Fix 与 Phase10 开发任务

> 日期: 2026-09-02  
> 范围: 只改 Java 后端、测试、后端文档；前端界面和前端代码保持不变。  
> Review 对象: `PHASE9_PRODUCTION_AND_FRONTEND_CONTRACT_COMPLETION_SUMMARY.md`  
> 本地验证: Surefire 汇总 `773 tests, 1 failure, 0 errors, 0 skipped`。  
> 下一份完成总结: `PHASE10_RELEASE_HARDENING_COMPLETION_SUMMARY.md`

## 一、Review 结论

### 可以接受

- Phase9 已完成生产配置 guard、Flyway 元数据迁移、系统模块服务边界、外部数据源管理、前端合同样本、Auth 扩展和运行手册。
- collection/field/plugin/system module 的公开写入口比 Phase8 更稳，主要 controller 已开始收敛到 service。
- `SystemSettings.valueType` 已加入，能避免新写入值靠字符串猜测类型。
- 前端仍未改动，后端继续兼容 `resource:action` 与 `/resource/action` 两套路由。

### 必须继续修正

- 当前测试报告不是全绿：`SqlDataSourceResolverIntegrationTest#mainDbMetadataUnaffected` 失败，原因是测试仍假设主库至少有一个用户，但生产初始化已不再默认创建用户。
- 完成总结写了 `1 pre-existing failure` 但同时写 `BUILD SUCCESS`，后续完成标准必须改为零失败，不能把失败归类后继续交付。
- `application.yml` 仍保留默认 `nocobase.jwt.secret`，虽然 guard 会拒绝，但更好的生产默认是空值或环境变量占位。
- JWT 环境变量文档和代码注释写成 `NOCOSBASE_JWT_SECRET`，疑似拼写错误，应统一为 `NOCOBASE_JWT_SECRET` 或明确项目约定。
- `ProductionConfigGuard` 在 `ApplicationReadyEvent` 执行，严格意义上不是启动早期 fail-fast；部分组件已初始化完成后才失败。
- 外部数据源 `ds_password` 文档写“encrypted/hashed”，但实现仍明文持久化；外部数据源密码必须加密保存，不能 hash，因为运行时还需要解密连接。
- `DataSourceConfigService.testConnection()` 直接接受任意 JDBC URL 和 `driverClassName`，存在连接探测和驱动滥用风险，需要 URL/driver allowlist。
- `DataSourceConfigService.update()` 仍直接 `(Boolean) body.get("enabled")`，前端传 `"true"`、`1` 时可能 500。
- `AuthController` 仍直接注入 `UserRepository`，架构测试对白名单放行；认证模块也应该进入 service 边界。
- Flyway V1 是 baseline 快照，但缺少若干数据库级唯一约束和一致性约束，例如 `ui_schemas.uid`、`user_roles(user_id,role_id)`、`role_resources(role_name,resource_name)`、`role_resource_actions(role_resource_id,action)`。
- `DataInitializer` 仍维护一份系统插件列表，没有复用 `PluginModuleRegistry.BUILT_IN_PLUGINS`，后续容易漂移。
- 真实前端合同目前仍是手写 JSON 样本，不是从实际前端运行请求中采集的 trace。

## 二、下一批并行任务

### P0-A: 修复测试失败并恢复零失败基线

负责人: Claude-A  
可并行: 是  
依赖: 无

目标:
- 修复 `SqlDataSourceResolverIntegrationTest#mainDbMetadataUnaffected`。
- 后续所有完成总结必须以 `0 failures, 0 errors` 作为硬门槛。

修改范围:
- `src/test/java/com/nocobase/sql/SqlDataSourceResolverIntegrationTest.java`
- 必要时调整 test profile 初始化逻辑
- 完成总结模板或运行手册

验收标准:
- `mvn test` 实际报告为 `0 failures, 0 errors`。
- `mainDbMetadataUnaffected` 不再假设生产默认用户存在；如果测试需要用户，必须在测试内显式创建 fixture。
- 测试仍验证外部数据源不会影响主库 metadata/runtime。
- 完成总结不得写“pre-existing failure 但 BUILD SUCCESS”。

### P0-B: 外部数据源密钥加密落库

负责人: Claude-B  
可并行: 是  
依赖: P0-C 需要对齐密钥配置规范

目标:
- `external_data_sources.ds_password` 加密保存，运行时按需解密使用。
- 密钥管理独立于 JWT secret，不混用。

修改范围:
- `src/main/java/com/nocobase/entity/DataSourceConfigEntity.java`
- `src/main/java/com/nocobase/service/DataSourceConfigService.java`
- `src/main/java/com/nocobase/sql/SqlDataSourceResolver.java`
- `src/main/resources/db/migration/`
- 新增加密配置/工具类和测试

验收标准:
- 新建/更新外部数据源时，数据库中不能出现明文密码。
- 运行时能解密密码并成功连接。
- 响应和日志永远不返回密码、密文、密钥。
- 缺少加密 master key 时，非 test profile fail-fast。
- 兼容旧明文记录：可迁移、可读取后重写为密文，或明确 fail-fast 并给出迁移说明。

### P0-C: JWT 与密钥配置规范统一

负责人: Claude-C  
可并行: 是  
依赖: 无

目标:
- 移除默认生产 JWT secret。
- 修正 `NOCOSBASE_JWT_SECRET` 拼写问题，统一环境变量命名。
- 将生产配置校验提前到应用启动早期。

修改范围:
- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- `src/test/resources/application.yml`
- `src/main/java/com/nocobase/security/JwtUtil.java`
- `src/main/java/com/nocobase/config/ProductionConfigGuard.java`
- `BACKEND_OPERATION_GUIDE.md`

验收标准:
- 文档、代码注释、配置示例统一使用 `NOCOBASE_JWT_SECRET`。
- 默认 `application.yml` 不再包含可用 JWT secret 原文，使用 `${NOCOBASE_JWT_SECRET:}` 或等价空默认。
- dev/test profile 有明确隔离的开发/测试 secret。
- 非 dev/test profile 缺 secret 或使用弱 secret 时，在 Web 服务 ready 前失败。
- 日志不输出 secret 内容。

### P0-D: 外部数据源 URL/Driver 安全白名单

负责人: Claude-D  
可并行: 是  
依赖: 无

目标:
- 防止 dataSources API 被用作任意 JDBC 连接探测或危险 H2 URL 执行入口。
- 统一 create/update/testConnection 的参数校验。

修改范围:
- `src/main/java/com/nocobase/service/DataSourceConfigService.java`
- `src/main/java/com/nocobase/config/NocobaseDataSourceProperties.java`
- `src/main/java/com/nocobase/controller/DataSourceController.java`
- data source API 安全测试

验收标准:
- `driverClassName` 只能来自支持 dialect 的白名单，不允许任意 `Class.forName()`。
- JDBC URL 只允许明确支持的前缀和安全参数；H2 URL 禁止 `INIT`、文件脚本等危险参数。
- `testConnection` 也必须执行同 create/update 一样的 URL/driver/dialect 校验。
- `enabled/readOnly` 支持 boolean/string/0/1 参数兼容，非法值返回 400，不抛 500。
- 连接失败响应脱敏，不包含完整 URL、用户名、密码或内网细节。

### P0-E: Auth 模块服务边界收敛

负责人: Claude-E  
可并行: 是  
依赖: P0-C 建议先合并配置规范

目标:
- `AuthController` 不再直接注入 `UserRepository`。
- 认证逻辑、token 刷新、当前用户读取进入 service。

修改范围:
- `src/main/java/com/nocobase/controller/AuthController.java`
- 新增 `src/main/java/com/nocobase/service/AuthService.java`
- `src/main/java/com/nocobase/security/JwtUtil.java`
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java`
- Auth API 合同测试

验收标准:
- `AuthController` 只依赖 `AuthService` 和必要 request/response 类型。
- `signIn/check/user/refresh/logout` 响应结构不变。
- token 缺失、过期、伪造、用户不存在时返回稳定 401。
- 架构测试移除 `AuthController` repository 白名单。
- 密码、hash、token 原文不进入错误响应或日志。

### P1-F: Flyway 约束与跨数据库验收

负责人: Claude-F  
可并行: 是  
依赖: P0-A 先恢复测试基线

目标:
- 把元数据表 migration 从“能建表”推进到“约束完整、H2/PostgreSQL 都可验收”。

修改范围:
- `src/main/resources/db/migration/`
- 元数据 migration 测试
- PostgreSQL 集成测试或 Testcontainers profile

验收标准:
- 增加数据库级约束：`ui_schemas.uid` 唯一、`user_roles(user_id,role_id)` 唯一、`role_resources(role_name,resource_name)` 唯一、`role_resource_actions(role_resource_id,action)` 唯一。
- 外键删除策略明确，不能因删除 role/resource 留孤儿数据。
- migration 在空 H2 和空 PostgreSQL 上均可成功执行。
- migration 失败时错误不泄漏敏感连接信息。
- 动态业务 collection 仍不进入 Flyway migration。

### P1-G: DataInitializer 复用系统 Registry

负责人: Claude-G  
可并行: 是  
依赖: 无

目标:
- 移除 DataInitializer 中重复维护的系统插件列表。
- 系统插件的 name/packageName/version/builtIn/enabled/installed 只以 `PluginModuleRegistry` 为权威来源。

修改范围:
- `src/main/java/com/nocobase/config/DataInitializer.java`
- `src/test/java/com/nocobase/config/TestDataInitializer.java`
- `src/main/java/com/nocobase/plugin/PluginModuleRegistry.java`
- 初始化测试

验收标准:
- DataInitializer 不再手写 core plugin name 数组。
- 启动时缺失/错误系统插件由 registry 统一校准。
- test profile 和非 test profile 行为一致，只在是否创建测试用户上不同。
- 插件元数据变更只需要改一处定义。

### P1-H: 真实前端请求 Trace 回放

负责人: Claude-H  
可并行: 是  
依赖: 无

目标:
- 从手写合同样本升级到可导入真实前端请求 trace 的回放测试。
- 仍不修改前端代码。

修改范围:
- `src/test/java/com/nocobase/ApiCompatibilityTest.java`
- `src/test/resources/frontend-contract/`
- 新增 trace schema 文档和回放 helper

验收标准:
- 支持从 JSON trace 导入 method/path/query/body/headers/expectedShape。
- trace 支持请求间变量提取，例如登录 token、创建后的 id/filterByTk。
- 至少覆盖登录、首页初始化、插件加载、系统设置、UI Schema、collection manager、基础 CRUD。
- 合同测试失败时能定位到 trace 文件、请求名称和缺失字段。
- 不依赖真实前端构建，不修改前端文件。

### P1-I: 外部数据源运行时一致性

负责人: Claude-I  
可并行: 是  
依赖: P0-B/P0-D 建议先合并

目标:
- dataSources API 修改后，SQL collection runtime 能正确刷新、缓存失效、失败隔离。

修改范围:
- `src/main/java/com/nocobase/service/DataSourceConfigService.java`
- `src/main/java/com/nocobase/sql/SqlDataSourceResolver.java`
- `src/main/java/com/nocobase/runtime/CollectionRuntimeService.java`
- data source runtime 集成测试

验收标准:
- update/delete data source 后，resolver 缓存立即失效。
- 禁用 data source 后，引用它的 SQL collection 返回稳定不可用错误。
- 重新启用 data source 后，SQL collection 可恢复。
- data source testConnection 不污染 resolver 正式缓存。
- 多线程 resolve/update/delete 不产生脏缓存。

### P2-J: 审计日志与管理操作记录

负责人: Claude-J  
可并行: 是  
依赖: P0/P1 可并行设计，最终集成

目标:
- 为系统管理操作增加后端审计记录，便于生产排查和安全追踪。

范围:
- collection/field create/update/destroy/dryRun
- plugin install/enable/disable/uninstall/remove
- users/roles/acl/systemSettings/uiSchemas/dataSources 写操作

验收标准:
- 审计记录包含 actor userId、action、resource、resourceKey、status、createdAt。
- 审计记录不保存 password、token、secret、完整 SQL、连接串、外部数据源密码。
- 审计写入失败不能导致主业务静默成功；策略必须明确。
- 提供 admin-only list API 或预留 service 查询接口。

### P2-K: Release Readiness 文档与检查脚本

负责人: Claude-K  
可并行: 是  
依赖: P0-C/P1-F 最终对齐

目标:
- 给后续上线验收建立固定检查清单，而不是只看测试数量。

修改范围:
- `BACKEND_OPERATION_GUIDE.md`
- 新增 `RELEASE_READINESS_CHECKLIST.md`
- 必要时新增轻量检查测试或脚本

验收标准:
- 清单覆盖配置、安全、migration、测试、PostgreSQL、前端合同、数据源、日志脱敏。
- 明确哪些命令必须在交付前运行。
- 明确 `0 failures, 0 errors, 0 skipped` 或可接受 skipped 的条件。
- 文档修正 `NOCOBASE_JWT_SECRET` 命名。

## 三、统一开发约束

- 前端不改，所有兼容问题必须由 Java 后端解决。
- 从 Phase10 开始，任何 `mvn test` 失败都不能标记为完成。
- 生产默认必须安全，开发便利只能放在 dev/test profile。
- 密码、token、JWT secret、外部数据源密码必须有明确密钥治理和日志脱敏策略。
- 元数据 schema 用 Flyway 管理；动态业务 collection 仍由 data layer DDL 管理。
- collection/field、plugin、system settings、ui schema、ACL、data sources 都是系统管理入口，不能无鉴权写入。
- 公开数据 CRUD 继续统一走 `DynamicRepository`；系统管理模块必须有清晰 service 边界。
- DDL 只能走统一 DDL/dialect 层，view/sql collection 不执行物理 DDL。
- SQL collection 继续只读，scope/filter/sort/page/count 必须在外层安全拼装并参数化。
- 完成后运行 `mvn test`，并在 `PHASE10_RELEASE_HARDENING_COMPLETION_SUMMARY.md` 中列出任务状态、变更文件、测试命令、测试结果、未完成项。

## 四、建议开发顺序

1. Claude-A 先修测试失败，恢复零失败基线。
2. Claude-B、Claude-C、Claude-D、Claude-E 并行处理密钥、数据源安全、Auth 服务边界。
3. Claude-F、Claude-G 处理 migration 约束和初始化 registry 去重。
4. Claude-H、Claude-I 做真实 trace 回放和数据源运行时一致性。
5. Claude-J、Claude-K 做审计与上线检查文档，作为 Phase10 收口。
