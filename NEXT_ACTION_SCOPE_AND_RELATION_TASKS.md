# NEXT_ACTION_SCOPE_AND_RELATION_TASKS

> 基于 `PERMISSION_SEMANTICS_COMPLETION_SUMMARY.md` 的 review 结果生成。
> 当前 `mvn -q test` 通过，测试报告为 `88 tests, 0 failures, 0 errors`，但权限语义仍未完全验收。
> 约束：NocoBase 前端保持不变，只修改 Java 后端。

## 一、Review 结论

本轮开发有明显进展：

- `create/update` 已改为 `readAfterWrite()`，不再直接调用公开 `get()`。
- `AssociationActionService.verifyInScope()` 已改为 `existsInScope()`。
- through 写操作已从公开 `create/destroy` 改为 `DynamicRepository` 内部 SQL。
- `Map.of(..., null)` 已开始改为可空 Map helper。
- 测试数量从 79 增加到 88。

但仍不能作为最终权限语义定版：

- `get()` 仍使用 `list` scope，而不是 `get` scope。
- `findByFilterForAction(collection, action, ...)` 参数里有 `action`，但实现仍固定使用 `list` scope。
- `RoleResourceScope` 表结构没有 action 维度，`AclFilterInjector` 无法真正按 action 区分 scope。
- belongsToMany append 查询 through 表仍通过 `findByFilterForAction(through, "list", ...)`，会受到 through 表 scope 影响，不是真正的内部 through 读取。
- `AclPermissionTest` 虽然增加了 member 用户，但很多用例没有真实断言权限失败或 scope 过滤结果。

## 二、必须优先修正的语义

公开 API：

- `list` 必须用 `list` action 权限、`list` scope、readable fields。
- `get` 必须用 `get` action 权限、`get` scope、readable fields。
- `create` 必须用 `create` 权限、create 相关写入规则，写后返回不能依赖 `get`。
- `update` 必须用 `update` 权限、`update` scope、writable fields。
- `destroy` 必须用 `destroy` 权限、`destroy` scope。

内部 API：

- `existsInScope(collection, action, id)` 必须只应用指定 action 的 scope。
- `findByFilterForAction(collection, action, filter, ...)` 必须只应用指定 action 的 scope。
- `readAfterWrite(collection, id, writeAction)` 必须只应用 writeAction scope。
- through link 操作必须绕过 through 表前端权限和 through 表前端 scope，只校验 source/target collection 权限与 scope。

## 三、任务清单

### P0-A：为 RoleResourceScope 增加 action 维度

涉及文件：

- `src/main/java/com/nocobase/entity/RoleResourceScope.java`
- `src/main/java/com/nocobase/repository/RoleResourceScopeRepository.java`
- `src/main/java/com/nocobase/acl/AclFilterInjector.java`
- `src/main/resources/db/migration`
- 相关 ACL 测试

任务要求：

- 在 `role_resource_scopes` 增加 action 字段，支持同一 resource 不同 action 使用不同 scope。
- repository 增加按 `roleResourceId + action` 查询 scope 的方法。
- `AclFilterInjector.buildScopeFilter(resourceName, action)` 必须只读取指定 action 的 scope。
- 兼容历史数据：旧 scope 没有 action 时要有明确策略，可以迁移为 `list`，或作为默认 scope，但必须写入文档和测试。
- scope 查询失败或 JSON 解析失败不能静默放开权限，至少应 fail closed 或返回明确错误。

验收标准：

- 同一 collection 配置 `list` scope 和 `update` scope 不同，查询和更新结果不同。
- `existsInScope(collection, "update", id)` 只受 update scope 影响。
- `get()` 只受 get scope 影响，不受 list scope 影响。

### P0-B：修正 DynamicRepository action scope 调用错误

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- 相关数据层测试

任务要求：

- `get()` 必须调用 `mergeScopeFilter(collectionName, "get", filter)`，不能使用 `"list"`。
- `findByFilterForAction(collectionName, action, ...)` 必须调用 `mergeScopeFilter(collectionName, action, filter)`，不能固定使用 `"list"`。
- `listLinks()` 不应通过 `findByFilterForAction(through, "list", ...)` 实现，避免套用 through 前端 scope。
- 检查所有 `mergeScopeFilter()` 调用点，确认 action 与当前语义一致。

验收标准：

- 静态测试能发现 `findByFilterForAction` 固定使用 `"list"` 的问题。
- get/list/update/destroy 各自 scope 测试全部通过。

### P0-C：实现 through 专用内部读取 API

依赖：P0-B。

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- `src/main/java/com/nocobase/data/RelationQueryService.java`
- 必要时新增 `ThroughLinkRecord` 或 `ThroughTableOperations`
- 相关 belongsToMany append 测试

任务要求：

- `listLinks()` 必须使用 through 内部 SQL 查询，不检查 through action，不应用 through scope。
- `listLinks()` 只允许返回 sourceKey、otherKey、必要主键，不返回完整 through row。
- `appendBelongsToMany()` 必须调用 `listLinks()`，不能再直接调 `findByFilterForAction(through, "list", ...)`。
- through table、sourceKey、otherKey 必须来自 relation metadata，并经过 collection/field metadata 校验。
- 如果 through collection 未注册，需要定义明确处理策略：报错、自动内部注册，或使用受控 table metadata。

