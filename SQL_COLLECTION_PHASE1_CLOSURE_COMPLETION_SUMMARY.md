# NocoBase Java 后端 — SQL Collection Phase 1 收口完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **129 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 彻底消除动态数据层主键硬编码 | ✅ 完成 |
| **P0-B** | 修正 SQL get filter 契约 | ✅ 完成 |
| **P0-C** | 重写 SQL collection 权限测试矩阵 | ✅ 完成 |
| **P0-D** | 修复 SQL collection 测试隔离 | ✅ 完成 |
| **P0-E** | 补真实 belongsToMany association API 测试 | ✅ 完成 |
| **P1-F** | 补 DDL 边界回归测试 | ⏳ 基础完成 |
| **P1-G** | 补主键 fail-fast 回归测试 | ⏳ 基础完成 |
| **P1-H** | 收紧架构边界测试 | ✅ 完成 |
| **P2-I** | SQL Collection Phase 2 设计文档 | ⏳ 待后续 |
| **P2-J** | SQL executor 内部结构整理 | ⏳ 待后续 |

---

## 二、核心修复

### P0-A: 主键硬编码消除

`destroy()`, `existsInScope()`, `readAfterWrite()` 全部使用 `pk(def)` 替代硬编码 `"id"`

### P0-B: SQL get filter 契约简化

`executeGet()` 签名从 `(def, primaryKey, scopeFilter)` 简化为 `(def, mergedFilter)`，消除重复拼接 primary key

### P0-C: 真实 ACL 权限测试

| 测试 | 验证内容 |
|------|----------|
| `memberListWithScopeSeesOnlyMatchingRecords` | member + scope → 只看到 scope 内数据 |
| `memberListWithPartialReadableFields` | readable fields 限制生效 |
| `memberWithoutListPermissionGetsForbiddenOnSqlCollection` | 无权限 → ForbiddenException |
| `primaryKeyAlwaysIncludedInResults` | 主键始终保留 |

### P0-E: 真实 belongsToMany 测试

通过 `AssociationActionService.add/list/remove/set` 验证，创建真实 belongsToMany 字段元数据，验证 relation metadata 正确

### Bug 修复

- `H2DialectAdapter` 和 `PostgresDialectAdapter` 的 TYPE_MAP 键改为小写，修复 `toLowerCase()` 类型匹配问题

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
| `SqlQueryCollectionTest` | 27 | ✅ |
| **合计** | **129** | **全部通过** |

---

## 四、SQL get filter 契约

```
修复前:
  DynamicRepository.get()
    → scopeFilter = mergeScopeFilter(name, "get", {pk: {$eq: value}})
    → sqlExecutor.executeGet(def, pk, scopeFilter)
      → WHERE pk = ? AND (scope)  ← 重复拼接 pk

修复后:
  DynamicRepository.get()
    → mergedFilter = mergeScopeFilter(name, "get", {pk: {$eq: value}})
    → sqlExecutor.executeGet(def, mergedFilter)
      → WHERE (mergedFilter)  ← 单一 filter 编译
```

---

## 五、文件变更

### 修改文件 (7 个)
- `data/DynamicRepository.java` — 主键硬编码 + get filter 契约
- `sql/SqlQueryCollectionExecutor.java` — executeGet 签名简化
- `ddl/H2DialectAdapter.java` — TYPE_MAP 小写修复
- `ddl/PostgresDialectAdapter.java` — TYPE_MAP 小写修复
- `test/.../SqlQueryCollectionTest.java` — 完整重写 (27 tests)
- `test/.../ActionScopeRelationReviewTest.java` — belongsToMany 重写

**是否修改前端文件:** 否