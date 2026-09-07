# NocoBase Java 后端 - Phase6 Review Fix 与系统必要模块开发任务

> 日期: 2026-09-02  
> 范围: 仅 Java 后端、测试、后端文档；前端界面和前端代码保持不变。  
> 基线验证: `mvn -q clean test` 通过，Surefire 汇总 `672 tests, 0 failures, 0 errors, 22 skipped`。  
> 背景: Data Layer Phase5 P1 Review Fix 与 Phase6 已完成。数据层核心进入可继续扩展状态，但仍有若干生产风险和必要插件模块缺口。

## 一、Review 结论

### 可接受项

- `index sync fail-fast` 已接入 `loadAll()` / `reload()`，索引解析或同步失败时集合不会进入 runtime registry。
- `IndexDefinition.parse()` 已收紧 collection-level index 校验，并把 field name 映射到 effective column name。
- `SchemaPlan.diff()` 已通过 `DialectAdapter` 检查 table/column/index，不再在 `DdlSynchronizer` 中硬编码 H2 information schema。
- `FieldOptions.default` 已从 raw SQL string 改为 `DefaultValue` 模型，DDL 生成通过 dialect 格式化。
- relation key 类型校验已补齐 belongsTo FK 与 belongsToMany through FK/otherKey。
- API compatibility tests 已扩展，当前没有前端文件变更。

### 仍需修正

- `FieldOptions.DefaultValue.parse()` 对 literal string 过度拦截：包含单引号、双引号、分号或 SQL 关键字的普通字符串默认值会被拒绝。安全边界应靠 literal escaping，而不是把所有标点都判定为 SQL 注入。
- `DefaultValue.toString()` 与错误信息会输出原始 default value；如果默认值包含业务敏感内容，会进入日志或错误链。
- PostgreSQL 验收测试仍有多处直接 `JdbcTemplate.execute("CREATE TABLE...")` / `CREATE INDEX...`，没有完整覆盖 `CollectionMetadataService -> DdlSynchronizer -> Runtime -> DynamicRepository` 真实后端链路。
- PostgreSQL 测试启动日志输出 `PG_URL`，连接串可能包含用户名、密码或内网地址，必须脱敏。
- 系统必要插件目前只是种子数据中的 enabled 列表，缺少模块化后端契约；进入真实前端联调时，权限、用户、插件、UI Schema、系统设置会成为阻塞点。

## 二、下一批并行任务

### P0-A: 默认值模型返工与脱敏

负责人: Claude-A  
可并行: 是  
依赖: 无

目标:
- literal 默认值允许正常业务字符串，例如 `it's ok`、`A;B`、`select plan`。
- 只有 expression 才需要白名单；literal 一律由 dialect 做转义和类型格式化。
- 错误信息、`toString()`、日志不得输出原始 default value。

修改点:
- `src/main/java/com/nocobase/field/FieldOptions.java`
- `src/main/java/com/nocobase/field/FieldOptionsParser.java`
- `src/main/java/com/nocobase/ddl/DialectAdapter.java`
- `src/test/java/com/nocobase/DefaultValueAndDialectDiffTest.java`

验收标准:
- JSON 配置 `{"default": "it's ok"}` 生成 `DEFAULT 'it''s ok'`。
- JSON 配置 `{"default": "A;B"}` 作为字符串 literal 成功，不执行额外 SQL。
- `{"default": {"expression": "CURRENT_TIMESTAMP"}}` 或等价明确表达式格式通过。
- 未白名单表达式如 `RANDOM()` 失败。
- `DefaultValue.toString()` 不包含真实 value。

### P0-B: PostgreSQL 验收改为真实后端链路

负责人: Claude-B  
可并行: 是  
依赖: 无

目标:
- PostgreSQL 验收不只验证数据库能执行 SQL，而是验证 Java 后端抽象链路。
- 测试日志不泄漏连接串。

修改点:
- `src/test/java/com/nocobase/postgresql/PostgreSqlIntegrationTest.java`
- `src/test/resources/application-postgresql.yml` 或现有 PostgreSQL profile 配置
- 必要时新增 PostgreSQL 专用 test helper

验收标准:
- 不再打印 raw `PG_URL`，只打印脱敏后的 host/db 或 `SqlErrorSanitizer` 结果。
- physical collection 创建、字段新增、默认值、索引同步必须通过 `CollectionMetadataService` 或 `DdlSynchronizer` 链路完成。
- SQL collection list/get/scope 必须通过 `DynamicRepository` 或 controller API 链路验证。
- 保留无环境变量时 skip，但报告要明确 `PostgreSQL tests skipped`。

### P0-C: 插件模块注册表后端契约

负责人: Claude-C  
可并行: 是  
依赖: 无

目标:
- 把 NocoBase 必要插件从“种子列表”升级为后端模块注册表。
- 不实现前端 UI，只保证现有前端请求能拿到稳定插件元信息和状态。

建议模块:
- `PluginModuleRegistry`
- `PluginModuleDefinition`
- `PluginModuleInitializer`

必须内置模块:
- `users`
- `auth`
- `acl`
- `collection-manager`
- `data-source-main`
- `ui-schema-storage`
- `system-settings`
- `application-plugins`

验收标准:
- `/api/applicationPlugins:listEnabled` 返回字段兼容现有前端，包含 name/packageName/enabled/installed/builtIn/version。
- 禁用内置必要模块时返回 400/403，不允许破坏系统。
- 启动时 registry 与数据库 `application_plugins` 自动校准，幂等。
- 不新增前端文件。

