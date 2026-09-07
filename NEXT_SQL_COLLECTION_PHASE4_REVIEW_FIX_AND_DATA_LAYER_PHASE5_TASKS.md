# NEXT_SQL_COLLECTION_PHASE4_REVIEW_FIX_AND_DATA_LAYER_PHASE5_TASKS

> 日期: 2026-09-01  
> 负责人: Claude  
> 范围: Java 后端改造；前端界面、前端调用方式、API 响应结构保持不变。  
> 输入 review 文档: `SQL_COLLECTION_PHASE4_PRODUCTION_GOVERNANCE_COMPLETION_SUMMARY.md`

---

## 0. Review 结论

Phase4 主方向正确，SQL collection 的多数据源、参数绑定、scope/filter/sort/page、只读边界和基础生产治理已经形成可继续迭代的后端能力。

本地复跑:

```bash
mvn -q test
# 472 tests, 0 failures, 0 errors, 13 skipped
```

但 review 发现两个必须进入下一批的生产级缺口:

1. **失败外部数据源仍会输出 Hikari 原始堆栈日志**  
   测试报告中存在 `com.zaxxer.hikari.pool.HikariPool : ... Exception during pool initialization`，并带有 JDBC 连接异常、host/port 和完整 stack trace。虽然业务异常已 sanitization，但底层连接池初始化日志仍绕过了治理目标。

2. **relation/appends 的大结果集仍可能被分页截断**  
   `RelationQueryService` / `AssociationActionService` 将 `Integer.MAX_VALUE` 改成 `SAFE_BATCH_SIZE=1000` 只解决了超大 `$in`，但当一个 source 对应超过 1000 条 hasMany / belongsToMany 记录，或目标是 SQL collection 且 `maxPageSize=200` 时，仍可能只取第一页，导致关联数据不完整。

下一批先修复这两个 P0，然后并行推进物理数据层的 SQL 构建收敛、索引同步、关系元数据校验和 schema sync。

---

## 1. 全局约束

- 只改 Java 后端，不改前端文件。
- 不改变现有前端 API 路径、请求参数、响应 envelope 和字段名。
- 公开数据访问仍然统一走 `DynamicRepository`，controller 不允许直连 `JdbcTemplate`。
- 内部 relation/association API 可以绕过 through 表前端 action 权限，但必须保留源集合/目标集合权限与 scope 语义。
- SQL collection 保持只读: `list/get` 允许，`create/update/destroy/add/remove/set` 禁止。
- 外部 SQL datasource 只用于 SQL collection 查询，不参与 JPA、Flyway、DDL、物理表同步和跨库事务。
- 新增 SQL 拼接能力必须集中在统一 builder/dialect 内，避免 SQL 字符串继续散落。

---

## 2. 并行开发分组

### Group A: Phase4 Review Fixes

> 优先级最高。先完成 A1/A2/A3，再进入 Phase5 主线。

#### P0-A1. 消除 Hikari 失败初始化原始日志

问题:

- 当前 `SqlDataSourceResolver.createExternalDataSourceHolder()` 创建 `HikariDataSource` 后，`verifyReadOnlyMode()` / `validateConnection()` 调用 `getConnection()`。
- 连接失败时 Hikari 自身会输出原始 `Exception during pool initialization` stack trace，业务日志 sanitization 无法覆盖。

要求:

- 不允许失败 datasource 初始化路径在测试报告或日志中出现:
  - `Exception during pool initialization`
  - 原始 JDBC URL
  - password / username
  - SQL 文本
  - 原始 stack trace
- 避免失败路径重复尝试两次连接。
- 推荐实现方向:
  - 在创建 Hikari pool 前做一次轻量 preflight 连接验证。
  - preflight 使用 driver/JDBC 直接获取连接或独立 helper，并只抛出 sanitized 业务异常。
  - 只有 preflight 成功后才创建并缓存 HikariDataSource。
  - `verifyReadOnlyMode()` 不应在失败连接场景制造第二次 pool 初始化。
- 保留成功路径的 Hikari 连接池和 lazy cache。

验收:

```bash
mvn -q test -Dtest=SqlDataSourceResolverIntegrationTest
rg -n "Exception during pool initialization|jdbc:h2:tcp|localhost:19999|password|Caused by:" target/surefire-reports
```

第二条命令不应命中外部 datasource 失败路径的原始泄露内容。Spring Security 生成密码日志如果仍存在，单独记录为后续安全配置任务，不阻塞本项。

#### P0-A2. relation/appends 内部全量读取不允许截断

问题:

- `RelationQueryService.batchList()` / `batchListByKey()` 每个 `$in` 批次只请求 `page=1,pageSize=1000`。
- `AssociationActionService.listViaFilter()` 对 hasOne/hasMany 也只取第一页。
- 当目标集合是 SQL collection 时，还会被 `nocobase.sql.max-page-size=200` 截断。

要求:

