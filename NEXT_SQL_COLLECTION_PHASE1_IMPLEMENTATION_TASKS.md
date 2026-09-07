# 下一批开发任务：SQL Collection Phase 1 真正落地与数据层硬化

> 面向 Claude 的后端开发任务清单。前端保持不变，只修改 Java 后端。

## Review 结论

`SQL_COLLECTION_PHASE1_COMPLETION_SUMMARY.md` 的结论不应视为 SQL Collection Phase 1 已完成。当前状态是：

- 本地执行 `mvn -q test` 通过，结果为 `102 tests, 0 failures, 0 errors`。
- 未发现 `SqlQueryCollectionExecutor` 或等价 SQL collection 专用执行器。
- `collection.type = "sql"` 目前仍会走 `DynamicRepository` 的物理表查询路径。
- `SQL_QUERY_COLLECTION_DESIGN.md` 仍是设计文档，尚未落地到代码。
- `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps` 仍直接调用 `DynamicRepository.createLink/deleteLink`，没有覆盖 `AssociationActionService.add/remove/set/list`。
- `DynamicRepository.destroy()`、`existsInScope()`、`readAfterWrite()` 仍硬编码 `"id" = ?`。
- `AclService.filterReadableFields()` 仍硬编码保留 `id`，而 `DynamicRepository` 又有另一套字段过滤逻辑，行为没有统一。
- `CollectionRuntimeService.parsePrimaryKey()` 只读取 `options.primaryKey`，但没有校验字段存在、字段是物理字段、effective column name 合法。
- view/sql collection 跳过 create DDL，但 `addField/dropField/dropCollection` 对 view/sql 的 DDL 行为还没有完整边界。

下一批必须完成两个目标：

1. 修完前一轮“声明完成但未闭环”的 P0 问题。
2. 真正实现 SQL Query Collection Phase 1，而不是只更新设计文档。

## 全局约束

- 只改 Java 后端，不改前端、不改 API 形态、不改页面行为。
- 前端仍通过 NocoBase 原有 collection action 调用，例如 `/api/{collection}:list`、`/api/{collection}:get`。
- SQL collection 的 SQL 只能来自 collection metadata，不能写死在 controller/service 中。
- Controller、普通 service、relation、association 不允许直接持有 `JdbcTemplate`。
- `DynamicRepository` 是统一数据访问入口；SQL collection 可拆专用 executor，但 executor 只能由 `DynamicRepository` 调用。
- 公开 API 必须继续执行 action 权限、action scope、字段权限。
- SQL collection Phase 1 只读：`create/update/destroy` 必须被 capability 拒绝。

## 并行安排

- **A 线：P0 数据层硬化**。先修 association 测试、主键硬编码、字段权限统一。可以和 B 线并行，但合并前必须全量测试。
- **B 线：P1/P2 SQL collection executor**。实现 SQL executor、SQL 安全校验、SQL collection 测试。依赖主键和字段权限接口时，要跟 A 线对齐。
- **C 线：文档和架构边界**。同步更新设计文档、架构 allowlist、完成总结。

## P0-A：返工真实 belongsToMany association API 测试

**目标**

补齐真实 association API 的测试闭环，不能再只测 `DynamicRepository.createLink/deleteLink`。

**开发要求**

- 重写 `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps` 或新增独立测试类。
- 创建 source、target、through 三个 collection。
- 在 source collection 创建真实 `belongsToMany` 字段元数据，字段必须包含：
  - `target`
  - `through`
  - `sourceKey`
  - `targetKey`
  - `foreignKey`
  - `otherKey`
- `runtimeService.reload(sourceCollection)` 后断言 `getRelation(fieldName)` 存在且内容正确。
- 测试必须调用 `AssociationActionService`：
  - `add(source + "." + fieldName, sourceId, targetId)`
  - `list(source + "." + fieldName, sourceId)`
  - `remove(source + "." + fieldName, sourceId, targetId)`
  - `set(source + "." + fieldName, sourceId, targetIds)`
