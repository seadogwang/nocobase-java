# 下一批后端开发任务

本文档基于 `ARCHITECT_REWORK_COMPLETION_SUMMARY.md` 和当前代码抽查结果编写，供 Claude 继续开发。前端必须保持不变。

## 当前 Review 结论

返工后的 P0 基线比上一轮明显收口，`mvn -q test` 当前通过。`CollectionController` 已改为调用 `CollectionMetadataService`，`FilterCompiler` 已接收 `CollectionDefinition`，capability 和写入规则也已进入 `DynamicRepository`。

但当前仍有几个关键缺口：

- ACL 相关服务已经存在，但还没有默认接入 `DynamicRepository` 主链路。
- `CurrentUserContext` 用 `role_1`、`role_3` 这种 role id 拼接判断 admin/root，不符合 NocoBase 的角色名语义。
- `RelationQueryService` 直接查询关联表全字段，没有经过 `DynamicRepository`、ACL scope、字段裁剪。
- `GenericCrudController` 仍在局部 catch 异常并统一返回 500，会绕过 `GlobalExceptionHandler` 的状态码语义。
- `uiSchemas` 的 `parseSchema()` 和 `toJsonString()` 是简化实现，会丢失真实 JSON schema 内容。
- `{resource}.{association}:list/get/add/remove/set` 关联动作仍未完整实现。
- 视图和 SQL collection 目前还没有真正按视图/SQL 查询源读取。

## 开发硬约束

- 禁止修改 `src/main/resources/static/v/**`。
- 保留 `/api/{resource}:{action}` 和 NocoBase 响应格式。
- 所有业务错误必须走 `GlobalExceptionHandler`。
- 数据权限必须在数据访问层生效，不能只依赖 Controller 或 Spring Security。
- 每个任务必须带测试，交付时写明测试命令和结果。

## 推荐并行安排

### 第一批，必须优先完成

- `N1` 异常处理主链路收口
- `N2` CurrentUserContext 角色名修正
- `N3` ACL 默认接入 DynamicRepository
- `N4` UI Schema JSON 正确读写

### 第二批，可并行

- `N5` 关联查询走权限链路
- `N6` 关联动作 AssociationActionService
- `N7` 真实 view/sql collection 查询
- `N8` API 协议兼容扩展测试

### 第三批，依赖前两批

- `N9` 数据源主模块 API 补齐
- `N10` 用户/角色/权限管理 API 补齐
- `N11` 系统模块注册表行为补齐
- `N12` PostgreSQL 验证与方言收口

## N1：异常处理主链路收口

### 目标

让 Controller 不再吞掉业务异常，避免所有错误都变成 500。

### 修改范围

- `controller/GenericCrudController.java`
- `controller/AuthController.java`
- `controller/CollectionController.java`
- `web/GlobalExceptionHandler.java`
- 相关测试

### 开发要求

- 移除 `GenericCrudController` 的大范围 `try/catch`。
- `ResourceNotFoundException` 返回 404 + `{ errors: [{ message }] }`。
- `ForbiddenException` 返回 403 + `{ errors: [{ message }] }`。
- `UnauthorizedException` 返回 401 + `{ errors: [{ message }] }`。
- `IllegalArgumentException` 返回 400 + `{ errors: [{ message }] }`。
- 不允许 Controller 返回 `ResponseEntity.notFound().build()`。

### 验收标准

- 未知 collection 返回 404，不是 500。
- view collection 写入返回 403，不是 500。
- 非法 filter 返回 400，不是 500。
- 全项目搜索不到 `ResponseEntity.notFound().build()`。
- `mvn test` 通过。

## N2：CurrentUserContext 角色名修正

### 目标

用真实角色名驱动 ACL，而不是 `role_1`、`role_3` 这类不稳定 id。

### 修改范围

- `acl/CurrentUserContext.java`
- `repository/RoleRepository.java`
- `entity/UserRole.java`
- `config/DataInitializer.java`
- 相关测试

### 开发要求

- `getCurrentUserRoles()` 返回 `admin`、`root`、`member` 等角色名。
- `isAdmin()` 根据角色名判断 `admin` 或 `root`。
- 不允许依赖 seed 数据里的 role id 顺序。
- 未认证用户返回空角色集合。
- 增加当前用户、角色解析、admin/root bypass 测试。

### 验收标准

- `admin@nocobase.com` 登录后角色包含 `admin`。
- role id 改变后 `isAdmin()` 仍正确。
- member 不会被误判为 admin。

## N3：ACL 默认接入 DynamicRepository

### 目标

让 action 权限、字段权限、scope filter 在通用 CRUD 主链路默认生效。

### 修改范围

- `data/DynamicRepository.java`
- `acl/AclService.java`
- `acl/AclFilterInjector.java`
- `data/FilterCompiler.java`
- 相关测试

