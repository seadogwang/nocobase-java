# 下一批开发任务：返工 Association/主键元数据，并实现 SQL Collection 起步

> 面向 Claude 的后端开发任务清单。前端保持不变，只修改 Java 后端。

## Review 结论

`ASSOCIATION_PRIMARY_KEY_VIEW_COMPLETION_SUMMARY.md` 和 `SQL_QUERY_COLLECTION_DESIGN.md` 的方向是对的，但当前实现还不能认为已完成主数据源抽象升级。

本地执行 `mvn -q test` 通过，测试汇总为 `102 tests, 0 failures, 0 errors`。但是这轮测试数量没有增长，且部分测试仍没有覆盖真实后端路径。

必须先修正以下问题：

- `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps` 仍然直接调用 `DynamicRepository.createLink/deleteLink`，没有走 `AssociationActionService.add/remove/set/list`。
- `belongsToManyThroughInternalOps` 没有创建 belongsToMany 字段元数据，所以没有证明 runtime relation metadata 生效。
- `ActionScopeRelationReviewTest` 仍使用 `@TestMethodOrder`，且权限 helper 找不到 member role 时静默 return，测试隔离不够可靠。
- `invalidThroughColumnsRejected`、`sqlInjectionColumnNameRejected` 仍使用 `assertThrows(Exception.class)`，异常断言不够具体。
- `CollectionDefinition.getPrimaryKeyFieldName()` 仍固定返回 `"id"`，不是可配置主键抽象。
- `DynamicRepository.destroy()`、`existsInScope()`、`readAfterWrite()` 仍硬编码 `"id"` 条件。
- `DynamicRepository.create()`、`createLink()` 仍使用 `new String[]{"id"}` 获取 generated key。
- `DynamicRepository.list()/get()/readAfterWrite()` 的字段过滤仍有重复实现，并且保留字段仍偏向硬编码 `id`。
- View collection 当前只是 capability 上只读，DDL 层仍会为 view 创建普通物理表，不是真正 view collection 能力。

因此下一批任务分两部分：P0/P1 修正前一轮未完成点，P2 开始实现 SQL query collection executor 的第一阶段。

## 全局约束

- 只改 Java 后端，不改前端、不改 API 形态、不改页面行为。
- 前端仍按 NocoBase 原有 collection action 和 association action 调用。
- 业务数据 SQL 不允许散落在 controller/service/relation/association。
- `DynamicRepository` 仍是数据访问统一入口；SQL collection 可以拆出专用 executor，但只能由 `DynamicRepository` 调用。
- 公开 API 必须执行 action 权限、action scope、字段权限。
- association 内部 through 操作继续绕过 through 表前端权限，但 source/target 权限和 scope 必须生效。

## P0-A：返工真实 belongsToMany association 测试

**目标**

让测试真正覆盖 NocoBase 前端会触发的 association API 后端路径，而不是直接测 repository 内部方法。

**开发要求**

- 重写 `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps`。
- 创建 source、target、through 三个 collection。
- 在 source collection 创建真实 `belongsToMany` 字段元数据，至少包含：
  - `target`
  - `through`
  - `sourceKey`
  - `targetKey`
  - `foreignKey`
  - `otherKey`
- `runtimeService.reload(sourceCollection)` 后必须断言 `CollectionDefinition.getRelation(fieldName)` 不为 null，且 relation 内容正确。
- 测试必须调用：
  - `associationActionService.add(source + "." + fieldName, sourceId, targetId)`
  - `associationActionService.list(source + "." + fieldName, sourceId)`
  - `associationActionService.remove(source + "." + fieldName, sourceId, targetId)`
  - `associationActionService.set(source + "." + fieldName, sourceId, targetIds)`
- 测试中不允许直接调用 `dynamicRepository.createLink/deleteLink/replaceLinks` 作为主要验收。

**验收标准**

- 不授予 through collection 任何 action 权限时，association add/list/remove/set 仍按预期工作。
- 给 through collection 配置严格 scope，不影响 association 内部 through 操作。
- 给 target collection 配置 list scope，不匹配的 target 不会出现在 association list 结果里。
- 如果 `AssociationActionService.listBelongsToMany()` 改回 `listViaFilter()`，测试必须失败。

## P0-B：修正 association 权限语义测试

**目标**

证明 association 公开 API 对 source/target 的 action 和 scope 检查符合既定语义。

**开发要求**

- list association：
  - 检查 source `get` action。
  - 检查 target `list` action。
  - source 不在 get scope 时应失败或不可见。
  - target 不在 list scope 时不返回该目标记录。
- add/remove/set association：
  - 检查 source `update` action。
  - 检查 target `update` action。
  - source/target 不在 update scope 时应拒绝。
- through 表权限和 scope 不参与前端权限判断。

**验收标准**

- 缺 source get 权限时，association list 被拒绝。
- 缺 target list 权限时，association list 被拒绝。
- 缺 source update 权限时，association add/remove/set 被拒绝。
- 缺 target update 权限时，association add/remove/set 被拒绝。

