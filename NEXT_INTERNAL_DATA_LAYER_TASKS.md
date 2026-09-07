# NEXT_INTERNAL_DATA_LAYER_TASKS

> 本任务清单基于 `REVIEW_FIX_COMPLETION_SUMMARY.md` 的架构 review 结果生成。
> 目标：在已收敛到 `DynamicRepository` 的基础上，继续拆清“公开 CRUD 权限”和“系统内部关系操作”的边界。
> 约束：NocoBase 前端保持不变，只修改 Java 后端。

## 一、当前状态

上一轮开发已经完成了重要收敛：

- `RelationQueryService` 不再直接使用 `JdbcTemplate`。
- `AssociationActionService` 不再直接使用 `JdbcTemplate`。
- 动态业务表访问主路径已经统一到 `DynamicRepository`。
- `update/destroy` 的 scope 已合并进最终 SQL。
- `fields/sort` 非法参数已开始严格校验。
- UI Schema JSON 解析失败不再静默吞错。
- 当前测试结果为 `61 tests, 0 failures, 0 errors`。

但当前架构仍存在核心问题：

- `DynamicRepository.create/update` 返回记录时仍调用公开 `get()`，导致写操作额外依赖 `get` 权限。
- `AssociationActionService.verifyInScope()` 使用公开 `get()` 判断 scope，导致关联写入额外依赖 `get` 权限。
- `belongsToMany` through 表被当作普通 collection 走 `list/create/destroy` 权限，语义不符合 NocoBase。
- 当前测试多为 admin 场景，无法证明普通角色下 ACL scope、字段权限、关系权限正确。

## 二、开发原则

- `DynamicRepository` 仍然是动态业务表 SQL 的统一出口。
- 不允许 controller、relation service、association service 自己拼动态业务表 SQL。
- 公开 API 权限和内部数据层操作必须分开。
- 内部关系维护可以绕过 through 表的前端资源权限，但不能绕过源 collection、目标 collection、scope、字段写入规则。
- ACL scope 必须用于判断记录是否可参与当前操作。
- 字段权限必须用于最终返回结果，不能为了内部校验误触发不相关的 readable 权限。

## 三、任务清单

### P0-A：拆分 DynamicRepository 内部访问 API

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- 必要时新增 `DynamicReadOptions`、`DynamicWriteOptions`、`DynamicQueryOptions`
- 相关单元/集成测试

任务要求：

- 保留现有公开方法：
  - `list(collectionName, filter, sort, page, pageSize, fields)`
  - `get(collectionName, primaryKey)`
  - `create(collectionName, data)`
  - `update(collectionName, primaryKey, data)`
  - `destroy(collectionName, primaryKey)`
- 新增内部方法，供 relation/association 使用：
  - `existsInScope(collectionName, action, primaryKey)`
  - `findByFilterForAction(collectionName, action, filter, sort, page, pageSize, fields)`
  - `readAfterWrite(collectionName, primaryKey, readableMode)`
  - `updateByFilterForAction(collectionName, action, data, filter)`
- 内部方法必须复用统一 SQL 构造逻辑，不得复制拼 SQL。
- `existsInScope()` 只判断当前 action 的 scope，不检查 `get` action，不应用 readable fields。
- `findByFilterForAction()` 可以指定使用 `list/get/update/destroy` 等 action 的权限和 scope。
- `readAfterWrite()` 用于 `create/update` 返回结果，不能因为没有 `get` 权限导致写操作失败。
- `updateByFilterForAction()` 用于关联批量更新，例如 `hasMany set` 清空旧 FK。

验收标准：

- 用户有 `update` 但无 `get` 权限时，`update()` 不因返回记录失败。
- 用户有 `create` 但无 `get` 权限时，`create()` 不因返回记录失败。
- `existsInScope(collection, "update", id)` 不调用公开 `get()`。
- 代码中没有为了内部校验而调用公开 `get()` 的逻辑。

### P0-B：修正 create/update 返回记录语义

依赖：P0-A。

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- `src/main/java/com/nocobase/controller/GenericCrudController.java`
- 相关 MockMvc 或集成测试

任务要求：

- `create()` 插入成功后不要直接调用公开 `get()`。
- `update()` 更新成功后不要直接调用公开 `get()`。
- 返回结果应兼容 NocoBase 前端：
  - 优先返回按 readable fields 过滤后的记录。
  - 如果当前角色没有读取字段权限，至少返回可识别的主键或兼容的最小结果。
  - 写操作成功不应因为读取权限不足被回滚或报错。
- 对无权限字段写入仍必须返回 403。

验收标准：

- 角色只有 `create` 权限时，创建成功。
- 角色只有 `update` 权限时，更新成功。
- 无 writable field 权限时，创建/更新仍然失败。
- 返回结构保持 `{ data: ... }` 兼容。

### P0-C：修正 AssociationActionService 的 scope 校验

依赖：P0-A。

涉及文件：

- `src/main/java/com/nocobase/data/AssociationActionService.java`
- 相关关联动作测试

任务要求：

- `verifyInScope()` 不再调用公开 `dynamicRepository.get()`。
- 关联 `add/remove/set` 的源记录校验使用 `existsInScope(sourceCollection, "update", sourceId)`。
- 关联 `add/remove/set` 的目标记录校验使用适合当前关系动作的 action，例如：
  - `hasMany/belongsToMany add`：目标记录必须在目标 collection 的 `update` scope 内。
  - `belongsTo set`：目标记录至少必须在目标 collection 的可关联 scope 内，当前阶段可使用 `update` scope。
