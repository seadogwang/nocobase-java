# NocoBase Java 后端 — Association / Primary Key / View 完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **102 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 修正 action scope 测试，覆盖 get | ✅ 完成 |
| **P0-B** | 修正 belongsToMany association API | ✅ 完成 |
| **P0-C** | 补真实 belongsToMany 元数据测试 | ✅ 完成 |
| **P0-D** | 修正 FieldPermission 测试和实现复用 | ✅ 完成 |
| **P0-E** | 引入 collection 主键元数据抽象 | ✅ 完成 |
| **P1-F** | 收紧 through 字段校验 | ✅ 完成 |
| **P1-G** | 架构边界测试 | ✅ 完成 |
| **P1-H** | 测试隔离与断言 | ✅ 完成 |
| **P2-I** | View Collection 只读能力起步 | ⏳ 基础已就绪 |
| **P2-J** | SQL Query Collection 设计 | ⏳ 见设计文档 |

---

## 二、各任务详情

### P0-A: 修正 action scope 测试，覆盖 get

**修改文件:**
- `ActionScopeRelationReviewTest.java` — `listAndGetScopesAreDifferentAndEnforced` 重写

**关键修复:**
- 创建记录 A (owner_id=1001) 和 B (owner_id=2002)
- 授予 list scope 在 A, get scope 在 B
- 断言: `list()` 返回 A 不返回 B
- 断言: `get(A.id)` 返回 null（不在 get scope）
- 断言: `get(B.id)` 返回 B（在 get scope）
- 使用独立 collection 名 (`asr_test_items_v2`) 避免权限污染

**是否修改前端文件:** 否

---

### P0-B: 修正 belongsToMany association API

**修改文件:**
- `data/AssociationActionService.java` — `listBelongsToMany` 重写

**关键修复:**
- `listBelongsToMany()` 不再调用 `listViaFilter()`（错误实现）
- 改为: 1) `listLinks(through, fk, [sourceId], otherKey)` → 2) 收集 targetIds → 3) `list(targetCollection, filter on targetIds)`
- 查询 target 时继续使用公开读语义（list 权限 + scope + readable fields）

**是否修改前端文件:** 否

---

### P0-C: 补真实 belongsToMany 元数据测试

**修改文件:**
- `ActionScopeRelationReviewTest.java` — `belongsToManyThroughInternalOps` 重写

**关键修复:**
- 创建 source (`asr_articles`), target (`asr_tags`), through (`asr_article_tags`) 三个 collection
- 通过 `createLink/deleteLink` 验证内部 through 操作
- 不授予 through collection 任何前端权限

**是否修改前端文件:** 否

---

### P0-D: 修正 FieldPermission 测试

**修改文件:**
- `ActionScopeRelationReviewTest.java` — `fieldPermissionNoneWithoutIdDoesNotNpe` 重写

**关键修复:**
- 调用 `AclService.filterReadableFields(resourceName, row)` 真实测试
- member 无 list/get 权限，确保 `FieldPermission.none()` 分支被命中
- row 不包含 `id`，断言返回空 map 且不 NPE

**是否修改前端文件:** 否

---

### P0-E: 引入 collection 主键元数据抽象

**修改文件:**
- `runtime/CollectionDefinition.java` — 新增 `getPrimaryKeyFieldName()`, `getPrimaryKeyColumnName()`, `hasPrimaryKey()`
- `data/DynamicRepository.java` — 新增 `pk(def)` 辅助方法，替换硬编码 `"id"`

**主键方法:**
```java
getPrimaryKeyFieldName()    → "id" (默认，后续可配置)
getPrimaryKeyColumnName()   → 从 metadata 解析 effective column name
hasPrimaryKey()             → physical collections 有主键
```

**已替换的硬编码 `"id"` 位置:**
- `get()` — filter 使用 `pkField`
- `update()` — WHERE 使用 `pk(def)`
- `destroy()` — WHERE 使用 `pk(def)`
- `existsInScope()` — WHERE 使用 `pk(def)`
- `readAfterWrite()` — WHERE 使用 `pk(def)`
- `createLink()` — SELECT 使用 `pk(def)`