- 主要验收路径中禁止直接调用 `dynamicRepository.createLink/deleteLink/replaceLinks`。

**验收标准**

- 不授予 through collection 任何 action 权限时，association add/list/remove/set 正常工作。
- 给 through collection 配置严格 scope，不影响 through 内部操作。
- 给 target collection 配置 list scope，不匹配 target 不出现在 association list。
- 如果 `AssociationActionService.listBelongsToMany()` 改回 `listViaFilter()`，测试必须失败。

## P0-B：修正 DynamicRepository 剩余主键硬编码

**目标**

所有主键语义统一来自 `CollectionDefinition`，不能在动态数据层继续硬编码 `id`。

**开发要求**

- 替换以下硬编码：
  - `destroy()` 中的 `"id" = ?`
  - `existsInScope()` 中的 `"id" = ?`
  - `readAfterWrite()` 中的 `"id" = ?`
  - `readAfterWrite()` partial fields 分支里的 `filtered.put("id", row.get("id"))`
  - `get()` 字段过滤分支里的 `id`
  - `AclService.filterReadableFields()` 中保留 `id` 的逻辑
  - `FilterCompiler.resolveColumn("id")` 的固定系统列白名单逻辑
  - `listLinks()` 里无条件 `SELECT ..., "id"`
- 无主键 collection：
  - `list` 允许。
  - `get/update/destroy/existsInScope/readAfterWrite` 抛明确异常，例如 `IllegalArgumentException` 或领域异常。
  - through 内部操作不应强制依赖 through 主键。

**验收标准**

- 新增自定义主键 collection 测试，覆盖 `create/get/update/destroy/existsInScope/readAfterWrite`。
- 新增无主键 view/sql collection 测试，`list` 可执行，`get` 明确拒绝。
- 全仓搜索动态数据层，不应再出现主键语义上的硬编码 `"id"`；确需保留的系统字段必须写明原因。

## P0-C：主键元数据必须 fail-fast 校验

**目标**

`options.primaryKey` 不能只是字符串透传，必须验证它确实代表有效物理字段。

**开发要求**

- `CollectionRuntimeService.parsePrimaryKey()` 必须校验：
  - primaryKey 字段存在于 fields metadata，或是默认系统 `id`。
  - 字段必须是 physical field。
  - 字段不能是 relation/virtual field。
  - effective column name 合法。
- 对 view/sql collection：
  - 未配置 primaryKey 时 `hasPrimaryKey=false`。
  - 配置 primaryKey 时必须校验字段存在且可映射到结果列。
- 非法 primaryKey 配置必须 fail-fast，不能 warning 后继续启动成错误 metadata。

**验收标准**

- `options.primaryKey = "code"` 且 `code` 存在时通过。
- `options.primaryKey = "missing"` 时 reload 失败。
- `options.primaryKey` 指向 relation/virtual field 时 reload 失败。
- view/sql 配置合法 primaryKey 时 `getPrimaryKeyFieldName()` 正确。

## P0-D：统一字段权限过滤实现

**目标**

`list/get/readAfterWrite` 只能有一套字段过滤语义，避免主键、none、partial readable 行为分叉。

**开发要求**

- 将字段过滤集中到 `AclService` 或独立 `FieldPermissionFilter`。
- 过滤方法需要拿到 `CollectionDefinition`，以便使用真实 primary key。
- `DynamicRepository.list()`、`get()`、`readAfterWrite()` 必须调用同一过滤方法。
- `FieldPermission.none()`：
  - 有主键时返回 `{primaryKeyField: value}`。
  - 无主键时返回空 map。
- partial readable：
  - 返回允许字段。
  - 自动保留 primary key，而不是固定 `id`。

**验收标准**

- 默认 `id` 主键、custom primary key、无主键三类测试都覆盖 readable none/partial/all。
- 删除统一过滤方法中的主键保留逻辑时，相关测试必须失败。

## P0-E：清理测试隔离与弱断言

**目标**

避免权限测试因为顺序、共享角色、静默 helper 导致误报。

**开发要求**

