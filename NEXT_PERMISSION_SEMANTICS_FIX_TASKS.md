# NEXT_PERMISSION_SEMANTICS_FIX_TASKS

> 本任务清单基于 `INTERNAL_DATA_LAYER_COMPLETION_SUMMARY.md` 的架构 review 结果生成。
> 目标：修正“权限语义分离”未真正落地的问题，确保公开 CRUD、内部 scope 校验、through 表内部操作三者边界清晰。
> 约束：NocoBase 前端保持不变，只修改 Java 后端。

## 一、当前验收结论

当前批次测试数量已增长到 `79 tests`，并且 `mvn -q test` 通过，但不能整体验收。

主要原因：

- through 内部操作名义上新增了 API，但内部仍调用公开 `create/destroy/list`，没有真正绕过 through 表前端权限。
- `existsInScope(collection, action, id)` 的 `action` 参数没有真正参与 scope 计算。
- `readAfterWrite()` 在 write-only 用户场景下可能泄露完整记录字段。
- 关联清空操作使用 `Map.of(..., null)`，会触发 `NullPointerException`。
- 普通角色测试不真实，测试主体仍多为 admin，没有构造完整 `RoleResourceAction/RoleResourceScope` 矩阵。

## 二、必须坚持的权限语义

### 公开 API

controller 面向前端调用，必须检查 action 权限：

- `list/get`：检查 `list/get` action + 对应 action scope + readable fields。
- `create/update/destroy`：检查对应 action + 对应 action scope + writable fields。

### 内部 API

association、relation、write-after-read 等内部调用，不应误触发公开 action：

- `existsInScope(collection, action, id)`：只应用指定 action 的 scope，不检查 `get` action，不应用 readable fields。
- `findByFilterForAction(collection, action, filter, ...)`：只应用指定 action 的 scope，不检查 action 权限；action 权限由调用方在公开入口检查。
- `readAfterWrite(collection, id, writeAction)`：使用 write action 的 scope，不检查 `get` 权限；返回字段不能超过当前用户真实 readable fields。
- through 操作：绕过 through 表前端资源权限；源/目标 collection 权限和 scope 由调用方负责检查。

## 三、任务清单

### P0-A：让 ACL scope 支持 action 维度

涉及文件：

- `src/main/java/com/nocobase/acl/AclFilterInjector.java`
- `src/main/java/com/nocobase/data/DynamicRepository.java`
- 相关 ACL 测试

任务要求：

- 新增或改造 scope 合并方法：
  - `mergeScopeFilter(resourceName, action, userFilter)`
  - 保留旧方法时只能作为兼容包装，不允许内部 API 继续忽略 action。
- `existsInScope(collection, action, id)` 必须应用指定 action 的 scope。
- `findByFilterForAction(collection, action, filter, ...)` 必须应用指定 action 的 scope。
- `readAfterWrite(collection, id, writeAction)` 必须应用 `create/update` 对应 action 的 scope。
- 公开 `list/get/create/update/destroy` 也必须传入对应 action 计算 scope。

验收标准：

- 同一资源配置不同 action scope 时，`list`、`get`、`update` 能得到不同 scope 结果。
- `existsInScope(collection, "update", id)` 不受 `get/list` scope 影响。
- 测试必须构造不同 action scope，不能只用 admin。

### P0-B：真正实现 through 表内部操作

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- 必要时新增 `ThroughTableOperations.java`
- 相关 belongsToMany 测试

任务要求：

- `listLinks()` 不能调用公开 `list()`，也不能要求 through 表 `list` 权限。
- `createLink()` 不能调用公开 `create()`，也不能要求 through 表 `create` 权限。
- `deleteLink()` 不能调用公开 `destroy()`，也不能要求 through 表 `destroy` 权限。
- `replaceLinks()` 不能调用公开 `destroy/create()`。
- through 操作仍必须走统一 SQL 构造/执行出口，不能把裸 `JdbcTemplate` 放回 `AssociationActionService` 或 `RelationQueryService`。
- through 操作必须校验 through 表或 through metadata 的字段合法性，避免任意 table/column 注入。
- through 操作不返回 through 表字段给前端。

验收标准：

- 普通角色没有 through 表任何 action 权限，也能在源/目标权限允许时完成 belongsToMany `add/remove/set`。
- 普通角色没有 through 表 `list` 权限，也能正常执行 belongsToMany `append/list`。
- through 表字段不会出现在 API 返回结果中。
- 相关测试必须显式断言 through 表无权限。

### P0-C：修正 RelationQueryService 的 belongsToMany append

依赖：P0-B。

涉及文件：

- `src/main/java/com/nocobase/data/RelationQueryService.java`
- 相关 append 测试

任务要求：

- `appendBelongsToMany()` 必须使用 through 内部 API，例如 `listLinks()`。
- 不允许调用 `dynamicRepository.list(throughTable, ...)` 查询 through 表。
- 查询目标 collection 仍必须走目标 collection 的 `list` 权限和 `list` scope。
- append 结果只返回目标 collection 数据，并按 readable fields 过滤。

验收标准：

