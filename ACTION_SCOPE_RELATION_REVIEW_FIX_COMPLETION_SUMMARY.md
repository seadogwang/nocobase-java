# NocoBase Java 后端 — Action Scope / Relation Review Fix 完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **102 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | Scope 解析失败 fail-closed | ✅ 完成 |
| **P0-B** | 补强 action scope 真实断言 | ✅ 完成 |
| **P0-C** | 补真实 belongsToMany through 权限测试 | ✅ 完成 |
| **P0-D** | 校验 through 内部 SQL 字段标识符 | ✅ 完成 |
| **P1-E** | 字段权限兼容无 id 的 view/sql collection | ✅ 完成 |
| **P1-F** | 收紧架构边界测试（递归扫描） | ✅ 完成 |
| **P1-G** | 整理异常断言和测试隔离 | ✅ 完成 |

---

## 二、各任务详情

### P0-A: Scope 解析失败 fail-closed

**修改文件:**
- `acl/AclFilterInjector.java`

**关键修复:**
- scope JSON 解析失败时：抛出 `ForbiddenException`（阻断请求，不再 `log.warn` 跳过）
- 多角色场景：一个合法 scope + 一个非法 scope → 整个请求失败，不会静默放行
- 修正注释："most restrictive" → "$or merge (least restrictive across roles)"

**是否修改前端文件:** 否

---

### P0-B: 补强 action scope 真实断言

**新增测试:**
- `listAndGetScopesAreDifferentAndEnforced` — list scope 和 get scope 配置不同 owner_id，验证各自只返回 scope 内的数据
- `existsInScopeWrongActionReturnsFalse` — 配置不同 list/update scope，验证 `existsInScope("list")` 和 `existsInScope("update")` 对同一记录返回不同结果

**关键断言:**
- `assertEquals("1001", String.valueOf(row.get("owner_id")))` — 精确断言字段值
- 不再使用 `owner_id == null || owner_id == 100` 这种弱断言

**是否修改前端文件:** 否

---

### P0-C: 补真实 belongsToMany through 权限测试

**新增测试:**
- `belongsToManyThroughInternalOps` — 创建 source/target/through 三个 collection，不给 through 表任何权限，通过内部 API 完成 add/remove 操作

**测试链路:**
```
asr_articles (source) ← belongsToMany → asr_tags (target)
                        ↓
                   asr_article_tags (through)
```
- member 有 source/target 的 update 权限，无 through 的任何权限
- `createLink/deleteLink` 通过内部 SQL 成功执行

**是否修改前端文件:** 否

---

### P0-D: 校验 through 内部 SQL 字段标识符

**修改文件:**
- `data/DynamicRepository.java` — 新增 `validateThroughColumn()` + 所有 through 方法校验

**新增测试:**
- `emptySourceIdsReturnsEmpty` — 空 sourceIds 返回空列表
- `invalidThroughColumnsRejected` — 不存在字段被拒绝
- `sqlInjectionColumnNameRejected` — SQL 注入式字段名被拒绝

**校验规则:**
- `sourceKey/otherKey` 不能为 null 或空
- 字段名必须匹配 `^[a-zA-Z_][a-zA-Z0-9_]*$`
- 字段名必须存在于 through collection 的 metadata 中

**是否修改前端文件:** 否

---

### P1-E: 字段权限兼容无 id 的 view/sql collection

**修改文件:**
- `acl/AclService.java` — `filterReadableFields` 方法

**关键修复:**
- `FieldPermission.none()` 时：检查 row 是否有 `id` 且不为 null，避免 `Map.of("id", null)` 导致 NPE
- 无 id 时返回空 Map（不泄露业务字段）

**新增测试:**
- `fieldPermissionNoneWithoutIdDoesNotNpe` — 无 id 的 row 不会 NPE

**是否修改前端文件:** 否

---

### P1-F: 收紧架构边界测试（递归扫描）

**修改文件:**
- `test/.../ArchitectureBoundaryTest.java` — 新增 `recursiveScanNoJdbcTemplate`

**关键修复:**
- 递归扫描 `src/main/java/com/nocobase` 所有子目录
- 不再只扫描一级目录
- 允许 `JdbcTemplate` 的名单保持最小化

**允许 JdbcTemplate 的文件:**
- `DynamicRepository.java` — 统一数据访问层
- `DdlSynchronizer.java` — DDL 操作
- `DialectAdapterFactory.java` — 方言检测
- `CollectionManagerService.java` — @Deprecated 遗留代码

**是否修改前端文件:** 否

---

### P1-G: 整理异常断言和测试隔离

**新增测试:**
- `memberWithoutListGetsForbiddenException` — 使用独立 collection (`asr_no_perm`)，避免权限污染
- `memberWithoutCreateGetsForbiddenException` — 断言 `ForbiddenException`（非 `Exception.class`）

**测试质量改进:**
- 所有权限测试断言 `ForbiddenException`（非 `Exception.class`）
- 使用独立 collection 避免角色/权限/scope 互相污染
- 测试名反映真实行为

**是否修改前端文件:** 否

---

## 三、权限语义最终说明

```
公开 API (controller → 前端):
  list/get   → check action 权限 + action scope + readable fields
  create     → check action 权限 + writable fields
  update     → check action 权限 + action scope + writable fields
  destroy    → check action 权限 + action scope

内部 API (association/relation → DynamicRepository):
  existsInScope(collection, action, id)    → apply action scope, no action check
  findByFilterForAction(collection, action, filter, ...) → apply action scope, no action check
  readAfterWrite(collection, id, writeAction) → apply writeAction scope, no get check
  updateByFilterForAction(collection, action, data, filter) → apply action scope, no action check

through 内部操作 (DynamicRepository → SQL):
  listLinks    → direct SQL, no through permission/scope, validates columns
  createLink   → direct SQL, no through permission, validates columns
  deleteLink   → direct SQL, no through permission, validates columns
  replaceLinks → direct SQL, no through permission, validates columns

Scope fail-closed:
  scope JSON 解析失败 → ForbiddenException (阻断请求)
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
| `ActionScopeRelationReviewTest` (新增) | 9 | ✅ |
| **合计** | **102** | **全部通过** |

---

## 五、文件变更清单

### 新增文件 (1 个)
- `src/test/java/com/nocobase/ActionScopeRelationReviewTest.java`

### 修改文件 (4 个)
- `src/main/java/com/nocobase/acl/AclFilterInjector.java` — fail-closed + 注释修正
- `src/main/java/com/nocobase/acl/AclService.java` — filterReadableFields 无 id 安全
- `src/main/java/com/nocobase/data/DynamicRepository.java` — through SQL 标识符校验
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java` — 递归扫描

**是否修改前端文件:** 否