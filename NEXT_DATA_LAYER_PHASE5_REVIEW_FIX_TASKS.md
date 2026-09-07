# NEXT_DATA_LAYER_PHASE5_REVIEW_FIX_TASKS

> 日期: 2026-09-01  
> 负责人: Claude  
> 范围: Java 后端改造；前端界面、前端请求参数、API 响应结构保持不变。  
> 输入完成文档: `DATA_LAYER_PHASE5_CORE_COMPLETION_SUMMARY.md`

---

## 0. Architect Review 结论

本轮不能按“完成”验收。总结文档中声明 `559 tests, 2 failures (pre-existing), BUILD SUCCESS`，但这两个失败不是可忽略项，且其中一个直接命中本轮新增的 relation/appends 大结果集修复目标。

本地报告汇总:

```bash
mvn -q test
# surefire reports: 559 tests, 2 failures, 0 errors, 13 skipped
```

失败测试:

```text
ActionScopeRelationReviewTest.belongsToManyThroughInternalOps
expected: 2, actual: 1

P0P1FixTest.belongsToManyLargeThroughLinksReturnsAll
expected: 1200, actual: 1
```

根因初判:

- `DynamicRepository.listLinks()` 把 through 查询改成 `sqlBuilder.buildListPlan(..., pageSize=batch.size())`。
- 当 `sourceIds` 只有 1 个 sourceId 时，`batch.size() == 1`，最终 SQL 带 `LIMIT 1 OFFSET 0`，所以 belongsToMany 只能取到 1 条 through link。

其他 review 发现:

1. `DynamicRepository` 中 `PhysicalSqlBuilder` 被硬编码为 `new PhysicalSqlBuilder(new H2SqlDialect())`，没有使用主库实际 dialect，P0-B2 未真正闭环。
2. `IndexDefinition.parse()` / `DdlSynchronizer.syncIndexes()` 主要只在测试中调用，未接入 collection 创建、字段新增或 runtime reload 的生产链路，index sync 不能算完成。
3. `FieldOptionsParser` / `FieldOptions` 基本没有被 DDL 生成链路使用，字段 length/precision/scale/nullable/default 仍未真实影响物理表 DDL。
4. `SchemaPlan.generate()` 没有 index diff 参数，也未接入 DDL/sync 流程，只是孤立模型。
5. `CollectionManagerService` 仍是 `@Service` 且直接注入 `JdbcTemplate`、直接执行 DDL；仅标记 `@Deprecated` 不足以满足“不能作为第二套动态 DDL 通道”。
6. `DdlSynchronizer.dropCollection()` 仍直接执行 `DROP TABLE`，没有统一走 DDL plan/日志治理。
7. `DdlSynchronizer.syncIndexes()` error path 仍拼出 index/table 和 `e.getMessage()`，异常 cause 仍可能携带 raw DB 信息。
8. `SqlPlan.toString()` 输出完整 SQL，后续任何日志打印 plan 都可能泄露 SQL。
9. Relation validator 对 `belongsTo` 的 `foreignKey` 未验证源集合物理列存在，类型兼容也把 `boolean` 归到 number，语义不准确。
10. Maven/测试验收必须以 surefire XML 失败数为准，不能把失败测试称为 pre-existing 后继续推进。

下一批目标: 先修复 Phase5 质量问题，让 `mvn test` 真正 0 failure，然后再进入 Phase6 API 兼容与生产验收。

---

## 1. 全局约束

- 只改 Java 后端，不改前端文件。
- 不改变 controller 路由、请求参数、响应 envelope、字段名。
- `DynamicRepository` 仍是公开数据访问唯一出口。
- relation/association 内部 API 不检查 public action 权限，但必须使用 action scope 和字段权限。
- SQL collection 保持只读。
- 外部 SQL datasource 不参与 JPA、Flyway、DDL、物理表同步、跨库事务。
- 动态数据层继续使用 `JdbcTemplate`，不要引入 MyBatis/Hibernate 作为本轮方案。
- 所有 SQL/DDL 标识符必须走 `SqlIdentifier` 或统一 dialect/builder。
- 完成总结中不得把失败测试标为通过或 pre-existing，除非给出明确历史证据和 issue 编号。