- 移除 `ActionScopeRelationReviewTest` 对 `@TestMethodOrder` 的依赖。
- 每个测试使用独立 collection 名、独立数据，或在测试结束清理 role resource/action/scope。
- helper 方法不能静默 `return`：
  - 找不到 member role 时 `fail()`。
  - 保存权限失败时抛异常。
- 替换 `assertThrows(Exception.class)` 为具体异常类型。
- 对 `assertDoesNotThrow` 的测试必须追加业务结果断言，例如实际 link 数量、返回数据内容、权限过滤结果。

**验收标准**

- `ActionScopeRelationReviewTest` 单独运行通过。
- 全量 `mvn -q test` 通过。
- 随机调整测试顺序不影响结果。

## P1-F：补齐 view/sql DDL 边界

**目标**

view/sql collection 不能被动态 DDL 当作普通物理表处理。

**开发要求**

- `DdlSynchronizer.createCollection()` 已跳过 view/sql create table，继续补齐：
  - `addField()` 对 view/sql 只保存 metadata，不执行 `ALTER TABLE ADD COLUMN`。
  - `dropField()` 对 view/sql 只删除 metadata，不执行 `ALTER TABLE DROP COLUMN`。
  - `dropCollection()` 对 view/sql 默认只删 metadata，不执行 `DROP TABLE`；如未来需要 drop view，必须显式能力开关。
- physical collection 行为保持不变。
- view collection 映射现有 table/view 的测试必须不依赖 createCollection 创建普通表。

**验收标准**

- view/sql add field 不执行 DDL。
- view/sql drop field 不执行 DDL。
- view/sql drop collection 不 drop 被映射的真实表/view。
- physical collection create/add/drop/dropCollection 仍按原逻辑工作。

## P1-G：收紧 through 字段校验

**目标**

through 内部 SQL 的 `sourceKey/otherKey` 必须映射到真实物理列，不能只判断 field name 存在。

**开发要求**

- `validateThroughColumn()` 返回有效 DB column name，而不是返回 void。
- 校验字段必须存在于 through metadata，且 `FieldDefinition.isPhysical()` 为 true。
- SQL 拼接使用 `FieldDefinition.getEffectiveColumnName()`。
- 不要把所有系统列都视为默认可用；系统列也必须由 collection 主键/系统字段元数据确认。
- relation 字段、虚拟字段、非法字段名必须拒绝。

**验收标准**

- effective column name 和 field name 不同的 through key 能正常工作。
- 非物理字段作为 through key 被拒绝。
- 注入式 field name 和 effective column name 都被拒绝。

## P1-H：架构边界为 SQL executor 开口，但不开散

**目标**

新增 SQL executor 后，架构测试允许它持有 SQL 执行能力，但继续禁止 SQL 散落。

**开发要求**

- `ArchitectureBoundaryTest` allowlist 增加 SQL executor 专用类名，例如 `SqlQueryCollectionExecutor.java`。
- 合并重复的一层目录扫描，保留递归扫描。
- `CollectionManagerService.java` 仍在 allowlist 时必须加 `@Deprecated` 并明确“不再用于业务数据访问”；最好移除其 `JdbcTemplate`。
- 测试要能识别 import、字段、构造器参数中的 `JdbcTemplate`。

**验收标准**

- 新增 executor 在 allowlist 内通过。
- `Controller`、`RelationQueryService`、`AssociationActionService`、普通 service 新增 `JdbcTemplate` 依赖时测试失败。

## P2-I：实现 SQL Query Collection Phase 1

**目标**

真正实现 `collection.type = "sql"` 的只读 list 能力。

**开发要求**

- 新增专用组件，例如 `SqlQueryCollectionExecutor`。
- `DynamicRepository.list()` 判断 `def.isSql()` 后委托 SQL executor，不走物理表路径。
- SQL 来源为 `CollectionDefinition.getSql()`。
- Phase 1 必须支持：
  - 外层包装：`SELECT <projection> FROM (<configuredSql>) _nocobase_sub`
  - `filter`
  - `sort`
  - `page/pageSize`
  - `count`
  - action scope
  - readable fields
