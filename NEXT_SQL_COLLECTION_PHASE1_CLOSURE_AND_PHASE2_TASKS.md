# 下一批开发任务：SQL Collection Phase 1 收口与 Phase 2 准备

> 面向 Claude 的后端开发任务清单。只修改 Java 后端，前端界面、前端调用方式和 API 形态保持不变。
> 本文基于 `SQL_COLLECTION_PHASE1_HARDENING_COMPLETION_SUMMARY.md` 的架构 review。

## Review 结论

本轮硬化有进展，`mvn -q test` 本地通过，当前结果为 `123 tests, 0 failures, 0 errors`。已确认的有效改动包括：

- SQL collection 已有 `SqlQueryCollectionExecutor.executeList/executeGet` 雏形。
- SQL list 已走 `DynamicRepository -> SqlQueryCollectionExecutor`。
- `FieldPermissionFilter` 已新增，部分 list/get 路径已开始统一字段过滤。
- `DdlSynchronizer` 已对 view/sql collection 的 `addField/dropField/dropCollection` 跳过物理 DDL。
- `CollectionRuntimeService` 已对 `options.primaryKey` 做字段存在性、物理字段、relation 字段和 column name 校验。

但本轮不能视为完全验收通过，原因是总结文档中部分“已完成”与实际代码不一致，且 SQL collection 权限测试仍有明显假覆盖。下一批必须先收口 Phase 1 的 P0，再进入 Phase 2 设计和小步实现。

## 当前阻断问题

- `DynamicRepository.destroy()` 仍然使用 `"id" = ?`，没有替换为动态主键。
- `DynamicRepository.existsInScope()` 仍然使用 `"id" = ?`，自定义主键会失败或绕过真实主键语义。
- `DynamicRepository.readAfterWrite()` 查询条件仍然使用 `"id" = ?`，partial readable 分支也仍然补 `"id"`。
- `DynamicRepository.get()` 对 SQL collection 先把 primary key 合并进 `scopeFilter`，再调用 `executeGet(def, primaryKey, scopeFilter)`；`SqlQueryCollectionExecutor.executeGet()` 又额外追加 primary key 条件，导致 SQL get 的 filter 契约不清晰且主键条件重复。
- `SqlQueryCollectionTest.sqlListAppliesActionScope()` 只验证 admin 能看到数据，没有验证 member role 的 action scope。
- `SqlQueryCollectionTest.sqlListAppliesReadableFields()` 验证的是 `fields=name` projection，不是 ACL readable fields 权限。
- `SqlQueryCollectionTest` 仍使用共享 collection/table 和 `>=` 断言，测试可能被历史数据污染。
- `ActionScopeRelationReviewTest.belongsToManyThroughInternalOps()` 仍直接调用 `dynamicRepository.createLink/deleteLink`，没有覆盖真实 `AssociationActionService.add/list/remove/set`。

## 全局约束

- 只改 Java 后端，不改前端。
- 前端仍通过现有 collection action 调用 SQL collection，例如 list/get。
- SQL collection 统一调用链必须保持为 `Controller -> DynamicRepository -> SqlQueryCollectionExecutor`。
- Controller、普通 service、relation service、association service 不得直接依赖 `JdbcTemplate` 执行业务查询。
- Controller、普通 service、relation service、association service 不得直接调用 `SqlQueryCollectionExecutor`。
- SQL collection Phase 1 仍然只读，`create/update/destroy` 必须明确拒绝。
- 数据权限闭环必须包含 action 权限、action scope、字段权限。

## P0-A：彻底消除动态数据层主键硬编码

**目标**

所有动态数据层的主键语义必须来自 `CollectionDefinition`，不能假设主键字段或列名一定是 `id`。

**开发要求**

- 修复 `DynamicRepository.destroy()`：
  - WHERE 条件使用 `pk(def)`。
  - collection 无主键时 fail-fast，不进入 SQL 执行。