---

## 2. P0 必修任务

### P0-A. 修复 belongsToMany through 查询截断

问题:

- `listLinks()` 对单个 sourceId 使用 `pageSize=batch.size()`，导致只取 1 条 link。
- 这直接破坏 association list 和 appends 的 belongsToMany 语义。

要求:

- `listLinks()` 必须按 sourceIds 分批防止超大 `IN`，但每个 sourceId 对应的 through rows 必须取全。
- 不允许通过 `batch.size()` 限制返回 link 数。
- 推荐实现:
  - 为 through link 查询新增专用 `PhysicalSqlBuilder.buildListLinksPlan()`，不带分页，或
  - 在 `listLinks()` 内使用安全的 `IN` 分批查询但不加 `LIMIT/OFFSET`。
- 查询仍只返回 `sourceKey`、`otherKey` 和可选 pk，不返回完整 through 表。
- 保持 through 表前端权限绕过语义，由调用方负责源/目标权限。

验收:

```bash
mvn -q test -Dtest=ActionScopeRelationReviewTest#belongsToManyThroughInternalOps,P0P1FixTest#belongsToManyLargeThroughLinksReturnsAll
mvn -q test
```

必须达到:

- `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps`: expected 2。
- `P0P1FixTest.belongsToManyLargeThroughLinksReturnsAll`: expected 1200。
- surefire XML 汇总 `failures=0 errors=0`。

### P0-B. 修复主库 dialect 硬编码

问题:

- `DynamicRepository` 构造器里直接 `new PhysicalSqlBuilder(new H2SqlDialect())`。
- PostgreSQL 主库下物理 CRUD builder 不会使用真实 dialect，P0-B2 未完成。

要求:

- `DynamicRepository` 不允许硬编码 H2 dialect。
- 通过 `SqlDataSourceResolver.resolveDialect("main")`、`DialectAdapterFactory` 或统一 `SqlDialectProvider` 注入主库 dialect。
- `PhysicalSqlBuilder` 应作为 Spring bean 或由 repository 通过真实 dialect 构建。
- PostgreSQL 主库场景必须能使用同一套 builder。

验收:

- 新增测试证明 repository 使用真实主库 dialect，不是固定 H2。
- `rg -n "new H2SqlDialect\\(|new PhysicalSqlBuilder" src/main/java/com/nocobase/data src/main/java/com/nocobase` 不应命中未解释的硬编码。

### P0-C. 让测试失败真正阻断验收

问题:

- 当前总结写了 `2 failures` 但仍标 `BUILD SUCCESS`，这会误导后续开发。

要求:

- 确认 Maven/Surefire 是否存在忽略失败的配置、环境变量或脚本包装。
- 如果 `mvn test` 进程退出码仍为 0 但 surefire XML 有 failures/errors，需要新增一个可执行验收脚本或 Maven 配置，保证 CI/本地验收失败。
- 不要只依赖终端日志尾部判断。

验收:

- `mvn -q test` 在测试失败时返回非 0，或新增 `scripts/verify-tests.ps1` / 等价脚本读取 surefire XML 并在 failures/errors 非 0 时返回非 0。
- 完成总结必须给出 surefire XML 汇总。

### P0-D. 清理 SQL/DDL 泄露风险

问题:

- `SqlPlan.toString()` 输出完整 SQL。
- `DdlSynchronizer.syncIndexes()` 异常中包含 table/index 和 raw `e.getMessage()`。
- `DdlSynchronizer.dropCollection()` 未走统一 DDL plan/日志治理。

要求:

- `SqlPlan.toString()` 不输出 SQL，只输出 operation、collection、参数数量、是否有 countSql。
- DDL error path 使用 `SqlErrorSanitizer` 或等价 sanitizer，不输出完整 SQL、JDBC URL、参数、raw stack。
- `dropCollection()` 改为统一 DDL plan 或统一 execution helper。
- index sync 日志只记录 collection、operation、columns count/identifier，不输出 raw SQL。

验收:

- 新增测试覆盖 `SqlPlan.toString()` 不含 SQL。
- 新增 DDL/index 失败日志或异常 sanitization 测试。
- `rg -n "e\\.getMessage\\(\\).*DDL|sql='|DROP TABLE IF EXISTS\" src/main/java/com/nocobase/ddl src/main/java/com/nocobase/data` 不应命中未解释风险。

### P0-E. 下线 `CollectionManagerService` 第二套 DDL 通道

问题:

- `CollectionManagerService` 仍是 Spring `@Service`，直接注入 `JdbcTemplate` 并执行 CREATE/ALTER/DROP。
- 仅 `@Deprecated` 不足以防止误注入或误调用。

要求:

- 推荐直接移除 `@Service`，或改成薄适配器，所有操作委托 `CollectionMetadataService` / `DdlSynchronizer`。
- 不允许该类直接注入 `JdbcTemplate`。
- 不允许该类直接拼接或执行 DDL。
- 架构测试不应把 `CollectionManagerService.java` 放在 allowed JdbcTemplate/DDL 白名单里。

验收:

- `ArchitectureBoundaryTest` 删除对 `CollectionManagerService` 的白名单。
- `rg -n "JdbcTemplate|CREATE TABLE|ALTER TABLE|DROP TABLE" src/main/java/com/nocobase/service/CollectionManagerService.java` 不应命中，除非该文件被删除。

---

## 3. P1 接入任务

### P1-F. 真正接入 IndexDefinition 与 index sync

问题:

- `IndexDefinition.parse()` 和 `DdlSynchronizer.syncIndexes()` 目前没有接入生产 create/add/reload/upgrade 链路。

要求:

- collection 创建时，根据 field/collection options 解析 index 并同步。
- addField 时，如果字段带 `index/unique`，同步对应索引。
- collection options 变更或 reload/upgrade 时能幂等同步缺失索引。
- SQL/view collection 只保留 metadata，不执行物理 index DDL。
- `IndexDefinition.parse()` 遇到非法 JSON、非法 index name、非法 field、非法 column 不应静默跳过；物理集合应 fail fast 或进入 invalid metadata 状态。
- collection-level indexes 的 `fields` 应按 field name 解析到 effective column name，不应直接当 column name。

验收:

- 通过 controller/service 创建 collection/field 后物理索引存在。
- 重复 reload/upgrade 不重复创建。
- 非法 index metadata 不生成部分 DDL。

### P1-G. 真正接入 FieldOptionsParser 到 DDL

问题:

- `FieldOptionsParser` 已有模型，但 DDL 仍基本使用 `mapFieldType(field.getType(), null, null, null)`。

要求:

- DDL 生成列定义时使用解析后的 `FieldOptions`:
  - `length`
  - `precision`
  - `scale`
  - `nullable`
  - `default`
- 非法 options 在执行 DDL 前失败，不保存半截 metadata/DDL。
- 兼容现有前端 metadata JSON shape。

验收:

- string length 生成 `VARCHAR(n)`。
- decimal/numeric precision/scale 生效。
- nullable/default 生效。
- 非法 options 不执行物理 DDL。

### P1-H. SchemaPlan 从孤立模型变成可用 dry-run

问题:

- `SchemaPlan.generate()` 没有 index diff 参数，也没有接入 schema sync。

要求:

- 支持 table/column/index 的 current vs target diff。
- 生成 missing table、missing column、missing index、incompatible change、no-op。
- 提供后端内部 service 方法用于 dry-run，不暴露前端 API。
- DDL synchronizer 可以复用 plan 执行非破坏性变更。

验收:

- dry-run plan 测试覆盖 table/column/index。
- sync 执行和 dry-run plan 的差异一致。

