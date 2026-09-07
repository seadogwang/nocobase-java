# NEXT_REVIEW_FIX_TASKS

> 本任务清单基于 `NEXT_DEVELOPMENT_COMPLETION_SUMMARY.md` 的架构 review 结果生成。
> 目标：继续保持 NocoBase 前端不变，只修正 Java 后端兼容性、权限链路和数据层抽象问题。

## 一、验收结论

当前批次不能整体验收。

- `N1` 异常响应主链路：基本可接受。
- `N2` `CurrentUserContext` 角色名修正：基本可接受。
- `N3` `DynamicRepository` 默认接入 ACL：方向正确，但 `update/destroy` scope 执行方式需要修正。
- `N4` UI Schema JSON 读写：未完全通过，存在静默吞错导致 schema 丢失的风险。
- `N5` 关联查询走权限链路：不通过，只检查 action，未应用目标 collection scope。
- `N6` `AssociationActionService`：不通过，存在空实现和绕过数据层权限的问题。

## 二、开发原则

- 前端保持不变，不修改 NocoBase 前端代码。
- 所有动态数据访问必须经过 collection metadata、field metadata、relation metadata、capability、ACL action、ACL scope、字段权限。
- 不允许 controller 或 relation service 绕过数据层权限直接裸查/裸改业务表。
- 动态业务表 SQL 必须集中在统一数据访问层，不允许后续模块各自拼接 SQL，避免权限、方言、字段映射和关系查询逻辑分散。
- API 响应结构继续保持 NocoBase 前端兼容：成功 `{ data }`，列表 `{ data, meta }`，错误 `{ errors: [{ message }] }`。
- 本批任务优先修正安全和兼容性，不做插件市场、工作流、移动端等扩展功能。

## 三、可并行任务分组

### P0-0：建立统一动态数据访问出口

此任务必须优先开发，P0-A、P0-B、P0-C 应基于本任务产物改造。

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- `src/main/java/com/nocobase/data/RelationQueryService.java`
- `src/main/java/com/nocobase/data/AssociationActionService.java`
- `src/main/java/com/nocobase/ddl/DdlSynchronizer.java`
- 必要时新增 `DynamicQueryExecutor`、`DynamicMutationExecutor`、`SqlBuilder`、`DataSourceRepository` 等类

任务要求：

- 梳理当前所有业务表 `JdbcTemplate` 使用点，区分动态 CRUD、关联查询、关联动作、DDL、系统元数据访问。
- 建立统一动态数据访问出口，后续动态 collection 的 `list/get/create/update/destroy/append/association` 都必须经过该出口。
- 统一处理 identifier quoting、table name、column name、field name 映射、where 条件、sort、pagination、bind parameters。
- 统一接入 collection capability、ACL action、ACL scope、readable fields、writable fields。
- 统一封装数据库方言差异，禁止业务 service 自己拼接方言相关 SQL。
- 明确允许例外：DDL 同步、Flyway migration、用户自定义 SQL collection/view/sql 查询执行器可以使用底层 SQL，但必须经过独立边界和安全校验。
- 增加代码约束：`controller`、`service`、`relation` 层不得直接对动态业务表调用 `JdbcTemplate.queryForList/update`。

验收标准：

- `RelationQueryService` 和 `AssociationActionService` 不再直接裸查或裸改动态业务表。
- 新增测试证明 ACL scope、字段权限、分页、排序在统一出口下仍然生效。
- 保留 `JdbcTemplate` 作为底层执行组件，但上层模块只能调用统一数据访问 API。
- 文档中说明哪些 SQL 使用场景允许绕过统一出口，以及为什么允许。

### P0-A：重做关联动作服务

负责人可独立开发。

涉及文件：

- `src/main/java/com/nocobase/data/AssociationActionService.java`
- `src/main/java/com/nocobase/controller/GenericCrudController.java`
- 必要时新增关联动作测试

任务要求：

- 完整实现 `list/get/add/remove/set` 关联动作。
- 支持 `belongsTo`、`hasOne`、`hasMany`、`belongsToMany`。
- `list/get` 不能裸 `SELECT *` 返回目标表数据，必须复用或抽象出受 ACL 保护的数据查询能力。
- `add/remove/set` 必须检查源 collection 和目标 collection 的 action 权限。
- `add/remove/set` 必须校验源记录和目标记录是否在当前用户 scope 内。
- `belongsTo` 当前 `listBelongsTo()` 返回空的问题必须修复。
- `setBelongsTo()` 当前空实现必须修复。
- `filterByTk` 缺失时返回 400，不允许落入 `NumberFormatException`。
- controller 必须显式支持不兼容或未实现的 action，并返回 NocoBase 兼容错误。

验收标准：