### P1-D: 用户与角色模块 API 补齐

负责人: Claude-D  
可并行: 是  
依赖: P0-C 建议先完成

目标:
- 用户、角色、用户角色关系作为系统必要模块提供后端 API。
- 后续 ACL 管理页面和用户管理页面可以直接接入。

接口范围:
- `users:list/get/create/update/destroy`
- `roles:list/get/create/update/destroy`
- 用户分配角色、移除角色、查询当前用户角色

设计要求:
- 用户数据访问仍走 `DynamicRepository` 或明确的系统服务边界；不能绕开权限语义造成公开 CRUD 漏洞。
- 密码永远不返回给前端。
- root/admin/member 角色保护策略清晰。

验收标准:
- admin 可管理用户和角色。
- 普通用户只能读取自身必要信息。
- 禁止删除最后一个 admin/root 可用账号。
- API response shape 与 NocoBase 前端习惯一致。

### P1-E: ACL 管理模块 API 补齐

负责人: Claude-E  
可并行: 是  
依赖: P1-D

目标:
- 把已有 `AclService`、`AclFilterInjector` 的底层能力暴露为管理 API。
- 支持角色资源、action、scope、字段权限配置。

接口范围:
- role resource list/get/create/update/destroy
- action 权限配置
- scope filter 配置
- readable/writable field 配置

验收标准:
- 配置后立即影响 `DynamicRepository` 的 list/get/create/update/destroy。
- scope JSON 错误 fail-fast，不进入半生效状态。
- 字段权限分别验证 readable 和 writable。
- association 内部语义保持不变，不因为管理 API 改造而回退。

### P1-F: UI Schema Storage 模块后端补齐

负责人: Claude-F  
可并行: 是  
依赖: P0-C 可并行，注意接口兼容

目标:
- 补齐前端 schema designer 依赖的 `uiSchemas` 后端能力。
- 保持树结构、insertAdjacent、patch、remove 等行为稳定。

验收标准:
- 支持按 uid/schemaUid 查询树。
- 支持 beforeBegin/afterBegin/beforeEnd/afterEnd 插入语义。
- patch schema 时保留未知 JSON 字段。
- 删除节点时子树删除事务一致。
- 响应结构与现有 `UiSchemaController` API compatibility test 锁定。

### P1-G: System Settings 模块后端补齐

负责人: Claude-G  
可并行: 是  
依赖: P0-C 可并行

目标:
- 系统设置从 key/value demo 提升为可扩展模块配置。
- 支持基础应用标题、logo、locale、theme、storage 等配置读取/更新。

验收标准:
- `/api/systemSettings:get` 返回前端可直接消费的 object。
- `/api/systemSettings:update` 支持局部更新并保持未知字段。
- 系统敏感配置不可通过该接口泄漏。
- 更新后可立即读取，事务一致。

### P2-H: Collection Manager API 兼容增强

负责人: Claude-H  
可并行: 是  
依赖: P0-A/P0-B 建议先完成

目标:
- 让后端 collection/field 管理更接近 NocoBase 前端真实调用。

范围:
- 创建 physical/view/sql collection 的完整 options 保存。
- 字段 options、uiSchema、interface、sort、target/sourceKey/targetKey/foreignKey/through/otherKey 全量保存。
- schema dry-run endpoint，可返回将执行的 DDL plan 摘要，不返回完整 SQL。

验收标准:
- 创建 collection 时不丢 `options`、`schema`、`filterTargetKey` 等未知扩展字段。
- 创建 field 时不丢 `options` 和 relation keys。
- view/sql collection 只保存 metadata，不执行物理 DDL。
- dry-run 响应只暴露 action/table/column/index 摘要，避免 SQL 泄漏。

### P2-I: 后端架构边界测试扩展

负责人: Claude-I  
可并行: 是  
依赖: 可最后整合

目标:
- 防止后续模块开发把 SQL、权限和 DDL 再次写散。

新增边界:
- Controller 不得直接注入 `JdbcTemplate`。
- 普通业务 Controller 不得直接注入 JPA repository，必须走 service 或 `DynamicRepository`。
- 只有 `DdlSynchronizer` / dialect adapter 可生成 DDL。
- 只有 `DynamicRepository`、SQL executor、DDL 层允许执行 SQL。
- 错误处理不得直接拼接未脱敏 `e.getMessage()` 给 client。

验收标准:
- 架构测试能扫描 `src/main/java/com/nocobase`。
- 新增违规代码时测试失败。
- 对必要例外建立明确白名单，白名单必须有注释说明。

## 三、统一开发约束

- 前端不改，接口结构必须兼容现有 NocoBase 前端。
- 所有公开数据 CRUD 继续走 `DynamicRepository`。
- 系统管理模块可以有 service，但不能绕过既定 ACL/scope/字段权限语义。
- 动态 SQL 值参数化，identifier 必须校验与 quote。
- DDL 只能由统一 DDL 层执行。
- 日志和错误响应不得泄漏完整 SQL、连接串、密码、token、默认值原文。
- 完成后输出 `PHASE6_REVIEW_FIX_AND_SYSTEM_MODULES_COMPLETION_SUMMARY.md`，列出任务状态、修改文件、测试命令和结果。

## 四、建议开发顺序

1. Claude-A、Claude-B、Claude-C 先并行做 P0。
2. Claude-D、Claude-E 处理用户/角色/ACL 管理模块。
3. Claude-F、Claude-G 同步补 UI Schema 和 System Settings。
4. Claude-H 做 collection manager 兼容增强。
5. Claude-I 最后补架构边界测试并全量回归。
