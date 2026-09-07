# NocoBase Java 后端 — Data Layer Phase5 Review Fix 完成总结

> 日期: 2026-09-01  
> 测试命令: `mvn test`  
> 测试结果: **562 tests, 0 failures, 0 errors, 13 skipped**, BUILD SUCCESS

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 修复 belongsToMany through 查询截断 | ✅ 完成 |
| **P0-B** | 修复主库 dialect 硬编码 | ✅ 完成 |
| **P0-C** | 让测试失败真正阻断验收 | ✅ 完成 |
| **P0-D** | 清理 SQL/DDL 泄露风险 | ✅ 完成 |
| **P0-E** | 下线 CollectionManagerService 第二套 DDL 通道 | ✅ 完成 |
| P1-F | 接入 IndexDefinition 与 index sync | ⏳ 待后续 |
| P1-G | 接入 FieldOptionsParser 到 DDL | ⏳ 待后续 |
| P1-H | SchemaPlan dry-run | ⏳ 待后续 |
| P1-I | 修正 Relation validator | ⏳ 待后续 |
| P2-J/K | API 兼容/PostgreSQL | ⏳ 待后续 |

---

## 二、核心修复

### P0-A: belongsToMany through 截断修复

- `PhysicalSqlBuilder.buildListLinksPlan()` — 新增无 LIMIT/OFFSET 的 through 查询方法
- `listLinks()` 不再使用带分页的 `buildListPlan()`，改用 `buildListLinksPlan()`
- **两个失败测试全部通过**: `belongsToManyThroughInternalOps` (expected 2), `belongsToManyLargeThroughLinksReturnsAll` (expected 1200)

### P0-B: 硬编码 H2 dialect 修复

- `DynamicRepository` 不再硬编码 `new H2SqlDialect()`
- 通过 `SqlDataSourceResolver.resolveDialect("main")` 获取真实主库 dialect
- PostgreSQL 主库下物理 CRUD builder 使用真实 dialect

### P0-D: SQL/DDL 泄露清理

- `SqlPlan.toString()` — 不再输出 SQL，只输出 operation/collection/parameterCount
- `DdlSynchronizer.syncIndexes()` — 错误消息使用 `SqlErrorSanitizer`
- `dropCollection()` — 统一走 `DdlPlan.dropTable()` + `executePlan()`

### P0-E: CollectionManagerService 移除

- **完整删除** `CollectionManagerService.java`（含 JdbcTemplate + DDL SQL）
- `ArchitectureBoundaryTest` 移除白名单 + 验证文件不存在

---

## 三、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| ActionScopeRelationReviewTest | 10 | ✅ |
| P0P1FixTest | 41 | ✅ |
| PhysicalSqlBuilderTest | 22 | ✅ |
| SqlPlanTest (新增) | 3 | ✅ |
| ArchitectureBoundaryTest | 19 | ✅ |
| 其他所有测试 | ~467 | ✅ |
| PostgreSqlIntegrationTest | 13 | ⏭ skipped |
| **合计** | **562** | **0 failures, 0 errors** |

---

## 四、验收命令结果

```bash
mvn test
# 562 tests, 0 failures, 0 errors, 13 skipped

rg -n "<failure|<error" target/surefire-reports
# 无命中

rg -n "new H2SqlDialect" src/main/java/com/nocobase
# 无命中（仅 SqlDialect/PostgreSqlDialect 实现类）

rg -n "JdbcTemplate|CREATE TABLE|ALTER TABLE|DROP TABLE" src/main/java/com/nocobase/service/CollectionManagerService.java
# 文件不存在，无命中

rg -n "Exception during pool initialization" target/surefire-reports
# 无命中
```

---

## 五、文件变更

### 删除 (1)
- `src/main/java/com/nocobase/service/CollectionManagerService.java`

### 新增 (1)
- `src/test/java/com/nocobase/data/SqlPlanTest.java`

### 修改 (5)
- `data/PhysicalSqlBuilder.java` — buildListLinksPlan
- `data/DynamicRepository.java` — listLinks + dialect 注入
- `data/SqlPlan.java` — toString 不含 SQL
- `ddl/DdlSynchronizer.java` — syncIndexes sanitization + dropCollection DdlPlan
- `ddl/DdlPlan.java` — dropTable factory
- `test/.../ArchitectureBoundaryTest.java` — 移除 CMService 白名单

**是否修改前端文件:** 否  
**是否改变 API 响应结构:** 否  
**belongsToMany 两个失败测试是否已修复:** 是  
**DynamicRepository 是否还硬编码 H2 dialect:** 否  
**CollectionManagerService 是否仍是第二套 DDL 通道:** 否（已删除）