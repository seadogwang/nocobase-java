# 下一批开发任务：Action Scope / Relation Review 修正

> 面向 Claude 的后端开发任务清单。前端界面保持不变，只修改 Java 后端。

## Review 结论

本轮 `ACTION_SCOPE_RELATION_COMPLETION_SUMMARY.md` 的主方向是正确的：`DynamicRepository` 已成为公开数据访问和关系内部访问的统一出口，`list/get/update/destroy/readAfterWrite/existsInScope` 已开始按 action 传递 scope，`belongsToMany` 查询也改成通过 `listLinks()` 绕过 through 表前端权限。

但当前实现还不能进入下一阶段主数据源能力开发，原因是权限语义仍有几个高风险缺口：

- scope 解析异常仍可能变成无限制查询，违反 fail-closed 原则。
- 新增 action scope 测试存在弱断言，部分用例名称和实际覆盖不一致。
- belongsToMany through 权限绕过缺少真实关系链路测试。
- through 内部 SQL 已收敛到 `DynamicRepository`，但字段标识符还缺少元数据校验。
- 字段权限在无 `id` 的视图/SQL 结果集场景有空值风险。

因此下一批任务优先修正这些问题。完成后再继续推进 view/sql collection、数据源管理、多数据库方言等主数据层能力。

## 全局约束

- 只改 Java 后端，不能修改 NocoBase 前端接口形态和页面行为。
- Controller 不能绕过 `DynamicRepository` 直接访问 `JdbcTemplate`。
- `RelationQueryService`、`AssociationActionService` 可以调用 `DynamicRepository` 的内部 API，但不能自己拼 SQL。
- 公开 API 必须执行 action 权限、scope、字段权限。
- 内部关系 API 必须保留语义分离：`existsInScope` 只校验 scope，`readAfterWrite` 使用写 action 的 scope，through 内部操作不检查 through 前端 action。
- 所有新增或修改行为必须补测试，测试要能失败地证明问题被修复。

## P0-A：Scope 解析失败必须 fail-closed

**目标**

修复 `AclFilterInjector.buildScopeFilter(resourceName, action)` 中 scope JSON 解析失败时跳过该 scope 导致权限放大的问题。

**开发要求**

- 如果当前用户某个角色配置了 action scope，但 scope JSON 无法解析，不能返回 `null` 或不受限制的 filter。
- 推荐行为：抛出权限/配置异常，阻断当前请求；如选择 no-result filter，也必须统一封装并测试清楚。
- 多角色场景中，只要命中的 action scope 存在非法 JSON，不能因为其他角色 scope 正常而静默忽略非法配置。
- 日志可以保留，但日志不能代替权限阻断。
- 修正注释：多角色 scope 当前是 `$or` 合并，不要写成“most restrictive”。

**验收测试**

- `list` action scope JSON 非法时，请求失败或返回确定的无结果，不能返回全量数据。
- `get` action scope JSON 非法时，不能读取任意记录。
- `update/destroy` action scope JSON 非法时，不能修改或删除记录。
- 多角色：一个合法 scope、一个非法 scope，最终不能静默放行。

## P0-B：补强 action scope 真实断言

**目标**

修复 `AclPermissionTest` 里 action scope 用例弱断言的问题，让测试真正验证不同 action 使用不同 scope。

**开发要求**

- `differentActionScopeForListAndGet` 必须同时断言：
  - `list` 只返回 list scope 允许的数据。
  - `get` 只能读取 get scope 允许的数据。
  - list scope 与 get scope 配置不同，且结果必须能区分。
- 不要用被字段权限过滤掉的字段做断言，例如 `owner_id == null || owner_id == 100` 这种断言无效。
- 测试字段权限时，要显式授予断言所需字段，或改用 `name/id` 做可见断言。
- `existsInScopeDifferentActions` 必须断言 action 不匹配时为 false，例如只配置 `update` scope 时，`existsInScope(..., "list", id)` 不能通过。

**验收测试**

- 故意把 `get()` 改回使用 `"list"` scope 时，测试必须失败。
- 故意把 `findByFilterForAction()` 固定成 `"list"` scope 时，测试必须失败。
- 故意让 `existsInScope()` 忽略 action 时，测试必须失败。

## P0-C：补真实 belongsToMany through 权限测试

**目标**

把当前 `belongsToManyWithoutThroughPermission` 从“普通 update 测试”改成真实 belongsToMany 关系链路测试。

**开发要求**

- 在测试中创建 source、target、through 三个 collection，例如 articles、tags、article_tags。
- 配置 belongsToMany 关系字段，through 表包含 source key 和 target key。
- 不授予 through collection 的 `list/create/update/destroy` 前端权限。
- 授予 source/target 必要权限后，验证关系操作仍能通过内部 through API 工作。
- 给 through collection 配置严格 scope，验证内部 through 查询/写入不受 through 前端 scope 影响。
- 给 target collection 配置 list/get scope，验证 append 结果仍受 target scope 约束。

**验收测试**

