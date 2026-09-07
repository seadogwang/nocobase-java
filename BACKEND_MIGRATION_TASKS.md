# NocoBase Java 后端迁移任务清单

本文档用于指导 Claude 分批开发 `nocobase-java` 后端。目标是在不修改前端的前提下，用 Java/Spring Boot 实现兼容 NocoBase 现有前端协议的数据层、权限层和系统必要模块。

## 硬约束

- 禁止修改 `src/main/resources/static/v/**`，前端界面和前端代码保持不变。
- 保留 NocoBase API 风格：`/api/{resource}:{action}`。
- 所有返回结构兼容现有前端：成功返回 `{ "data": ... }`，列表额外返回 `{ "meta": { "count", "page", "pageSize" } }`，失败返回 `{ "errors": [{ "message": "..." }] }`。
- Spring Security 只负责认证；collection/action/field/scope 权限必须落到数据访问层。
- 不要短期实现 npm 插件兼容机制；系统必要插件先改造成 Java 内置模块。
- 动态数据层是优先级最高的核心，工作流、文件、通知、AI、MCP 暂缓。
- 每个任务必须增加或更新测试，并在交付说明中写明测试命令和结果。

## 推荐执行顺序

### 第一批，可并行

- `A0` 项目基线治理
- `A1` API 兼容基线
- `B1` Collection 元数据模型
- `B2` Field 元数据模型

### 第二批，可并行

- `C1` Collection Runtime Registry
- `C2` Collection Capability 模型
- `E1` DDL Synchronizer
- `E2` 字段类型映射

### 第三批，可并行

- `D1` DynamicRepository 基础 CRUD
- `D2` Filter DSL 编译器
- `D3` 排序、分页、字段投影
- `G1` ACL 数据模型

### 第四批，依赖前面任务

- `G2` ACL Query 注入
- `F1` 关系字段查询
- `F2` 关联动作
- `H1` 系统模块注册表
- `H2` UI Schema Storage 兼容

## A0：项目基线治理

### 目标

让 Java 后端进入可持续开发状态，具备测试、迁移和稳定配置基础。

### 修改范围

- `pom.xml`
- `src/main/resources/application.yml`
- `src/test/java/**`
- `src/main/resources/db/migration/**`

### 开发要求

- 新增 Spring Boot 集成测试基础类。
- 引入 Flyway 迁移目录和 baseline 脚本。
- 禁用 `spring.jpa.hibernate.ddl-auto=update`，改为 `validate` 或 `none`。
- 保留 H2 开发环境，但结构设计以 PostgreSQL 为目标。
- 测试不能依赖已有本地 `storage/db/*.db`。

### 验收标准

- `mvn test` 可运行。
- 后端可启动。
- 不修改任何前端静态文件。

## A1：API 兼容基线

### 目标

锁住现有 NocoBase 前端调用协议，避免后续开发破坏兼容性。

### 修改范围

- `controller/**`
- `config/NocobaseUrlFilter.java`
- 统一响应/错误处理相关类
- `src/test/java/**`

### 开发要求

- 保留并测试以下接口：
  - `/api/auth:signIn`
  - `/api/auth:check`
  - `/api/applicationPlugins:listEnabled`
  - `/api/systemSettings:get`
  - `/api/uiSchemas:getTree`
  - `/api/collections:list`
- `NocobaseUrlFilter` 只做 URL 协议适配，不承载业务逻辑。
- 新增统一响应封装，避免各 Controller 手写不一致的结构。
- 新增统一异常处理，错误格式为 `{ "errors": [{ "message": "..." }] }`。

### 验收标准

- 集成测试覆盖上述接口。
- 未登录访问受保护 API 返回兼容错误。
- 登录、检查登录态、系统设置、插件列表、UI schema 基础读取不返回 404/500。

## B1：Collection 元数据模型

### 目标

把 `collections` 从简单表配置升级为 NocoBase 的数据集定义，可代表物理表、视图、SQL 查询结果集、外部数据源集合等。

### 修改范围

- `entity/CollectionEntity.java`
- `repository/CollectionRepository.java`
- collection DTO/service
- 相关测试

### 必要字段

