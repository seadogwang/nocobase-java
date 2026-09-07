# 下一批开发任务：SQL Collection Phase 1 硬化与遗留权限闭环

> 面向 Claude 的后端开发任务清单。前端保持不变，只修改 Java 后端。

## Review 结论

`SQL_COLLECTION_PHASE1_ACCEPTANCE_COMPLETION_SUMMARY.md` 本轮有实质进展：

- 新增了 `SqlQueryCollectionExecutor`。
- 新增了 `SqlValidator`。
- `DynamicRepository.list()` 已在 `def.isSql()` 时委托 SQL executor。
- 新增 `SqlQueryCollectionTest`，测试总数从 `102` 增长到 `119`。
- 本地执行 `mvn -q test` 通过，当前结果为 `119 tests, 0 failures, 0 errors`。

但当前仍不能进入更复杂的多数据源/跨库阶段。SQL collection 的核心 list 路径已具备雏形，但权限、主键、DDL 和测试隔离还有 P0 缺口：

- `DynamicRepository.get()` 中计算了 SQL collection 的 `scopeFilter`，但没有传给 `SqlQueryCollectionExecutor.executeGet()`，SQL get 可能绕过 get scope。
- `SqlQueryCollectionTest` 覆盖了基础 list/filter/sort/page/count/projection/write rejection/validator，但缺少 SQL collection 的 action scope、readable fields、get scope、primaryKey 行为测试。
- `DynamicRepository.list()`、`get()`、`readAfterWrite()`、`executeSqlList()` 仍有重复字段过滤逻辑，`AclService.filterReadableFields()` 仍硬编码 `id`。
- `destroy()`、`existsInScope()`、`readAfterWrite()` 仍出现 `"id" = ?`，主键语义没有完全收敛到 metadata。
- `CollectionRuntimeService.parsePrimaryKey()` 仍缺少 fail-fast 校验。
- `DdlSynchronizer.addField/dropField/dropCollection` 对 view/sql collection 仍可能执行物理 DDL。
- `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps` 仍直接调用 `DynamicRepository.createLink/deleteLink`，没有覆盖真实 association API。
- `SqlQueryCollectionTest` 使用共享 collection 和 `>=` 断言，测试可能被历史数据污染。

本批目标：把 SQL Collection Phase 1 从“可跑”提升到“权限语义可验收”，同时清理前几批遗留 P0。

## 全局约束

- 只改 Java 后端，不改前端、不改 API 形态、不改页面行为。
- SQL collection 前端调用仍使用现有 collection action。
- SQL collection 唯一调用链：
  - `GenericCrudController -> DynamicRepository -> SqlQueryCollectionExecutor`
- Controller、普通 service、relation、association 不得直接使用 `JdbcTemplate`。
- Controller、普通 service 不得直接调用 `SqlQueryCollectionExecutor`。
- 公开 API 必须执行 action 权限、action scope、readable fields。
- SQL collection Phase 1 仍只读；`create/update/destroy` 必须拒绝。

## P0-A：修复 SQL collection get scope 绕过

**目标**

`DynamicRepository.get()` 对 SQL collection 必须真正应用 get action scope。

**问题定位**

当前代码中：

- `DynamicRepository.get()` 计算了 `scopeFilter = aclFilterInjector.mergeScopeFilter(...)`。
- 但随后调用的是 `sqlExecutor.executeGet(def, primaryKey)`。
- `scopeFilter` 没有传入 executor，也没有参与 SQL WHERE。

**开发要求**

- 修改 `SqlQueryCollectionExecutor.executeGet()` 签名，接收已合并的 filter，或让 `DynamicRepository` 调用统一的 SQL list/get 查询构建逻辑。
- SQL get 外层 WHERE 必须同时包含：
  - primary key filter。
  - get action scope。
- 不允许只靠 `executeGet(def, primaryKey)` 查询。
- get 结果仍必须执行 readable fields 过滤。
- 无 primaryKey SQL collection 的 get 保持明确拒绝。

**验收测试**

- SQL collection 配置 `primaryKey = "id"`。
- list scope 和 get scope 设置为不同 owner。
- `list()` 只能看到 list scope 数据。
- `get(listScopeRecord.id)` 返回 `null` 或不可见。
- `get(getScopeRecord.id)` 返回记录。
- 如果去掉 get scope 合并，测试必须失败。

## P0-B：补 SQL collection 权限测试矩阵

**目标**

让 SQL collection 的权限闭环真正被测试覆盖。

**开发要求**

在 `SqlQueryCollectionTest` 中新增或重写以下用例，必须通过 `DynamicRepository` 调用：

- `sqlListAppliesActionScope`
- `sqlListAppliesReadableFields`
- `sqlListReadableNoneReturnsPrimaryKeyOnly`
- `sqlListReadableNoneWithoutPrimaryKeyReturnsEmptyRows`
- `sqlGetAppliesGetScope`
- `sqlGetAppliesReadableFields`
- `sqlGetWithoutPrimaryKeyRejected`
- `sqlFilterValueInjectionIsParameterized`

**验收标准**