## P0-C：完成真正可配置的主键元数据抽象

**目标**

把主键从“默认 id 方法”升级成真正由 collection/field metadata 决定的能力。

**开发要求**

- 定义主键配置来源，优先建议：
  - `CollectionEntity.options.primaryKey` 指定主键字段名；或
  - `FieldEntity.options.primaryKey=true` 指定字段为主键。
- `CollectionRuntimeService.buildDefinition()` 必须解析主键配置并写入 `CollectionDefinition`。
- `CollectionDefinition` 必须保存主键字段名，不能 `getPrimaryKeyFieldName()` 固定返回 `"id"`。
- 默认 physical collection 仍兼容 `id`。
- view/sql collection 没配置主键时，`hasPrimaryKey()` 返回 false。
- 自定义主键字段必须存在、必须是 physical field，并能解析 effective column name。
- 主键配置非法时，runtime reload 应 fail-fast，不要静默退回 `id`。

**验收标准**

- 默认 physical collection 主键仍是 `id`。
- options 配置 `primaryKey` 后，`getPrimaryKeyFieldName()` 返回配置字段。
- 非法 primaryKey 字段导致 reload 失败。
- view/sql 无 primaryKey 时，list 可用，get/update/destroy 明确拒绝或提示需要主键。

## P0-D：替换 DynamicRepository 剩余 id 硬编码

**目标**

动态数据层所有主键语义统一使用 `CollectionDefinition` 主键元数据。

**开发要求**

- 替换以下位置的硬编码：
  - `create()` 的 `new String[]{"id"}`
  - `destroy()` 的 `"id" = ?`
  - `existsInScope()` 的 `"id" = ?`
  - `readAfterWrite()` 的 `"id" = ?`
  - `readAfterWrite()` 最小返回里的 `"id"`
  - `list()/get()` 字段过滤里自动保留的 `"id"`
  - `FilterCompiler.resolveColumn("id")` 对系统主键的固定白名单逻辑
  - `listLinks()` 返回 through 主键时的 `"id"`
  - `createLink()` generated key 和回查主键
- 如果 collection 无主键：
  - `list` 允许执行。
  - `get/update/destroy/existsInScope/readAfterWrite` 必须返回明确异常。
  - `listLinks/createLink` 不应强制依赖 through 主键。

**验收标准**

- 自定义主键 collection 的 `create/get/update/destroy/existsInScope/readAfterWrite` 全部通过。
- 无主键 view/sql collection 的 `list` 不因为缺 `id` 报错。
- 删除 `id` 系统列白名单后，默认 collection 仍通过主键元数据识别 `id`。

## P0-E：统一字段权限过滤逻辑

**目标**

避免 `list/get/readAfterWrite` 三处各自实现字段过滤，减少主键和字段权限行为分叉。

**开发要求**

- `AclService.filterReadableFields()` 接收 collection metadata 或能从 resourceName 获取 primary key metadata。
- `DynamicRepository.list()`、`get()`、`readAfterWrite()` 必须统一调用同一个字段过滤方法。
- `FieldPermission.none()` 时只返回允许的最小主键字段；无主键时返回空 map。
- 对用户请求 `fields` 参数时：
  - SQL SELECT projection 可按请求字段减少列。
  - 返回前仍必须再执行 readable fields 过滤。

**验收标准**

- readable fields 为 none 时，默认主键 collection 返回 id-only。
- 自定义主键 collection 返回 custom-pk-only。
- 无主键 collection 返回空对象，不泄露业务字段。

## P1-F：View Collection 做成真实只读视图抽象

**目标**

当前 view collection 只是 capability 只读，但 DDL 仍创建普通表。需要修成真正的数据层 view 抽象。

**开发要求**

- `DdlSynchronizer.createCollection()` 对 view collection 不创建普通表。
- 支持两种 view 来源：
  - 已存在数据库 view/table，通过 `tableName` 映射。
  - metadata 配置只读 SQL，后续可复用 SQL collection executor。
- view collection `create/update/destroy` 必须被 capability 拒绝。
- view collection `list` 必须执行 action 权限、scope、字段权限。
- view collection `get` 只有配置主键时允许；无主键时返回明确异常。
- 不要为了测试 view 而创建同名普通物理表。

**验收标准**

- 创建 view metadata 不触发 `CREATE TABLE`。
- 映射到已存在 view/table 的 view collection 可以 list。
- 无主键 view 可以 list，get 被明确拒绝。
- 有主键 view 可以 get。

## P1-G：清理测试隔离与弱断言

**目标**

让后续权限回归更可靠。

**开发要求**

- 移除 `ActionScopeRelationReviewTest` 对 `@TestMethodOrder` 的依赖。
- 每个测试使用独立 collection 名、独立数据、独立权限配置。
- helper 方法不能静默成功：
  - 找不到 member role 时 fail。
  - 权限保存失败时 fail。
