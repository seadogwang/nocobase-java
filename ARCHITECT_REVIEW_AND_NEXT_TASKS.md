# 架构 Review 与后续开发任务

本文档基于 `DEVELOPMENT_COMPLETION_SUMMARY.md` 和当前代码抽查结果编写，供 Claude 后续开发使用。

## Review 结论

当前实现已经完成了 Java 后端原型的第一轮骨架，`mvn test` 当前可通过，结果为 41 个测试全部通过。但总结中“已完成”的部分需要重新分级：多数任务完成了类和基础 happy path，不等于已经达到 NocoBase 后端兼容语义。

后续不要继续扩展工作流、文件、通知、AI、MCP。必须先把数据层和权限层收口，否则后续模块都会返工。

## 必须修正的问题

### P0-1：CollectionController 绕过了新数据层

`CollectionController` 仍直接使用 `JdbcTemplate` 建表、加列、删表，并手写 SQL 类型映射和错误格式。这绕过了 `DdlSynchronizer`、`CollectionRuntimeService`、`FieldTypeMapper`、capability 和统一响应。

影响：

- 动态建表和字段添加没有走统一 DDL 方言。
- 不刷新 runtime registry。
- 错误返回仍是 `{ "error": "..." }`，不兼容约定的 `{ "errors": [...] }`。
- 关系字段会被错误当成普通列处理。

要求：

- `CollectionController` 只能调用 service，不允许直接使用 `JdbcTemplate`。
- collection/field 的创建、删除、修改统一走 `DdlSynchronizer` 和 runtime reload。
- 移除或废弃旧 `CollectionManagerService` 中重复的 JDBC DDL 实现。

### P0-2：Filter DSL 没有使用 metadata 校验字段

`DynamicRepository` 调用 `CompiledFilter.compile(filter)` 时没有传入 `CollectionDefinition`，`CompiledFilter` 直接 quote 用户传入的字段名。

影响：

- filter 可以引用不存在字段。
- filter 不知道字段真实列名，例如 `belongsTo` 的 `foreignKey`。
- 后续 ACL scope filter 无法安全合并。

要求：

- 改为 `FilterCompiler.compile(filter, collectionDefinition)`。
- 字段名必须来自 `CollectionDefinition`。
- SQL 条件使用字段的 `effectiveColumnName`。
- 不支持字段和不支持操作符必须抛标准业务异常。

### P0-3：Capability 模型没有被执行路径使用

`CollectionCapability` 已存在，但 `DynamicRepository.create/update/destroy/list` 没有检查 capability。

影响：

- view/sql collection 仍可能被写入。
- schemaMutable/indexMutable 没有约束 DDL 操作。

要求：

- `CollectionDefinition` 持有或可计算 `CollectionCapability`。
- 所有读写和 DDL 操作前检查 capability。
- view/sql collection 写操作必须返回标准错误。

### P0-4：DDL Synchronizer 方言选择错误

`DdlSynchronizer` 构造函数固定注入 `H2DialectAdapter`。

影响：

- 生产 PostgreSQL 环境会继续用 H2 方言生成 DDL。
- `PostgresDialectAdapter` 目前只是存在，未被实际选择。

要求：

- 新增 `DialectAdapterFactory` 或基于 datasource/hibernate dialect 的方言选择。
- H2 测试使用 H2 adapter，PostgreSQL SQL 生成测试使用 Postgres adapter。
- 禁止在业务 service 中硬编码具体 adapter 实现。

### P0-5：未知字段被静默忽略

`DynamicRepository.filterFields()` 会静默丢弃未知字段。

影响：

- 前端或调用方误传字段时无法发现错误。
- 权限层接入后无法区分“未知字段”和“无权字段”。

要求：

- 未知字段返回明确错误。
- 只读字段、系统字段、关系字段写入规则要显式处理。
- 测试必须断言未知字段不能被静默忽略。

### P0-6：统一错误格式尚未真正统一

`AuthController`、`CollectionController` 和部分测试仍使用或断言 `$.error`。

影响：

- 前端兼容层不稳定。
- 全局异常处理被 Controller 局部 try/catch 绕过。

要求：

- Controller 不直接返回 `{ "error": ... }`。
- 测试全部改为断言 `$.errors[0].message`。
- 业务错误通过标准异常交给 `GlobalExceptionHandler`。

### P1-1：ACL 只有数据模型，没有数据层注入

目前只有 `RoleResource` 等实体和 repository，没有 `AclService`、字段权限裁剪、scope filter 注入。

影响：

- 现在的 CRUD 只有认证，没有 collection/action/field/scope 授权。
- 与 NocoBase “权限控制在数据层”的核心语义不匹配。

要求：

- 实现 `AclService` 和 `AclFilterInjector`。
- `DynamicRepository` 所有读写路径必须接收 current user/role context。
- list/get/update/destroy 必须叠加 scope filter。
- create/update 必须校验可写字段。
- response 必须裁剪不可读字段。

### P1-2：关系字段只是 metadata，没有查询和关联动作

