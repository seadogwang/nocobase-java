# NocoBase Java 后端 — SQL Collection Phase 2 Runtime Context 完成总结

> 日期: 2026-08-30  
> 测试命令: `mvn test`  
> 测试结果: **308 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | Reject Malformed Named Parameter Tokens | ✅ 完成 |
| **P0-B** | Bind Typed Parameter Values | ✅ 完成 |
| **P0-C** | Clean Invalid Metadata in Runtime Tests | ✅ 完成 |
| **P0-D** | Add Real Controller Error Compatibility Tests | ✅ 完成 |
| **P0-E** | Track Invalid Collections Skipped by loadAll | ✅ 完成 |
| **P1-F** | Implement currentUser Parameter Source | ✅ 完成 |
| **P1-G** | Separate Parameter Resolution From Metadata Parsing | ✅ 完成 |
| **P1-H** | Improve SQL Collection Error Sanitization | ✅ 完成 |
| **P2-I** | Multi-Datasource Boundary Design Only | ✅ 完成 |

---

## 二、核心产出

### P0-A: Malformed 命名参数拒绝

`:1bad`, `:bad-name`, `:`, `: status`, `:=` 全部在解析时 fail-fast，错误消息不含完整 SQL

### P0-B: 类型化参数绑定

- `buildValueList()` 返回归一化 Java 值（Integer/Long/BigDecimal, Boolean, java.sql.Date, Timestamp）
- 12 个类型验证单元测试 + 2 个集成测试

### P0-C: 运行时测试清理

注入 `CollectionRepository`/`FieldRepository`，直接删除测试元数据行

### P0-D: Controller 错误兼容测试

`SqlCollectionErrorTest` — 9 个 MockMvc 测试，验证 `{ "errors": [{ "message": "..." }] }` 格式

### P0-E: Invalid Collection Tracking

`CollectionRuntimeService` 新增 `invalidCollections` 追踪，`loadAll()` 记录跳过集合

### P1-F: currentUser 参数源

支持 `source: "currentUser"`, `path: "id"/"email"`，admin 和 member 绑定各自 ID

### P1-G: SqlParameterResolver

分离 metadata 解析和参数值解析，支持 static 和 currentUser 源

### P1-H: Error Sanitization

`sanitizeJdbcError()` 从 JDBC 错误消息中剥离 SQL 文本

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
| SqlQueryCollectionTest | 58 | ✅ |
| DdlBoundaryTest | 10 | ✅ |
| SqlNamedParameterParserTest | 21 | ✅ |
| SqlQueryPlanTest | 17 | ✅ |
| SqlParameterMetadataTest | 49 | ✅ |
| SqlParameterResolverTest (新增) | 12 | ✅ |
| CollectionRuntimeServiceTest | 14 | ✅ |
| SqlCollectionErrorTest (新增) | 9 | ✅ |
| **合计** | **308** | **全部通过** |

---

## 四、文件变更

### 新增 (2)
- `src/test/java/com/nocobase/sql/SqlParameterResolverTest.java`
- `src/test/java/com/nocobase/SqlCollectionErrorTest.java`

### 新增 (1)
- `src/main/java/com/nocobase/sql/SqlParameterResolver.java`

### 修改 (8)
- `sql/SqlNamedParameterParser.java` — malformed token 拒绝
- `sql/SqlParameterMetadata.java` — 类型化绑定 + currentUser 源
- `sql/SqlQueryCollectionExecutor.java` — resolver 集成 + error sanitization
- `acl/CurrentUserContext.java` — getCurrentUserEmail
- `runtime/CollectionRuntimeService.java` — invalid collection tracking
- `test/.../SqlQueryCollectionTest.java` — 集成测试
- `test/.../runtime/CollectionRuntimeServiceTest.java` — 清理 + tracking
- `test/.../sql/SqlParameterMetadataTest.java` — 类型化值测试
- `SQL_QUERY_COLLECTION_DESIGN.md` — Phase 3 设计

**是否修改前端文件:** 否