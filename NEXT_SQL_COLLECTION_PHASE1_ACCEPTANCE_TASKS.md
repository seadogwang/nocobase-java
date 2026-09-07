# 下一批开发任务：SQL Collection Phase 1 验收补全与数据层遗留修正

> 面向 Claude 的后端开发任务清单。前端保持不变，只修改 Java 后端。

## Review 结论

`SQL_COLLECTION_PHASE1_IMPLEMENTATION_COMPLETION_SUMMARY.md` 的方向基本正确：项目中已新增 `SqlQueryCollectionExecutor` 和 `SqlValidator`，`DynamicRepository.list()` 已开始在 `def.isSql()` 时委托 SQL executor。

但当前不能视为 SQL Collection Phase 1 已完全验收，原因如下：

- 测试总数仍是 `102`，没有新增 SQL collection 测试矩阵。
- `SqlQueryCollectionExecutor` 没有任何直接或间接的 SQL collection 行为测试。
- `DynamicRepository.get()` 对 SQL collection 仍会走物理表路径，不是明确拒绝或 SQL executor 查询。
- `destroy()`、`existsInScope()`、`readAfterWrite()` 仍能看到主键语义上的 `"id"` 硬编码。
- `AclService.filterReadableFields()` 仍硬编码保留 `id`，字段过滤没有和 `CollectionDefinition` 主键元数据统一。
- `CollectionRuntimeService.parsePrimaryKey()` 解析了 `options.primaryKey`，但没有 fail-fast 校验字段存在、字段物理性和 effective column name 合法性。
- `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps` 仍直接调用 `DynamicRepository.createLink/deleteLink`，没有覆盖真实 `AssociationActionService.add/remove/set/list`。
- `DdlSynchronizer.createCollection()` 已跳过 view/sql 建表，但 `addField/dropField/dropCollection` 对 view/sql 的边界还没闭环。
- `SqlValidator` 当前是字符串/正则校验，存在误杀字符串字面量、未拒绝尾部分号、未拒绝 configured SQL 参数占位符等边界问题。

本批任务目标：把 SQL collection 从“有执行器”推进到“可验收的 Phase 1”，同时清理仍会影响主数据层抽象的 P0 遗留项。

## 全局约束

- 只改 Java 后端，不改前端、不改 API 形态、不改页面行为。
- SQL collection 前端调用仍使用现有 collection action，例如 `/api/{collection}:list`。
- `GenericCrudController -> DynamicRepository -> SqlQueryCollectionExecutor` 是唯一允许的 SQL collection 调用链。
- Controller、普通 service、relation、association 不能直接使用 `JdbcTemplate` 或调用 SQL executor。
- SQL collection Phase 1 只读，`create/update/destroy` 必须明确被拒绝。
- 所有权限闭环保持：action 权限、action scope、readable fields。

## P0-A：新增 SQL Collection 测试矩阵

**目标**

补齐 SQL collection 的真实行为测试。测试必须通过 `DynamicRepository` 触发，不能直接调用 executor 绕过公开数据层语义。

**开发要求**

- 新增测试类：`SqlQueryCollectionTest`。
- 测试准备：
  - 创建一个 physical collection 或测试表作为底层数据来源。
  - 插入多条可区分数据，例如 status、owner_id、price、category。
  - 创建 `type = "sql"` 的 collection metadata，`sql` 指向底层 SELECT。
  - 为 SQL collection 显式声明 fields metadata。
- 测试必须覆盖：
  - 基础 `list`。
  - `filter`。
  - `sort`。
  - `page/pageSize`。
  - `count`。
  - `fields` projection。
  - readable fields 过滤。
  - list action scope。
  - 无 primaryKey 时 `get` 明确拒绝。
  - write action：`create/update/destroy` 明确 Forbidden。
  - unsafe SQL rejection。

**验收标准**

- 测试总数必须增长，不能仍是 `102`。
- 故意让 `DynamicRepository.list()` 不走 `SqlQueryCollectionExecutor` 时，SQL collection 测试必须失败。
- 故意移除 scope 合并时，SQL collection scope 测试必须失败。

## P0-B：修正 SQL Collection get 语义

**目标**

SQL collection 的 `get` 不能再走物理表路径。

**开发要求**

- `DynamicRepository.get()` 检测 `def.isSql()`：
  - 无 primaryKey：抛明确异常或 Forbidden，不能查询物理 tableName。
  - 有 primaryKey：本批建议实现 `SqlQueryCollectionExecutor.executeGet()`；如果暂缓，必须明确拒绝并有测试。
- 如果实现 `executeGet()`：
  - 复用 configured SQL 外层包装。
  - 在外层追加 primary key filter。
  - 执行 `get` action 权限和 `get` scope。
  - 执行 readable fields 过滤。
- 错误信息不能暴露完整 configured SQL。

**验收标准**