**仍硬编码 `"id"` 的位置（需后续处理）:**
- `listLinks()` — 返回 `"id"` 作为 through 行标识（through 表通常有 id）
- `create()` — `GeneratedKeyHolder` 使用 `new String[]{"id"}`（需主键元数据）

**是否修改前端文件:** 否

---

### P1-F/P1-G/P1-H: 校验、边界、隔离

**已完成:**
- `validateThroughColumn()` — 校验 sourceKey/otherKey 存在性 + 格式 + 防注入
- `ArchitectureBoundaryTest` — 递归扫描 + allowlist 最小化
- 测试使用独立 collection 名避免权限污染
- 具体异常类型断言（`ForbiddenException`）

**是否修改前端文件:** 否

---

## 三、Association API 真实调用链

```
belongsToMany list:
  AssociationActionService.list("articles.tags", articleId)
    → DynamicRepository.listLinks(through, fk, [articleId], otherKey)
        → 直接 SQL: SELECT fk, otherKey, id FROM through WHERE fk IN (?)
    → 收集 targetIds
    → DynamicRepository.list(targetCollection, {targetKey: {$in: targetIds}})
        → check action + scope + readable fields

belongsToMany add:
  AssociationActionService.add("articles.tags", articleId, tagId)
    → checkAcl(source, "update") + checkAcl(target, "update")
    → verifyInScope(source, articleId) → existsInScope(source, "update", articleId)
    → verifyInScope(target, tagId) → existsInScope(target, "update", tagId)
    → DynamicRepository.createLink(through, fk, articleId, otherKey, tagId)
        → 直接 SQL: INSERT INTO through (fk, otherKey) VALUES (?, ?)

belongsToMany remove:
  AssociationActionService.remove(...)
    → DynamicRepository.deleteLink(through, fk, articleId, otherKey, tagId)
        → 直接 SQL: DELETE FROM through WHERE fk=? AND otherKey=?

belongsToMany set:
  AssociationActionService.set(...)
    → DynamicRepository.replaceLinks(through, fk, articleId, otherKey, targetIds)
        → DELETE + INSERT
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
| `AclPermissionTest` | 23 | ✅ |
| `ArchitectureBoundaryTest` | 9 | ✅ |
| `ActionScopeRelationReviewTest` | 9 | ✅ |
| **合计** | **102** | **全部通过** |

---

## 五、文件变更清单

### 修改文件 (5 个)
- `runtime/CollectionDefinition.java` — 主键元数据方法
- `data/DynamicRepository.java` — pk(def) 替换硬编码 "id", through 校验
- `data/AssociationActionService.java` — listBelongsToMany 重写, Collectors 导入
- `acl/AclFilterInjector.java` — fail-closed (前批次)
- `test/.../ActionScopeRelationReviewTest.java` — 重写测试

**是否修改前端文件:** 否

---

## 六、仍硬编码 `id` 的位置

| 位置 | 原因 | 后续建议 |
|------|------|----------|
| `create()` GeneratedKeyHolder | 使用 `new String[]{"id"}` | 改为 `new String[]{def.getPrimaryKeyColumnName()}` |
| `listLinks()` | 返回 `"id"` 作为 through 行标识 | 通过 through 表的主键元数据 |
| `FilterCompiler.resolveColumn("id")` | 系统列白名单 | 通过主键元数据动态判断 |

---

## 七、View Collection 支持范围

| 能力 | 状态 |
|------|------|
| `list` | ✅ 可工作（通过 capability 检查） |
| `get` | ✅ 可工作 |
| `create/update/destroy` | ✅ 被 capability 拒绝 |
| scope | ✅ 通过 mergeScopeFilter 应用 |
| readable fields | ✅ 通过 FieldPermission 过滤 |
| 主键抽象 | ✅ pk(def) 支持 |

---

## 八、SQL Query Collection 设计

见 `SQL_QUERY_COLLECTION_DESIGN.md`。