验收标准：

- 用户没有 through 表 `list` 权限和 scope 时，belongsToMany append 仍成功。
- through 表配置了限制性 scope 时，不影响 belongsToMany append。
- API 返回中不出现 through 表字段。

### P0-D：补齐真实 action scope 测试

依赖：P0-A、P0-B。

涉及文件：

- `src/test/java/com/nocobase/AclPermissionTest.java`
- 必要时新增 `ActionScopePermissionTest.java`

任务要求：

- 构造非 admin 用户、非 admin role、真实 `RoleResource`、`RoleResourceAction`、`RoleResourceScope`。
- 为同一 collection 配置不同 action scope：
  - `list` 只能看 A 记录。
  - `get` 只能看 B 记录。
  - `update` 只能改 C 记录。
  - `destroy` 只能删 D 记录。
- 测试必须断言具体结果，不允许只 `assertNotNull()`。
- 核心权限失败必须断言 `ForbiddenException` 或 HTTP 403。
- 不允许用 `assertThrows(Exception.class)` 作为核心权限断言。

验收标准：

- `list` 看不到 list scope 外记录。
- `get` 拿不到 get scope 外记录。
- `update` 改不了 update scope 外记录。
- `destroy` 删不了 destroy scope 外记录。
- `existsInScope("update")` 与 `existsInScope("get")` 对同一记录可返回不同结果。

### P0-E：补齐 belongsToMany through 权限测试

依赖：P0-C。

涉及文件：

- `src/test/java/com/nocobase/AclPermissionTest.java`
- 必要时新增 `BelongsToManyPermissionTest.java`

任务要求：

- 构造 source collection、target collection、through collection。
- 给普通角色 source/target 必要权限，不给 through 表任何 action 权限。
- 覆盖 association：
  - `add`
  - `remove`
  - `set`
  - `list/get`
- 覆盖 appends：
  - `?appends=tags`
- 给 through 表配置一个限制性 scope，证明 through scope 不影响内部关系读取和写入。
- 给 target collection 配置限制性 list scope，证明 target scope 仍影响最终 append 结果。

验收标准：

- 无 through 权限时，belongsToMany 操作成功。
- through scope 不影响关系内部 link 查询。
- target scope 会过滤最终关联结果。
- through 字段不出现在 API 返回结果。

### P1-F：修正 readable fields 语义歧义

涉及文件：

- `src/main/java/com/nocobase/acl/AclService.java`
- `src/main/java/com/nocobase/data/DynamicRepository.java`
- 相关字段权限测试

任务要求：

- 不再用 `null` 同时表示“全部字段可读”和“无读取权限配置”。
- 引入明确返回类型，例如：
  - `FieldPermission.all()`
  - `FieldPermission.none()`
  - `FieldPermission.only(Set<String>)`
- `list/get` 已通过 action 权限检查时，空 fields 可解释为 all。
- `readAfterWrite` 在无 list/get action 时必须返回最小结果 `{ id }`。
- readable/writable fields 字段名要统一使用 NocoBase field name，不要混入数据库列名造成误判。

验收标准：

- 有 list/get 且 fields 为空时返回全字段。
- 无 list/get 时写后只返回 `{ id }`。
- 有 list/get 且 fields 限制时只返回允许字段和必要主键。

### P1-G：收紧测试质量和架构边界

涉及文件：

- `src/test/java/com/nocobase/AclPermissionTest.java`
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java`
- `src/main/java/com/nocobase/service/CollectionManagerService.java`

任务要求：

- 删除或改写没有真实断言的测试，例如只断言 `memberUserId != null` 的用例。
- 核心权限测试不允许 `assertThrows(Exception.class)`。
- `CollectionManagerService` 如果已废弃，应安排删除；如果暂不能删除，应加注释说明替代路径和删除条件。
- 架构边界测试应扫描递归子目录，不只扫描一级 Java 文件。
- 检查测试之间是否依赖执行顺序或共享脏数据，必要时重构为独立数据集。

验收标准：

- 测试能在错误实现下失败。
- 架构边界测试能防止新增 service/controller 裸用 `JdbcTemplate`。
- `mvn -q test` 全量通过。

## 四、暂缓任务

以下任务不要混入本批：

- 真实 `view/sql collection` 查询。
- 多数据源管理。
- 用户/角色/权限配置 API 完整实现。
- 插件生命周期和插件依赖排序。
- PostgreSQL/MySQL 真实环境验证。

## 五、Claude 交付要求

完成后输出：

- `ACTION_SCOPE_RELATION_COMPLETION_SUMMARY.md`

总结必须包含：

- 每个任务完成状态。
- 数据库 schema/migration 变更说明。
- action scope 的最终数据模型说明。
- through 内部 API 最终调用链。
- 普通角色测试矩阵。
- `mvn -q test` 结果。
- 未完成问题和原因。

禁止只更新 summary，不修实现或不补有效测试。
