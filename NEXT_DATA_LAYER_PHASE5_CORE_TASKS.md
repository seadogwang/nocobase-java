# NEXT_DATA_LAYER_PHASE5_CORE_TASKS

> 日期: 2026-09-01  
> 负责人: Claude  
> 范围: Java 后端改造；前端界面、前端请求参数、API 响应结构保持不变。  
> 输入完成文档: `SQL_COLLECTION_PHASE4_REVIEW_FIX_AND_DATA_LAYER_PHASE5_COMPLETION_SUMMARY.md`

---

## 0. Architect Review 结论

本轮完成了上一批任务中的 Group A:

- Hikari 失败初始化原始日志已通过 preflight 方式消除。
- relation/appends 已从单页读取改为 `listAllInternal()` 循环分页。
- `DynamicRepository.quote()` 已改为 `SqlIdentifier.quote()`。

本地复跑:

```bash
mvn -q test
# 481 tests, 0 failures, 0 errors, 13 skipped
```

专项验证:

```bash
rg -n "Exception during pool initialization|Caused by: java\\.net|jdbc:h2:tcp://localhost:19999|localhost:19999" target/surefire-reports
# 无命中
```

仍需修复或继续推进:

1. `listAllInternal()` 到达 `nocobase.internal.query-max-limit` 时会 warn 后返回部分数据，relation/appends 对前端表现为“成功但数据不完整”，这不是可接受语义。
2. SQL identifier 只统一了一部分，`FilterCompiler`、旧 `CompiledFilter.compile(Map)`、DDL dialect adapter、`CollectionManagerService.escapeIdentifier()` 仍有本地 quote/escape。
3. 物理 CRUD SQL 仍混在 `DynamicRepository` 中，之前规划的 Physical SQL Builder 尚未开发。
4. DDL 执行失败仍会记录完整 DDL SQL 和 raw exception，生产日志治理还没有覆盖物理 DDL。
5. Index metadata/sync、Relation metadata validator、Field options parser、Schema diff plan 仍未进入实现。

下一批目标: 进入 Phase5 核心数据层建设，先收敛 SQL 生成边界，再做索引、关系和 schema sync。

---

## 1. 全局原则

- 只改 Java 后端，不改前端文件。
- 不改变现有 controller 路由、请求参数、响应 envelope、字段名。
- `Controller -> DynamicRepository` 仍是公开数据访问唯一出口。
- relation/association 内部 API 不检查 public action 权限，但必须使用 action scope 和字段权限。
- SQL collection 保持只读。
- 外部 SQL datasource 不参与 JPA、Flyway、DDL、物理表同步、跨库事务。
- 所有 SQL 标识符必须通过 `SqlIdentifier` 或统一 dialect/builder 间接处理。
- 不引入 MyBatis/Hibernate 作为动态数据层方案；当前动态数据层继续基于 `JdbcTemplate`，但 SQL 拼接必须集中治理。

---

## 2. 并行任务分组

### Group A: Phase5 P0 Review Fixes

#### P0-A1. 修复 `listAllInternal()` 静默截断语义

问题:

- 当前 `listAllInternal()` 达到 `maxInternalQueryLimit` 后只记录 warn 并返回部分数据。
- 对 association/appends 来说，前端无法知道数据被截断，会误认为 relation 数据完整。

要求:

- 不允许 relation/appends 默认静默返回部分结果。
- 设计明确的内部读取结果语义，二选一:
  - 推荐: 超过上限时抛出后端异常，API 返回错误，提示需要缩小 scope/filter 或提高后端配置。
  - 或: 返回带 `truncated=true` 的内部结果对象，但不能改变前端公开响应结构；因此 relation/appends 场景仍应转成明确错误。
- `listAllInternal()` 应在加入当前页前判断是否会超过上限，不能返回超过上限的数据。
- max limit、page size 需要正数校验，非法配置回退或 fail fast，但不能导致死循环。
- 日志只记录 collection/action/count/limit，不输出 SQL。

验收:

- 新增测试:
  - max limit 小于结果总数时 association list 抛出明确异常。
  - max limit 小于结果总数时 appends 抛出明确异常。
  - limit 非 500 整数倍时不会返回超过上限的数据。
  - SQL collection target 超限时同样不静默截断。

#### P0-A2. 彻底统一 filter 标识符处理

问题:

- `FilterCompiler.resolveColumn()` 内部仍用本地 `quote()`。
- `CompiledFilter.compile(Map)` 无 collection metadata，仍可直接 quote 任意 key。

要求:

- `FilterCompiler` 使用 `SqlIdentifier.quote()` 或统一 builder/dialect。
- 废弃或限制 `CompiledFilter.compile(Map)`:
  - 生产代码不允许调用无 metadata 的 compile。
  - 如果保留，仅用于测试或内部已验证场景，并必须使用 `SqlIdentifier`。
- 所有 filter 生成的 WHERE 子句都必须经过 collection metadata 字段校验。
- 恶意 filter key、relation key、system field key 测试全部覆盖。

