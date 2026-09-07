# NocoBase Java 后端 — Internal Data Layer 完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **79 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 优先级 | 状态 |
|------|------|--------|------|
| **P0-A** | 拆分 DynamicRepository 内部访问 API | P0 | ✅ 完成 |
| **P0-B** | 修正 create/update 返回记录语义 | P0 | ✅ 完成 |
| **P0-C** | 修正 AssociationActionService scope 校验 | P0 | ✅ 完成 |
| **P0-D** | 建立 belongsToMany through 内部访问语义 | P0 | ✅ 完成 |
| **P1-E** | 补齐普通角色权限测试 | P1 | ✅ 完成 |
| **P1-F** | 增加架构边界检测测试 | P1 | ✅ 完成 |

---

## 二、新增内部 API 列表

### DynamicRepository 内部方法

| 方法 | 用途 | 权限语义 |
|------|------|----------|
| `existsInScope(collection, action, id)` | 校验记录是否在 scope 内 | 不检查 `get` 权限，只检查 scope |
| `findByFilterForAction(collection, action, filter, ...)` | 按 action 的 scope 查询 | 不检查 action 权限，只应用 scope |
| `readAfterWrite(collection, id, writeAction)` | 写操作后读取记录 | 不检查 `get` 权限，用 writeAction 的 scope |
| `updateByFilterForAction(collection, action, data, filter)` | 批量更新（关联操作） | 不检查 action 权限，应用 scope |
| `listLinks(through, srcKey, srcIds, otherKey)` | 查询 through 表链接 | 绕过 through 表前端权限 |
| `createLink(through, srcKey, srcId, otherKey, tgtId)` | 创建 through 表链接 | 绕过 through 表前端权限 |
| `deleteLink(through, srcKey, srcId, otherKey, tgtId)` | 删除 through 表链接 | 绕过 through 表前端权限 |
| `replaceLinks(through, srcKey, srcId, otherKey, tgtIds)` | 替换 through 表链接 | 绕过 through 表前端权限 |

### 权限语义说明

```
公开 CRUD 权限 (controller 调用):
  list/get → 检查 "list"/"get" action 权限 + scope + readable fields
  create/update/destroy → 检查对应 action 权限 + scope + writable fields

内部 scope 校验 (association 调用):
  existsInScope → 只检查 scope，不检查 action 权限
  findByFilterForAction → 只应用 scope，不检查 action 权限
  readAfterWrite → 用 write action 的 scope，不检查 "get" 权限

through 表内部访问 (belongsToMany):
  createLink/deleteLink/replaceLinks → 绕过 through 表前端权限
  但必须在调用前检查 source/target collection 权限
```

---

## 三、各任务详情

### P0-A: 拆分 DynamicRepository 内部访问 API

**改动文件:**
- `data/DynamicRepository.java` — 新增 8 个内部方法

**设计说明:**
- 公开方法 `list/get/create/update/destroy` 保持不变
- 内部方法供 `RelationQueryService` 和 `AssociationActionService` 使用
- 所有内部方法复用统一 SQL 构造逻辑（quote, buildSelectClause, buildSortClause 等）
- `existsInScope` 不调用公开 `get()`，只做 scope 检查
- `readAfterWrite` 不检查 `get` action 权限

**是否修改前端文件:** 否

---

### P0-B: 修正 create/update 返回记录语义

**改动文件:**
- `data/DynamicRepository.java` — create/update 改用 `readAfterWrite`

**关键修复:**
- `create()` 不再调用公开 `get()` → 使用 `readAfterWrite(collection, id, "create")`
- `update()` 不再调用公开 `get()` → 使用 `readAfterWrite(collection, id, "update")`
- 有 `create` 无 `get` 权限时，创建成功并返回记录
- 有 `update` 无 `get` 权限时，更新成功并返回记录

**是否修改前端文件:** 否

---

### P0-C: 修正 AssociationActionService scope 校验

**改动文件:**
- `data/AssociationActionService.java` — verifyInScope + 写入方法

