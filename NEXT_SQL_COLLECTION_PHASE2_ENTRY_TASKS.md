# 下一批开发任务：SQL Collection Phase 2 入口与 Closure 遗留修复

> 面向 Claude 的后端开发任务清单。只修改 Java 后端，前端界面、前端调用方式和 API 形态保持不变。
> 本文基于 `SQL_COLLECTION_PHASE1_CLOSURE_COMPLETION_SUMMARY.md` 的架构 review。

## Review 结论

本轮 closure 有实际进展，`mvn -q test` 本地通过，当前结果为 `129 tests, 0 failures, 0 errors`。已确认：

- `DynamicRepository.destroy()`、`existsInScope()`、`readAfterWrite()` 的主查询条件已改为 `pk(def)`。
- `SqlQueryCollectionExecutor.executeGet()` 已改为接收 `mergedFilter`，不再额外拼一次 primary key。
- SQL collection 的 list action scope 和 readable fields 测试已经从上一轮的假覆盖升级为 member ACL 测试。
- belongsToMany 已经开始通过 `AssociationActionService.add/list/remove/set` 验收。
- `CollectionManagerService` 已标记为 `@Deprecated`。

但还不能直接进入复杂 SQL 能力扩展。当前仍有几个会影响主数据层抽象稳定性的缺口，必须先补齐。

## 当前遗留问题

- `AclFilterInjector.buildScopeFilter()` 在当前用户无角色时仍返回 `Map.of("id", ...)`，这仍然是动态 collection 主键语义硬编码。
- `SqlQueryCollectionTest` 仍没有覆盖 SQL collection `get` 成功路径、`get` action scope、`get` readable fields。
- `SqlQueryCollectionTest` 没有覆盖 readable none 场景，也没有覆盖无主键 SQL collection readable none 返回空对象。
- SQL collection 自定义 primaryKey 只测了 `id`，没有测试 `code` 这类非 id 主键。
- `SqlQueryCollectionTest` 的 ACL helper 找不到 member role 时仍静默 return，应改为 fail-fast。
- `ActionScopeRelationReviewTest` 仍保留 `@TestMethodOrder` 和固定 collection 名，部分断言仍是 `assertThrows(Exception.class)`。
- DDL 边界和 primaryKey fail-fast 仍只是“基础完成”，缺少完整回归测试矩阵。
- `ArchitectureBoundaryTest` 只检查 `JdbcTemplate`，没有明确禁止 controller/relation/association 直接 import `SqlQueryCollectionExecutor`。
- `SQL_QUERY_COLLECTION_DESIGN.md` 仍描述 `:param` 支持，但当前 validator 明确拒绝 named parameter，文档与代码能力不一致。

## 全局约束

- 只改 Java 后端，不改前端。
- 前端仍使用现有 collection action，不新增前端协议。
- SQL collection 入口保持 `Controller -> DynamicRepository -> SqlQueryCollectionExecutor`。
- SQL collection Phase 2 之前仍只读，`create/update/destroy` 必须拒绝。
- ACL 语义保持：公开 API 检查 action 权限、action scope、字段权限；内部 API 按前几轮定义的 scope-only/read-after-write/through 语义执行。

## P0-A：移除 AclFilterInjector 的 `id` no-result filter

**目标**

无角色或必须强制空结果时，不再依赖固定 `id` 字段生成 false condition。

**开发要求**

- 修改 `AclFilterInjector` 的 no-result 语义，不再返回 `Map.of("id", Map.of("$eq", -1))`。
- 推荐新增内部 filter DSL：
  - 例如 `$alwaysFalse` 或 `$none`。
  - 在 `FilterCompiler` 中编译为 `1 = 0`。
  - 该 DSL 只作为后端内部 filter 使用，不暴露为前端能力。
- 或者调整 `mergeScopeFilter` 签名，让其接收 `CollectionDefinition`，使用真实 primaryKey；但无主键 collection 仍必须可生成 false condition。
- 覆盖以下调用链：
  - public `list/get/update/destroy`
  - internal `existsInScope`
  - internal `readAfterWrite`
  - SQL collection list/get

**验收标准**

- 全局搜索动态主键语义，不再出现 no-result 用固定 `id` 的实现。
- 无主键 SQL/view collection 在无角色场景不会生成 unknown column `id` SQL。
- `mvn -q test` 通过。

## P0-B：补 SQL collection get 权限测试矩阵

**目标**

SQL collection `get` 必须和物理 collection 一样执行 get action、get scope、readable fields。

**开发要求**

- 新增 SQL collection 成功 get 测试：
  - SQL collection 配置 `primaryKey`。
  - `dynamicRepository.get(SQL_COLL_PK, pk)` 返回正确记录。
