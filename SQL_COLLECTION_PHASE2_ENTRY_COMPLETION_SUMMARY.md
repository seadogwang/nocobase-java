# NocoBase Java 后端 — SQL Collection Phase 2 入口完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **146 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 移除 AclFilterInjector 的 `id` no-result filter | ✅ 完成 |
| **P0-B** | 补 SQL collection get 权限测试矩阵 | ✅ 完成 |
| **P0-C** | 补 readable none 与 custom primaryKey 场景 | ✅ 完成 |
| **P0-D** | 修复测试 helper 与测试隔离 | ✅ 完成 |
| **P1-E** | 补 DDL 边界完整回归测试 | ✅ 完成 |
| **P1-F** | 补 primaryKey fail-fast 回归测试 | ✅ 完成 |
| **P1-G** | 收紧架构边界测试 | ✅ 完成 |
| **P1-H** | 修正 SQL 设计文档与当前能力不一致 | ✅ 完成 |
| **P2-I** | 整理 SQL executor 内部结构 | ⏳ 待后续 |
| **P2-J** | 设计并小步实现 named parameter 预处理 | ⏳ 待后续 |

---

## 二、核心修复

### P0-A: $alwaysFalse 替代固定 id no-result filter

- `FilterCompiler` 新增 `$alwaysFalse` → 编译为 `1 = 0`
- `AclFilterInjector` 无角色时返回 `Map.of("$alwaysFalse", true)` 替代 `Map.of("id", ...)`
- 适用于所有 collection（包括无主键 view/sql）

### P0-B: SQL get 权限测试 (4 个新测试)

| 测试 | 验证 |
|------|------|
| `sqlGetWithPrimaryKeyReturnsCorrectRecord` | get 成功返回记录 |
| `sqlGetActionScopeDifferentFromListScope` | list/get scope 分别生效 |
| `sqlGetAppliesReadableFields` | readable fields 过滤 + PK 自动保留 |
| `sqlGetWithoutPermissionThrowsForbiddenException` | 无权限 → ForbiddenException |

### P0-C: custom primaryKey 测试 (3 个新测试)

- `sqlGetWithCustomPrimaryKey` — `primaryKey = "code"` get 成功
- `readableNoneWithCustomPkReturnsOnlyPk` — FieldPermissionFilter 返回仅 code
- `readableNoneWithoutPkReturnsEmptyObject` — 无主键返回空 Map

### P0-D: 测试隔离修复

- 所有 helper 找不到 role 时 `fail()` (不再静默 return)
- `ActionScopeRelationReviewTest` 移除 `@TestMethodOrder`
- `assertThrows(Exception.class)` → 具体异常类型

### P1-E: DDL 边界测试 (4 个新测试)

| 测试 | 验证 |
|------|------|
| `sqlAddFieldOnlySavesMetadata` | SQL addField 不执行 ALTER TABLE |
| `sqlDropFieldOnlyDeletesMetadata` | SQL dropField 不执行 ALTER TABLE |
| `sqlDropCollectionOnlyDeletesMetadata` | SQL dropCollection 不 DROP TABLE |
| `physicalCollectionOperationsWorkNormally` | physical DDL 保持不变 |

### P1-F: primaryKey fail-fast 测试 (6 个新测试)

- legal custom PK, missing field, relation field, non-physical field
- SQL/view hasPrimaryKey=false, SQL with valid PK

### P1-H: 设计文档修正

- 状态更新为 "Phase 1 已实现"
- `:param` 改为 "Phase 2 计划支持"

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
| `SqlQueryCollectionTest` | 34 | ✅ |
| `DdlBoundaryTest` (新增) | 10 | ✅ |
| **合计** | **146** | **全部通过** |

---

## 四、文件变更

### 新增 (1)
- `src/test/java/com/nocobase/DdlBoundaryTest.java`

### 修改 (7)
- `acl/AclFilterInjector.java` — $alwaysFalse
- `data/FilterCompiler.java` — $alwaysFalse 支持
- `test/.../SqlQueryCollectionTest.java` — 7 个新测试 + helper fail-fast
- `test/.../ActionScopeRelationReviewTest.java` — 测试隔离 + 异常类型
- `test/.../ArchitectureBoundaryTest.java` — SqlQueryCollectionExecutor 检查
- `SQL_QUERY_COLLECTION_DESIGN.md` — 状态和能力对齐

**是否修改前端文件:** 否