**关键修复:**
- `verifyInScope()` 改用 `existsInScope(collection, "update", id)` → 不再调用 `get()`
- 关联 `add/remove/set` 的源记录校验使用 `existsInScope(source, "update", sourceId)`
- 关联 `add/remove/set` 的目标记录校验使用 `existsInScope(target, "update", targetId)`
- 有 `update` 无 `get` 权限的角色可以执行合法关联写入

**是否修改前端文件:** 否

---

### P0-D: 建立 belongsToMany through 内部访问语义

**改动文件:**
- `data/DynamicRepository.java` — 新增 `listLinks/createLink/deleteLink/replaceLinks`
- `data/AssociationActionService.java` — 使用 through 内部操作

**设计说明:**
- through 表不作为前端普通资源权限对象
- `addBelongsToMany` → `createLink(through, ...)`
- `removeBelongsToMany` → `deleteLink(through, ...)`
- `setBelongsToMany` → `replaceLinks(through, ...)`
- 普通角色无需拥有 through 表 `list/create/destroy` 权限
- through 表字段不会出现在 API 返回结果中

**是否修改前端文件:** 否

---

### P1-E: 补齐普通角色权限测试

**新增文件:**
- `src/test/java/com/nocobase/AclPermissionTest.java` — 11 个测试

**测试覆盖:**

| 测试 | 场景 |
|------|------|
| `adminCanCreateAndRead` | 管理员创建并读取 |
| `adminCanUpdateAndRead` | 管理员更新并读取 |
| `createDoesNotRequireGetPermission` | 创建不依赖 get 权限 |
| `updateDoesNotRequireGetPermission` | 更新不依赖 get 权限 |
| `unknownFieldsRejectedForCreate` | 未知字段被拒绝 |
| `systemFieldsRejectedForCreate` | 系统字段被拒绝 |
| `viewCollectionCreateReturnsForbidden` | view collection 写入返回 ForbiddenException |
| `adminCanListAndGet` | 管理员列表和获取 |
| `destroyUsesAtomicScopeCheck` | destroy 原子 scope 检查 |
| `invalidSortFieldReturnsError` | 非法 sort 字段返回错误 |
| `invalidFieldsParameterReturnsError` | 非法 fields 参数返回错误 |

---

### P1-F: 增加架构边界检测测试

**新增文件:**
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java` — 7 个测试

**检测规则:**
- `RelationQueryService` 和 `AssociationActionService` 无 `JdbcTemplate` 依赖 ✅
- `controller` 目录下所有文件无 `JdbcTemplate` 依赖 ✅
- `service` 目录下所有文件无 `JdbcTemplate` 依赖（`CollectionManagerService` 已标记为 legacy 例外）✅
- `acl` 目录下所有文件无 `JdbcTemplate` 依赖 ✅
- 只有 `DynamicRepository`、`DdlSynchronizer`、`DialectAdapterFactory` 允许使用 `JdbcTemplate` ✅

**允许例外名单:**
- `DynamicRepository` — 统一数据访问层
- `DdlSynchronizer` — DDL 操作
- `DialectAdapterFactory` — 方言检测
- `CollectionManagerService` — 遗留代码，已不再被新代码引用

---

## 四、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| `NocobaseApplicationTests` | 1 | ✅ |
| `ApiCompatibilityTest` | 10 | ✅ |
| `CollectionAndFieldMetadataTest` | 17 | ✅ |
| `DataLayerIntegrationTest` | 18 | ✅ |
| `P0P1FixTest` | 15 | ✅ |
| `AclPermissionTest` (新增) | 11 | ✅ |
| `ArchitectureBoundaryTest` (新增) | 7 | ✅ |
| **合计** | **79** | **全部通过** |

---

## 五、文件变更清单

### 新增文件 (2 个)
- `src/test/java/com/nocobase/AclPermissionTest.java`
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java`

### 修改文件 (2 个)
- `src/main/java/com/nocobase/data/DynamicRepository.java` — 新增 8 个内部方法 + 修复 create/update
- `src/main/java/com/nocobase/data/AssociationActionService.java` — 使用内部 API + through 操作

**是否修改前端文件:** 否