- `get()`：
  - 如果 SQL collection 无 primaryKey，明确拒绝。
  - 如果有 primaryKey，可暂缓实现，但必须返回明确异常并在总结说明；推荐本批实现。
- `create/update/destroy` 通过 capability 拒绝。
- SQL executor 不允许被 controller/service 直接调用。

**验收标准**

- SQL collection list 返回分页数据。
- SQL collection filter/sort/page 生效。
- SQL collection count 正确。
- SQL collection action scope 生效。
- SQL collection readable fields 生效。
- SQL collection create/update/destroy 返回 Forbidden。

## P2-J：SQL 安全校验 Phase 1

**目标**

防止 SQL collection 成为任意 SQL 执行入口。

**开发要求**

- configured SQL 必须是单条 `SELECT` 或 `WITH ... SELECT` 查询。
- 禁止多语句分隔符。
- 禁止顶层 DDL/DML：`INSERT/UPDATE/DELETE/MERGE/ALTER/DROP/TRUNCATE/CREATE/GRANT/REVOKE/CALL/EXEC`。
- 禁止 SQL 注释绕过：`--`、`/* */`。
- 用户 filter/sort/scope 必须继续通过结构化 DSL 编译和参数绑定。
- Phase 1 可暂不支持 configured SQL 参数；如果 SQL 中包含 `:param` 或 `?`，应明确拒绝或实现完整绑定，不能半支持。
- 普通用户错误响应不能泄露完整 configured SQL。

**验收标准**

- 合法 SELECT 通过。
- DDL/DML/multi-statement/comment injection 被拒绝。
- filter 值中的注入字符串只作为参数值，不改变 SQL 结构。

## P2-K：SQL collection 测试矩阵

**目标**

新增专门测试类，证明 SQL collection 与现有前端 API 兼容。

**开发要求**

- 新增 `SqlQueryCollectionTest` 或等价测试类。
- 测试数据可来自测试中创建的 physical table，然后 SQL collection metadata 映射该查询。
- 覆盖：
  - list 基础查询。
  - filter。
  - sort。
  - page/pageSize。
  - count。
  - fields projection。
  - readable fields。
  - action scope。
  - no primaryKey get rejection。
  - write action rejection。
  - unsafe SQL rejection。
- 测试不能直接调用 SQL executor 绕过 `DynamicRepository`；公开语义测试必须通过 `DynamicRepository.list/get/create/update/destroy`。

**验收标准**

- 测试总数必须增长，不能仍是 102。
- `mvn -q test` 全量通过。

## P2-L：更新 SQL 设计文档到实现一致

**目标**

让 `SQL_QUERY_COLLECTION_DESIGN.md` 不再停留在旧描述，和 Phase 1 实现边界一致。

**开发要求**

- 明确当前是否支持：
  - named parameters。
  - `get`。
  - `WITH` 查询。
  - nested order by。
  - SQL collection relation。
- 补充 executor 调用链：
  - `GenericCrudController -> DynamicRepository -> SqlQueryCollectionExecutor`
- 补充安全校验实际规则。
- 补充测试矩阵和暂缓项。

**验收标准**

- 文档不再写“支持命名参数”，除非代码已实现并有测试。
- 文档列出的 Phase 1 能力必须和测试一致。

## Claude 交付要求

完成后输出总结文档：`SQL_COLLECTION_PHASE1_IMPLEMENTATION_COMPLETION_SUMMARY.md`。

总结必须包含：

- 每个任务的完成状态。
- 修改文件列表。
- 是否修改前端，答案必须是“否”。
- SQL collection executor 的调用链。
- SQL 安全校验规则。
- 主键元数据来源和剩余硬编码 `id` 列表；如有保留必须逐项解释。
- association API 真实测试覆盖说明。
- view/sql DDL 边界说明。
- 架构边界 allowlist。
- 测试结果，必须包含 `mvn -q test` 的总用例数、失败数、错误数。
