# 下一批开发任务：Association API 返工、主键抽象、View/SQL Collection 起步

> 面向 Claude 的后端开发任务清单。前端保持不变，只修改 Java 后端。

## Review 结论

`ACTION_SCOPE_RELATION_REVIEW_FIX_COMPLETION_SUMMARY.md` 声明当前为 `102 tests, 0 failures, 0 errors`，我本地重新运行 `mvn -q test` 也通过。

但这轮修复仍有几个关键缺口，不能直接视为完成：

- `ActionScopeRelationReviewTest.listAndGetScopesAreDifferentAndEnforced` 只断言了 `list` scope，没有实际调用 `dynamicRepository.get()` 验证 `get` scope。
- `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps` 没有创建 belongsToMany 关系字段，也没有调用 `AssociationActionService.add/remove/set` 或 `RelationQueryService.appendRelations`，只是直接调用 `DynamicRepository.createLink/deleteLink`。
- `AssociationActionService.listBelongsToMany()` 当前仍走 `listViaFilter()`，等价于在 target 表上按 foreignKey 查，不是真正通过 through 表查询 belongsToMany。
- `ActionScopeRelationReviewTest.fieldPermissionNoneWithoutIdDoesNotNpe` 没有调用 `AclService.filterReadableFields()`，只是构造了一个本地空 map，因此没有覆盖真实 bug。
- `DynamicRepository.readAfterWrite()`、`list/get` 字段过滤、`createLink()` generated key、`listLinks()` select 都仍硬编码 `id`，会阻塞后续 view/sql collection 和自定义主键。
- `ArchitectureBoundaryTest` 仍允许 `CollectionManagerService.java` 使用 `JdbcTemplate`，并且部分旧测试仍是一层目录扫描，边界测试还不够干净。

因此下一批任务先做第二轮 review 返工，然后再进入 view/sql collection 起步能力。

## 全局约束

- 只改 Java 后端，不修改前端接口、页面、Schema 行为。
- 保持 NocoBase 前端现有 API 兼容：collection action、association action、append relation 的请求/响应形态不能变。
- 所有业务数据访问继续收敛到 `DynamicRepository`，controller/service/relation/association 不得直接使用 `JdbcTemplate`。
- 公开 API 继续执行 action 权限、action scope、字段权限。
- 内部关系 API 继续语义分离：through 表不检查前端权限，但 source/target 权限和 scope 必须由调用方保证。

## P0-A：修正 action scope 测试，必须覆盖 get

**目标**

让 action scope 测试真正证明 `list` 和 `get` 使用不同 scope。

**开发要求**

- 修改 `ActionScopeRelationReviewTest.listAndGetScopesAreDifferentAndEnforced`。
- 创建两条可区分记录：
  - A：只在 list scope 内。
  - B：只在 get scope 内。
- member 授权：
  - `list` scope 只允许 A。
  - `get` scope 只允许 B。
- 测试必须断言：
  - `dynamicRepository.list()` 只返回 A，不返回 B。
  - `dynamicRepository.get(A.id)` 返回 `null` 或不可见。
  - `dynamicRepository.get(B.id)` 返回 B。
- 字段权限要授予断言所需字段，不能依赖被过滤后的 null。

**验收标准**

- 如果把 `DynamicRepository.get()` 改回使用 `"list"` scope，该测试必须失败。
- 如果 `findByFilterForAction()` 忽略 action，该测试必须失败。

## P0-B：修正 belongsToMany association API 实现

**目标**

让 `AssociationActionService.list/add/remove/set` 对 belongsToMany 走真实 through 关系语义。

**开发要求**

- `AssociationActionService.listBelongsToMany()` 不能继续调用 `listViaFilter()`。
- belongsToMany list 必须通过 `DynamicRepository.listLinks(through, foreignKey, sourceIds, otherKey)` 查询 through，再通过 target collection 查询目标记录。
- 查询 target 记录时必须继续使用公开读语义：
  - 检查 target `list` 权限。
  - 应用 target `list` scope。
  - 应用 target readable fields。
- `add/remove/set` 必须调用 `AssociationActionService` 自身方法验证 source/target 权限和 scope，不能在测试中直接跳到 `DynamicRepository.createLink/deleteLink/replaceLinks`。
- `set` 必须验证每个 targetId 的 update scope 后再替换 through links。

**验收标准**