验收:

```bash
rg -n "private static String quote|return \"\\\\\\\"\" \\+ identifier|CompiledFilter\\.compile\\(" src/main/java/com/nocobase/data
```

除 `SqlIdentifier` 或明确 deprecated 测试路径外，不应存在本地 quote。

#### P0-A3. 收敛 DDL identifier 与日志治理

问题:

- `H2DialectAdapter` / `PostgresDialectAdapter` 仍直接拼 `"`。
- `DdlSynchronizer.executePlan()` 记录完整 DDL SQL，异常时传 raw exception。
- `CollectionManagerService` 仍有独立 `escapeIdentifier()`，使用反引号并静默删除非法字符。

要求:

- DDL dialect adapter 的 `quoteIdentifier()` 统一委托 `SqlIdentifier.quote()`。
- 禁止静默清洗标识符后继续执行 DDL；非法名称必须拒绝。
- `CollectionManagerService` 不允许再绕过 `DdlSynchronizer` 直接执行 DDL:
  - 如果该 service 是遗留入口，将其改为调用 `DdlSynchronizer`。
  - 如果已不使用，标记废弃并加架构边界测试，防止 controller 调用旧 DDL 路径。
- DDL 日志:
  - debug 可记录 operation、collection、table、column、index，不直接输出完整 SQL。
  - error 不输出完整 DDL SQL，不携带 raw stack trace 到业务日志。

验收:

- 恶意 collection/table/column/index name 被拒绝。
- `rg -n "return \"\\\\\\\"\" \\+ identifier|escapeIdentifier|replaceAll\\(\" src/main/java/com/nocobase/ddl src/main/java/com/nocobase/service` 不应命中未解释的旧实现。
- DDL 失败日志不包含完整 SQL。

---

### Group B: Physical SQL Builder 核心化

#### P0-B1. 新增物理表 SQL Plan/Builder

要求:

- 新增 `PhysicalSqlBuilder` 或 `RepositorySqlBuilder`。
- builder 只生成 SQL plan，不执行数据库，不检查 ACL。
- 至少支持:
  - list data SQL
  - list count SQL
  - get SQL
  - insert SQL
  - update SQL
  - delete SQL
  - existsInScope SQL
  - updateByFilterForAction SQL
  - listLinks/createLink/deleteLink/replaceLinks SQL
- 新增 `SqlPlan`/`MutationSqlPlan` 等轻量对象，包含:
  - `sql`
  - `parameters`
  - 可选 `countSql`
  - 可选 `countParameters`
  - `operation`
  - `collectionName`
- `DynamicRepository` 只保留权限、scope、字段过滤和执行逻辑。

验收:

- builder 单元测试覆盖所有 plan。
- `DynamicRepository` 中不再散落复杂 SQL 拼接。
- 所有现有测试通过。

#### P0-B2. 物理 SQL 复用 dialect 分页

要求:

- 物理 list/findByFilter 不再硬编码 `LIMIT ? OFFSET ?`。
- 主库 dialect 通过统一入口获取，H2/PostgreSQL 行为一致。
- builder 生成分页参数顺序必须有测试锁定。

验收:

- H2 测试通过。
- PostgreSQL acceptance 能覆盖物理 CRUD builder。

---

### Group C: Index Metadata 与同步

#### P0-C1. 建立 `IndexDefinition`

要求:

- 从 field options 和 collection options 解析索引:
  - field-level `index`
  - field-level `unique`
  - collection-level indexes，如果当前 metadata 已支持。
- 支持普通索引、唯一索引、多字段索引。
- index name、table name、column names 全部走 `SqlIdentifier`。
- view/sql collection 只保存 metadata，不执行物理 index DDL。

验收:

- 解析测试覆盖 field-level 与 collection-level。
- 非法 index name/column name 被拒绝。
- SQL/view collection 不执行 index DDL。

#### P0-C2. 实现 idempotent index sync

要求:

- 在 DDL 同步流程中创建缺失索引。
- 已存在索引不重复创建。
- metadata 删除后的 drop index 先走保守策略:
  - 默认不自动 drop。
  - 输出 schema plan / log 标记待人工处理。
- H2/PostgreSQL 分别实现 index exists 查询。
- 外部 SQL datasource 禁止 index DDL。

验收:

- H2 集成测试: create/skip/unique/multi-column。
- PostgreSQL acceptance: index exists/create。
- 多次 reload/upgrade 不重复创建索引。

---

### Group D: Relation Metadata Validator

#### P0-D1. 构建 relation 元数据校验器

要求:

- 在 runtime reload/build definition 时校验:
  - target collection 存在。
  - through collection 存在。
  - sourceKey/targetKey/foreignKey/otherKey 存在。
  - key 是物理字段或允许的系统主键。
  - key 类型兼容，至少覆盖 string/number/bigInt/uuid。
  - belongsTo/hasOne/hasMany/belongsToMany 各自必填项完整。
