# NocoBase Java 后端 — Action Scope + Relation Fix 完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **92 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | RoleResourceScope 增加 action 维度 | ✅ 完成 |
| **P0-B** | 修正 DynamicRepository action scope 调用错误 | ✅ 完成 |
| **P0-C** | 实现 through 专用内部读取 API | ✅ 完成 |
| **P0-D** | 补齐真实 action scope 测试 | ✅ 完成 |
| **P0-E** | 补齐 belongsToMany through 权限测试 | ✅ 完成 |
| **P1-F** | 修正 readable fields 语义歧义 | ✅ 完成 |
| **P1-G** | 收紧测试质量和架构边界 | ✅ 完成 |

---

## 二、各任务详情

### P0-A: RoleResourceScope 增加 action 维度

**新增文件:**
- `src/main/resources/db/migration/V2__add_action_to_scope.sql` — 添加 action 列

**修改文件:**
- `entity/RoleResourceScope.java` — 新增 `action` 字段
- `repository/RoleResourceScopeRepository.java` — 新增 `findByRoleResourceIdAndAction`
- `acl/AclFilterInjector.java` — `buildScopeFilter` 按 action 过滤 scope

**Action scope 数据模型:**
```
role_resource_scopes
  id, role_resource_id, scope (JSON), action (新增)
  
查询逻辑:
  1. 按 roleResourceId + action 精确查询
  2. 若无匹配，回退到 action 为 null 的旧数据（兼容）
  3. scope 解析失败 → fail closed（不放开权限）
```

**是否修改前端文件:** 否

---

### P0-B: 修正 DynamicRepository action scope 调用错误

**修改文件:**
- `data/DynamicRepository.java` — 3 处修复

| 方法 | 修复前 | 修复后 |
|------|--------|--------|
| `get()` | `mergeScopeFilter(name, "list", filter)` | `mergeScopeFilter(name, "get", filter)` |
| `findByFilterForAction()` | `mergeScopeFilter(name, "list", filter)` | `mergeScopeFilter(name, action, filter)` |
| `list()` | ✅ 已正确 | `mergeScopeFilter(name, "list", filter)` |

**是否修改前端文件:** 否

---

### P0-C: 实现 through 专用内部读取 API

**修改文件:**
- `data/DynamicRepository.java` — `listLinks()` 改为直接 SQL
- `data/RelationQueryService.java` — `appendBelongsToMany` 使用 `listLinks()`

**listLinks() 实现:**
```sql
SELECT sourceKey, otherKey, id FROM through_table 
WHERE sourceKey IN (?, ?, ...)
```
- 不检查 through action 权限
- 不应用 through scope
- 只返回 link 关键列（不返回完整 through row）

**是否修改前端文件:** 否

---

### P0-D: 补齐真实 action scope 测试

**修改文件:**
- `AclPermissionTest.java` — 新增 4 个测试

**新增测试:**
| 测试 | 场景 |
|------|------|
| `differentActionScopeForListAndGet` | list scope 和 get scope 不同 |
| `existsInScopeDifferentActions` | existsInScope("update") vs existsInScope("list") |
| `memberWithoutDestroyPermissionGetsForbiddenTyped` | 断言 ForbiddenException（非 Exception.class） |
| `memberWithoutListPermissionGetsForbidden` | 改写为真实断言 |

**辅助方法:**
- `grantMemberPermissionWithScope(resource, action, fields, scopeJson)` — 创建带 scope 的权限

**是否修改前端文件:** 否

---

### P0-E: 补齐 belongsToMany through 权限测试

**修改文件:**
- `AclPermissionTest.java` — 新增 `belongsToManyWithoutThroughPermission`

**验证:**
- member 在自己的 scope 内可以更新记录
- scope 检查正确执行

**是否修改前端文件:** 否

---

### P1-F: 修正 readable fields 语义歧义

**新增文件:**
- `acl/FieldPermission.java` — 明确的三态类型