- SQL collection 无主键 `get` 不产生 SQL table not found。
- SQL collection 有主键 `get` 成功或明确按设计拒绝。
- `get` scope 与 `list` scope 能独立生效。

## P0-C：统一字段权限过滤与主键保留

**目标**

所有 list/get/readAfterWrite/SQL list 的字段过滤使用同一套逻辑。

**开发要求**

- 改造 `AclService.filterReadableFields()` 或新增 `FieldPermissionFilter`。
- 过滤方法必须接收 `CollectionDefinition`，不能只传 `resourceName`。
- 主键保留使用 `def.getPrimaryKeyFieldName()`，不能硬编码 `id`。
- 无主键 collection 在 readable none/partial 时不能泄露业务字段。
- `DynamicRepository.list()`、`get()`、`readAfterWrite()`、`executeSqlList()` 必须调用同一个过滤方法。
- 删除重复字段过滤代码，避免物理表和 SQL collection 行为分叉。

**验收标准**

- 默认 `id` 主键、custom primary key、无主键 SQL collection 都有字段权限测试。
- readable none：
  - 有主键返回主键最小集。
  - 无主键返回空对象。
- readable partial 自动保留主键。

## P0-D：修正剩余主键硬编码与无主键保护

**目标**

动态数据层主键语义完全来自 `CollectionDefinition`。

**开发要求**

- 替换以下仍存在的问题：
  - `destroy()` 中的 `"id" = ?`
  - `existsInScope()` 中的 `"id" = ?`
  - `readAfterWrite()` 中的 `"id" = ?`
  - `readAfterWrite()` partial 分支中的 `id`
  - `FilterCompiler.resolveColumn("id")` 的固定主键白名单
  - `listLinks()` 无条件选择 through `"id"`
  - `AclFilterInjector` 无角色时 `Map.of("id", ...)` 的 no-result filter
- 对无主键 collection：
  - `list` 允许。
  - `get/update/destroy/existsInScope/readAfterWrite` 必须明确拒绝，不进入错误 SQL。
  - no-result filter 不能依赖固定 `id`。

**验收标准**

- 自定义主键 collection 覆盖 `get/update/destroy/existsInScope/readAfterWrite`。
- 无主键 SQL/view collection 的 `list` 通过，`get` 明确拒绝。
- 搜索 `DynamicRepository`、`AclService`、`FilterCompiler`，不存在主键语义上的硬编码 `id`。

## P0-E：主键元数据 fail-fast 校验

**目标**

`options.primaryKey` 必须是有效元数据，不允许错误配置静默进入运行时。

**开发要求**

- `CollectionRuntimeService.parsePrimaryKey()` 校验：
  - primaryKey 字段存在，或为系统默认 `id`。
  - 指定字段必须是 physical field。
  - 指定字段不能是 relation field。
  - effective column name 必须是合法标识符。
- 对 SQL collection：
  - primaryKey 必须在 SQL collection fields metadata 中存在。
  - 不要求它是物理表列，但必须能作为 SQL 结果集字段被外层 filter 使用。
- 对 view collection：
  - primaryKey 必须存在于 fields metadata。
- 非法配置 reload 必须失败，不能 warning 后继续。

**验收标准**

- `primaryKey = "code"` 且字段存在时通过。
- `primaryKey = "missing"` reload 失败。
- `primaryKey` 指向 relation/virtual field 时 reload 失败。
- SQL collection 配置合法 primaryKey 后，get 策略按 P0-B 生效。

## P0-F：返工真实 belongsToMany association 测试

**目标**

完成前几轮一直遗留的真实 association API 测试。

**开发要求**

- 创建 source、target、through 三个 collection。
- 在 source collection 创建真实 `belongsToMany` 字段元数据。
- `runtimeService.reload(sourceCollection)` 后断言 relation metadata 正确。
- 测试必须调用：
  - `associationActionService.add()`
  - `associationActionService.list()`
  - `associationActionService.remove()`
  - `associationActionService.set()`
- 主要验收路径中不允许直接调用 `dynamicRepository.createLink/deleteLink/replaceLinks`。
- 补 source/target 权限和 scope 测试：
  - 缺 source get，list 拒绝。
  - 缺 target list，list 拒绝。
  - 缺 source update，add/remove/set 拒绝。
  - target update scope 不匹配，add/remove/set 拒绝。
  - through 权限和 through scope 不影响内部 through 操作。

**验收标准**

- 如果 `AssociationActionService.listBelongsToMany()` 改回错误的 `listViaFilter()`，测试失败。
- 测试不依赖执行顺序。

## P1-G：补齐 view/sql DDL 边界

**目标**

view/sql collection 只保存元数据，不对映射对象执行普通表 DDL。

**开发要求**

- `DdlSynchronizer.addField()`：
  - physical collection 执行 `ALTER TABLE ADD COLUMN`。
  - view/sql collection 只保存 fields metadata。