- 校验失败时只标记当前 collection invalid，不影响其他 collection。
- association controller 不应在运行期才出现 NPE 或 SQL grammar error。

验收:

- 每种 relation 类型都有 happy path 和 invalid path。
- invalid relation reload 后 registry 不包含该 collection 或标记 invalid。
- 错误消息不泄露 SQL。

#### P1-D2. 明确 SQL/external relation 策略

要求:

- SQL collection 可作为只读 relation source/target 的 list/get/appends。
- SQL collection 不允许作为 association write 的 source 或 target。
- external SQL collection 与 main physical collection 的 relation 只支持读路径。
- 跨 datasource write relation 明确 forbidden。

验收:

- main physical -> SQL target append/list。
- SQL source -> physical target list。
- 任意 SQL source/target add/remove/set rejected。

---

### Group E: Field Options 与 Schema Plan

#### P1-E1. Field options parser 强类型化

要求:

- 将 nullable/default/length/precision/scale/index/unique/options 解析集中到一个 parser/model。
- DDL 不直接读取原始 JSON Map。
- 非法 options 在 DDL 前报 metadata 错误，不生成半截 DDL。

验收:

- string length、decimal precision/scale、nullable/default 测试。
- 非法 options 类型测试。
- 兼容现有前端 metadata shape。

#### P1-E2. Schema diff / dry-run plan

要求:

- 生成内部 schema plan:
  - missing table
  - missing column
  - missing index
  - incompatible column change
  - no-op
- 危险变更默认不自动执行破坏性 DDL。
- 不暴露前端接口，先用于测试和后续 upgrade。

验收:

- schema plan 单元测试。
- reload/upgrade 多次执行 idempotent。

---

### Group F: 架构边界与验收

#### P1-F1. 架构边界测试

要求:

- controller 不能直接注入 `JdbcTemplate`。
- relation/association 服务不能直接注入 `JdbcTemplate`。
- 物理 CRUD SQL 只能从 builder 生成。
- DDL 只能从 DDL synchronizer/schema sync 入口执行。
- `CollectionManagerService` 不允许作为第二套动态 DDL 通道。

验收:

- 新增或扩展 `ArchitectureBoundaryTest`。

#### P1-F2. API 兼容回归

要求:

- controller 层测试覆盖:
  - collection list/get/create/update/destroy。
  - association list/add/remove/set。
  - appends。
  - SQL collection list/get/error。
- 确认前端 API envelope 不变。

#### P1-F3. PostgreSQL acceptance 扩展

要求:

- 无 PG 环境变量时继续 skipped。
- 有 PG 环境变量时覆盖:
  - physical CRUD builder。
  - index sync。
  - relation append/list。
  - SQL collection external datasource。

---

## 3. 建议并行安排

- Claude-1: Group A
- Claude-2: Group B
- Claude-3: Group C
- Claude-4: Group D
- Claude-5: Group E/F

合并顺序:

1. Group A 先合并，补齐本轮 review 缺口。
2. Group B 第二合并，先收敛 SQL builder。
3. Group C/D/E 在 builder 后合并，减少 SQL 分叉。
4. Group F 最后统一验收。

---

## 4. 完成总结要求

完成后输出:

```text
DATA_LAYER_PHASE5_CORE_COMPLETION_SUMMARY.md
```

总结必须包含:

- 每个任务完成状态。
- 修改文件列表。
- 新增/修改测试列表。
- 关键调用链说明。
- `mvn -q test` 结果。
- 是否修改前端文件: 必须为否。
- 是否改变 API 响应结构: 必须为否。
- `listAllInternal()` 是否还会静默截断: 必须为否。
- identifier quote 是否已统一到 `SqlIdentifier`/dialect/builder。
- 物理 CRUD SQL 是否已从 `DynamicRepository` 移到 builder。
- index sync 是否 idempotent。

---

## 5. 最终验收命令

```bash
mvn -q test
rg -n "Exception during pool initialization|Caused by: java\\.net|jdbc:h2:tcp://localhost:19999|localhost:19999" target/surefire-reports
rg -n "return \"\\\\\\\"\" \\+ identifier|escapeIdentifier|replaceAll\\(\" src/main/java/com/nocobase/data src/main/java/com/nocobase/ddl src/main/java/com/nocobase/service
rg -n "JdbcTemplate" src/main/java/com/nocobase/controller src/main/java/com/nocobase/data/RelationQueryService.java src/main/java/com/nocobase/data/AssociationActionService.java
rg -n "CREATE INDEX|DROP INDEX|ALTER TABLE|CREATE TABLE|DROP TABLE" src/main/java/com/nocobase
```

验收说明:

- 第一条必须通过。
- 第二条不得命中 datasource failure 原始日志。
- 第三条不得命中未解释的本地 quote/escape。
- 第四条 controller/relation/association 不应直连 JDBC。
- 第五条允许命中 builder/synchronizer/dialect，不应在 controller/service 中散落。