- 用户无 through `list` 权限时，belongsToMany append 仍成功。
- 目标 collection 无 `list` 权限时，append 返回 403。
- 目标记录在目标 `list` scope 外时，append 不返回该记录。

### P0-D：修正 readAfterWrite 字段泄露

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- `src/main/java/com/nocobase/acl/AclService.java`
- 相关 write-only 权限测试

任务要求：

- `readAfterWrite()` 不能因为用户没有 `list/get` 字段配置就默认返回全字段。
- 明确定义写后返回策略：
  - 如果用户有 readable fields，返回这些字段，并始终包含主键。
  - 如果用户没有任何读取权限，返回最小结果，例如 `{ id }`。
  - 如果 admin/root，返回完整记录。
- 修正 `AclService.getReadableFields()` 的歧义：区分“全部字段可读”和“没有任何读取权限”。
- 不允许把“无 list/get action”解释成“全字段可读”。

验收标准：

- 只有 `create` 权限、没有 `list/get` 权限的用户创建成功，但返回结果只包含 `id` 或最小兼容字段。
- 只有 `update` 权限、没有 `list/get` 权限的用户更新成功，但返回结果不泄露不可读字段。
- 有 readable fields 限制时，写后返回只包含允许字段和 `id`。

### P0-E：修正 nullable Map 构造问题

涉及文件：

- `src/main/java/com/nocobase/data/AssociationActionService.java`
- 可能涉及 `DynamicRepository.java`
- 相关 association 测试

任务要求：

- 全面检查 `Map.of(..., null)`、`List.of(null)` 等 JDK 不允许 null 的写法。
- `belongsTo set null` 必须能清空外键。
- `hasMany remove` 必须能把目标外键置空。
- `hasMany set` 清空旧关联时必须能把旧记录外键置空。
- 可使用 `LinkedHashMap` 或 helper 构造允许 null value 的 Map。

验收标准：

- `belongsTo set` 传空 targetIds 时不抛 `NullPointerException`，并成功清空外键。
- `hasMany remove` 不抛 `NullPointerException`。
- `hasMany set` 清空旧关系不抛 `NullPointerException`。

### P1-F：重做普通角色权限测试

依赖：P0-A、P0-B、P0-C、P0-D、P0-E。

涉及文件：

- `src/test/java/com/nocobase/AclPermissionTest.java`
- 必要时新增 `AssociationPermissionTest.java`

任务要求：

- 测试必须创建真实非 admin 用户。
- 测试必须创建真实 role。
- 测试必须写入真实：
  - `RoleResource`
  - `RoleResourceAction`
  - `RoleResourceScope`
- 测试不能只调用 `authAsAdmin()`。
- 禁止用 `assertThrows(Exception.class)` 作为核心权限断言，应断言明确异常类型或 HTTP 状态。
- 至少覆盖以下场景：
  - 有 `create` 无 `get/list`：创建成功，返回不泄露字段。
  - 有 `update` 无 `get/list`：更新成功，返回不泄露字段。
  - `update` scope 与 `list` scope 不同：各自按 action scope 生效。
  - `existsInScope("update")` 不依赖 `get` 权限。
  - belongsToMany 无 through 权限但源/目标权限允许：操作成功。
  - belongsToMany append 无 through `list` 权限：append 成功。
  - 目标 collection 无 `list`：append 返回 403。
  - 目标记录在目标 `list` scope 外：append 不返回。
  - writable fields 限制生效。
  - readable fields 限制生效。

验收标准：

- 新增测试能在修复前失败、修复后通过。
- 普通角色测试数量明显增加。
- `mvn -q test` 全量通过。

### P1-G：收紧架构边界测试

涉及文件：

- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java`
- `src/main/java/com/nocobase/service/CollectionManagerService.java`

任务要求：

- `ArchitectureBoundaryTest` 不能简单把 `CollectionManagerService` 永久列为允许例外。
- 如果 `CollectionManagerService` 已不再使用：
  - 标记为 deprecated。
  - 增加测试确保没有 controller/service 注入它。
  - 后续任务中安排删除。
- 如果仍在使用：
  - 必须改造成委托 `CollectionMetadataService/DdlSynchronizer`，不能保留自己的 DDL 拼接逻辑。
- 架构边界测试要明确允许列表：
  - `DynamicRepository`
  - `DdlSynchronizer`
  - 方言探测/SQL collection executor
  - migration

验收标准：

- 新增 service/controller 直接注入 `JdbcTemplate` 时测试失败。
- `RelationQueryService`、`AssociationActionService` 保持无 `JdbcTemplate`。
- `CollectionManagerService` 的遗留状态被明确处理。

## 四、交付要求

Claude 完成后输出：

- `PERMISSION_SEMANTICS_COMPLETION_SUMMARY.md`

总结必须包含：

- 每个任务完成状态。
- 修改文件清单。
- 新增/修改内部 API 列表。
- 公开 CRUD、内部 scope、through 内部操作三类权限语义说明。
- 普通角色测试矩阵。
- `mvn -q test` 结果。
- 未完成问题和原因。

禁止只更新 summary，不修实现或不补测试。