- 新增 `DynamicRepository` 内部读取能力，用于 relation/association:
  - 不检查 public action 权限。
  - 使用指定 action 的 scope。
  - 应用 readable field 过滤。
  - 支持按页循环直到读取完整结果，或提供受治理的 streaming/chunk API。
- public `list()` 仍然保留前端分页语义和 maxPageSize 治理。
- SQL collection public list 继续 cap pageSize；内部 relation 读取可以使用循环分页，不允许绕过 query timeout/scope/filter/field 权限。
- belongsToMany 的 through 表 `listLinks()` 也要支持按 sourceIds 分批，避免超大 `IN`。

验收:

- 新增测试覆盖:
  - 一个 source 有 1201 条 hasMany 目标，association list 返回 1201。
  - appends hasMany 对多个 source 分组，总结果超过 1000 仍完整。
  - SQL collection 作为 target，`maxPageSize=200`，内部 relation 通过循环返回超过 200 条。
  - belongsToMany through links 超过 1000 条仍完整。
- 所有数据访问仍经 `DynamicRepository`。

#### P0-A3. 统一 DynamicRepository identifier quoting

问题:

- `SqlIdentifier` 已用于 SQL collection 和部分 runtime 校验。
- `DynamicRepository.quote()` 仍是 `return "\"" + identifier + "\"";`，through column 校验仍有本地 regex。

要求:

- `DynamicRepository` 的表名、列名、主键、through key 全部使用 `SqlIdentifier.quote()` / `validate()`。
- 删除重复 regex 校验逻辑。
- 错误信息保持对前端兼容，不泄露 SQL 片段。

验收:

- 增加 malicious table/column/relation key 测试。
- `rg -n "return \"\\\\\\\"\" \\+ identifier|matches\\(\" src/main/java/com/nocobase/data src/main/java/com/nocobase/runtime` 不应再命中重复实现。

---

### Group B: Physical Data SQL Builder 收敛

#### P0-B1. 建立物理表 SQL 构建器

背景:

当前物理 CRUD SQL 仍混在 `DynamicRepository` 方法中。随着 collection/field/relation/index 继续扩展，SQL 会快速失控。需要先把 SQL 生成边界收敛，再继续开发更多数据层能力。

要求:

- 新增后端内部组件，例如 `PhysicalSqlBuilder` / `RepositorySqlBuilder`。
- 覆盖以下语句构建:
  - list data SQL
  - list count SQL
  - get SQL
  - insert SQL
  - update SQL
  - delete SQL
  - existsInScope SQL
  - listLinks/createLink/deleteLink/replaceLinks SQL
- builder 只负责 SQL 字符串和参数计划，不执行数据库。
- `DynamicRepository` 保持统一出口，只调用 builder 生成 plan 后执行。
- SQL plan 对象必须包含:
  - `sql`
  - `parameters`
  - 可选 `countSql`
  - 可选 `countParameters`
  - 操作名，便于 observability。
- builder 全部使用统一 `SqlIdentifier`。

验收:

- `DynamicRepository` 中不再直接拼复杂 SQL，只保留执行和权限流程。
- builder 单元测试覆盖所有 CRUD/internal SQL plan。
- 现有 `mvn -q test` 通过。

#### P1-B2. 统一物理表 dialect 边界

要求:

- 物理表 SQL builder 不要硬编码 `LIMIT ? OFFSET ?`，改为复用 dialect。
- 主库 dialect 由 `SqlDataSourceResolver.resolveDialect("main")` 或等价统一入口获得。
- H2/PostgreSQL 下的 identifier quote 和分页语义一致。

验收:

- H2 现有测试通过。
- PostgreSQL profile 可复用同一 builder，不需要为物理 CRUD 写另一套 SQL。

---

### Group C: Index Metadata 与同步能力

#### P0-C1. 建立索引元数据模型

要求:

- 支持从 collection/field metadata 解析索引定义:
  - field-level `index`
  - field-level `unique`
  - collection-level indexes，如果当前 metadata 已有对应 options。
- 新增 `IndexDefinition` 或等价 runtime model。
- 索引名称、字段名全部经过 `SqlIdentifier` 校验。
- SQL/view/sql collection 不执行物理 index DDL；只保留 metadata。

验收:

- 单字段普通索引、唯一索引、多字段索引解析测试。
- 非法索引名/字段名测试。
- SQL/view collection 不触发物理 DDL 测试。

#### P0-C2. 实现索引同步器

要求:

- 在现有 DDL 同步流程中加入 idempotent index sync。
- 支持:
  - create missing index
  - create missing unique index
  - skip existing index
  - metadata 删除后是否 drop index 先做保守策略: 默认不 drop，输出 plan/log；如已有明确删除语义再实现 drop。
- H2 与 PostgreSQL dialect 分别处理 index exists 查询。
- index DDL 不允许用于外部 SQL datasource。

验收:

- H2 集成测试覆盖 create/skip。
- PostgreSQL acceptance 测试入口覆盖 index exists / create。
- 多次 reload/upgrade 不重复创建索引。

---

### Group D: Relation Metadata 校验与能力边界