- 修复 `DynamicRepository.existsInScope()`：
  - WHERE 条件使用 `pk(def)`。
  - collection 无主键时 fail-fast 或返回明确拒绝，不能生成错误 SQL。
- 修复 `DynamicRepository.readAfterWrite()`：
  - WHERE 条件使用 `pk(def)`。
  - readable none 返回真实 primary key field。
  - partial readable 自动保留真实 primary key field，不再写死 `id`。
- 全局搜索动态数据层中主键语义硬编码：
  - `DynamicRepository`
  - `AclFilterInjector`
  - `AclService`
  - `FilterCompiler`
  - `FieldPermissionFilter`
  - `SqlQueryCollectionExecutor`
- 系统表自身的 `id` 字段不属于本任务范围；只清理“动态 collection 主键语义”的硬编码。

**验收标准**

- 自定义主键 physical collection 覆盖 `get/update/destroy/existsInScope/readAfterWrite`。
- 无主键 SQL/view collection 覆盖 `get/update/destroy/existsInScope/readAfterWrite` 的明确拒绝。
- 搜索动态数据层代码，不再存在主键语义上的固定 `"id" = ?`。
- `mvn -q test` 通过。

## P0-B：修正 SQL get 的 filter 契约

**目标**

SQL collection get 必须同时应用 primary key 与 get action scope，但调用契约只能有一种含义，不能重复拼 primary key。

**开发要求**

二选一实现，推荐方案 1：

- 方案 1：`DynamicRepository.get()` 构造完整 filter。
  - `DynamicRepository.get()` 合并 primary key filter 与 get scope。
  - `SqlQueryCollectionExecutor.executeGet(def, filter)` 只接收完整 filter，不再额外接收 `primaryKey`。
  - executor 根据完整 filter 编译 WHERE，并 `LIMIT 1`。
- 方案 2：executor 接收 scope-only filter。
  - `DynamicRepository.get()` 只传 get scope，不把 primary key 放入 scopeFilter。
  - `SqlQueryCollectionExecutor.executeGet(def, primaryKey, scopeOnlyFilter)` 负责拼 primary key。
  - 参数名必须改清楚，避免再次把 merged filter 传入。

**验收标准**

- SQL get 的 WHERE 不重复拼 primary key。
- list scope 与 get scope 可分别配置并分别生效。
- 移除 get scope 合并时，对应测试必须失败。
- SQL collection 无 primaryKey 时 get 明确拒绝。

## P0-C：重写 SQL collection 权限测试矩阵

**目标**

把当前“看起来有测试，但没有验证权限语义”的用例改成真正能失败的权限测试。

**开发要求**

- `sqlListAppliesActionScope` 必须使用非 admin 用户和 member role scope。
- `sqlListAppliesReadableFields` 必须通过 ACL readable field 配置验证字段过滤，不得只靠 `fields` projection。
- 新增或重写以下用例：
  - SQL list 应用 list action scope。
  - SQL get 应用 get action scope。
  - SQL list 应用 readable partial。
  - SQL get 应用 readable partial。
  - SQL list 在 readable none 且有 primaryKey 时只返回 primary key。
  - SQL list 在 readable none 且无 primaryKey 时返回空对象。
  - SQL write action `create/update/destroy` 明确拒绝。
  - SQL unsafe configured SQL 在 `DynamicRepository` 调用链上被拒绝。
- 测试必须通过 `DynamicRepository` 触发，不要直接测 executor 绕过数据层权限语义。

**验收标准**

- 删除 SQL list scope 合并逻辑时，测试失败。
- 删除 SQL get scope 合并逻辑时，测试失败。
- 删除 SQL readable fields 过滤逻辑时，测试失败。
- 测试断言使用精确数量和精确字段，不使用 `>=` 兜底。

## P0-D：修复 SQL collection 测试隔离

**目标**

SQL collection 测试不能被历史数据或执行顺序污染。

**开发要求**