- 新增 SQL get action scope 测试：
  - list scope 与 get scope 配成不同条件。
  - `list()` 只能看到 list scope 数据。
  - `get(listScopeOnlyRecord)` 返回 null 或明确不可见。
  - `get(getScopeRecord)` 返回记录。
- 新增 SQL get readable fields 测试：
  - member 只有部分 readable fields。
  - `get()` 返回允许字段和 primaryKey，不返回未授权字段。
- 新增 SQL get 无权限测试：
  - 有 list 权限但无 get 权限时，`get()` 必须 Forbidden。

**验收标准**

- 删除 `DynamicRepository.get()` 中 SQL get scope 合并时，测试失败。
- 删除 SQL get 字段过滤时，测试失败。
- 删除 get action 权限检查时，测试失败。

## P0-C：补 readable none 与 custom primaryKey 场景

**目标**

字段权限过滤必须完全依赖 `CollectionDefinition`，不能只在默认 `id` 主键下正确。

**开发要求**

- 新增 SQL collection `primaryKey = "code"` 的测试 collection。
- configured SQL 返回 `code/name/status/owner_id` 等字段。
- 覆盖：
  - readable none + 有 primaryKey：只返回 `code`。
  - readable none + 无 primaryKey：返回空对象。
  - partial readable + custom primaryKey：自动保留 `code`。
  - SQL get + custom primaryKey：可按 `code` 获取。
- 覆盖 physical custom primaryKey：
  - `get/update/destroy/existsInScope/readAfterWrite` 使用自定义主键。

**验收标准**

- `FieldPermissionFilter` 不依赖固定 `id`。
- `DynamicRepository.readAfterWrite()` 不依赖固定 `id`。
- SQL/physical custom primaryKey 测试都通过。

## P0-D：修复测试 helper 与测试隔离

**目标**

测试失败必须暴露真实问题，不能因为角色缺失、权限缺失、历史数据残留而静默通过。

**开发要求**

- `SqlQueryCollectionTest` 的 `cleanupMemberPermissions`、`grantMemberPermission`、`grantMemberPermissionWithScope` 找不到 member role 时必须 `fail(...)`。
- `ActionScopeRelationReviewTest` 的 `grantScope`、`grantMemberAction` 找不到 member role 时必须 `fail(...)`。
- 移除 `ActionScopeRelationReviewTest` 的 `@TestMethodOrder`，或把每个测试改成独立 collection 名和独立数据。
- 把 `ActionScopeRelationReviewTest` 中剩余 `assertThrows(Exception.class)` 改成明确异常类型。
- 避免固定 collection 名重复创建导致测试依赖执行顺序；必要时使用测试名前缀或随机后缀。

**验收标准**

- `SqlQueryCollectionTest` 单独连续运行两次通过。
- `ActionScopeRelationReviewTest` 单独连续运行两次通过。
- 全量 `mvn -q test` 通过。

## P1-E：补 DDL 边界完整回归测试

**目标**

把 view/sql metadata-only DDL 边界锁成测试。

**开发要求**

- SQL collection：
  - `addField` 只保存 metadata，不新增底层表列。
  - `dropField` 只删除 metadata，不删除底层表列。
  - `dropCollection` 只删除 metadata，不 drop configured SQL 中引用的底层表。
- View collection：
  - `addField/dropField/dropCollection` 同样只影响 metadata。
- Physical collection：
  - 保持原有 create/add/drop/dropCollection 物理 DDL 行为。

**验收标准**

- 对 SQL/view mapped table 执行 metadata 删除后，底层表仍可查询。
- 对 physical collection drop 后，物理表确实删除。
- 相关测试不依赖前一测试留下的表。

## P1-F：补 primaryKey fail-fast 回归测试

**目标**

把 `CollectionRuntimeService` 的 primaryKey 语义锁住。

**开发要求**

- 覆盖合法 custom primaryKey。
- 覆盖 missing primaryKey field。
- 覆盖 relation field 作为 primaryKey。
- 覆盖 non-physical field 作为 physical collection primaryKey。
- 覆盖非法 effective column name。
- 覆盖 SQL/view collection 未配置 primaryKey 时 `hasPrimaryKey=false`。
- 覆盖 SQL/view collection 配置合法 primaryKey 时可用于 SQL get/filter。

**验收标准**

- 非法 metadata reload/build definition 时 fail-fast。
- 错误信息不包含完整 configured SQL。

## P1-G：收紧架构边界测试

**目标**

防止后续开发绕过统一数据层或直接调用 SQL executor。

**开发要求**

- `ArchitectureBoundaryTest` 新增扫描：
  - controller 不得 import `SqlQueryCollectionExecutor`。
  - relation service 不得 import `SqlQueryCollectionExecutor`。
  - association service 不得 import `SqlQueryCollectionExecutor`。
  - 普通 service 不得 import `SqlQueryCollectionExecutor`。