- 测试总数继续增长，不能仍是 `119`。
- 移除 `executeSqlList()` 中的 readable fields 过滤时，测试必须失败。
- 移除 SQL list scope 合并时，测试必须失败。
- 移除 SQL get scope 合并时，测试必须失败。

## P0-C：统一字段权限过滤实现

**目标**

消除物理 collection 和 SQL collection 各自实现字段过滤导致的行为分叉。

**开发要求**

- 新增或改造统一组件，例如：
  - `FieldPermissionFilter`
  - 或 `AclService.filterReadableFields(CollectionDefinition def, Map<String,Object> row)`
- 过滤方法必须接收 `CollectionDefinition`，不能只用 `resourceName`。
- 主键保留使用 `def.getPrimaryKeyFieldName()`。
- 无主键 collection：
  - readable none 返回空 map。
  - partial readable 只返回允许字段，不额外补 `id`。
- `DynamicRepository.list()`、`get()`、`readAfterWrite()`、`executeSqlList()` 全部调用同一过滤方法。
- 删除重复字段过滤代码。
- 保留旧 `filterReadableFields(String resourceName, ...)` 时，只能作为兼容入口，并委托新方法；不能继续硬编码 `id`。

**验收测试**

- 默认 `id` 主键 collection：readable none 返回 id-only。
- 自定义主键 collection：readable none 返回 custom-pk-only。
- 无主键 SQL collection：readable none 返回空对象。
- partial readable 自动保留真实主键。

## P0-D：彻底替换动态数据层主键硬编码

**目标**

主键语义不能再固定到 `id`。

**开发要求**

- 替换以下位置：
  - `DynamicRepository.destroy()` 的 `"id" = ?`
  - `DynamicRepository.existsInScope()` 的 `"id" = ?`
  - `DynamicRepository.readAfterWrite()` 的 `"id" = ?`
  - `DynamicRepository.readAfterWrite()` partial 分支中的 `id`
  - `FilterCompiler.resolveColumn("id")` 的固定主键白名单
  - `AclFilterInjector` 无角色 no-result filter 的固定 `id`
  - `DynamicRepository.listLinks()` 无条件 `SELECT ..., "id"`
- 如果 collection 无主键：
  - `list` 允许。
  - `get/update/destroy/existsInScope/readAfterWrite` 必须明确拒绝，不进入错误 SQL。
  - no-result filter 必须使用通用 false condition，不依赖主键字段。

**验收测试**

- 自定义主键 physical collection 覆盖 `get/update/destroy/existsInScope/readAfterWrite`。
- SQL collection 自定义主键覆盖 `get`。
- 无主键 SQL/view collection 的 `get` 明确拒绝。
- 搜索动态数据层，不存在主键语义硬编码 `id`；系统实体自身 `id` 不在本任务范围。

## P0-E：主键元数据 fail-fast 校验

**目标**

`options.primaryKey` 必须是有效元数据，不能错误配置后运行时才 SQL 失败。

**开发要求**

- `CollectionRuntimeService.parsePrimaryKey()` 校验：
  - `primaryKey` 字段存在于 fields metadata，或是默认系统 `id`。
  - physical collection 指定主键字段必须是 physical field。
  - relation/virtual field 不能作为 primaryKey。
  - effective column name 必须是合法 SQL identifier。
- view/sql collection：
  - 未配置 primaryKey 时 `hasPrimaryKey=false`。
  - 配置 primaryKey 时字段必须存在于 fields metadata。
  - SQL collection primaryKey 代表结果集字段，不要求底层物理表主键。
- 非法配置必须 fail-fast，不能 log warn 后继续。

**验收测试**

- legal primaryKey 通过。
- missing primaryKey 字段 reload 失败。
- relation primaryKey reload 失败。
- 非法 effective column name reload 失败。
- SQL collection legal primaryKey 支持 get scope 测试。

## P0-F：返工真实 belongsToMany association API 测试

**目标**

完成前几轮遗留：belongsToMany 必须通过 `AssociationActionService` 验收。

**开发要求**

- 创建 source、target、through 三个 collection。
- 在 source collection 创建真实 `belongsToMany` 字段元数据。
- `runtimeService.reload(source)` 后断言 relation metadata 正确。
- 测试必须调用：
  - `associationActionService.add()`
  - `associationActionService.list()`
  - `associationActionService.remove()`
  - `associationActionService.set()`
- 主要验收路径不能直接调用 `dynamicRepository.createLink/deleteLink/replaceLinks`。
- 覆盖 source/target 权限和 scope：
  - 缺 source get，association list 拒绝。
  - 缺 target list，association list 拒绝。
  - 缺 source update，add/remove/set 拒绝。
  - target update scope 不匹配，add/remove/set 拒绝。
  - through 权限和 through scope 不影响内部 through 操作。

**验收标准**

- 如果 `AssociationActionService.listBelongsToMany()` 改回 `listViaFilter()`，测试失败。
- 测试不依赖 `@TestMethodOrder`。

## P1-G：补齐 view/sql DDL 边界

**目标**

view/sql collection 只能管理 metadata，不能误改映射的底层 table/view。