- 普通用户无法读取或修改 scope 外的关联数据。
- 无目标 action 权限时，关联 list/get/add/remove/set 返回 403。
- `belongsTo`、`hasMany`、`belongsToMany` 至少各有一个正向和一个越权测试。

### P0-B：修正 append 关联查询权限

负责人可与 P0-A 并行。

涉及文件：

- `src/main/java/com/nocobase/data/RelationQueryService.java`
- `src/main/java/com/nocobase/data/DynamicRepository.java`
- 必要时新增 relation append 测试

任务要求：

- `appends` 查询目标 collection 时，必须应用目标 collection 的 ACL scope。
- 不允许只检查 `aclService.canAction(targetCollection, "list")` 后直接查询全表。
- 查询字段必须受目标 collection readable fields 控制。
- `belongsToMany` through 表访问需要有明确边界：through 表只作为 join 中间表，不把 through 表字段暴露给前端。
- 对未知 relation 字段继续返回 400。

验收标准：

- 主表记录可见时，append 的关联记录仍然必须受目标表 scope 限制。
- 目标表无 `list` 权限时 append 返回 403。
- readable fields 限制对 append 返回数据生效。

### P0-C：修正 update/destroy scope 原子性

负责人可独立开发。

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- 相关 ACL scope 测试

任务要求：

- `update` 最终 SQL 必须包含 `id` 条件和 ACL scope 条件。
- `destroy` 最终 SQL 必须包含 `id` 条件和 ACL scope 条件。
- 不允许先 `SELECT COUNT` 再仅按 `id` 更新/删除。
- 根据 affected rows 判断记录不存在或无权限，返回兼容错误。
- `create/update` 返回记录时，不应额外要求用户拥有 `get` 权限；如果需要返回字段，按 readable fields 过滤，或返回最小兼容结果。

验收标准：

- 用户对 scope 外记录执行 update/destroy 不会改动数据库。
- 并发情况下 scope 检查和写入条件不会分离。
- 对无 `get` 但有 `create/update` 权限的角色，创建/更新行为不应被错误拒绝。

### P1-D：严格校验 fields 和 sort 参数

负责人可与 P0 任务并行。

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- `src/main/java/com/nocobase/controller/GenericCrudController.java`
- 查询参数测试

任务要求：

- `fields` 包含未知字段时返回 400。
- `fields` 包含非物理字段时返回 400，除非该字段通过 `appends`/relation 专门处理。
- `fields` 全部非法时不能回退到 `*`。
- `sort` 包含未知字段或非物理字段时返回 400。
- 保持 NocoBase 前端现有合法请求不受影响。

验收标准：

- `fields=not_exists` 返回 400。
- `sort=not_exists` 返回 400。
- 合法 `fields` 和 `sort` 仍能正常分页查询。

### P1-E：修正 UI Schema JSON 错误处理

负责人可独立开发。

涉及文件：

- `src/main/java/com/nocobase/controller/UiSchemaController.java`
- UI Schema API 测试

任务要求：

- `parseSchema()` 解析失败时不能返回空 Map，必须抛出明确异常。
- `toJsonString()` 序列化失败时不能返回 `{}`，必须抛出明确异常。
- `insertAdjacent` 的 `position` 必须校验，只允许 NocoBase 兼容的位置值。
- `patch` 深合并保持当前行为，但坏 JSON 必须返回 400。

验收标准：

- 坏 schema JSON 返回 `{ errors: [{ message }] }`。
- 坏 schema 不会被保存为空 schema。
- 合法 `insertAdjacent/patch/getJsonSchema/getTree` 行为保持兼容。

### P1-F：补齐测试矩阵

此任务依赖 P0-A、P0-B、P0-C、P1-D、P1-E，可由单独负责人最后统一补齐。

涉及目录：

- `src/test/java`

任务要求：

- 新增 AssociationActionService 或 MockMvc 集成测试。
- 新增 relation append + ACL scope 测试。
- 新增 update/destroy scope 原子性测试。
- 新增非法 `fields/sort` 测试。
- 新增 UI Schema 坏 JSON 测试。
- 保持 `mvn -q test` 全量通过。

验收标准：

- 测试数量应明显增加，不能仍停留在当前 46 个测试。
- 每个 P0/P1 修复点至少有一个失败前可复现、修复后通过的测试。

## 四、暂缓任务

以下任务暂不进入本批开发，等 P0/P1 修完后再安排：

- 真实 `view/sql collection` 查询执行。
- 多数据源 API。
- 用户、角色、权限完整管理 API。
- 插件注册表和系统模块启动顺序。
- PostgreSQL/MySQL 方言兼容验证。

## 五、Claude 开发交付要求

Claude 完成后需要提交：

- 修改文件清单。
- 每个任务的完成状态。
- 新增测试列表。
- `mvn -q test` 运行结果。
- 仍未解决的问题和原因。

禁止只更新 summary 而不补测试。
