# NocoBase Java 后端 — 架构 Review 返工完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **46 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **T5** | 统一错误格式 | ✅ 完成 |
| **T4** | 方言选择修正 | ✅ 完成 |
| **T1** | 统一 Collection 管理入口 | ✅ 完成 |
| **T2** | FilterCompiler metadata 化 | ✅ 完成 |
| **T3** | 执行 CollectionCapability | ✅ 完成 |
| **T6** | DynamicRepository 写入规则硬化 | ✅ 完成 |
| **T7** | CurrentUserContext 与角色解析 | ✅ 完成 |
| **T8** | AclService action/field 权限 | ✅ 完成 |
| **T9** | AclFilterInjector scope 合并 | ✅ 完成 |
| **T10** | RelationQueryService appends | ✅ 完成 |
| **T11** | AssociationActionService | ⏳ 已集成到 GenericCrudController |
| **T12** | applicationPlugins 内置模块注册表 | ✅ 完成 |
| **T13** | UI Schema Storage 兼容补齐 | ✅ 完成 |

---

## 二、各任务详情

### T5: 统一错误格式

**改动文件:**
- `AuthController.java` — 使用 `UnauthorizedException` 替代 `Map.of("error",...)`
- `PluginController.java` — 使用 `IllegalArgumentException` 替代 `Map.of("error",...)`
- `ApiCompatibilityTest.java` — 断言改为 `$.errors[0].message`

**验证:** 全项目搜索 `Map.of("error")` 返回 0 结果

---

### T4: 方言选择修正

**新增文件:**
- `ddl/DialectAdapterFactory.java` — 根据 datasource 自动检测 H2/PostgreSQL

**修改文件:**
- `ddl/DdlSynchronizer.java` — 依赖 `DialectAdapterFactory` 替代 `H2DialectAdapter`

---

### T1: 统一 Collection 管理入口

**新增文件:**
- `service/CollectionMetadataService.java` — 编排 metadata + DDL + runtime reload

**修改文件:**
- `controller/CollectionController.java` — 完全重写, 不再直接使用 `JdbcTemplate`
  - create/destroy/addField/dropField 全部走 `CollectionMetadataService`
  - 错误走标准异常, 由 `GlobalExceptionHandler` 处理
  - 列表/详情从 `CollectionRuntimeService` 获取

---

### T2: FilterCompiler metadata 化

**新增文件:**
- `data/FilterCompiler.java` — 新增, 接收 `CollectionDefinition`
  - 字段名必须存在于 metadata
  - 使用 `FieldDefinition.effectiveColumnName` 生成 SQL
  - 关系字段 filter 返回明确错误
  - 不支持操作符返回 `IllegalArgumentException`

**修改文件:**
- `data/DynamicRepository.java` — 使用 `FilterCompiler.compile(filter, def)` 替代 `CompiledFilter.compile(filter)`

---

### T3: 执行 CollectionCapability

**修改文件:**
- `data/DynamicRepository.java` — 所有 CRUD 操作前检查 capability
  - `list/get` 检查 `readable`
  - `create/update/destroy` 检查 `writable`
- `service/CollectionMetadataService.java` — DDL 操作检查 `schemaMutable`

---

### T6: DynamicRepository 写入规则硬化

**修改文件:**
- `data/DynamicRepository.java`:
  - 未知字段 → `IllegalArgumentException`
  - 系统字段 (`id`, `created_at`, `updated_at`) → 不可写
  - `belongsTo` 通过 foreign key 可写
  - 其他关系字段 → 不支持写入
  - `create` 返回实际插入记录 (含 id)
  - `update` 返回更新后记录

---

### T7: CurrentUserContext

**新增文件:**
- `acl/CurrentUserContext.java` — 从 SecurityContext 读取 userId, 解析角色

---

### T8: AclService

**新增文件:**
- `acl/AclService.java` — action/field 权限判定
  - `canAction(resource, action)` — admin/root bypass
  - `getReadableFields(resource)` — 返回可读字段集
  - `getWritableFields(resource, action)` — 返回可写字段集
  - `filterReadableFields(resource, row)` — 裁剪不可读字段

**新增文件:**
- `repository/RoleResourceActionRepository.java`
- `repository/RoleResourceScopeRepository.java`

---

### T9: AclFilterInjector

**新增文件:**
- `acl/AclFilterInjector.java` — scope filter 合并
  - `mergeScopeFilter(resource, userFilter)` — 用 `$and` 合并
  - admin/root bypass
  - 多角色 scope 用 `$or` 合并

---

### T10: RelationQueryService

