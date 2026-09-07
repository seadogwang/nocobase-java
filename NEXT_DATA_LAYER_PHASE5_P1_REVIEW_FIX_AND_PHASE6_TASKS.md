# NocoBase Java 后端 - Data Layer Phase5 P1 Review Fix 与 Phase6 任务清单

> 日期: 2026-09-01  
> 范围: 仅 Java 后端，不修改前端，不改变现有 NocoBase 前端依赖的 API 响应结构。  
> 触发原因: P1-F/G/H/I 已提交后架构复核。`mvn -q clean test` 本地验证通过，但仍发现若干生产链路语义问题需要在进入更大插件改造前修正。

## 一、Review 结论

### 已通过

- P0-A: `belongsToMany` through 查询截断已修复，`listLinks()` 已改为无分页查询计划。
- P0-B: `DynamicRepository` 主库 SQL builder 不再硬编码 `H2SqlDialect`，已通过 `SqlDataSourceResolver.resolveDialect("main")` 获取。
- P0-D: `SqlPlan.toString()` 不再输出完整 SQL，`dropCollection()` 已走 `DdlPlan.dropTable()`。
- P0-E: `CollectionManagerService` 已删除，第二套 DDL 通道被移除。
- P1-G: `FieldOptionsParser` 已接入 `createCollection()` / `addField()` DDL，支持 `length`、`precision`、`scale`、`nullable`、`default`。
- P1-I: `boolean` 已从 number 类别移除，基础类型兼容测试已覆盖。

### 必须返工

- P1-F 仍未达到 fail-fast 语义: `CollectionRuntimeService.syncIndexesForEntity()` 捕获所有异常并只打 warn，测试 `indexSyncFailureDoesNotPreventLoading()` 明确断言 reload 成功。这与本轮要求的 `IndexDefinition.parse fail-fast` 相反。
- P1-F collection-level index 配置仍会静默跳过错误: 缺少 `name`、缺少 `fields`、字段不存在、字段为 relation/virtual 时没有明确失败。
- P1-F collection-level `indexes[].fields` 目前按传入值直接当列名使用，未从 field name 映射到 `FieldEntity.getEffectiveColumnName()`，遇到自定义 columnName 会建错索引。
- P1-H `DdlSynchronizer.diff()` 的 `tableExists()` / `columnExists()` 仍直接查询 H2 `INFORMATION_SCHEMA`，不是跨数据库生产实现，PostgreSQL 主库下 dry-run 会不可靠。
- P1-G `default` 直接拼入 DDL: `DdlPlan.ColumnDef.toSql()` 使用 `DEFAULT ` + raw string。后续默认值必须区分 literal 与 DB expression，并做类型/方言校验。

## 二、并行任务分组

### P0-A: 修正 index sync fail-fast 语义

负责人: Claude-A  
可并行: 是  
依赖: 无

目标:
- `reload(collectionName)` 对物理集合的 index parse/sync 失败必须失败，并进入 `invalidCollections`。
- `loadAll()` 可继续跳过坏集合，但该集合不能进入 runtime registry，且 `invalidCollections` 要记录脱敏原因。
- view/sql collection 的 index 仍保持 metadata-only，不执行物理 DDL，但 metadata 配置解析错误仍应按坏 metadata 处理，不应静默成功。

修改点:
- `src/main/java/com/nocobase/runtime/CollectionRuntimeService.java`
- `src/test/java/com/nocobase/runtime/CollectionRuntimeServiceTest.java`
- 必要时新增独立 `IndexSyncRuntimeTest`

验收标准:
- 删除或改写 `indexSyncFailureDoesNotPreventLoading()`，改为 `reloadFailsOnInvalidIndexMetadata()`。
- 新增 `loadAllSkipsCollectionWithInvalidIndexMetadata()`，断言坏集合不在 registry，且 `invalidCollections` 有记录。
- `mvn -q test` 通过。

### P0-B: 收紧 IndexDefinition.parse 校验

负责人: Claude-B  
可并行: 是  
依赖: 无

目标:
- field-level 和 collection-level index metadata 都必须 fail-fast。
- collection-level `indexes[].fields` 表示字段名，不是裸列名；必须解析到有效物理列名。

修改点:
- `src/main/java/com/nocobase/runtime/IndexDefinition.java`
- `src/test/java/com/nocobase/runtime/IndexDefinitionTest.java`

验收标准:
- `indexes` 不是数组时报错。
- index item 不是对象时报错。
- 缺 `name`、缺 `fields`、`fields` 为空、字段不存在、字段为 relation/virtual 均报错。
- 自定义 `columnName` 字段生成索引时使用 effective column name。
- index name、table name、column name 错误信息脱敏，不输出完整 SQL 或参数值。

### P0-C: 方言化 SchemaPlan diff

负责人: Claude-C  
可并行: 是  
依赖: 无

目标:
- `DdlSynchronizer.diff()` 不再硬编码 H2 `INFORMATION_SCHEMA.TABLES/COLUMNS`。
- 表、列、索引存在性检查全部通过 `DialectAdapter`。

