# NocoBase Java 后端 - Phase8 Review Fix 与 Phase9 开发任务

> 日期: 2026-09-02  
> 范围: 只改 Java 后端、测试、后端文档；前端界面和前端代码保持不变。  
> Review 对象: `PHASE8_BACKEND_HARDENING_COMPLETION_SUMMARY.md`  
> 本地验证: Surefire 汇总 `763 tests, 0 failures, 0 errors, 0 skipped`。  
> 下一份完成总结: `PHASE9_PRODUCTION_AND_FRONTEND_CONTRACT_COMPLETION_SUMMARY.md`

## 一、Review 结论

### 可以接受

- Phase8 已把 collection/field 写接口从 `permitAll()` 收紧到认证后再由方法权限控制，旧 `PluginController` 也已委托 `PluginModuleRegistry`。
- 生产 `DataInitializer` 不再创建 `admin123`，测试账号已隔离到 `@Profile("test")`。
- ACL update 路径、System Settings 类型恢复、参数解析、UI Schema uid 唯一性、架构边界测试和 API 合同测试都有明显进展。
- 当前测试基线为 `763 tests, 0 failures, 0 errors, 0 skipped`，与完成总结一致。

### 必须继续修正

- `application.yml` 仍包含默认 `nocobase.jwt.secret`，生产环境如果未覆盖会直接使用弱默认密钥，必须 fail-fast。
- 默认配置仍开放 H2 console 和 `ddl-auto: update`，这不适合作为生产默认；元数据表应进入正式迁移体系。
- `CollectionController` 仍直接注入 `CollectionRepository`、`FieldRepository`、`DdlSynchronizer`，dry-run 也在 controller 内手写摘要，架构边界测试没有真正封住 controller repository 依赖。
- `SystemSettingsController`、`UiSchemaController`、`AclController` 仍有大量 repository 直写，短期可运行，但系统模块边界还不清晰。
- `DataInitializer` 的系统插件补齐仍直接写 `ApplicationPluginRepository`，没有复用 `PluginModuleRegistry` 的权威定义，存在元数据漂移风险。
- `SystemSettingsController` 通过字符串猜测 boolean/number 类型，会把原本业务字符串 `"00123"`、`"false"` 错读为 number/boolean；需要显式 value type。
- 目前 API 合同测试是后端自定义合同，尚未形成“真实 NocoBase 前端请求样本回放”机制。
- 生产部署还缺少 Flyway/Liquibase、启动健康检查、外部数据源连接治理、审计日志、并发 DDL/metadata 锁。

## 二、下一批并行任务

### P0-A: 生产配置 Fail-Fast

负责人: Claude-A  
可并行: 是  
依赖: 无

目标:
- 生产/default profile 不允许使用默认 JWT secret、H2 console、`ddl-auto: update`。
- 启动阶段对高危配置 fail-fast，避免“开发配置跑到生产”。

修改范围:
- `src/main/resources/application.yml`
- `src/main/java/com/nocobase/config/SecurityConfig.java`
- 新增 `ProductionConfigGuard` 或等价配置校验类
- 配置启动测试

验收标准:
- 未显式配置安全 JWT secret 时，非 test profile 启动失败并给出脱敏错误。
- test profile 可使用测试 secret，但只能在测试配置中出现。
- H2 console 只在 dev/test profile 开启。
- 非 test profile 不允许 `spring.jpa.hibernate.ddl-auto=update/create/create-drop`。
- 日志不输出 secret 原文。

### P0-B: 元数据表迁移体系

负责人: Claude-B  
可并行: 是  
依赖: P0-A 可并行

目标:
- 将系统元数据表从 Hibernate 自动更新推进到正式 migration 管理。
- 动态业务 collection 的 DDL 仍由 `CollectionMetadataService -> DdlSynchronizer` 管理，不混到元数据 migration。

修改范围:
- `pom.xml`
- `src/main/resources/db/migration/`
- `src/main/resources/application*.yml`
- 元数据 schema 初始化测试

验收标准:
- 引入 Flyway 或 Liquibase，优先 Flyway。
- 元数据表包括 users/roles/user_roles/collections/fields/application_plugins/system_settings/ui_schemas/acl 相关表。
- 默认 profile 禁用 Hibernate `ddl-auto` 自动建表，测试 profile 可选择 create-drop 但必须有说明。
- migration 可在空库完成初始化。
- 不为动态业务 collection 生成静态 migration。