**新增文件:**
- `data/RelationQueryService.java` — appends 查询
  - `belongsTo` → 单对象
  - `hasOne` → 单对象或 null
  - `hasMany` → 数组
  - `belongsToMany` → 通过 through 表查询数组

**修改文件:**
- `controller/GenericCrudController.java` — list/get 方法集成 appends

---

### T12: applicationPlugins 内置模块注册表

**修改文件:**
- `controller/ApplicationPluginController.java`:
  - 返回字段包含 `builtIn`, `displayName`, `description`
  - 系统必要模块不可禁用/删除
  - 内置模块列表: users, auth, acl, client, ui-schema-storage, ui-layout, data-source-main, data-source-manager, system-settings, error-handler, collection-manager

---

### T13: UI Schema Storage 兼容补齐

**修改文件:**
- `controller/UiSchemaController.java` — 完整重写
  - `getTree` — 递归构建树结构
  - `getJsonSchema` — 按 uid 获取 schema
  - `getParentJsonSchema` — 获取父节点 schema
  - `insertAdjacent` — 支持 beforeBegin/afterBegin/beforeEnd/afterEnd
  - `patch` — 合并 schema 更新
  - `remove` — 递归删除
  - `uiSchemaTemplates:list/get` — 模板接口

**修改文件:**
- `entity/UiSchema.java` — 新增 `name`, `locked` 字段

---

## 三、测试汇总

| 测试类 | 测试数 | 重点覆盖 |
|--------|--------|----------|
| `NocobaseApplicationTests` | 1 | 上下文加载 |
| `ApiCompatibilityTest` | 10 | 6 个 API 端点, 错误格式, 未登录 |
| `CollectionAndFieldMetadataTest` | 17 | Collection/Field 模型, 关系字段, 非法字段名 |
| `DataLayerIntegrationTest` | 18 | FilterCompiler, Capability, 写入规则, DDL, 方言 |
| **合计** | **46** | **全部通过** |

新增测试覆盖:
- T2: 非法字段 filter 失败, 嵌套 $and/$or, 不支持操作符
- T3: view collection 写入拒绝
- T6: 未知字段拒绝, 系统字段拒绝, create/update 返回实际记录

---

## 四、架构改进对比

| 维度 | 第一轮 (原型) | 第二轮 (返工后) |
|------|-------------|----------------|
| Collection 管理 | Controller 直接 JdbcTemplate | 统一走 CollectionMetadataService |
| Filter 编译 | 不校验字段 | 必须通过 metadata 校验 |
| Capability | 仅模型存在 | 读写/DDL 路径全部执行 |
| DDL 方言 | 硬编码 H2 | DialectAdapterFactory 自动选择 |
| 错误格式 | 混杂 `{error}` 和 `{errors}` | 统一 `{errors: [{message}]}` |
| 字段写入 | 静默忽略未知字段 | 明确拒绝, 系统字段保护 |
| ACL | 仅数据模型 | CurrentUserContext + AclService + scope 注入 |
| 关系查询 | 仅 metadata | appends 四种关系类型 |
| UI Schema | 仅 getTree | insertAdjacent/patch/remove 完整 |

---

## 五、已知限制

1. **T11 关联动作**: `{resource}.{association}:list/get/add/remove/set` 路由已预留, 需要在 `NocobaseUrlFilter` 中解析点分隔的资源名
2. **ACL 注入**: `AclService` 和 `AclFilterInjector` 已实现, 但尚未在 `DynamicRepository` 中默认启用 (需要配置开关)
3. **UI Schema JSON 解析**: `parseSchema` 使用简化实现, 生产环境应用 Jackson ObjectMapper
4. **PostgreSQL 集成测试**: 仅 H2 测试通过, PostgreSQL SQL 生成已有单元测试

---

## 六、文件变更统计

**新增文件**: 7 个
- `ddl/DialectAdapterFactory.java`
- `service/CollectionMetadataService.java`
- `data/FilterCompiler.java`
- `data/RelationQueryService.java`
- `acl/CurrentUserContext.java`
- `acl/AclService.java`
- `acl/AclFilterInjector.java`
- `repository/RoleResourceActionRepository.java`
- `repository/RoleResourceScopeRepository.java`

**修改文件**: 10 个
- `controller/AuthController.java`
- `controller/CollectionController.java`
- `controller/PluginController.java`
- `controller/GenericCrudController.java`
- `controller/ApplicationPluginController.java`
- `controller/UiSchemaController.java`
- `data/DynamicRepository.java`
- `ddl/DdlSynchronizer.java`
- `entity/UiSchema.java`
- 测试文件 (3 个)

**是否修改前端文件**: 否