#### P0-D1. 关系定义完整校验

要求:

- 在 `CollectionRuntimeService` 构建/重载 collection 时校验:
  - target collection 存在。
  - belongsTo/hasOne/hasMany/belongsToMany 所需 key 存在。
  - through collection 存在。
  - sourceKey/targetKey/foreignKey/otherKey 是物理字段或允许的系统主键字段。
  - key 类型兼容，至少对 string/number/bigInt/uuid 做基础一致性校验。
- 校验失败时 collection 标记 invalid，不影响其他 collection 加载。
- 错误日志不输出 SQL，不输出敏感 datasource 信息。

验收:

- 每种 relation 类型都有 happy path 和 invalid path 测试。
- invalid relation 不应在 association controller 调用时才暴露为 NPE 或 SQL 异常。

#### P1-D2. 明确跨 datasource relation 策略

要求:

- 当前阶段不支持跨 datasource 写 relation。
- SQL collection 可以作为只读 target 被 list/get/append，但不允许作为 association write 的 source/target。
- external SQL datasource collection 与 main physical collection 的 relation 只允许读路径，且必须通过循环分页和权限过滤。

验收:

- 补充文档和测试:
  - main physical -> SQL target append/list。
  - SQL source -> physical target list。
  - 任意 SQL source/target add/remove/set rejected。

---

### Group E: Schema Sync 与字段 options 硬化

#### P1-E1. 字段 options 解析收敛

要求:

- 将 nullable/default/length/precision/scale/index/unique 等字段 options 解析集中到 field runtime model 或 dedicated parser。
- DDL 需要使用解析后的强类型 options，不直接读取原始 Map。
- options 错误在 reload/DDL 前暴露为清晰 metadata 错误。

验收:

- string length、decimal precision/scale、nullable/default 测试。
- 非法 options 类型不会生成半截 DDL。

#### P1-E2. Schema diff / dry-run plan

要求:

- DDL 同步前能生成 schema plan:
  - missing table
  - missing column
  - missing index
  - incompatible column change
  - no-op
- 先实现内部 API/测试，不需要暴露前端接口。
- 对危险变更采取保守策略: 不自动破坏数据，输出 plan 并拒绝或标记需人工处理。

验收:

- schema plan 单元测试。
- reload/upgrade 多次执行 idempotent。

---

### Group F: 测试、文档和验收

#### P1-F1. API 兼容回归

要求:

- 增加 controller 层集成测试，确认前端相关接口响应结构不变:
  - collection list/get/create/update/destroy。
  - association list/add/remove/set。
  - appends。
  - SQL collection list/get/error。
- 不新增前端参数，不删除现有字段。

#### P1-F2. PostgreSQL acceptance 扩展

要求:

- 保留无环境变量时 skipped 的机制。
- 扩展 PostgreSQL profile 验收:
  - physical CRUD builder。
  - index sync。
  - relation append/list。
  - SQL collection external datasource。

#### P2-F3. 输出完成总结

完成后输出:

```text
SQL_COLLECTION_PHASE4_REVIEW_FIX_AND_DATA_LAYER_PHASE5_COMPLETION_SUMMARY.md
```

总结必须包含:

- 修改文件列表。
- 每个任务的完成状态。
- 关键调用链说明。
- 测试命令和结果。
- 是否修改前端文件: 必须为否。
- 是否改变 API 响应结构: 必须为否。
- 是否仍存在 Hikari/raw datasource failure log: 必须明确说明并给出 `rg` 结果。
- relation/appends 是否已解决超过单页的数据截断: 必须明确说明测试覆盖。

---

## 3. 建议执行顺序

可以并行:

- Claude-1: Group A
- Claude-2: Group B
- Claude-3: Group C
- Claude-4: Group D
- Claude-5: Group E/F

合并顺序:

1. 先合并 Group A，修复 Phase4 未闭环生产治理问题。
2. 再合并 Group B，避免后续索引/关系/DDL 继续堆 SQL 字符串。
3. 然后合并 Group C/D/E。
4. 最后统一跑 Group F 验收。

---

## 4. 最终验收命令

```bash
mvn -q test
rg -n "Exception during pool initialization|jdbc:h2:tcp|localhost:19999|password|Caused by:" target/surefire-reports
rg -n "return \"\\\\\\\"\" \\+ identifier|matches\\(\" src/main/java/com/nocobase/data src/main/java/com/nocobase/runtime
rg -n "Integer\\.MAX_VALUE|pageSize, null\\)|pageSize=1000|SAFE_BATCH_SIZE" src/main/java/com/nocobase/data src/test/java/com/nocobase
```

说明:

- 第一条必须通过。
- 第二条不得命中 datasource failure 的原始泄露；若只命中 Spring Security 自动生成密码日志，记录为独立安全配置任务。
- 第三条不得命中重复 identifier quote/regex 实现。
- 第四条用于人工 review relation/appends 是否仍有单页截断风险，不要求完全无命中，但必须解释每个命中点的合理性。