### 开发要求

- `list/get/create/update/destroy` 默认调用 `AclService.canAction()`。
- `list/get` 查询前调用 `AclFilterInjector.mergeScopeFilter()`。
- `update/destroy` 不能只按 id 操作，必须叠加 ACL scope。
- `create/update` 调用 `AclService.checkWritableField()`。
- `list/get` 返回前调用 `AclService.filterReadableFields()`。
- admin/root 保持 bypass。
- 为测试提供明确方式构造 member 权限。

### 验收标准

- 无 list 权限时查询返回 403。
- 无 update 权限时更新返回 403。
- 字段不可写时 create/update 返回 403。
- 字段不可读时 response 中不出现该字段。
- scope 外数据不能被 get/update/destroy。
- scope filter 与用户 filter 正确 `$and` 合并。

## N4：UI Schema JSON 正确读写

### 目标

修复 `UiSchemaController` 简化 JSON 实现，保证前端 schema 不丢失。

### 修改范围

- `controller/UiSchemaController.java`
- `entity/UiSchema.java`
- `repository/UiSchemaRepository.java`
- 相关测试

### 开发要求

- 使用 Spring 注入的 Jackson `ObjectMapper` 解析和序列化 JSON。
- `parseSchema()` 必须返回真实 schema 内容。
- `toJsonString()` 必须保留嵌套对象和数组。
- `insertAdjacent` 必须保留传入 schema 的所有字段，包括 `x-component`、`x-decorator`、`properties`、`x-uid`。
- `patch` 要做 JSON 对象合并，不能把嵌套对象写成 `"__json__"`。
- `remove` 支持 request body 和 query param 两种传参方式。

### 验收标准

- 插入带嵌套 `properties` 的 schema 后，`getJsonSchema` 原样返回核心字段。
- `patch` 嵌套对象后不丢失原有字段。
- `getTree` 返回真实树结构。
- 测试覆盖 insert/patch/remove/getTree/getJsonSchema。

## N5：关联查询走权限链路

### 目标

让 `appends` 查询不绕过 ACL 和字段裁剪。

### 修改范围

- `data/RelationQueryService.java`
- `data/DynamicRepository.java`
- `acl/AclService.java`
- 相关测试

### 开发要求

- append 目标 collection 必须检查 `list/get` 权限。
- append 查询必须叠加目标 collection 的 ACL scope。
- append 返回字段必须经过 `filterReadableFields()`。
- 不允许 `SELECT *` 直接暴露目标 collection 全字段。
- belongsTo、hasOne、hasMany、belongsToMany 都要覆盖。

### 验收标准

- 无目标 collection 权限时 append 返回 403。
- 目标 collection 不可读字段不会出现在 append 结果。
- 目标 collection scope 外记录不会被 append。
- 四种关系类型测试通过。

## N6：关联动作 AssociationActionService

### 目标

完整支持 NocoBase 关联资源动作。

### 新增建议

- `data/AssociationActionService.java`
- `data/AssociationRequest.java`

### 修改范围

- `controller/GenericCrudController.java`
- `config/NocobaseUrlFilter.java`
- `runtime/RelationDefinition.java`
- 相关测试

### 支持动作

- `{resource}.{association}:list`
- `{resource}.{association}:get`
- `{resource}.{association}:add`
- `{resource}.{association}:remove`
- `{resource}.{association}:set`

### 开发要求

- URL 适配层必须能保留点分资源名，例如 `/api/posts.tags:list`。
- `GenericCrudController` 能识别 `posts.tags` 并分发到 `AssociationActionService`。
- belongsToMany 通过 through table 操作。
- hasMany/set 通过 foreign key 操作。
- belongsTo/set 通过 source record foreign key 操作。
- 关联动作必须检查 source 和 target collection ACL。

### 验收标准

- `posts.tags:list/add/remove/set` 测试通过。
- `users.posts:list/set` 测试通过。
- 关联动作不能绕过字段权限和数据范围权限。

## N7：真实 view/sql collection 查询

### 目标

让 collection 不只代表物理表，也能代表视图和 SQL 查询结果集。

### 修改范围

- `runtime/CollectionDefinition.java`
- `data/DynamicRepository.java`
- `service/CollectionMetadataService.java`
- `ddl/DdlSynchronizer.java`
- 相关测试

### 开发要求

- view collection 读取时从 `tableName` 指向的视图读取，不创建普通表。
- sql collection 读取时以配置 SQL 作为子查询来源。
- sql collection 必须只读。
- sql collection 必须显式声明 fields，filter/sort/fields 只能引用声明字段。
- SQL collection 禁止拼接用户 SQL，只能对已配置 SQL 外层追加安全 filter/sort/page。

### 验收标准