- `JdbcTemplate` allowlist 必须最小化并写明理由。
- `CollectionManagerService` 如继续保留在 allowlist：
  - 必须 `@Deprecated`。
  - 必须没有 controller/service/data/acl 新代码注入。
- 架构测试不要吞掉 `IOException`，文件不存在可以跳过，但读取失败必须 fail。

**验收标准**

- 新增任意违规 import 时测试失败。
- 架构测试递归扫描 `src/main/java/com/nocobase`。
- `mvn -q test` 通过。

## P1-H：修正 SQL 设计文档与当前能力不一致

**目标**

`SQL_QUERY_COLLECTION_DESIGN.md` 必须准确描述当前代码能力，不能把 Phase 2 未实现能力写成已支持。

**开发要求**

- 明确当前 Phase 1/closure 状态：
  - configured SQL 仅支持只读 SELECT/WITH SELECT。
  - configured SQL 当前不支持 `?` 参数。
  - configured SQL 当前不支持 `:named` 参数。
  - 外层 filter/sort/page 由后端根据 metadata 编译。
- 把 named parameter 放入 Phase 2 设计章节，而不是当前能力章节。
- 明确 Phase 2 named parameter 方案：
  - 参数来源白名单。
  - 参数类型声明。
  - named parameter 到 JDBC `?` 的转换。
  - 与外层 filter/scope 参数的顺序合并。
  - 禁止前端 filter 字符串拼接进 configured SQL。
- 明确 SQL collection relation 能力暂缓范围。

**验收标准**

- 文档与 `SqlValidator` 当前行为一致。
- 至少给出 3 个“前端 API 不变”的示例。
- 明确当前不支持但后续计划支持的能力。

## P2-I：整理 SQL executor 内部结构

**目标**

在引入 named parameter 前，把 executor 从字符串拼接雏形整理为可演进结构。

**开发要求**

- 在不改变 public API 的前提下，整理 `SqlQueryCollectionExecutor` 内部职责：
  - validate configured SQL。
  - build subquery wrapper。
  - build projection。
  - compile filter。
  - build sort。
  - build count query。
  - append pagination。
- 抽出 SQL query plan/value object 可选，但不要引入过度抽象。
- `buildSelectClause` 和 `buildSortClause` 的字段校验规则与 `DynamicRepository` 保持一致。
- 保持权限判断仍在 `DynamicRepository`，executor 不判断 action 权限。

**验收标准**

- 当前 SQL list/get 行为不回退。
- 代码为 Phase 2 named parameter 留出清晰入口。
- `mvn -q test` 通过。

## P2-J：设计并小步实现 named parameter 预处理

**目标**

为 SQL collection Phase 2 提供最小可用的 named parameter 预处理，不接入前端新能力。

**开发要求**

- 先实现内部组件，不立即放开 configured SQL 使用 `:param`。
- 组件职责：
  - 识别 SQL 中的 `:name` 参数。
  - 忽略字符串字面量中的 `:name`。
  - 输出转换后的 SQL 和参数名顺序。
  - 后续可根据 metadata 绑定参数值。
- 新增单元测试：
  - 单参数。
  - 重复参数。
  - 多参数顺序。
  - 字符串字面量中的冒号不识别。
  - PostgreSQL `::type` 不误识别。
- `SqlValidator` 暂时仍拒绝 configured SQL named parameter，直到绑定来源和 metadata schema 完整。

**验收标准**

- 新组件测试通过。
- 当前 SQL collection 运行行为不改变。
- 文档说明这是 Phase 2 内部准备，不是前端可用能力。

## 并行开发建议

- 并行组 1：P0-A、P0-C。同属主键语义，建议同一人处理。
- 并行组 2：P0-B、P0-D。同属测试闭环和 SQL get 权限验收。
- 并行组 3：P1-E、P1-F。DDL 与 primaryKey 回归测试可并行。
- 并行组 4：P1-G、P1-H。架构边界与文档一致性可并行。
- 并行组 5：P2-I、P2-J。必须在 P0 合并后再开始，避免基于不稳定 executor 改造。

## Claude 交付要求

完成后输出总结文档：`SQL_COLLECTION_PHASE2_ENTRY_COMPLETION_SUMMARY.md`。

总结必须包含：

- 是否修改前端，答案必须是“否”。
- 每个 P0/P1/P2 任务的完成状态。
- 修改文件列表。
- SQL collection list/get 最终调用链。
- SQL get action scope 与 readable fields 测试说明。
- dynamic primaryKey 硬编码清理结果。
- readable none 和 custom primaryKey 测试说明。
- belongsToMany association 测试隔离说明。
- DDL 边界与 primaryKey fail-fast 回归测试说明。
- SQL design 文档与当前能力一致性说明。
- `mvn -q test` 的总测试数、失败数、错误数。