当前只构建了 `RelationDefinition`，但 `appends` 参数没有实际使用。

影响：

- 前端关联字段、子表、关系区块无法工作。
- 关联权限无法验证。

要求：

- 支持 `belongsTo`、`hasOne`、`hasMany`、`belongsToMany` 的 append 查询。
- 支持 `{resource}.{association}:list/get/add/remove/set`。
- 关联动作也必须经过 ACL。

### P1-3：测试覆盖不足

当前测试主要覆盖 happy path，未覆盖关键失败路径和 NocoBase 兼容语义。

要求：

- 添加只读 collection 写入失败测试。
- 添加 filter 非法字段测试。
- 添加未知字段写入失败测试。
- 添加 CollectionController 不直接拼 SQL 的行为测试。
- 添加 ACL scope 合并测试。
- 添加 relation append 和 association action 测试。

## 后续开发任务安排

### 第一批：P0 返工收口，可并行但需频繁合并

#### T1：统一 Collection 管理入口

依赖：无。

目标：

- 消除 `CollectionController` 和 `CollectionManagerService` 中的旧 JDBC DDL 路径。

开发要求：

- 新增或重构 `CollectionMetadataService`。
- `collections:create`、`collections:destroy`、`fields:create`、`fields:destroy` 统一调用 `CollectionMetadataService`。
- `CollectionMetadataService` 编排 metadata repository、`DdlSynchronizer`、`CollectionRuntimeService.reload()`。
- Controller 不直接注入 `JdbcTemplate`。
- 所有错误走统一异常处理。

验收标准：

- `CollectionController` 中不存在 `JdbcTemplate`。
- 创建 collection 后 runtime 立即可见。
- 添加 field 后 runtime 立即可见。
- 关系字段不会被错误添加普通列。
- 测试覆盖 create/destroy/addField/dropField。

#### T2：FilterCompiler metadata 化

依赖：C1 已有 runtime。

目标：

- 用 metadata 驱动 filter 编译，禁止 raw field quote。

开发要求：

- 新增 `FilterCompiler`，替代或重构 `CompiledFilter.compile()`。
- 编译入口必须接收 `CollectionDefinition`。
- 字段名必须存在于 `CollectionDefinition`。
- SQL 使用 `FieldDefinition.effectiveColumnName`。
- 对关系字段先返回明确“不支持关系 filter”错误，后续再扩展。
- 不支持操作符和非法结构返回标准异常。

验收标准：

- 非法字段 filter 失败。
- `belongsTo` 字段能按 foreign key 编译。
- `$and/$or` 嵌套仍正常。
- 所有值参数绑定。

#### T3：执行 CollectionCapability

依赖：C2 已有 capability。

目标：

- capability 不再只是模型，必须参与读写和 DDL 决策。

开发要求：

- `CollectionDefinition` 增加 `getCapability()`。
- `DynamicRepository.list/get` 检查 readable。
- `DynamicRepository.create/update/destroy` 检查 writable。
- `CollectionMetadataService` 的结构修改检查 schemaMutable/indexMutable。
- view/sql collection 默认只读。

验收标准：

- physical collection 可 CRUD。
- view/sql collection 可 list/get。
- view/sql collection create/update/destroy 返回标准错误。
- view/sql collection add/drop field 返回标准错误。

#### T4：方言选择与 DDL 原子性修正

依赖：无。

目标：

- 修正 `DdlSynchronizer` 固定使用 H2 adapter 的问题，并明确 DDL 失败后的 metadata 行为。

开发要求：

- 新增 `DialectAdapterFactory`。
- 按当前 datasource 或配置选择 H2/PostgreSQL adapter。
- `DdlSynchronizer` 依赖 `DialectAdapter` 抽象，不依赖 `H2DialectAdapter`。
- metadata 保存和 DDL 执行顺序需要明确：建议先校验 DDL plan，再执行 DDL，再保存 metadata；或者失败时显式补偿删除 metadata。
- 记录哪些 DDL 在 H2/PostgreSQL 下不能事务回滚。

验收标准：

- H2 集成测试通过。
- PostgreSQL adapter SQL 生成单测通过。
- 模拟 DDL 失败时，不留下 collection/field metadata。

#### T5：统一错误格式返工

依赖：无。

目标：

- 消除 `{ "error": ... }` 返回。

开发要求：

- `AuthController`、`CollectionController`、`GenericCrudController` 不手写 `Map.of("error", ...)`。
- 业务失败抛标准异常。
- 404 也返回 `{ "errors": [{ "message": "..." }] }`。
- 修正测试，禁止断言 `$.error`。

验收标准：

- 全项目搜索不到 `Map.of("error"`。
- API 失败测试全部断言 `$.errors[0].message`。
- `mvn test` 通过。

#### T6：DynamicRepository 写入规则硬化

依赖：T2、T3。

目标：

- 明确字段写入规则，避免静默忽略。

开发要求：