- 不再让 `SqlQueryCollectionTest` 依赖共享静态数据不断累积。
- 每个测试使用唯一 collection/table 名，或在 `@BeforeEach/@AfterEach` 做可靠清理。
- 移除核心断言中的 `assertTrue(count >= n)`。
- helper 找不到角色、权限、scope 时必须 fail，不允许静默 return。
- 本轮触达的测试中，把 `assertThrows(Exception.class)` 替换为明确异常类型。

**验收标准**

- `SqlQueryCollectionTest` 单独连续运行两次通过。
- 全量 `mvn -q test` 连续运行两次通过。
- SQL collection count 不受历史插入影响。

## P0-E：补真实 belongsToMany association API 测试

**目标**

补上前几轮遗留项：belongsToMany through 内部操作必须通过真实 association API 验收，而不是直接调用 repository 内部方法。

**开发要求**

- 创建 source、target、through 三个 collection。
- 在 source collection 创建真实 `belongsToMany` 字段 metadata。
- `runtimeService.reload(sourceCollection)` 后断言 relation metadata 正确。
- 测试必须调用：
  - `associationActionService.add()`
  - `associationActionService.list()`
  - `associationActionService.remove()`
  - `associationActionService.set()`
- 主验收路径不允许直接调用 `dynamicRepository.createLink/deleteLink/replaceLinks`。
- 覆盖 source/target 权限与 scope：
  - 缺 source get，association list 拒绝。
  - 缺 target list，association list 拒绝。
  - 缺 source update，add/remove/set 拒绝。
  - target update scope 不匹配，add/remove/set 拒绝。
  - through 表无前端权限时，内部 through 操作仍可由 association service 完成。

**验收标准**

- 测试不依赖 `@TestMethodOrder`。
- 如果 association service 回退到错误的普通 relation 查询路径，测试失败。
- 如果 through 表前端权限被错误检查，测试失败。

## P1-F：补 DDL 边界回归测试

**目标**

当前 view/sql DDL 边界代码方向正确，但需要可回归测试锁住行为。

**开发要求**

- view/sql collection `addField` 只新增 metadata，不执行 `ALTER TABLE ADD COLUMN`。
- view/sql collection `dropField` 只删除 metadata，不执行 `ALTER TABLE DROP COLUMN`。
- view/sql collection `dropCollection` 只删除 metadata，不 drop mapped table/view。
- physical collection 原有 DDL 行为不能回退。

**验收标准**

- 对 view/sql mapped table 执行 drop collection 后，底层 mapped table 仍存在。
- 对 physical collection drop collection 后，物理表被删除。
- `mvn -q test` 通过。

## P1-G：补主键 fail-fast 回归测试

**目标**

把 `CollectionRuntimeService` 的 primaryKey 校验锁成测试，避免后续重构退化。

**开发要求**

- 覆盖合法 custom primaryKey。
- 覆盖 missing primaryKey field。
- 覆盖 relation field 作为 primaryKey。
- 覆盖 virtual/non-physical field 作为 physical collection primaryKey。
- 覆盖非法 effective column name。
- 覆盖 SQL/view collection 未配置 primaryKey 时 `hasPrimaryKey=false`。
- 覆盖 SQL/view collection 配置合法 primaryKey 时可用于 get/filter。

**验收标准**

- 非法 primaryKey metadata reload 或 build definition 时 fail-fast。
- 错误信息不要包含完整 configured SQL。

## P1-H：收紧架构边界测试

**目标**

防止 SQL 能力和 JDBC 访问重新扩散到 controller 或 service。

**开发要求**

- `ArchitectureBoundaryTest` 递归扫描 `src/main/java/com/nocobase`。
- allowlist 保持最小化：
  - `DynamicRepository.java`
  - `SqlQueryCollectionExecutor.java`
  - `DdlSynchronizer.java`
  - `DialectAdapterFactory.java`
- 若 `CollectionManagerService` 暂时仍依赖 JDBC，必须明确标注 `@Deprecated`，并确认没有新代码注入它。
- 新增检查：controller、relation service、association service 不得 import `SqlQueryCollectionExecutor`。
- 新增检查：controller、relation service、association service 不得直接 import `JdbcTemplate`。