- `name`
- `title`
- `tableName`
- `schema`
- `template`
- `type`
- `view`
- `sql`
- `inherits`
- `category`
- `sortable`
- `logging`
- `hidden`
- `system`
- `options`

### 开发要求

- `fields` 表是字段元数据主来源，不再依赖 `CollectionEntity.fields`。
- `options`、`view`、`sql`、`inherits` 等 JSON 字段用明确的 JSON 映射策略，避免到处手写字符串解析。
- 支持保存以下 collection 类型：
  - physical table
  - view collection
  - sql collection
  - external collection 预留
- 系统 collection 需要标记 `system=true`。

### 验收标准

- repository/service 测试覆盖三类 collection metadata 保存和读取。
- 不破坏 `/api/collections:list` 的前端兼容返回。

## B2：Field 元数据模型

### 目标

支持普通字段、系统字段、关系字段、虚拟字段的完整元数据。

### 修改范围

- `entity/FieldEntity.java`
- `repository/FieldRepository.java`
- field DTO/service
- 相关测试

### 必要字段

- `collectionName`
- `name`
- `type`
- `interface`
- `uiSchema`
- `options`
- `target`
- `foreignKey`
- `sourceKey`
- `targetKey`
- `through`
- `otherKey`
- `sort`
- `hidden`
- `system`

### 开发要求

- `interface` 表示 NocoBase 字段接口；`type` 表示底层字段类型或关系类型。
- 关系字段不能默认生成业务物理列。
- `belongsTo` 可通过 `foreignKey` 生成外键列。
- `hasOne`、`hasMany`、`belongsToMany` 通常不生成当前 collection 的普通列。
- 字段名必须做合法性校验，禁止 SQL 注入字符。

### 验收标准

- 测试覆盖 `string`、`integer`、`boolean`、`datetime`、`json`、`belongsTo`、`hasMany`、`belongsToMany`。
- 非法字段名被拒绝。
- 关系字段元数据可以保存且不会错误建列。

## C1：Collection Runtime Registry

### 目标

建立运行时数据模型缓存，所有通用查询、DDL、权限、关系处理都通过它获取 collection 定义。

### 新增建议

- `CollectionDefinition`
- `FieldDefinition`
- `RelationDefinition`
- `CollectionRuntime`
- `CollectionRuntimeService`

### 开发要求

- 应用启动时从 `collections` 和 `fields` 加载所有 collection。
- 支持 `reloadAll()`。
- 支持 `reload(collectionName)`。
- Controller 和 Repository 禁止直接猜表名，必须通过 runtime 获取。
- 不存在的 collection 返回标准错误。

### 验收标准

- 新建/修改 collection 后 runtime 可刷新。
- `DynamicRepository` 可以通过 runtime 获取表名、字段、关系、能力。
- 单元测试覆盖 reload 行为。

## C2：Collection Capability 模型

### 目标

按数据集来源区分可读、可写、可改结构、可关联等能力。

### 新增建议

- `CollectionCapability`
- `CollectionType`

### 开发要求

- physical table：支持 `read/create/update/delete/schemaMutable/indexMutable/relationSupported`。
- view collection：默认只读，支持查询、过滤、排序。
- sql collection：默认只读，必须显式声明字段。
- external collection：预留能力接口，不在当前任务实现连接器。
- 所有写操作前必须检查 capability。

### 验收标准

- 对只读 collection 执行 create/update/delete 返回明确错误。
- 测试覆盖 physical/view/sql 三种类型。

## D1：DynamicRepository 基础 CRUD

### 目标

替换 `GenericCrudController` 内部直接拼 SQL 的实现，建立元数据驱动的数据访问入口。

### 新增建议

- `DynamicRepository`
- `DynamicQueryService`
- `DynamicMutationService`

### 开发要求

- `GenericCrudController` 只解析 resource/action，然后调用 service。
- 支持 `list/get/create/update/destroy`。
- 表名、列名、字段白名单必须来自 `CollectionRuntime`。
- 请求体中未知字段必须拒绝或忽略，不能直接入库。
- SQL 参数必须绑定，不能拼接用户值。

### 验收标准