- `assertThrows(Exception.class)` 全部替换为具体异常类型。
- 所有测试断言必须验证业务结果，不只验证“不抛异常”。

**验收标准**

- `ActionScopeRelationReviewTest` 单独运行通过。
- 全量 `mvn -q test` 通过。
- 随机调整测试顺序不影响结果。

## P1-H：收紧架构边界和 SQL 存放规则

**目标**

防止 SQL 再次混到 controller/service 中，为 SQL collection executor 落地铺路。

**开发要求**

- `ArchitectureBoundaryTest` 合并重复扫描逻辑，保留递归扫描。
- 明确 allowlist：
  - `DynamicRepository.java`
  - `DdlSynchronizer.java`
  - `DialectAdapterFactory.java`
  - 新增的 SQL/View executor 专用类
- 评估并移除 `CollectionManagerService.java` 中的 `JdbcTemplate`，如果短期不能移除，必须加 `@Deprecated` 并写明迁移任务。
- 新增测试：controller/service/data/acl/runtime 子包新增 `JdbcTemplate` import、字段、构造器参数时必须失败。

**验收标准**

- `RelationQueryService`、`AssociationActionService` 不允许出现在 JdbcTemplate allowlist。
- SQL collection executor 是唯一新增可持有 SQL 执行能力的组件。

## P2-I：实现 SQL Query Collection Phase 1

**目标**

按照 `SQL_QUERY_COLLECTION_DESIGN.md` 实现 SQL collection 的最小可用只读能力。

**开发要求**

- 新增专用组件，例如 `SqlQueryCollectionExecutor`，由 `DynamicRepository` 调用。
- `collection.type = "sql"` 时：
  - `list` 使用 SQL executor。
  - `get` 只有配置 primary key 时允许。
  - `create/update/destroy` 通过 capability 拒绝。
- SQL collection 的 SQL 来源只能来自 collection metadata，不允许写死在业务代码中。
- Phase 1 支持：
  - 外层包装 `SELECT * FROM (<configuredSql>) _nocobase_sub`
  - filter
  - sort
  - page/pageSize
  - count
  - readable fields
  - action scope
- 暂不要求 named parameter 完整支持；如果暂缓，必须在总结中说明。

**验收标准**

- SQL collection list 返回分页结果。
- filter/sort/page 对 SQL 结果集生效。
- list action 权限、scope、readable fields 生效。
- create/update/destroy 明确 Forbidden。
- SQL collection 不在 controller/service 中新增 SQL 拼接。

## P2-J：SQL 安全校验和参数策略

**目标**

让 SQL collection 不成为任意 SQL 执行入口。

**开发要求**

- SQL 必须是单条 SELECT。
- 禁止 DDL/DML 关键字作为顶层语句。
- 禁止多语句执行。
- 用户 filter/sort/page/scope 必须继续通过结构化编译和参数绑定。
- 配置 SQL 中是否允许参数，需要明确策略：
  - Phase 1 可以不支持配置 SQL 参数。
  - 如支持 named parameters，必须先编译成 JDBC `?` 并绑定白名单参数。
- 错误信息不能泄露敏感 SQL 细节给普通用户。

**验收标准**

- `SELECT ...` 合法。
- `UPDATE/DELETE/INSERT/ALTER/DROP/TRUNCATE` 被拒绝。
- 包含多语句分隔的 SQL 被拒绝。
- filter 值注入不会改变 SQL 结构。

## P2-K：补 SQL Query Collection 设计文档

**目标**

完善 `SQL_QUERY_COLLECTION_DESIGN.md`，让后续实现有清晰边界。

**开发要求**

- 增加主键策略章节：
  - 无主键只允许 list。
  - 有主键时支持 get。
- 增加安全校验章节：
  - 单条 SELECT。
  - 参数策略。
  - 错误处理策略。
- 增加 executor 边界章节：
  - controller/service 不拼 SQL。
  - `DynamicRepository -> SqlQueryCollectionExecutor`。
- 增加测试矩阵：
  - ACL。
  - scope。
  - fields。
  - paging/sort/filter。
  - unsafe SQL rejection。
- 修正文档里“支持命名参数”的描述：如果当前阶段不实现，不要写成已支持。

**验收标准**

- 文档和代码能力一致。
- 至少包含 3 个前端 API 不变的示例。
- 明确 H2/PostgreSQL/MySQL 差异及后续方言抽象点。

## Claude 交付要求

完成后输出总结文档：`SQL_COLLECTION_PHASE1_COMPLETION_SUMMARY.md`。

总结必须包含：

- 每个任务的完成状态。
- 修改文件列表。
- 是否修改前端，答案必须是“否”。
- association API 的真实调用链和测试覆盖说明。
- 主键元数据来源和剩余硬编码 `id` 列表；如果仍存在，必须逐项解释原因。
- view collection 当前支持范围。
- SQL collection Phase 1 支持范围和暂缓项。
- 架构边界 allowlist。
- 测试结果，必须包含 `mvn -q test` 的总用例数、失败数、错误数。