**验收标准**

- 新增违规 `JdbcTemplate` 依赖时测试失败。
- 新增违规 SQL executor 依赖时测试失败。
- 现有合法 allowlist 明确可解释。

## P2-I：SQL Collection Phase 2 设计文档更新

**目标**

在继续扩大 SQL collection 能力前，先把 Phase 2 边界写清楚，避免 SQL 拼接和参数来源混乱。

**开发要求**

- 更新 `SQL_QUERY_COLLECTION_DESIGN.md`，新增 Phase 2 章节。
- 明确 named parameters 策略：
  - 是否支持 `:param`。
  - 参数来源：当前用户、上下文变量、固定 metadata、请求 filter，分别是否允许。
  - 参数白名单和类型校验。
  - 参数绑定顺序与 JDBC `?` 转换规则。
- 明确 configured SQL 与外层 filter/sort/page 的边界：
  - configured SQL 只定义只读结果集。
  - 用户 filter/sort/page 只能拼在外层。
  - 禁止把前端 filter 直接字符串拼入 configured SQL。
- 明确 SQL collection 与 association/relation 的 Phase 2 范围：
  - 是否允许 SQL collection 作为 source。
  - 是否允许 SQL collection 作为 target。
  - 是否允许 SQL collection 上声明 relation field。
  - 哪些能力暂缓。
- 明确数据库方言差异：
  - H2 当前测试边界。
  - PostgreSQL identifier quote、LIMIT/OFFSET、CTE 边界。
  - MySQL identifier quote、分页边界。

**验收标准**

- 文档中的能力描述与当前代码一致，不把未实现能力写成已支持。
- 至少给出 3 个前端 API 不变的调用示例。
- 明确 Phase 2 的最小开发范围和暂缓范围。

## P2-J：SQL executor 内部结构整理

**目标**

在进入 named parameters 前，先把 SQL executor 的构造职责拆清楚，避免继续变成大字符串拼接类。

**开发要求**

- 提取或整理以下内部职责：
  - configured SQL 校验。
  - projection 构建。
  - filter/scope 编译。
  - sort 构建。
  - count SQL 构建。
  - pagination 参数追加。
- 继续使用 metadata 校验字段名，不能接受任意前端字段作为 SQL identifier。
- 不引入过度抽象；只拆真实重复和边界清晰的部分。
- 保持 `DynamicRepository` 是权限入口，executor 不直接做 action 权限判断。

**验收标准**

- 现有 SQL collection 行为不回退。
- `mvn -q test` 通过。
- 后续支持 named parameters 时，不需要重写 list/get 主流程。

## 并行开发建议

- 并行组 1：P0-A、P0-B。二者都涉及主键与 get 查询契约，建议同一人处理。
- 并行组 2：P0-C、P0-D、P1-G。测试矩阵、隔离和 primaryKey 回归可以并行推进，但最终要统一跑全量测试。
- 并行组 3：P0-E。belongsToMany association API 测试相对独立。
- 并行组 4：P1-F、P1-H。DDL 边界和架构边界测试相对独立。
- 并行组 5：P2-I、P2-J。Phase 2 文档和 executor 整理可以在 P0 收口后合并。

## Claude 交付要求

完成后输出总结文档：`SQL_COLLECTION_PHASE1_CLOSURE_COMPLETION_SUMMARY.md`。

总结必须包含：

- 是否修改前端，答案必须是“否”。
- 每个 P0/P1/P2 任务的完成状态。
- 修改文件列表。
- SQL collection list/get 的最终调用链。
- SQL get filter 契约说明。
- 主键硬编码清理结果和剩余例外说明。
- SQL collection 权限测试矩阵。
- belongsToMany association API 真实测试说明。
- view/sql DDL 边界测试说明。
- 架构边界 allowlist。
- `mvn -q test` 的总测试数、失败数、错误数。
