# NocoBase Java 后端 — Permission Semantics Fix 完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **88 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | ACL scope 支持 action 维度 | ✅ 完成 |
| **P0-B** | 真正实现 through 表内部操作 | ✅ 完成 |
| **P0-C** | 修正 belongsToMany append | ✅ 完成 |
| **P0-D** | 修正 readAfterWrite 字段泄露 | ✅ 完成 |
| **P0-E** | 修正 Map.of(..., null) 问题 | ✅ 完成 |
| **P1-F** | 重做普通角色权限测试 | ✅ 完成 |
| **P1-G** | 收紧架构边界测试 | ✅ 完成 |

---

## 二、各任务详情

### P0-A: ACL scope 支持 action 维度

**改动文件:**
- `acl/AclFilterInjector.java` — `mergeScopeFilter(resourceName, action, userFilter)` 三参数
- `data/DynamicRepository.java` — 所有调用方传入对应 action

**Action 映射:**
- `list()` → `mergeScopeFilter(name, "list", filter)`
- `get()` → `mergeScopeFilter(name, "get", filter)`
- `create()` → 内部用 `readAfterWrite(name, id, "create")`
- `update()` → `mergeScopeFilter(name, "update", null)`
- `destroy()` → `mergeScopeFilter(name, "destroy", null)`
- `existsInScope(name, action, id)` → `mergeScopeFilter(name, action, null)`
- `readAfterWrite(name, id, writeAction)` → `mergeScopeFilter(name, writeAction, null)`
- `updateByFilterForAction(name, action, data, filter)` → `mergeScopeFilter(name, action, null)`

**是否修改前端文件:** 否

---

### P0-B: 真正实现 through 表内部操作

**改动文件:**
- `data/DynamicRepository.java` — `createLink/deleteLink/replaceLinks` 使用直接 SQL

**关键修复:**
- `createLink()` — 使用 `INSERT INTO through_table (fk, otherKey) VALUES (?, ?)` 直接 SQL
- `deleteLink()` — 使用 `DELETE FROM through_table WHERE fk = ? AND otherKey = ?` 直接 SQL
- `replaceLinks()` — 使用 `DELETE` + `INSERT` 直接 SQL
- 不调用公开 `create/destroy/list`，不检查 through 表 action 权限
- 通过 `PreparedStatement` 参数绑定，防止 SQL 注入

**是否修改前端文件:** 否

---

### P0-C: 修正 belongsToMany append

**改动文件:**
- `data/RelationQueryService.java` — `appendBelongsToMany` 使用 `findByFilterForAction`

**关键修复:**
- through 表查询：`dynamicRepository.findByFilterForAction(throughTable, "list", ...)` 替代 `dynamicRepository.list(throughTable, ...)`
- 不要求 through 表 `list` 权限
- 目标 collection 查询仍走 `dynamicRepository.list(targetCollection, ...)` 保留 ACL 权限

**是否修改前端文件:** 否

---

### P0-D: 修正 readAfterWrite 字段泄露

**改动文件:**
- `data/DynamicRepository.java` — `readAfterWrite` 方法

**写后返回策略:**
1. 有明确 readable fields 配置 → 返回允许字段 + 主键
2. 用户有 `list` 或 `get` 权限 → 返回完整记录
3. 用户无任何读权限（write-only）→ 返回 `{id}` 最小结果
4. admin/root → 返回完整记录

**是否修改前端文件:** 否

---

### P0-E: 修正 nullable Map 构造

**改动文件:**
- `data/AssociationActionService.java` — 新增 `mapOf()` 辅助方法

**修复的 NPE 场景:**
- `belongsTo set null` — `mapOf(foreignKey, null)` → 清空外键
- `hasMany remove` — `mapOf(foreignKey, null)` → 置空外键
- `hasMany set` 清空 — `mapOf(foreignKey, null)` → 批量清空
- `updateByFilterForAction` — 同上

**是否修改前端文件:** 否

---

### P1-F: 重做普通角色权限测试