- `list/get` 关联读取继续使用目标 collection 的 `list/get` 语义。
- 不允许因为用户缺少 `get` 权限而阻止合法的关联写入。

验收标准：

- 有 `update` 无 `get` 的角色可以执行合法关联写入。
- 目标记录在 scope 外时，关联写入失败。
- 源记录在 scope 外时，关联写入失败。
- 关联读取仍受目标 collection readable fields 控制。

### P0-D：建立 belongsToMany through 内部访问语义

依赖：P0-A。

涉及文件：

- `src/main/java/com/nocobase/data/DynamicRepository.java`
- `src/main/java/com/nocobase/data/AssociationActionService.java`
- `src/main/java/com/nocobase/data/RelationQueryService.java`
- 必要时新增 `ThroughTableOperations`
- 相关 belongsToMany 测试

任务要求：

- through 表不能作为前端普通资源权限对象参与 association/appends 权限判断。
- 新增 through 内部操作：
  - `listLinks(throughCollectionOrTable, sourceKey, sourceIds, otherKey)`
  - `createLink(throughCollectionOrTable, sourceKey, sourceId, otherKey, targetId)`
  - `deleteLink(throughCollectionOrTable, sourceKey, sourceId, otherKey, targetId)`
  - `replaceLinks(throughCollectionOrTable, sourceKey, sourceId, otherKey, targetIds)`
- through 内部操作仍必须通过统一 SQL 出口执行，不能回到 service 裸 `JdbcTemplate`。
- through 操作前必须校验源 collection 和目标 collection 权限/scope。
- through 返回数据只允许作为内部 join/link 数据使用，不直接返回给前端。
- 如果 through collection 未显式注册，也要有明确处理策略：要么自动注册内部 collection definition，要么通过受控 table metadata 访问。

验收标准：

- 普通角色无需拥有 through 表 `list/create/destroy` 权限，也能在源/目标权限允许时完成 belongsToMany 操作。
- through 表字段不会出现在 API 返回结果中。
- belongsToMany append 只返回目标 collection 数据，并受目标 scope/readable fields 控制。
- through 表未注册时的行为有测试覆盖。

### P1-E：补齐普通角色权限测试

依赖：P0-B、P0-C、P0-D。

涉及文件：

- `src/test/java/com/nocobase`
- 必要时新增 `AclIntegrationTest`、`AssociationAclTest`

任务要求：

- 新增非 admin 用户和角色测试数据。
- 新增 role resource/action/scope/field 权限测试数据。
- 覆盖以下场景：
  - 有 `create` 无 `get`：创建成功。
  - 有 `update` 无 `get`：更新成功。
  - 有 `update` 但目标记录 scope 外：更新失败。
  - 关联 `add/remove/set` 不依赖 `get` 权限。
  - 关联目标 scope 外时失败。
  - appends 目标 scope 外时不返回。
  - readable fields 限制对普通 list/get/appends 都生效。
  - writable fields 限制对 create/update/association 写入生效。
  - belongsToMany 不要求 through 表直接权限。

验收标准：

- 新测试不能只使用 admin 身份。
- 每个 P0 权限语义至少有一个普通角色测试。
- 测试断言具体异常类型或 HTTP 状态，不要只写 `assertThrows(Exception.class)`。
- `mvn -q test` 全量通过。

### P1-F：增加架构边界检测测试

可与 P1-E 并行。

涉及文件：

- `src/test/java/com/nocobase`
- 必要时新增 `ArchitectureBoundaryTest`

任务要求：

- 增加测试或静态扫描，防止后续 service/controller 再次直接使用 `JdbcTemplate` 访问动态业务表。
- 允许例外名单：
  - `DynamicRepository`
  - `DdlSynchronizer`
  - Flyway migration
  - 明确命名的 SQL collection/view executor
  - 系统元数据 JPA repository
- 当前遗留的 `CollectionManagerService` 如果已经不用，应标记废弃或纳入重构；如果仍在用，必须改为走 `CollectionMetadataService/DdlSynchronizer`。

验收标准：

- 新增代码若在 `controller/service/data` 中直接注入 `JdbcTemplate`，测试应失败，除非在允许例外名单内。
- `RelationQueryService`、`AssociationActionService` 保持无 `JdbcTemplate` 依赖。
- 架构规则写入测试或文档，便于 Claude 后续继续遵守。

## 四、暂不处理

以下事项仍暂缓，不要混入本批任务：

- 真实 `view/sql collection` 查询执行。
- 多数据源管理 API。
- 完整用户、角色、权限配置页面后端。
- 插件生命周期和插件依赖排序。
- PostgreSQL/MySQL 真实数据库兼容验证。

## 五、Claude 交付要求

完成后必须输出新的总结文档，例如：

- `INTERNAL_DATA_LAYER_COMPLETION_SUMMARY.md`

总结文档必须包含：

- 每个任务完成状态。
- 新增/修改文件清单。
- 新增内部 API 列表和用途说明。
- 权限语义说明：公开 CRUD 权限、内部 scope 校验、through 表内部访问如何区分。
- 新增测试列表。
- `mvn -q test` 结果。
- 未完成事项和原因。

禁止只修改 summary，不补实现或测试。