### P1-I. 修正 Relation validator 语义

问题:

- `belongsTo` 的 `foreignKey` 未验证源集合物理列是否存在。
- `boolean` 被归类为 number，不适合作为通用 key 类型兼容。
- SQL/external relation 只读策略还需要更多运行期覆盖。

要求:

- belongsTo foreignKey 必须验证 source collection 中存在对应物理字段或 relation 字段的 effective FK column。
- 类型兼容只允许明确同类 key:
  - bigint/integer/number 可兼容。
  - uuid/string 需按当前字段类型策略明确，不能混用。
  - boolean 不应作为 number key 默认兼容。
- SQL collection source/target 的 read-only relation 策略要有运行期测试。

验收:

- belongsTo foreignKey 缺失时 runtime reload 标记 invalid。
- boolean key mismatch 被拒绝。
- SQL source/target add/remove/set 全部 forbidden，list/appends 按设计可读。

---

## 4. P2 后续推进任务

### P2-J. API 兼容回归补齐

要求:

- controller 层测试覆盖:
  - collection list/get/create/update/destroy。
  - association list/add/remove/set。
  - appends。
  - SQL collection list/get/error。
  - index/field options metadata 不改变前端请求响应结构。

### P2-K. PostgreSQL acceptance 扩展

要求:

- 无 PG 环境变量时保持 skipped。
- 有 PG 环境变量时覆盖:
  - physical CRUD builder 使用真实 PostgreSQL dialect。
  - index sync。
  - relation append/list。
  - SQL collection external datasource。
  - schema dry-run。

---

## 5. 建议并行安排

先串行:

1. Claude-1: P0-A + P0-B + P0-C，先让测试真正全绿。

再并行:

- Claude-2: P0-D + P0-E
- Claude-3: P1-F
- Claude-4: P1-G + P1-H
- Claude-5: P1-I + P2-J/K

合并顺序:

1. P0-A/B/C
2. P0-D/E
3. P1-F/G/H/I
4. P2-J/K

---

## 6. 完成总结要求

完成后输出:

```text
DATA_LAYER_PHASE5_REVIEW_FIX_COMPLETION_SUMMARY.md
```

总结必须包含:

- 每个 P0/P1/P2 任务完成状态。
- 修改文件列表。
- 新增/修改测试列表。
- surefire XML 汇总，而不是只写 BUILD SUCCESS。
- `mvn -q test` 结果。
- 是否修改前端文件: 必须为否。
- 是否改变 API 响应结构: 必须为否。
- belongsToMany 两个失败测试是否已修复。
- DynamicRepository 是否还硬编码 H2 dialect。
- IndexDefinition/index sync 是否已接入生产链路。
- FieldOptionsParser 是否已接入 DDL。
- SchemaPlan 是否已支持 index diff 并被 schema sync 使用。
- CollectionManagerService 是否仍是第二套 DDL 通道。

---

## 7. 最终验收命令

```bash
mvn -q test
rg -n "<failure|<error|<<< FAILURE|<<< ERROR" target/surefire-reports
rg -n "new H2SqlDialect\\(|new PhysicalSqlBuilder" src/main/java/com/nocobase
rg -n "JdbcTemplate|CREATE TABLE|ALTER TABLE|DROP TABLE" src/main/java/com/nocobase/service/CollectionManagerService.java
rg -n "Exception during pool initialization|Caused by: java\\.net|jdbc:h2:tcp://localhost:19999|localhost:19999" target/surefire-reports
rg -n "sql='|SqlPlan\\{|e\\.getMessage\\(\\)" src/main/java/com/nocobase/data src/main/java/com/nocobase/ddl
```

验收说明:

- 前两条必须无失败/错误。
- 第三条不得命中生产硬编码 H2 builder。
- 第四条不得命中旧 DDL/JdbcTemplate 通道。
- 第五条不得命中 datasource failure 原始日志。
- 第六条命中必须逐条解释，不允许 SQL/DDL 泄露风险遗留。