- `AssociationActionService.list("articles.tags", articleId)` 能返回通过 through 表关联的 tags。
- target list scope 不匹配时，association list 不返回该 tag。
- source get scope 不匹配时，association list 被拒绝或返回不可见。
- 不授予 through collection 任何 action 权限时，list/add/remove/set 仍能完成 through 内部操作。

## P0-C：补真实 belongsToMany 元数据测试

**目标**

把 `belongsToManyThroughInternalOps` 改成真实 NocoBase 元数据链路测试。

**开发要求**

- 创建 source、target、through 三个 collection。
- 在 source collection 创建 belongsToMany field，字段元数据必须包含：
  - target collection。
  - through collection。
  - source key / foreign key。
  - target key / other key。
- 通过 `runtimeService.reload()` 后，从 `CollectionDefinition.getRelation()` 读取关系，确认关系定义存在。
- 测试必须调用：
  - `AssociationActionService.add()`。
  - `AssociationActionService.remove()`。
  - `AssociationActionService.set()`。
  - `AssociationActionService.list()` 或 `RelationQueryService.appendRelations()`。
- 不能只直接调用 `DynamicRepository.createLink/deleteLink`。

**验收标准**

- 未授权 through collection 时，association add/remove/set/list 全部按预期工作。
- 给 through collection 设置极窄 scope，不影响内部 through 操作。
- 给 target collection 设置极窄 scope，会影响 association list/append 返回。

## P0-D：修正 FieldPermission 无 id 测试和实现复用

**目标**

让无 id 字段权限场景被真实服务覆盖，并减少字段过滤逻辑重复。

**开发要求**

- `ActionScopeRelationReviewTest.fieldPermissionNoneWithoutIdDoesNotNpe` 必须调用 `AclService.filterReadableFields(resourceName, row)`。
- member 对该 resource 没有 list/get 权限，确保 `FieldPermission.none()` 分支被命中。
- row 不包含 `id`，测试断言返回空 map 且不抛异常。
- `DynamicRepository.list()`、`get()`、`readAfterWrite()` 的字段过滤逻辑尽量复用 `AclService.filterReadableFields()`，避免三处实现不一致。
- `readAfterWrite()` 的 `FieldPermission.none()` 分支不能 `minimal.put("id", row.get("id"))` 后放入 null。

**验收标准**

- 删除 `AclService.filterReadableFields()` 的无 id 保护时，测试必须失败。
- list/get/readAfterWrite 对无可读字段的返回策略一致。

## P0-E：引入 collection 主键元数据抽象

**目标**

移除动态数据层对 `"id"` 的核心硬编码，为自定义主键、视图、SQL 查询结果集做准备。

**开发要求**

- 在 runtime metadata 中增加主键识别能力，例如：
  - `CollectionDefinition.getPrimaryKeyFieldName()`。
  - `CollectionDefinition.getPrimaryKeyColumnName()`。
  - `CollectionDefinition.hasPrimaryKey()`。
- 默认 physical collection 仍使用 `id`，保持现有前端兼容。
- 字段过滤时自动保留 primary key，而不是固定保留 `id`。
- `get()`、`existsInScope()`、`readAfterWrite()`、`update()`、`destroy()` 的主键过滤使用 metadata 主键。
- `create()` 读取 generated key 时使用主键列名；如果 collection 无主键，必须有明确返回策略。
- `listLinks()` 可返回 through 主键，但不能强制要求 every through collection 必须有 `id`，至少不能在无 id 场景 SQL 失败。

**验收标准**

- 默认 `id` 主键的现有测试全部通过。
- 新增一个自定义主键 collection 测试，`get/update/destroy/existsInScope/readAfterWrite` 使用自定义主键成功。
- 新增一个无主键只读 collection 测试，`list` 不因为强制选择 `id` 失败。

## P1-F：收紧 through 字段校验到物理列

**目标**

当前 `validateThroughColumn()` 只判断 field name 存在，下一步要确保它映射到真实物理列。

**开发要求**

- 校验 `sourceKey/otherKey` 对应字段必须是 physical field。
- 如果 field 的 `effectiveColumnName` 与 field name 不同，SQL 里必须使用 effective column name。
- 系统列只允许 metadata 明确存在或 collection 能力明确允许，不要把所有系统列都默认为可用。
- 错误信息要明确指出 collection、field、reason。