- `AssociationActionService.add()` 能创建 through link，且不要求 through create 权限。
- `AssociationActionService.remove()` 能删除 through link，且不要求 through destroy 权限。
- `AssociationActionService.set()` 能替换 through links，且不要求 through update 权限。
- `RelationQueryService.appendRelations()` 查询 belongsToMany 时不要求 through list 权限。
- target 无权限或 target scope 不匹配时，append 不得返回目标记录。

## P0-D：校验 through 内部 SQL 的字段标识符

**目标**

`DynamicRepository.listLinks/createLink/deleteLink/replaceLinks` 已经收敛为统一出口，但 `sourceKey`、`otherKey` 等 SQL 标识符必须经过元数据校验，不能直接信任关系配置字符串。

**开发要求**

- 使用 through collection 的 runtime metadata 校验 `sourceKey`、`otherKey` 是真实字段/列。
- 如果字段不存在、字段不是普通列、或字段名包含非法字符，必须拒绝执行。
- `throughCollection` 继续通过 runtime metadata 解析物理表名，不允许直接传裸表名绕过 collection 元数据。
- `listLinks()` 对空 `sourceIds` 必须直接返回空结果，不能生成 `IN ()` SQL。
- `createLink/deleteLink/replaceLinks` 必须继续保持参数化值绑定，不允许拼接用户值。

**验收测试**

- 空 `sourceIds` 返回空列表。
- 非法 `sourceKey/otherKey` 被拒绝，不执行 SQL。
- 不存在字段被拒绝。
- 尝试注入式字段名不能影响数据库。
- 正常 belongsToMany 查询和增删改链路仍通过。

## P1-E：字段权限兼容无 id 的 view/sql collection

**目标**

修复 `AclService.filterReadableFields()` 和 `DynamicRepository.readAfterWrite()` 对 `id` 字段的隐式假设，为后续视图和 SQL collection 打基础。

**开发要求**

- `FieldPermission.none()` 时，如果 row 没有 `id` 或 `id` 为 null，不能使用 `Map.of("id", null)` 造成 NPE。
- 通过 collection metadata 判断 primary key 字段，而不是硬编码只认 `id`。
- 对没有 primary key 的 collection 类型，定义清楚返回策略：
  - list/get 无可读字段时返回空对象；或
  - 返回系统允许的最小字段集。
- `readAfterWrite()` 对无主键或非 `id` 主键 collection 要有明确行为，不能隐藏 NPE。

**验收测试**

- 无 `id` 的查询结果集不会抛 NPE。
- 自定义 primary key collection 字段过滤正确。
- 无可读字段时不会泄露业务字段。

## P1-F：收紧架构边界测试

**目标**

让架构测试真正防止后续 SQL 再次分散到 service/controller。

**开发要求**

- `ArchitectureBoundaryTest` 要递归扫描 `src/main/java`，不能只扫一级文件。
- 允许 `JdbcTemplate` 的文件名单必须保持最小化。
- 评估 `CollectionManagerService` 是否仍需要持有 `JdbcTemplate`：
  - 如果不再需要，迁移到 `DynamicRepository` 或 schema/DDL 专用组件后移除。
  - 如果仍需要，必须在测试和注释中说明它只用于元数据/DDL，不用于业务数据查询。
- Controller、ACL、Relation、Association 层出现新的 `JdbcTemplate` 依赖时测试必须失败。

**验收测试**

- 在任意 service 子目录新增 `JdbcTemplate` 依赖，架构测试必须失败。
- 当前所有合法 `JdbcTemplate` 使用点有明确 allowlist 和说明。

## P1-G：整理异常断言和测试隔离

**目标**

降低测试误报风险，确保权限回归能被稳定发现。

**开发要求**

- 避免 `assertThrows(Exception.class)`，改为具体异常类型或明确 HTTP 状态。
- 每个 ACL 测试使用独立数据集或明确清理，避免角色、权限、scope 互相污染。
- 新增 helper 时保持语义清楚，例如 `grantActionWithScope()`、`grantReadableFields()`、`assertVisibleNames()`。
- 测试名必须反映真实行为，不要用关系权限名称测试普通 update。

**验收测试**

- 全量 `mvn -q test` 通过。
- `AclPermissionTest` 中每个新增测试单独运行也通过。

## P2-H：暂缓下一阶段主数据层能力

以下任务暂缓，等 P0/P1 修完后再开始：

- view collection 只读查询能力。
- SQL query collection 结果集能力。
- 多数据源连接管理。
- 多数据库方言抽象。
- 生命周期 hook / 审计日志在 through 内部操作中的语义定义。

## Claude 交付要求

完成后输出新的总结文档：`ACTION_SCOPE_RELATION_REVIEW_FIX_COMPLETION_SUMMARY.md`。

总结必须包含：

- 修改文件列表。
- 每个 P0/P1 任务的完成状态。
- 权限语义说明：公开 API、内部 relation API、through 操作分别检查什么。
- 测试结果，必须包含 `mvn -q test` 的总用例数、失败数、错误数。
- 如有暂未处理项，必须明确原因和后续建议。