### P0-C: Collection Manager 服务边界重构

负责人: Claude-C  
可并行: 是  
依赖: 无

目标:
- `CollectionController` 不再直接注入 repository 或 DDL 组件。
- create/destroy/addField/dropField/dryRun 全部委托服务层，controller 只做参数接收和响应包装。

修改范围:
- `src/main/java/com/nocobase/controller/CollectionController.java`
- `src/main/java/com/nocobase/service/CollectionMetadataService.java`
- 必要时新增 `CollectionManagerService`
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java`

验收标准:
- `CollectionController` 不再 import `CollectionRepository`、`FieldRepository`、`DdlSynchronizer`。
- dry-run 复用 `SchemaPlan` 或服务层统一 plan，不在 controller 手写 DDL 摘要逻辑。
- 权限检查保持 admin/root 可写、member/anonymous 拒绝。
- API 响应不变。
- 架构测试能阻止 controller 再次直接注入 repository/DDL。

### P0-D: 系统模块服务边界收敛

负责人: Claude-D  
可并行: 是  
依赖: 无

目标:
- 把 `SystemSettingsController`、`UiSchemaController`、`AclController` 的 repository 直写收敛到服务层。
- controller 只保留路由、权限、参数解析、响应包装。

修改范围:
- `src/main/java/com/nocobase/controller/SystemSettingsController.java`
- `src/main/java/com/nocobase/controller/UiSchemaController.java`
- `src/main/java/com/nocobase/controller/AclController.java`
- 新增或扩展 `SystemSettingsService`、`UiSchemaService`、`AclManagementService`
- 架构边界测试

验收标准:
- 三个 controller 不再直接注入 repository。
- 事务边界在 service 层。
- 现有 763 个测试保持通过。
- API 合同测试不需要调整前端请求。
- 架构测试允许的 controller repository 白名单为空，或只保留有明确说明的临时例外。

### P1-E: System Settings 显式类型存储

负责人: Claude-E  
可并行: 是  
依赖: P0-B 可并行，最终需对齐 migration

目标:
- 系统设置不再靠字符串内容猜类型。
- 保留旧数据兼容，同时为新写入数据记录明确 value type。

修改范围:
- `src/main/java/com/nocobase/entity/SystemSettings.java`
- `src/main/java/com/nocobase/controller/SystemSettingsController.java`
- `src/main/java/com/nocobase/service/SystemSettingsService.java`
- migration 或 test schema
- system settings 测试

验收标准:
- 新写入值记录 `valueType`，支持 string/boolean/number/json/null。
- `"00123"` 读回仍是 string，`"false"` 作为字符串写入时读回仍是 string。
- boolean/number/json 通过明确类型读回原类型。
- 旧 `settingValue` 无 type 数据继续兼容读取。
- 敏感 key 过滤继续生效。

### P1-F: 真实前端 API 请求样本回放

负责人: Claude-F  
可并行: 是  
依赖: 无

目标:
- 从“后端自定义合同测试”升级为“真实 NocoBase 前端请求样本回放”。
- 仍不修改前端代码。

修改范围:
- `src/test/java/com/nocobase/ApiCompatibilityTest.java`
- 新增 `src/test/resources/frontend-contract/*.json`
- 必要时新增合同回放 helper

验收标准:
- 合同样本以 JSON 固化：method/path/query/body/expectedStatus/expectedShape。
- 覆盖登录后首页启动所需接口、插件列表、系统设置、UI Schema 树、collection manager、CRUD 基础链路。
- 同一请求样本验证 colon 路由和 slash 路由。
- 响应只校验前端依赖字段，不强绑无关实现字段。
- 合同测试失败时能指出具体请求样本名称。

### P1-G: 外部数据源管理 API

负责人: Claude-G  
可并行: 是  
依赖: 无

目标:
- 将当前 yml 静态外部数据源能力推进为后端可管理模块，为 NocoBase data source manager 对齐做准备。
- 外部数据源默认只读，不能影响主数据源元数据和 DDL。

修改范围:
- `src/main/java/com/nocobase/sql/SqlDataSourceResolver.java`
- `src/main/java/com/nocobase/config/NocobaseDataSourceProperties.java`
- 新增 data source entity/repository/service/controller
- 数据源 API 与 SQL collection 测试

验收标准:
- 支持 list/get/create/update/delete/testConnection。
- secret/password 不返回给前端，日志脱敏。
- dataSourceKey 校验复用现有规则。
- 外部数据源只允许 SQL collection read-only 查询。
- 不允许在外部数据源执行动态 DDL 或业务写入。

### P1-H: Metadata 与 DDL 并发锁

负责人: Claude-H  
可并行: 是  
依赖: P0-C 建议先合并

目标:
- 防止并发创建 collection/field、reload runtime、index sync 时出现元数据和物理表不一致。

修改范围:
- `src/main/java/com/nocobase/service/CollectionMetadataService.java`
- `src/main/java/com/nocobase/runtime/CollectionRuntimeService.java`
- `src/main/java/com/nocobase/ddl/DdlSynchronizer.java`
- 并发测试

验收标准:
- 同名 collection 并发创建只有一个成功，其余返回稳定 400/409。
- 同 field 并发 add/drop 不会导致 runtime registry 与数据库表不一致。
- DDL 执行与 runtime reload 顺序明确。
- PostgreSQL 下优先使用数据库锁或事务锁；H2 下有测试替代实现。

### P2-I: Auth 模块兼容补齐

负责人: Claude-I  
可并行: 是  
依赖: P0-A 建议先合并

目标:
- auth 从最小 signIn/check 扩展到 NocoBase 前端常用认证合同。
- 不引入前端改动。

修改范围:
- `src/main/java/com/nocobase/controller/AuthController.java`
- `src/main/java/com/nocobase/security/JwtUtil.java`
- 用户服务相关类
- Auth API 合同测试

验收标准:
- `auth:signIn`、`auth:check` 保持兼容。
- 补齐当前前端启动或账号页需要的 current user/session/logout/refresh 能力；若暂不实现，必须用测试固定稳定响应。
- token 过期、无效 token、缺 token 响应稳定。
- JWT secret 来自安全配置，不接受默认生产 secret。
- 所有认证错误不泄漏 token 原文。

### P2-J: 后端运行手册与风险清单

负责人: Claude-J  
可并行: 是  
依赖: P0-A/P0-B 最终对齐

目标:
- 给后续 Claude 和人工部署提供明确运行、测试、配置、风险边界文档。

修改范围:
- `README.md` 或新增 `BACKEND_OPERATION_GUIDE.md`
- `SQL_QUERY_COLLECTION_DESIGN.md` 如需补充
- 当前任务/完成总结模板

验收标准:
- 文档说明 dev/test/prod profile 差异。
- 文档说明主数据源、外部数据源、SQL collection、动态 DDL 的边界。
- 文档列出必须配置的环境变量和禁止使用的默认配置。
- 文档给出 `mvn test`、PostgreSQL 集成测试、启动本地后端的命令。
- 文档明确前端不改，后端需兼容的 API 规则。

## 三、统一开发约束

- 前端不改，所有兼容问题必须由 Java 后端解决。
- 生产默认必须安全，开发便利只能放在 dev/test profile。
- 元数据 schema 用 migration 管理；动态业务 collection 仍由 data layer DDL 管理。
- collection/field、plugin、system settings、ui schema、ACL 都是系统管理入口，不能无鉴权写入。
- 公开数据 CRUD 继续统一走 `DynamicRepository`；系统管理模块必须有清晰 service 边界。
- DDL 只能走统一 DDL/dialect 层，view/sql collection 不执行物理 DDL。
- SQL collection 继续只读，scope/filter/sort/page/count 必须在外层安全拼装并参数化。
- 日志和错误响应不得泄漏完整 SQL、连接串、密码、token、密钥、默认值原文。
- 完成后运行 `mvn test`，并在 `PHASE9_PRODUCTION_AND_FRONTEND_CONTRACT_COMPLETION_SUMMARY.md` 中列出任务状态、变更文件、测试命令、测试结果、未完成项。

## 四、建议开发顺序

1. Claude-A、Claude-B、Claude-C、Claude-D 并行处理 P0。
2. Claude-E 与 P0-B 对齐 system settings schema；Claude-F 同步做真实前端合同回放。
3. Claude-G、Claude-H 做外部数据源和并发锁，注意不要影响 P0 合并。
4. Claude-I、Claude-J 做认证兼容和运行文档，作为 Phase9 收口。