- 创建 collection 后可以 CRUD 数据。
- SQL 注入测试通过。
- 未定义字段不能写入。
- 只读 collection 写操作被拒绝。

## D2：Filter DSL 编译器

### 目标

实现 NocoBase 风格 filter 到 SQL 条件的安全编译。

### 新增建议

- `FilterParser`
- `FilterCompiler`
- `CompiledFilter`

### 支持操作符

- `$eq`
- `$ne`
- `$gt`
- `$gte`
- `$lt`
- `$lte`
- `$in`
- `$notIn`
- `$and`
- `$or`
- `$null`
- `$notNull`
- `$includes`

### 开发要求

- 字段名必须来自 metadata。
- 所有值必须参数绑定。
- 嵌套 `$and/$or` 必须支持。
- 不支持的操作符返回明确错误，不要静默忽略。

### 验收标准

- 每个操作符都有测试。
- 嵌套 `$and/$or` 有测试。
- 非法字段名、非法操作符、非法值结构有测试。

## D3：排序、分页、字段投影

### 目标

补齐 NocoBase 列表查询协议。

### 开发要求

- 支持 `page`。
- 支持 `pageSize`。
- 支持 `sort=-id,name`。
- 支持 `fields=id,name`。
- 返回 `meta.count/page/pageSize`。
- 投影字段必须来自 metadata。

### 验收标准

- 测试覆盖默认分页、指定分页、复杂排序、字段投影。
- 不存在字段不能出现在 SELECT 中。
- 不可见字段后续要可被 ACL 裁剪，当前任务需预留接口。

## E1：DDL Synchronizer

### 目标

把动态建表/改表逻辑从 `CollectionManagerService` 中抽离，形成可测试、可扩展的 DDL 同步器。

### 新增建议

- `DdlSynchronizer`
- `DialectAdapter`
- `H2DialectAdapter`
- `PostgresDialectAdapter`
- `DdlPlan`

### 支持能力

- create table
- add column
- drop column
- create index
- drop index
- unique constraint
- foreign key 预留或首批支持 `belongsTo`

### 开发要求

- metadata 保存和 DDL 执行必须在 service 层统一编排。
- DDL 失败不能留下半成功 metadata。
- 方言差异集中在 `DialectAdapter`。
- 不依赖 Hibernate 自动改表。

### 验收标准

- 建表、加字段、删字段、唯一索引有测试。
- H2 集成测试通过。
- PostgreSQL SQL 生成有单元测试。

## E2：字段类型映射

### 目标

建立 NocoBase field type/interface 到数据库列类型和运行时行为的映射。

### 新增建议

- `FieldTypeRegistry`
- `FieldTypeMapper`
- `FieldTypeDescriptor`

### 首批支持

- `string`
- `text`
- `integer`
- `bigInt`
- `float`
- `double`
- `boolean`
- `date`
- `datetime`
- `json`
- `uuid`
- `password`
- `belongsTo`
- `hasOne`
- `hasMany`
- `belongsToMany`

### 开发要求

- 普通字段返回列定义。
- 虚拟字段不生成列。
- 关系字段根据关系类型决定是否生成外键列。
- 字段默认值、nullable、unique、index 从 `options` 中读取。

### 验收标准

- 每种字段类型有映射测试。
- `belongsTo` 可生成外键字段。
- `hasMany`、`belongsToMany` 不会错误生成当前表普通列。

## F1：关系字段查询

### 目标

支持 NocoBase `appends` 查询关联数据。

### 开发要求

- 支持 `belongsTo` 追加对象。
- 支持 `hasOne` 追加对象。
- 支持 `hasMany` 追加数组。
- 支持 `belongsToMany` 通过 through 表追加数组。
- 支持 `appends=author,tags`。
- 关联字段查询必须经过 ACL 裁剪预留接口。

### 验收标准

- 文章-作者 `belongsTo` 测试通过。
- 用户-文章 `hasMany` 测试通过。
- 文章-标签 `belongsToMany` 测试通过。
- 无效 append 字段返回明确错误。

## F2：关联动作

### 目标

兼容 NocoBase 关联资源动作。

### 支持动作

- `{resource}.{association}:list`
- `{resource}.{association}:get`
- `{resource}.{association}:add`
- `{resource}.{association}:remove`
- `{resource}.{association}:set`