- view collection list/get 成功，create/update/destroy 失败。
- sql collection list/get 成功，create/update/destroy 失败。
- sql collection filter/sort/page 生效。
- 未声明字段 filter/sort 失败。

## N8：API 协议兼容扩展测试

### 目标

用测试锁住前端不变的关键 API 协议。

### 修改范围

- `src/test/java/com/nocobase/**`

### 开发要求

- 增加 MockMvc 测试覆盖：
  - `/api/{collection}:list`
  - `/api/{collection}:get?filterByTk=`
  - `/api/{collection}:create`
  - `/api/{collection}:update?filterByTk=`
  - `/api/{collection}:destroy?filterByTk=`
  - `/api/{collection}.{association}:list`
  - `/api/uiSchemas:insertAdjacent`
  - `/api/uiSchemas:patch`
  - `/api/applicationPlugins:listEnabled`
- 验证响应结构，不只验证 status。
- 验证错误结构统一为 `errors[0].message`。

### 验收标准

- 所有新增测试通过。
- 不依赖固定测试执行顺序。
- 不使用本地文件数据库。

## N9：数据源主模块 API 补齐

### 目标

补齐前端管理 collections/fields 所需的数据源主模块 API。

### 修改范围

- `controller/CollectionController.java`
- `service/CollectionMetadataService.java`
- `runtime/CollectionRuntimeService.java`
- 相关 DTO 和测试

### 开发要求

- 支持 `collections:get`。
- 支持 `collections:update`，只更新 metadata 中允许变更的字段。
- 支持 `fields:list`。
- 支持 `fields:get`。
- 支持 `fields:update`，处理 nullable/default/unique/index/options。
- 支持 `fields:destroy` 的依赖检查占位，避免删除被关系依赖的字段。

### 验收标准

- 前端 collection manager 常用读取接口不报 404。
- 更新 collection title/options 后 runtime 刷新。
- 更新 field options 后 runtime 刷新。

## N10：用户/角色/权限管理 API 补齐

### 目标

让 users、roles、rolesResources 等基础权限管理能被前端调用。

### 修改范围

- `controller/**`
- `acl/**`
- `entity/**`
- `repository/**`
- 相关测试

### 开发要求

- users 继续走 DynamicRepository，但补齐 `users:updateProfile`、`users:updateLang`。
- roles 支持 list/get/create/update/destroy。
- roles.resources/actions/scopes 支持基础 CRUD。
- 角色权限变更后 ACL 查询立即生效。

### 验收标准

- admin 可管理 roles。
- member 不能管理 roles。
- 修改权限后无需重启即可影响 DynamicRepository。

## N11：系统模块注册表行为补齐

### 目标

让 `applicationPlugins` 更接近 NocoBase 内置插件协议。

### 修改范围

- `controller/ApplicationPluginController.java`
- `entity/ApplicationPlugin.java`
- `config/DataInitializer.java`
- 相关测试

### 开发要求

- `listEnabled` 返回 `name`、`packageName`、`enabled`、`installed`、`builtIn`、`version`、`displayName`、`description`、`packageJson`。
- 补齐 `applicationPlugins:list`。
- 禁用/删除系统必要模块返回 403。
- 非必要模块可预留启停接口，但不实现 npm 安装。

### 验收标准

- 前端插件列表页读取不缺字段。
- 系统必要模块不可禁用/删除。

## N12：PostgreSQL 验证与方言收口

### 目标

减少 H2 原型与 PostgreSQL 生产环境之间的偏差。

### 修改范围

- `ddl/**`
- `src/test/**`
- `application.yml`
- Maven 测试配置

### 开发要求

- 增加 PostgreSQL profile 配置。
- 增加 Testcontainers PostgreSQL 集成测试，若当前环境无法拉镜像，则至少保留可跳过 profile。
- 验证 baseline migration 在 PostgreSQL 可执行。
- 验证动态 DDL 在 PostgreSQL 可执行或 SQL 生成正确。
- 明确 JSON 字段在 PostgreSQL 中使用 `jsonb` 还是 `text`。

### 验收标准

- H2 测试继续通过。
- PostgreSQL profile 下 migration 和 DDL 测试可运行，或明确被条件跳过。
- 文档说明 H2 与 PostgreSQL 的已知差异。

## Claude 交付要求

每个任务完成后必须提交：

- 任务编号和名称。
- 主要改动文件。
- 是否修改前端文件，必须回答“否”。
- 设计说明，特别说明与 NocoBase 语义的兼容点。
- 测试命令。
- 测试结果。
- 已知限制和下一步依赖。

## 暂缓任务

以下任务继续暂缓：

- 工作流执行引擎。
- 文件管理与附件存储。
- 通知中心。
- AI、MCP、OAuth/IDP。
- npm 插件安装和远程插件市场。
- 任何前端 UI、组件、样式、构建产物修改。