**验收标准**

- relation 字段、虚拟字段、非物理字段不能作为 through key。
- effective column name 不同的 through key 可以正常工作。
- 注入式 field name 和 column name 都被拒绝。

## P1-G：彻底清理架构边界测试

**目标**

避免后续 SQL 再次散落在 controller/service/relation/association。

**开发要求**

- `ArchitectureBoundaryTest` 保留一个递归扫描入口即可，删除或合并重复的一层目录扫描测试。
- `CollectionManagerService.java` 如果仍在 allowlist，必须有明确 `@Deprecated` 注解和迁移计划；如果已无必要，移除其 `JdbcTemplate` 依赖。
- allowlist 只允许：
  - `DynamicRepository.java`
  - `DdlSynchronizer.java`
  - 方言探测/DDL 专用组件
  - 未来 SQL/View executor 专用组件
- 架构测试要区分 import、字段注入、构造器参数、方法参数里的 `JdbcTemplate`。

**验收标准**

- 在任意 controller/service/data/acl/runtime 子包新增 `JdbcTemplate` 依赖，测试失败。
- `RelationQueryService`、`AssociationActionService` 永远不能出现在 allowlist。

## P1-H：修正测试隔离与断言类型

**目标**

减少 ACL 测试之间的权限污染和弱断言。

**开发要求**

- `ActionScopeRelationReviewTest` 不要依赖 `@TestMethodOrder` 保证数据顺序。
- 每个测试使用独立 collection 名、独立用户或清理对应 role resource/action/scope。
- `invalidThroughColumnsRejected`、`sqlInjectionColumnNameRejected` 不要使用 `assertThrows(Exception.class)`，改为具体异常类型。
- helper 方法不要静默 `return`，例如找不到 member role 时应 fail 测试。

**验收标准**

- `ActionScopeRelationReviewTest` 单独运行通过。
- 全量 `mvn -q test` 通过。
- 随机调整测试顺序不影响结果。

## P2-I：View Collection 只读能力起步

**目标**

在 P0/P1 修完后，开始实现 NocoBase 数据层抽象中的 view collection，只读支持优先。

**开发要求**

- collection type 为 `view` 或 `physical + view=true` 时：
  - 支持 `list/get`。
  - 禁止 `create/update/destroy`。
  - 仍执行 list/get action 权限、scope、readable fields。
- view collection 的 SQL 来源优先使用元数据里的 table/view name，不在业务代码散落 SQL。
- DDL 同步要能识别 view collection，不对 view 做普通表 create/alter。
- 如果 view 无主键：
  - `list` 支持。
  - `get/update/destroy` 明确拒绝或要求配置 primary key。

**验收标准**

- view collection 可以 list。
- view collection create/update/destroy 返回明确 Forbidden。
- view collection scope 和字段权限生效。
- 无主键 view 不会因为硬编码 `id` 抛 SQL 错误。

## P2-J：SQL Query Collection 设计文档

**目标**

先输出设计，不急于实现，避免 SQL collection 后期混乱。

**开发要求**

- 新增设计文档，说明 SQL query collection 如何接入 `DynamicRepository`：
  - SQL 存放位置。
  - 参数绑定策略。
  - filter/sort/page 如何包裹到外层查询。
  - ACL scope 如何编译并作用到 SQL 结果集。
  - 字段权限如何基于结果字段裁剪。
  - 是否允许 get，是否要求 primary key。
  - 禁止 create/update/destroy。
- 明确 SQL 不允许散落在 controller/service，必须进入专用 executor 或 repository 子组件。

**验收标准**

- 输出 `SQL_QUERY_COLLECTION_DESIGN.md`。
- 文档中给出至少 3 个前端不变的 API 示例。
- 文档明确 H2/PostgreSQL/MySQL 差异点和后续方言抽象需求。

## Claude 交付要求

完成后输出总结文档：`ASSOCIATION_PRIMARY_KEY_VIEW_COMPLETION_SUMMARY.md`。

总结必须包含：

- 每个任务的完成状态。
- 修改文件列表。
- 是否修改前端，答案必须是“否”。
- association API 的真实调用链说明。
- 主键抽象说明，列出仍硬编码 `id` 的位置；如果还有，必须说明原因。
- view collection 支持范围。
- 测试结果，必须包含 `mvn -q test` 的总用例数、失败数、错误数。