### 开发要求

- `belongsToMany` 通过 through collection 操作。
- `hasMany:set` 需要处理 foreign key。
- 关联动作也必须经过 action 权限、字段权限、数据范围权限。

### 验收标准

- add/remove/set/list 集成测试通过。
- 关联动作不能绕过 collection 权限。

## G1：ACL 数据模型

### 目标

补齐 NocoBase 数据层权限表和运行时权限模型。

### 新增实体

- `RoleResource`
- `RoleResourceAction`
- `RoleResourceScope`
- `RoleSnippet`

### 开发要求

- role 控制 resource。
- action 控制 `list/get/create/update/destroy`。
- fields 控制可读/可写字段。
- scope 保存 filter JSON。
- 初始化 `root`、`admin`、`member` 角色。
- admin/root 默认拥有全部权限。
- member 默认最小权限。

### 验收标准

- 权限数据可保存、读取、刷新。
- 测试覆盖 role/resource/action/scope/fields。

## G2：ACL Query 注入

### 目标

将 ACL 权限真正落到 `DynamicRepository` 和查询编译流程中。

### 新增建议

- `AclService`
- `AclFilterInjector`
- `CurrentUserContext`
- `FieldPermission`

### 开发要求

- `list/get` 自动叠加数据范围 filter。
- `create/update` 自动裁剪或拒绝不可写字段。
- response 自动移除不可读字段。
- `destroy/update` 必须叠加 scope 条件，不能只按主键操作。
- Spring Security 不承担 collection 级授权。

### 验收标准

- 不同角色读取同一 collection 返回不同数据。
- 无 update 权限时接口拒绝。
- 字段级读写权限测试通过。
- scope filter 与用户请求 filter 正确 AND 合并。

## H1：系统模块注册表

### 目标

把 NocoBase 系统必要插件改造成 Java 内置模块，同时保留 `application_plugins` 对前端的兼容返回。

### 内置模块首批

- `users`
- `auth`
- `acl`
- `client`
- `ui-schema-storage`
- `ui-layout`
- `data-source-main`
- `data-source-manager`
- `system-settings`
- `error-handler`

### 开发要求

- `application_plugins` 返回前端需要的数据。
- 内置模块标记 `builtIn=true`、`installed=true`、`enabled=true`。
- 系统必要模块不可删除、不可禁用。
- 暂不实现动态安装 npm 插件。

### 验收标准

- 前端插件列表可正常加载。
- 禁用/删除系统必要模块返回标准错误。
- 插件列表数据包含名称、包名、版本、启用状态、安装状态。

## H2：UI Schema Storage 兼容

### 目标

让现有前端页面 schema 正常读写。

### 支持接口

- `uiSchemas:getTree`
- `uiSchemas:getJsonSchema`
- `uiSchemas:getParentJsonSchema`
- `uiSchemas:insertAdjacent`
- `uiSchemas:patch`
- `uiSchemas:remove`
- `uiSchemaTemplates:list`
- `uiSchemaTemplates:get`

### 开发要求

- 保持现有 `uiSchemas` 树结构语义。
- `x-uid`、parent、children、sort 顺序必须兼容。
- patch/insert/remove 后再次 getTree 能看到正确结果。
- 不要改前端 schema 格式。

### 验收标准

- 前端菜单、页面、区块 schema 可加载。
- 新增/编辑页面配置后可持久化。
- 集成测试覆盖 insert/patch/remove/getTree。

## Claude 每个任务的交付格式

每个任务完成后，请提交以下内容：

- 任务编号和任务名称。
- 主要改动文件。
- 设计说明，重点说明如何兼容 NocoBase 语义。
- 测试命令。
- 测试结果。
- 是否修改了前端文件，必须明确回答“否”。
- 已知限制和后续依赖。

## 暂缓任务

以下内容不要在当前阶段开发：

- 工作流执行引擎和工作流节点插件。
- 文件管理、通知、备份恢复。
- AI、MCP、OAuth/IDP。
- npm 插件安装、远程插件市场、动态插件加载。
- 前端页面、组件、样式或构建产物修改。