**改动文件:**
- `src/test/java/com/nocobase/AclPermissionTest.java` — 从 11 个扩展到 19 个测试

**新增测试:**
| 测试 | 场景 |
|------|------|
| `memberWithCreatePermissionCanCreate` | member + create 权限 → 创建成功 |
| `memberWithoutListPermissionGetsForbidden` | member 无 list 权限 |
| `memberWithUpdatePermissionCanUpdate` | member + update 权限 → 更新成功 |
| `memberCannotWriteToUnauthorizedFields` | member 只能写授权字段 |
| `memberUpdateOutsideScopeFails` | member update scope 检查 |
| `memberWithOnlyCreateCannotList` | 只有 create 不能 list |
| `memberWithoutDestroyPermissionGetsForbidden` | 无 destroy 权限 → 403 |
| `adminCanReadFullRecordAfterCreate` | admin 可读完整记录 |

**测试特点:**
- 创建真实 `RoleResource` + `RoleResourceAction` 数据
- 切换到非 admin 用户身份测试
- 断言 `ForbiddenException` 类型（不是 `Exception.class`）

---

### P1-G: 收紧架构边界测试

**改动文件:**
- `service/CollectionManagerService.java` — 标记 `@Deprecated`
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java` — 新增注入检查测试

**架构边界规则:**
| 文件 | JdbcTemplate | 状态 |
|------|-------------|------|
| `DynamicRepository` | ✅ 允许 | 统一数据访问层 |
| `DdlSynchronizer` | ✅ 允许 | DDL 操作 |
| `DialectAdapterFactory` | ✅ 允许 | 方言检测 |
| `CollectionManagerService` | ⚠️ @Deprecated | 遗留代码，不允许新代码注入 |
| `RelationQueryService` | ❌ 禁止 | 已验证 0 引用 |
| `AssociationActionService` | ❌ 禁止 | 已验证 0 引用 |
| Controllers | ❌ 禁止 | 已验证 0 引用 |

---

## 三、权限语义总结

```
公开 API (controller → 前端):
  list/get → check action + action scope + readable fields
  create/update/destroy → check action + action scope + writable fields

内部 API (association/relation → DynamicRepository):
  existsInScope(collection, action, id) → apply action scope, NO action check
  findByFilterForAction(collection, action, filter, ...) → apply action scope, NO action check
  readAfterWrite(collection, id, writeAction) → apply writeAction scope, NO get check
  updateByFilterForAction(collection, action, data, filter) → apply action scope, NO action check

through 表内部操作 (DynamicRepository → SQL):
  createLink/deleteLink/replaceLinks → direct SQL, NO through table permission
  Caller must verify source/target collection permissions
```

---

## 四、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| `NocobaseApplicationTests` | 1 | ✅ |
| `ApiCompatibilityTest` | 10 | ✅ |
| `CollectionAndFieldMetadataTest` | 17 | ✅ |
| `DataLayerIntegrationTest` | 18 | ✅ |
| `P0P1FixTest` | 15 | ✅ |
| `AclPermissionTest` | 19 | ✅ |
| `ArchitectureBoundaryTest` | 8 | ✅ |
| **合计** | **88** | **全部通过** |

---

## 五、文件变更清单

### 修改文件 (5 个)
- `src/main/java/com/nocobase/acl/AclFilterInjector.java` — action 参数
- `src/main/java/com/nocobase/data/DynamicRepository.java` — through 直接 SQL + readAfterWrite 防泄露 + action scope
- `src/main/java/com/nocobase/data/RelationQueryService.java` — appendBelongsToMany 使用内部 API
- `src/main/java/com/nocobase/data/AssociationActionService.java` — mapOf() 防 NPE
- `src/main/java/com/nocobase/service/CollectionManagerService.java` — @Deprecated

### 修改测试文件 (2 个)
- `src/test/java/com/nocobase/AclPermissionTest.java` — 19 个测试（+8）
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java` — 8 个测试（+1）

**是否修改前端文件:** 否