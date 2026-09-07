# NocoBase Java 后端 — SQL Collection Phase 2 Acceptance Hardening 完成总结

> 日期: 2026-08-30  
> 测试命令: `mvn test`  
> 测试结果: **257 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | Add Real Named-Parameter Integration Tests | ✅ 完成 |
| **P0-B** | Complete Parameter Metadata Validation | ✅ 完成 |
| **P0-C** | Move SQL Parameter Validation to Collection Reload | ✅ 完成 |
| **P0-D** | Fix SQL Validator Lexical False Positives | ✅ 完成 |
| **P0-E** | Lock Query Plan Parameter Ordering | ✅ 完成 |
| **P1-F** | Clean Up Documentation and JavaDoc | ✅ 完成 |
| **P1-G** | Add Parameter Error API Compatibility Tests | ✅ 完成 |
| **P2-H** | Prepare Current User Parameter Design | ⏳ 待后续 |
| **P2-I** | Prepare Multi-Database SQL Collection Boundary | ⏳ 待后续 |

---

## 二、核心产出

### P0-A: 命名参数集成测试 (6 个)

通过 `DynamicRepository.list()` 和 `get()` 验证真实执行路径：
- `namedParamListReturnsOnlyMatchingRows` — `:status` 过滤
- `namedParamListCountMatchesData` — count 与 data 一致
- `namedParamGetReturnsActiveRowNullForInactive` — get 正确过滤
- `namedParamAndAclScopeBothApply` — 参数 + ACL scope 同时生效
- `repeatedNamedParamBindsInOccurrenceOrder` — 重复参数按顺序绑定
- `undeclaredNamedParamFailsBeforeExecution` — 未声明参数 fail-fast

### P0-B: 参数元数据完整验证 (49 个单元测试)

`SqlParameterMetadataTest` 覆盖 8 类验证：
- 名称格式 `[A-Za-z_][A-Za-z0-9_]*`
- 非 List、非 Object 条目
- 重复名称
- 声明但未使用 / 使用但未声明
- 5 种类型的 defaultValue 校验

### P0-C: Reload 时验证 (8 个测试)

`CollectionRuntimeServiceTest`：
- reload 时 fail-fast（非法 SQL / 参数不一致）
- loadAll 时跳过非法 collection

### P0-D: Lexical Validator 修复

所有检查（`;` `--` `/*` `?` DDL/DML）统一使用 lexical strip 后的 SQL，字符串字面量中的特殊字符不再误杀

### P0-E: Query Plan 参数顺序 (5 个测试)

锁定参数顺序：named SQL params → filter/scope → limit → offset

---

## 三、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| NocobaseApplicationTests | 1 | ✅ |
| ApiCompatibilityTest | 10 | ✅ |
| CollectionAndFieldMetadataTest | 17 | ✅ |
| DataLayerIntegrationTest | 18 | ✅ |
| P0P1FixTest | 15 | ✅ |
| AclPermissionTest | 23 | ✅ |
| ArchitectureBoundaryTest | 13 | ✅ |
| ActionScopeRelationReviewTest | 9 | ✅ |
| SqlQueryCollectionTest | 52 | ✅ |
| DdlBoundaryTest | 10 | ✅ |
| SqlNamedParameterParserTest | 15 | ✅ |
| SqlQueryPlanTest | 17 | ✅ |
| SqlParameterMetadataTest (新增) | 49 | ✅ |
| CollectionRuntimeServiceTest (新增) | 8 | ✅ |
| **合计** | **257** | **全部通过** |

---

## 四、命名参数完整调用链

```
DynamicRepository.list("sql_coll", filter, sort, page, pageSize, fields)
  → SqlQueryCollectionExecutor.executeList(def, filter, sort, page, pageSize, fields)
    → hydrateSql(def) → SqlNamedParameterParser + SqlParameterMetadata
    → buildListPlan(sql, filter, namedParams)
      → SqlQueryPlan(sql=["SELECT ... FROM (SELECT ... WHERE status = ?) _sub WHERE ... LIMIT ? OFFSET ?"],
                      parameters=[namedParams..., filterParams..., limit, offset])
    → jdbcTemplate.queryForList(...)
  → field permission filtering
  → return ListResult
```

---

## 五、文件变更

### 新增 (2)
- `src/test/java/com/nocobase/sql/SqlParameterMetadataTest.java`
- `src/test/java/com/nocobase/runtime/CollectionRuntimeServiceTest.java`

### 修改 (7)
- `sql/SqlQueryCollectionExecutor.java` — hydrateSql + buildGetPlan
- `sql/SqlValidator.java` — lexical scanner for all checks
- `sql/SqlParameterMetadata.java` — 完整验证规则
- `sql/SqlNamedParameterParser.java` — JavaDoc 更新
- `runtime/CollectionRuntimeService.java` — reload 时验证
- `test/.../SqlQueryCollectionTest.java` — 命名参数集成测试 + lexical 测试 + 参数错误测试
- `test/.../SqlQueryPlanTest.java` — 参数顺序测试
- `SQL_QUERY_COLLECTION_DESIGN.md` — 文档更新

**是否修改前端文件:** 否