- 未知字段写入返回错误。
- `id`、`created_at`、`updated_at` 等系统字段默认不可写。
- hidden 字段暂不等于无权字段，ACL 任务再裁剪。
- 关系字段写入只支持明确规则：首批允许 `belongsTo` 通过 foreign key 写入，其他关系字段返回不支持。
- create 返回实际插入后的记录，不能只返回原始 request body。

验收标准：

- 未知字段 create/update 失败。
- 系统字段 create/update 失败。
- create 返回含 id 的实际记录。
- update 返回更新后的实际记录。

### 第二批：ACL 数据层落地，可并行拆分

#### T7：CurrentUserContext 与角色解析

依赖：T5。

目标：

- 为数据层提供当前用户、当前角色、用户角色集合。

开发要求：

- JWT filter 设置 user id 到 SecurityContext。
- 新增 `CurrentUserContext`。
- 支持读取当前用户角色。
- 默认 admin/root 全权限，member 最小权限。

验收标准：

- 登录后可在 service 层拿到 userId。
- 用户角色解析测试通过。

#### T8：AclService action/field 权限

依赖：G1、T7。

目标：

- 实现 collection/action/field 权限判定。

开发要求：

- `AclService.canAction(resource, action, context)`。
- `AclService.readableFields(resource, context)`。
- `AclService.writableFields(resource, action, context)`。
- root/admin bypass。
- 无权限抛 `ForbiddenException`。

验收标准：

- 无 list 权限不能查询。
- 无 update 权限不能更新。
- 不可写字段不能写入。
- 不可读字段不出现在 response。

#### T9：AclFilterInjector scope 合并

依赖：T2、T8。

目标：

- 将角色数据范围 filter 注入查询和变更。

开发要求：

- scope filter 与用户请求 filter 用 `$and` 合并。
- update/destroy 不能只按 id，必须同时满足 scope。
- 多角色策略先采用最保守明确策略，需写入设计说明。

验收标准：

- member 只能看到 scope 内数据。
- update scope 外数据返回无权限或未找到，不能更新成功。
- destroy scope 外数据失败。

### 第三批：关系字段与 appends

#### T10：RelationQueryService appends 查询

依赖：T2、T8。

目标：

- 支持前端常用关联字段读取。

开发要求：

- 实现 `belongsTo` append。
- 实现 `hasOne` append。
- 实现 `hasMany` append。
- 实现 `belongsToMany` append。
- `appends=author,tags` 在 `DynamicRepository.list/get` 中生效。
- append 结果也要经过 ACL 字段裁剪。

验收标准：

- belongsTo 返回对象。
- hasOne 返回对象或 null。
- hasMany 返回数组。
- belongsToMany 返回数组。
- 非法 append 字段失败。

#### T11：AssociationActionService

依赖：T10。

目标：

- 兼容 NocoBase 关联资源动作。

开发要求：

- 支持 `{resource}.{association}:list`。
- 支持 `{resource}.{association}:get`。
- 支持 `{resource}.{association}:add`。
- 支持 `{resource}.{association}:remove`。
- 支持 `{resource}.{association}:set`。
- `GenericCrudController` 或 URL 适配层能正确识别带点资源名。

验收标准：

- `posts.tags:list/add/remove/set` 测试通过。
- `users.posts:list` 测试通过。
- 关联动作不能绕过 ACL。

### 第四批：前端兼容补齐

#### T12：applicationPlugins 内置模块注册表

依赖：T5。

目标：

- 让前端插件列表、系统必要模块状态稳定。

开发要求：

- 内置模块包括 `users`、`auth`、`acl`、`client`、`ui-schema-storage`、`ui-layout`、`data-source-main`、`data-source-manager`、`system-settings`、`error-handler`。
- 返回字段包含 `name`、`packageName`、`enabled`、`installed`、`builtIn`、`version`、`displayName`、`description`。
- 系统必要模块不可禁用、不可删除。

验收标准：

- `applicationPlugins:listEnabled` 返回前端需要字段。
- 禁用/删除系统必要模块失败。

#### T13：UI Schema Storage 兼容补齐

依赖：T5、T8。

目标：

- 支持现有前端页面 schema 读写。

开发要求：

- 支持 `uiSchemas:getTree`。
- 支持 `uiSchemas:getJsonSchema`。
- 支持 `uiSchemas:getParentJsonSchema`。
- 支持 `uiSchemas:insertAdjacent`。
- 支持 `uiSchemas:patch`。
- 支持 `uiSchemas:remove`。
- 支持 `uiSchemaTemplates:list/get`。
- 保留 `x-uid`、parent、children、sort 语义。

验收标准：

- getTree 能返回稳定树结构。
- insert/patch/remove 后再次 getTree 正确。
- 不修改前端文件。

## Claude 交付要求

每个任务完成后必须提交：

- 任务编号。
- 主要改动文件。
- 是否修改前端文件，必须为“否”。
- 设计说明。
- 测试命令。
- 测试结果。
- 已知限制。

## 当前测试状态

已运行：

```bash
mvn -q test
```

结果：

- 测试通过。
- 但当前测试覆盖不足，不能作为架构完成依据。