修改点:
- `src/main/java/com/nocobase/ddl/DialectAdapter.java`
- `src/main/java/com/nocobase/ddl/H2DialectAdapter.java`
- `src/main/java/com/nocobase/ddl/PostgresDialectAdapter.java`
- `src/main/java/com/nocobase/ddl/DdlSynchronizer.java`
- `src/test/java/com/nocobase/ddl/SchemaPlanTest.java` 或新增 `DdlSynchronizerDiffTest`

验收标准:
- `DdlSynchronizer` 中不再出现 `INFORMATION_SCHEMA.TABLES` / `INFORMATION_SCHEMA.COLUMNS`。
- H2 与 PostgreSQL adapter 都实现 `tableExists()`、`columnExists()`。
- 新增测试证明 diff 会调用 adapter，而不是固定 H2 SQL。
- PostgreSQL 相关集成测试可继续 skip，但 adapter 单测必须覆盖生成/查询逻辑。

### P1-D: 默认值 DDL 安全模型

负责人: Claude-D  
可并行: 是  
依赖: 无

目标:
- `FieldOptions.default` 不再作为 raw SQL 无约束拼接。
- 支持安全 literal default 与有限 DB expression default。

建议设计:
- `FieldOptions` 增加 `DefaultValue` 结构，至少包含 `kind=LITERAL|EXPRESSION` 与 `value`。
- 字符串/数字/boolean literal 由 dialect adapter 格式化。
- DB expression 只允许白名单，例如 `CURRENT_TIMESTAMP`、`CURRENT_DATE`、`NOW()`，按方言输出。
- 未支持的表达式 fail-fast。

修改点:
- `src/main/java/com/nocobase/field/FieldOptions.java`
- `src/main/java/com/nocobase/field/FieldOptionsParser.java`
- `src/main/java/com/nocobase/ddl/DdlPlan.java`
- `src/main/java/com/nocobase/ddl/DialectAdapter.java`
- 对应 H2/PostgreSQL adapter 与测试

验收标准:
- `DEFAULT abc` 这类未引用字符串不再生成。
- 恶意 default 如 `1); DROP TABLE users; --` 必须 fail-fast。
- 字符串、数字、boolean、timestamp expression 都有测试。

### P1-E: relation key 类型校验补全

负责人: Claude-E  
可并行: 是  
依赖: 无

目标:
- belongsTo 的 `foreignKey` 类型也要与 `targetKey` 兼容；当前主要检查 `sourceKey` vs `targetKey`，但 FK 才是实际保存目标 key 的列。
- belongsToMany 的 through `foreignKey` / `otherKey` 类型要分别兼容 source/target key。

修改点:
- `src/main/java/com/nocobase/runtime/CollectionRuntimeService.java`
- `src/test/java/com/nocobase/runtime/CollectionRuntimeServiceTest.java`

验收标准:
- belongsTo: source FK string 指向 target id(bigInt) 必须失败。
- belongsTo: source FK bigInt 指向 target id(bigInt) 必须通过。
- belongsToMany: through FK/otherKey 类型错配必须失败。
- view/sql 目标集合的显式 primaryKey 类型也参与兼容校验。

### P2-F: API 兼容性契约扩展

负责人: Claude-F  
可并行: 是  
依赖: P0-A/P0-B 建议先完成

目标:
- 扩展后端 API compatibility tests，锁定前端不变原则。
- 覆盖 collection schema、CRUD、association、SQL collection read-only、错误响应结构。

修改点:
- `src/test/java/com/nocobase/ApiCompatibilityTest.java`
- controller 层测试相关 helper

验收标准:
- 不改前端文件。
- public CRUD response shape 与现有前端预期一致。
- forbidden/not found/validation error 的 JSON 结构稳定。
- association append/remove/list/get 的接口返回结构稳定。

### P2-G: PostgreSQL 主库验收准备

负责人: Claude-G  
可并行: 是  
依赖: P0-C/P1-D 建议先完成

目标:
- 把 PostgreSQL 从“可跳过集成测试”推进到“可本地一键验收”。

修改点:
- `src/test/java/com/nocobase/PostgreSqlIntegrationTest.java`
- `src/test/resources/application-test*.yml`
- 必要的 README/测试说明

验收标准:
- 无 PostgreSQL 环境时明确 skip，不误报成功为生产通过。
- 有 PostgreSQL 环境变量时覆盖 physical CRUD、DDL add/drop field、index sync、SQL collection list/get/scope。
- 测试输出能明确区分 H2-only pass 与 PostgreSQL pass。

## 三、统一验收要求

- 只能改 Java 后端、测试、后端文档；不得改前端。
- 所有数据访问仍必须保持 `Controller -> DynamicRepository` 统一出口。
- 不得新增第二套 DDL 执行服务。
- 所有动态 SQL 值必须参数化；identifier 必须走 `SqlIdentifier` / dialect quote。
- 错误日志和 client error 不得泄漏完整 SQL、连接串、参数值。
- 完成后输出 `DATA_LAYER_PHASE5_P1_REVIEW_FIX_AND_PHASE6_COMPLETION_SUMMARY.md`，列出每个任务状态、修改文件、测试命令和结果。

## 四、建议开发顺序

1. Claude-A、Claude-B、Claude-C 并行处理 P0。
2. Claude-D、Claude-E 并行处理 P1。
3. P0/P1 合并后再做 Claude-F 的 API 契约扩展。
4. Claude-G 在 P0-C/P1-D 完成后接入 PostgreSQL 验收。