**开发要求**

- `DdlSynchronizer.addField()`：
  - physical collection 执行 `ALTER TABLE ADD COLUMN`。
  - view/sql collection 只保存 fields metadata。
- `DdlSynchronizer.dropField()`：
  - physical collection 执行 `ALTER TABLE DROP COLUMN`。
  - view/sql collection 只删除 fields metadata。
- `DdlSynchronizer.dropCollection()`：
  - physical collection 删除 metadata 后 drop table。
  - view/sql collection 只删 metadata，不 drop mapped table/view。
- 更新相关测试，不能依赖 view/sql createCollection 创建普通表。

**验收测试**

- view/sql add field 不执行 DDL。
- view/sql drop field 不执行 DDL。
- view/sql drop collection 不删除底层 mapped table/view。
- physical collection DDL 行为不回退。

## P1-H：修正 SQL validator 边界测试

**目标**

让 `SqlValidator` 的保守规则可预期，避免误报能力。

**开发要求**

- 保留 Phase 1 保守策略：拒绝分号、注释、参数占位符、DDL/DML 关键字。
- 为以下场景补测试：
  - 小写 select/with。
  - 前置空白。
  - `WITH x AS (...) SELECT ...` 合法。
  - `WITH x AS (DELETE ... RETURNING ...) SELECT ...` 必须拒绝。
  - 字符串字面量中出现 `drop` 的行为必须明确：保守拒绝或正确允许，文档和测试一致。
- 错误信息不要包含完整 configured SQL。

**验收标准**

- validator 规则与 `SQL_QUERY_COLLECTION_DESIGN.md` 一致。
- SQL collection 测试覆盖 unsafe configured SQL 在 `DynamicRepository.list()` 路径被拒绝。

## P1-I：清理测试隔离

**目标**

避免 SQL/ACL 测试被历史数据污染。

**开发要求**

- `SqlQueryCollectionTest` 不要使用 `>=` 作为主要断言。
- 每个测试使用唯一 collection/table 名，或在 `@BeforeEach` 清理对应数据。
- 不要让 `@BeforeAll` 重复插入导致 count 越来越大。
- 移除 `ActionScopeRelationReviewTest`、`AclPermissionTest`、`P0P1FixTest` 中不必要的 `@TestMethodOrder`，或说明必须保留的原因。
- helper 方法找不到角色/权限时不能静默 return，必须 fail。
- 替换本轮触达测试中的 `assertThrows(Exception.class)`。

**验收标准**

- `SqlQueryCollectionTest` 单独运行通过。
- 全量 `mvn -q test` 通过。
- 重复运行测试两次，count 不因历史数据变化。

## P1-J：架构边界继续收紧

**目标**

SQL executor 开口后，防止 SQL 能力扩散。

**开发要求**

- `ArchitectureBoundaryTest` 递归扫描所有 `src/main/java/com/nocobase`。
- allowlist 保持最小：
  - `DynamicRepository.java`
  - `SqlQueryCollectionExecutor.java`
  - `DdlSynchronizer.java`
  - `DialectAdapterFactory.java`
- 移除或明确处理 `CollectionManagerService.java`：
  - 最好移除 `JdbcTemplate`。
  - 如果暂留，类上必须 `@Deprecated`，且架构测试确认没有新代码注入。
- 增加检查：controller/service 不得 import `SqlQueryCollectionExecutor`。

**验收标准**

- controller/service/relation/association 新增 `JdbcTemplate` 依赖时测试失败。
- controller/service 直接调用 SQL executor 时测试失败。

## P2-K：SQL collection get 与 named parameter 设计

**目标**

为下一阶段 SQL collection 能力扩展做设计，不急于实现。

**开发要求**

- 更新 `SQL_QUERY_COLLECTION_DESIGN.md`，新增下一阶段章节：
  - `executeGet()` 是否正式支持。
  - configured SQL 参数策略。
  - named parameters 的来源、白名单、绑定顺序。
  - query collection 是否允许关联字段。
  - SQL collection 是否支持 append relation。
  - PostgreSQL/MySQL 方言差异。
- 当前 Phase 1 暂缓项必须明确写出，不能写成已支持。

**验收标准**

- 文档和代码能力一致。
- 至少包含 3 个前端 API 不变示例。
- 明确 Phase 2 的最小开发范围。

## Claude 交付要求

完成后输出总结文档：`SQL_COLLECTION_PHASE1_HARDENING_COMPLETION_SUMMARY.md`。

总结必须包含：

- 每个 P0/P1/P2 任务的完成状态。
- 修改文件列表。
- 是否修改前端，答案必须是“否”。
- SQL collection list/get 的完整调用链。
- SQL collection 权限测试矩阵和新增测试数量。
- SQL get scope 修复说明。
- 主键元数据来源、无主键行为、剩余硬编码 `id` 列表。
- belongsToMany association API 的真实测试覆盖说明。
- view/sql DDL 边界说明。
- SQL validator 规则和暂缓项。
- 架构边界 allowlist。
- `mvn -q test` 的总测试数、失败数、错误数。
