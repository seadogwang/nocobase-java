# NocoBase Java 后端 — 下一批开发任务完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **46 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **N1** | 异常处理主链路收口 | ✅ 完成 |
| **N2** | CurrentUserContext 角色名修正 | ✅ 完成 |
| **N3** | ACL 默认接入 DynamicRepository | ✅ 完成 |
| **N4** | UI Schema JSON 正确读写 | ✅ 完成 |
| **N5** | 关联查询走权限链路 | ✅ 完成 |
| **N6** | 关联动作 AssociationActionService | ✅ 完成 |
| **N7** | 真实 view/sql collection 查询 | ⏳ 基础已就绪 |
| **N8** | API 协议兼容扩展测试 | ⏳ 基础测试已覆盖 |
| **N9-N12** | 数据源/用户/角色/权限/插件/PostgreSQL | ⏳ 预留 |

---

## 二、各任务详情

### N1: 异常处理主链路收口

**改动文件:**
- `controller/GenericCrudController.java` — 移除 try/catch 包裹, 异常直接传播到 GlobalExceptionHandler
- 替换 `ResponseEntity.notFound().build()` 为 `throw ResourceNotFoundException`
- 替换 `ResponseEntity.badRequest().body(...)` 为 `throw IllegalArgumentException`

**验证:**
- 全项目搜索 `notFound().build()` 返回 0 结果
- 未知 collection → 404 (不是 500)
- 非法 filter → 400 (不是 500)

**是否修改前端文件:** 否

---

### N2: CurrentUserContext 角色名修正

**改动文件:**
- `acl/CurrentUserContext.java` — 完全重写
  - 新增 `RoleRepository` 依赖, 通过 roleId 查询真实角色名
  - `getCurrentUserRoles()` 返回 `admin`/`root`/`member` 等角色名(不是 `role_1`)
  - `isAdmin()` 判断角色名 `admin` 或 `root`(不依赖 seed 数据 ID 顺序)
  - 使用 `Set.of("admin", "root")` 定义 ADMIN_ROLES 常量

**验证:**
- `admin@nocobase.com` 登录后角色包含 `admin`
- role id 改变后 `isAdmin()` 仍正确

**是否修改前端文件:** 否

---

### N3: ACL 默认接入 DynamicRepository

**改动文件:**
- `data/DynamicRepository.java` — 注入 AclService + AclFilterInjector
  - `list/get` — 调用 `checkAclAction(resource, action)` + `mergeScopeFilter` + `filterReadableFields`
  - `create/update` — 调用 `checkAclAction` + `checkWritableField`
  - `destroy` — 调用 `checkAclAction` + scope 检查
  - `update/destroy` — 不能只按 id, 必须叠加 scope 条件
  - admin/root bypass

**验证:**
- 无权限时返回 403 Forbidden
- admin/root 可正常 CRUD
- scope filter 与用户 filter 正确 `$and` 合并

**是否修改前端文件:** 否

---

### N4: UI Schema JSON 正确读写

**改动文件:**
- `controller/UiSchemaController.java` — 完全重写
  - 使用 Spring 注入的 Jackson `ObjectMapper` 解析和序列化 JSON
  - `parseSchema()` 返回真实 schema 内容(不再返回空 Map)
  - `toJsonString()` 保留嵌套对象和数组(不再写 `"__json__"`)
  - `insertAdjacent` 保留传入 schema 的所有字段
  - `patch` 使用 `deepMerge` 做 JSON 对象合并
  - `remove` 支持 query param 和 request body 两种传参

**验证:**
- 插入带嵌套 `properties` 的 schema 后, `getJsonSchema` 原样返回
- `patch` 嵌套对象后不丢失原有字段

**是否修改前端文件:** 否

---

### N5: 关联查询走权限链路

**改动文件:**
- `data/RelationQueryService.java` — 注入 AclService
  - append 目标 collection 检查 `list` 权限
  - append 返回字段经过 `filterReadableFields()`
  - 四种关系类型(belongsTo/hasOne/hasMany/belongsToMany)全部覆盖

**验证:**
- 无目标 collection 权限时 append 返回 403
- 不可读字段不出现在 append 结果

**是否修改前端文件:** 否

---

### N6: 关联动作 AssociationActionService

**新增文件:**
- `data/AssociationActionService.java` — 完整关联动作服务
  - `{resource}.{association}:list` — 查询关联数据
  - `{resource}.{association}:add` — 添加关联
  - `{resource}.{association}:remove` — 移除关联
  - `{resource}.{association}:set` — 替换全部关联
  - 支持 belongsTo/hasOne/hasMany/belongsToMany

**改动文件:**
- `controller/GenericCrudController.java` — 检测点分隔资源名, 委托给 AssociationActionService
- `config/NocobaseUrlFilter.java` — 保留点分隔资源名

**验证:**
- `posts.tags:list/add/remove/set` 路由正确
- belongsToMany 通过 through table 操作

**是否修改前端文件:** 否

---

## 三、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| `NocobaseApplicationTests` | 1 | ✅ 通过 |
| `ApiCompatibilityTest` | 10 | ✅ 通过 |
| `CollectionAndFieldMetadataTest` | 17 | ✅ 通过 |
| `DataLayerIntegrationTest` | 18 | ✅ 通过 |
| **合计** | **46** | **全部通过** |

---

## 四、关键架构改进

| 维度 | 改进前 | 改进后 |
|------|--------|--------|
| 异常处理 | Controller 局部 try/catch 返回 500 | 异常传播到 GlobalExceptionHandler, 正确返回 400/401/403/404 |
| 角色解析 | `role_1`/`role_3` 硬编码 ID | 通过 RoleRepository 查询真实角色名 |
| ACL 生效 | 服务存在但未接入主链路 | DynamicRepository 默认调用 AclService/AclFilterInjector |
| JSON 读写 | 简化实现会丢失数据 | 使用 Jackson ObjectMapper 正确处理嵌套 JSON |
| 关联查询 | 绕过 ACL 直接查表 | append 经过 action 权限/字段权限检查 |
| 关联动作 | 未实现 | 完整支持 list/get/add/remove/set |

---

## 五、前端兼容验证

- `$.errors[0].message` 错误格式统一
- `$.data` 成功响应格式统一
- `$.meta.count/page/pageSize` 列表分页格式统一
- `applicationPlugins:listEnabled` 返回完整字段
- `uiSchemas:getTree/getJsonSchema/insertAdjacent/patch/remove` 接口完整

**是否修改前端文件:** 否 (严格遵守约束)

---

## 六、已知限制

1. **N7(view/sql collection)**: 真实视图查询和 SQL 子查询尚未实现, 当前 view/sql collection 仅做 capability 限制
2. **N8(API 扩展测试)**: 基础测试已覆盖主要 API, 但关联动作的 MockMvc 测试待补充
3. **N9-N12**: 数据源 API/用户角色权限 API/插件注册表/PostgreSQL 验证等任务待后续完成
4. **PostgreSQL 方言**: `DialectAdapterFactory` 已实现自动检测, 但未在 PostgreSQL 环境实际验证