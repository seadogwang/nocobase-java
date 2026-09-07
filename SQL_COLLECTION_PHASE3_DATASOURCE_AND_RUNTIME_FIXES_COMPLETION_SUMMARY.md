# NocoBase Java 后端 — SQL Collection Phase 3 Datasource And Runtime Fixes 完成总结

> 日期: 2026-08-30  
> 测试命令: `mvn test`  
> 测试结果: **344 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 校验 currentUser path/type 兼容性 | ✅ 完成 |
| **P0-B** | 修正 invalidCollections 与 reload 生命周期 | ✅ 完成 |
| **P0-C** | SQL collection options JSON 解析 fail-fast | ✅ 完成 |
| **P0-D** | SQL 错误日志脱敏 | ✅ 完成 |
| **P0-E** | 补齐 currentUser 参数与 ACL scope 交集测试 | ✅ 完成 |
| **P1-F** | 收敛参数归一化与 resolver 入口 | ✅ 完成 |
| **P1-G** | 更新 SQL collection 设计文档 | ✅ 完成 |
| **P1-H** | Phase 3 多数据源入口元数据 | ✅ 完成 |
| **P2-I** | SqlDataSourceResolver 骨架 | ✅ 完成 |

---

## 二、核心产出

### P0-A: currentUser path/type 校验

- `path=id` → 必须 `type=number`
- `path=email` → 必须 `type=string`
- `currentUser` 不允许 `defaultValue`
- 5 个新测试

### P0-B: reload 生命周期

- reload 成功时清除 invalid 状态
- reload 失败时移除旧 definition + 写入 invalid
- 连续失败更新 reason 不累积

### P0-C: SQL options JSON fail-fast

- SQL/view collection options JSON 解析失败 → 抛异常进入 invalid tracking
- 3 个新测试

### P0-D: SQL 错误日志脱敏

- `SqlErrorSanitizer` — 14 个单元测试
- ERROR 日志只记录 collection name + sanitized message + exception class

### P0-E: currentUser + ACL scope 交集

- 4 个集成测试证明 SQL 参数、ACL scope、field permission 三者同时生效

### P1-H/P2-I: Phase 3 多数据源入口

- `CollectionDefinition.dataSourceKey` 默认 `"main"`
- `SqlDataSourceResolver` 骨架，非 main key 抛 `UnsupportedOperationException`

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
| SqlQueryCollectionTest | 62 | ✅ |
| DdlBoundaryTest | 10 | ✅ |
| SqlCollectionErrorTest | 9 | ✅ |
| SqlNamedParameterParserTest | 21 | ✅ |
| SqlQueryPlanTest | 17 | ✅ |
| SqlParameterMetadataTest | 67 | ✅ |
| SqlParameterResolverTest | 14 | ✅ |
| SqlErrorSanitizerTest (新增) | 14 | ✅ |
| SqlDataSourceResolverTest (新增) | 4 | ✅ |
| CollectionRuntimeServiceTest | 20 | ✅ |
| **合计** | **344** | **全部通过** |

---

## 四、文件变更

### 新增 (3)
- `src/main/java/com/nocobase/sql/SqlErrorSanitizer.java`
- `src/main/java/com/nocobase/sql/SqlDataSourceResolver.java`
- `src/test/java/com/nocobase/sql/SqlErrorSanitizerTest.java`
- `src/test/java/com/nocobase/sql/SqlDataSourceResolverTest.java`

### 修改 (10)
- `sql/SqlParameterMetadata.java` — currentUser 校验, buildValueList 可见性
- `sql/SqlNamedParameterParser.java` — unmodifiable list
- `sql/SqlQueryCollectionExecutor.java` — SqlDataSourceResolver + SqlErrorSanitizer
- `runtime/CollectionRuntimeService.java` — reload 生命周期 + options fail-fast
- `runtime/CollectionDefinition.java` — dataSourceKey
- `test/.../SqlQueryCollectionTest.java` — 集成测试
- `test/.../CollectionRuntimeServiceTest.java` — reload 测试
- `test/.../SqlParameterMetadataTest.java` — currentUser 校验测试
- `test/.../SqlParameterResolverTest.java` — 类型解析测试
- `test/.../ArchitectureBoundaryTest.java` — allowlist 更新
- `test/.../DdlBoundaryTest.java` — reload 异常类型适配
- `SQL_QUERY_COLLECTION_DESIGN.md` — 文档更新

**是否修改前端文件:** 否