# NocoBase Java 后端 — SQL Collection Phase 1 硬化完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **123 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 修复 SQL collection get scope 绕过 | ✅ 完成 |
| **P0-B** | 补 SQL collection 权限测试 | ✅ 完成 |
| **P0-C** | 统一字段权限过滤 | ✅ 完成 |
| **P0-D** | 彻底替换硬编码 id | ✅ 完成 |
| **P0-E** | 主键 fail-fast 校验 | ✅ 完成 |
| **P0-F** | 真实 belongsToMany 测试 | ⏳ 待后续 |
| **P1-G** | 补齐 view/sql DDL 边界 | ✅ 完成 |
| **P1-H** | SQL validator 边界 | ✅ 完成 |
| **P1-I** | 测试隔离 | ⏳ 部分完成 |
| **P1-J** | 架构边界 | ✅ 完成 |
| **P2-K** | SQL 设计文档 | ⏳ 待后续 |

---

## 二、核心修复

### P0-A: SQL get scope 修复

`executeGet()` 现在接收 `scopeFilter` 参数，在外层 WHERE 中同时应用 pk + get scope。

### P0-C: 统一字段权限过滤

新增 `FieldPermissionFilter.filter(permission, def, row)` — 所有字段过滤统一调用此方法:
- `list()` → `FieldPermissionFilter.filter()`
- `get()` → `FieldPermissionFilter.filter()`
- `readAfterWrite()` → `FieldPermissionFilter.filter()`
- `executeSqlList()` → `FieldPermissionFilter.filter()`

### P0-D: 硬编码 id 替换

| 位置 | 状态 |
|------|------|
| destroy() WHERE | → pk(def) ✅ |
| existsInScope() WHERE | → pk(def) ✅ |
| readAfterWrite() WHERE | → pk(def) ✅ |
| listLinks() SELECT | → 动态主键 ✅ |
| AclFilterInjector no-result | 注释 ✅ |

### P0-E: 主键 fail-fast 校验

`parsePrimaryKey()` 现在校验:
- 字段存在于 metadata
- physical collection: 必须是 physical field, 不能是 relation
- effective column name 必须是合法 SQL 标识符
- 非法配置 → `IllegalArgumentException` (fail-fast)

### P1-G: DDL 边界

| 操作 | physical | view/sql |
|------|----------|----------|
| addField | ALTER TABLE | 只 metadata |
| dropField | ALTER TABLE | 只 metadata |
| dropCollection | DROP TABLE | 只 metadata |

---

## 三、测试汇总

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
| `SqlQueryCollectionTest` | 21 | ✅ |
| **合计** | **123** | **全部通过** |

新增 SQL 权限测试: `sqlListAppliesActionScope`, `sqlListAppliesReadableFields`, `sqlGetWithoutPrimaryKeyRejected`, `sqlFilterValueInjectionIsParameterized`

---

## 四、文件变更

### 新增 (1)
- `acl/FieldPermissionFilter.java`

### 修改 (5)
- `data/DynamicRepository.java` — 统一过滤 + get scope + 主键硬编码
- `sql/SqlQueryCollectionExecutor.java` — executeGet scope
- `runtime/CollectionRuntimeService.java` — PK fail-fast
- `ddl/DdlSynchronizer.java` — view/sql DDL 边界
- `test/.../SqlQueryCollectionTest.java` — 4 个新权限测试

**是否修改前端文件:** 否