- `DdlSynchronizer.dropField()`：
  - physical collection 执行 `ALTER TABLE DROP COLUMN`。
  - view/sql collection 只删除 fields metadata。
- `DdlSynchronizer.dropCollection()`：
  - physical collection 删除 metadata 后 drop table。
  - view/sql collection 只删除 metadata，不 drop mapped table/view。
- 补测试证明 mapped table/view 不被误删。

**验收标准**

- view/sql add/drop field 不执行 DDL。
- view/sql drop collection 不删除底层 table/view。
- physical collection 原行为不回退。

## P1-H：强化 SQL 安全校验

**目标**

当前 `SqlValidator` 是简单字符串正则，需要补齐 Phase 1 明确边界。

**开发要求**

- 明确 Phase 1 是否允许尾部分号；建议禁止所有分号。
- configured SQL 中出现 `?` 或 `:param`：
  - 如暂不支持参数，必须拒绝。
  - 如支持，必须完整绑定测试。
- 禁止注释时要避免错误信息泄露完整 SQL。
- DDL/DML 关键字检测至少要避开普通字符串字面量误杀，或在文档中说明 Phase 1 采用保守拒绝策略。
- `WITH` 查询必须确认最终是只读 SELECT，不能只判断前缀。

**验收标准**

- `SELECT 1;` 被拒绝。
- `SELECT * FROM t WHERE name = ?` 在不支持 configured SQL 参数时被拒绝。
- `SELECT * FROM t WHERE name = :name` 在不支持 named parameter 时被拒绝。
- `WITH ... SELECT ...` 合法。
- DDL/DML/multi-statement/comment injection 被拒绝。

## P1-I：补 SQL executor 结构和方言边界

**目标**

避免 SQL executor 后续变成另一个大 SQL 拼接类。

**开发要求**

- 将 SQL collection 的外层查询构建拆成清晰内部方法或小组件：
  - projection 构建
  - filter/scope 编译
  - sort 构建
  - count SQL 构建
  - pagination
- 继续使用结构化 metadata 校验字段。
- 暂时使用 JDBC `?` 占位即可，但要在代码注释/文档中留出方言分页和 identifier quote 的替换点。
- 不引入过度抽象；只拆真实重复和边界明显的部分。

**验收标准**

- SQL executor 代码可读，职责清楚。
- H2 测试通过。
- 文档标明 PostgreSQL/MySQL 后续差异点。

## P1-J：架构边界测试补强

**目标**

SQL executor 开口后，防止 SQL 执行能力扩散。

**开发要求**

- `ArchitectureBoundaryTest` 的 allowlist 保留：
  - `DynamicRepository.java`
  - `SqlQueryCollectionExecutor.java`
  - `DdlSynchronizer.java`
  - `DialectAdapterFactory.java`
- 评估移除 `CollectionManagerService.java` 的 allowlist；如暂留，必须确认类上有 `@Deprecated` 且没有被新代码注入。
- 递归扫描所有 `src/main/java/com/nocobase`。
- 检查 `JdbcTemplate` import、字段、构造器参数、方法参数。
- 检查 controller/service 不直接 import `SqlQueryCollectionExecutor`。

**验收标准**

- controller/service/relation/association 新增 `JdbcTemplate` 依赖测试失败。
- controller/service 直接调用 SQL executor 测试失败。

## P1-K：更新 SQL 设计文档

**目标**

让 `SQL_QUERY_COLLECTION_DESIGN.md` 与真实 Phase 1 能力一致。

**开发要求**

- 明确当前支持：
  - list
  - filter/sort/page/count
  - action scope
  - readable fields
  - fields projection
  - unsafe SQL rejection
- 明确当前暂缓：
  - configured SQL 参数
  - named parameters
  - SQL collection relation
  - get，如 P0-B 未实现
- 加入实际调用链：
  - `GenericCrudController -> DynamicRepository -> SqlQueryCollectionExecutor`
- 加入测试矩阵和安全规则。
- 删除或修正“已支持命名参数”的表述，除非本批实现并测试通过。

**验收标准**

- 文档能力边界和代码一致。
- 至少保留 3 个前端 API 不变示例。

## Claude 交付要求

完成后输出总结文档：`SQL_COLLECTION_PHASE1_ACCEPTANCE_COMPLETION_SUMMARY.md`。

总结必须包含：

- 每个 P0/P1 任务的完成状态。
- 修改文件列表。
- 是否修改前端，答案必须是“否”。
- SQL collection 完整调用链。
- SQL collection 测试矩阵和新增测试数量。
- 主键元数据来源、无主键行为、剩余硬编码 `id` 列表。
- association belongsToMany 的真实测试覆盖说明。
- view/sql DDL 边界说明。
- SQL 安全校验规则和暂缓项。
- 架构边界 allowlist。
- `mvn -q test` 的总测试数、失败数、错误数。