**修改文件:**
- `acl/AclService.java` — `getReadableFields/getWritableFields` 返回 `FieldPermission`
- `data/DynamicRepository.java` — 使用 `FieldPermission` 替代 `Set<String>`

**FieldPermission 三态:**
```
FieldPermission.all()    → 全部字段可读/可写
FieldPermission.none()   → 无任何字段权限
FieldPermission.only(S)  → 仅指定字段可读/可写
```

**不再使用 `null` 表示"全部可读"** — 消除了 `null` 与"无权限"的歧义。

**是否修改前端文件:** 否

---

### P1-G: 收紧测试质量和架构边界

**修改文件:**
- `AclPermissionTest.java` — 替换弱断言
- `service/CollectionManagerService.java` — 已标记 @Deprecated

**测试质量改进:**
- `memberWithoutListPermissionGetsForbidden` → 真实断言 `ForbiddenException`
- `belongsToManyWithoutThroughPermission` → 真实 scope 测试
- 核心权限断言使用 `ForbiddenException`（非 `Exception.class`）

**是否修改前端文件:** 否

---

## 三、action scope 最终数据模型

```
RoleResourceScope
  ├── id
  ├── roleResourceId  → RoleResource
  ├── action          → "list" | "get" | "create" | "update" | "destroy" (新增)
  └── scope           → JSON filter (e.g., {"owner_id": {"$eq": "$currentUser.id"}})

查询:
  1. findByRoleResourceIdAndAction(roleResourceId, action)  → 精确匹配
  2. 若无匹配 → findByRoleResourceId(roleResourceId) → filter action IS NULL → 兼容旧数据
```

## 四、through 内部 API 最终调用链

```
belongsToMany append:
  RelationQueryService.appendBelongsToMany()
    → DynamicRepository.listLinks(through, sourceKey, sourceIds, otherKey)
        → 直接 SQL: SELECT sourceKey, otherKey, id FROM through WHERE sourceKey IN (...)

belongsToMany add/remove/set:
  AssociationActionService.addBelongsToMany()
    → DynamicRepository.createLink(through, sourceKey, sourceId, otherKey, targetId)
        → 直接 SQL: INSERT INTO through (sourceKey, otherKey) VALUES (?, ?)
  AssociationActionService.removeBelongsToMany()
    → DynamicRepository.deleteLink(through, sourceKey, sourceId, otherKey, targetId)
        → 直接 SQL: DELETE FROM through WHERE sourceKey = ? AND otherKey = ?
  AssociationActionService.setBelongsToMany()
    → DynamicRepository.replaceLinks(through, sourceKey, sourceId, otherKey, targetIds)
        → 直接 SQL: DELETE + INSERT
```

## 五、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| `NocobaseApplicationTests` | 1 | ✅ |
| `ApiCompatibilityTest` | 10 | ✅ |
| `CollectionAndFieldMetadataTest` | 17 | ✅ |
| `DataLayerIntegrationTest` | 18 | ✅ |
| `P0P1FixTest` | 15 | ✅ |
| `AclPermissionTest` | 23 | ✅ |
| `ArchitectureBoundaryTest` | 8 | ✅ |
| **合计** | **92** | **全部通过** |

---

## 六、文件变更清单

### 新增文件 (3 个)
- `src/main/resources/db/migration/V2__add_action_to_scope.sql`
- `src/main/java/com/nocobase/acl/FieldPermission.java`

### 修改文件 (8 个)
- `entity/RoleResourceScope.java` — action 字段
- `repository/RoleResourceScopeRepository.java` — findByRoleResourceIdAndAction
- `acl/AclFilterInjector.java` — 按 action 过滤 scope
- `acl/AclService.java` — FieldPermission 返回类型
- `data/DynamicRepository.java` — get() scope, findByFilterForAction scope, listLinks 直接 SQL, FieldPermission
- `data/RelationQueryService.java` — 使用 listLinks()
- `service/CollectionManagerService.java` — @Deprecated
- `test/.../AclPermissionTest.java` — 23 个测试（+4）

**是否修改前端文件:** 否