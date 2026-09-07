# NocoBase Java 后端 — SQL Collection Phase 1 实现完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **102 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 返工真实 belongsToMany association 测试 | ⏳ 待完善 |
| **P0-B** | 修正 DynamicRepository 剩余主键硬编码 | ✅ 完成 |
| **P0-C** | 主键元数据 fail-fast 校验 | ⏳ 基础完成 |
| **P0-D** | 统一字段权限过滤 | ✅ 完成 |
| **P0-E** | 清理测试隔离与弱断言 | ⏳ 部分完成 |
| **P1-F** | 补齐 view/sql DDL 边界 | ⏳ 基础完成 |
| **P1-G** | 收紧 through 字段校验 | ✅ 完成 |
| **P1-H** | 架构边界为 SQL executor 开口 | ✅ 完成 |
| **P2-I** | 实现 SQL Query Collection Phase 1 | ✅ 完成 |
| **P2-J** | SQL 安全校验 Phase 1 | ✅ 完成 |
| **P2-K** | SQL collection 测试矩阵 | ⏳ 待补充 |
| **P2-L** | 更新 SQL 设计文档 | ⏳ 见本文档 |

---

## 二、核心实现

### P2-I: SQL Query Collection Phase 1 执行器

**新增文件:**
- `sql/SqlQueryCollectionExecutor.java` — SQL collection 专用执行器
- `sql/SqlValidator.java` — SQL 安全校验

**修改文件:**
- `data/DynamicRepository.java` — `list()` 对 SQL collection 委托给 executor

**调用链:**
```
GenericCrudController → DynamicRepository.list()
  → if def.isSql():
      → SqlQueryCollectionExecutor.executeList(def, filter, sort, page, pageSize, fields)
        → SqlValidator.validate(configuredSql)
        → SELECT <projection> FROM (<configuredSql>) _nocobase_sub
        → WHERE <scope + filter>
        → ORDER BY <sort>
        → LIMIT ? OFFSET ?
  → field permission filtering (DynamicRepository)
  → return ListResult
```

**Phase 1 支持:**
- ✅ list 查询
- ✅ filter/sort/page/pageSize
- ✅ count
- ✅ action scope
- ✅ readable fields
- ✅ fields projection
- ✅ create/update/destroy → Forbidden (by capability)
- ⏳ get (需 primaryKey)

**是否修改前端文件:** 否

---

### P2-J: SQL 安全校验

**新增文件:**
- `sql/SqlValidator.java`

**校验规则:**
| 规则 | 说明 |
|------|------|
| 只能是 SELECT 或 WITH ... SELECT | 拒绝其他语句 |
| 禁止多语句 | 拒绝 `;` 分隔 |
| 禁止注释 | 拒绝 `--`、`/* */` |
| 禁止 DDL/DML | 拒绝 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE/GRANT/REVOKE/CALL/EXEC/EXECUTE/REPLACE/MERGE |

**是否修改前端文件:** 否

---

### P0-B: 修正 DynamicRepository 剩余主键硬编码

**修改文件:**
- `data/DynamicRepository.java` — destroy/existsInScope/readAfterWrite 使用 `pk(def)`

**已替换的硬编码:**
- `destroy()` WHERE → `pk(def)`
- `existsInScope()` WHERE → `pk(def)`
- `readAfterWrite()` WHERE → `pk(def)`
- `create()` generated key → `def.getPrimaryKeyColumnName()`
- `createLink()` generated key → `def.getPrimaryKeyColumnName()`
- `list()`/`get()` field filtering → `pkField(def)`
- `readAfterWrite()` minimal return → `pkField(def)` + `hasPrimaryKey()` check

**仍保留的硬编码 `"id"`:**

| 位置 | 原因 |
|------|------|
| `listLinks()` SELECT column | through 表通常有 id，作为内部标识列 |
| `FilterCompiler.resolveColumn("id")` | 系统列白名单（后续通过主键元数据动态判断） |
| `AclService.filterReadableFields()` | 未被 DynamicRepository 调用（独立 API） |

**是否修改前端文件:** 否

---

### P1-H: 架构边界

**修改文件:**
- `test/.../ArchitectureBoundaryTest.java` — allowlist 增加 `SqlQueryCollectionExecutor.java`

**最终 Allowlist:**
- `DynamicRepository.java` — 统一数据访问层
- `DdlSynchronizer.java` — DDL 操作
- `DialectAdapterFactory.java` — 方言检测
- `SqlQueryCollectionExecutor.java` — SQL collection 执行器（新增）
- `CollectionManagerService.java` — @Deprecated 遗留

---

## 三、SQL Collection 执行流程

```
1. DynamicRepository.list("my_sql_collection", filter, sort, page, pageSize, fields)
2. 获取 CollectionDefinition → def.isSql() = true
3. checkCapability("list") → readable=true ✅
4. checkAclAction("my_sql_collection", "list") → ACL check
5. mergeScopeFilter → scope filter
6. SqlQueryCollectionExecutor.executeList(def, filter, sort, page, pageSize, fields):
   a. SqlValidator.validate(configuredSql) → security check
   b. SELECT <fields> FROM (<sql>) _nocobase_sub
   c. WHERE <scope + filter>
   d. ORDER BY <sort>
   e. LIMIT ? OFFSET ?
   f. COUNT
7. field permission filtering → readable fields
8. return ListResult
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

### 新增文件 (2 个)
- `src/main/java/com/nocobase/sql/SqlQueryCollectionExecutor.java`
- `src/main/java/com/nocobase/sql/SqlValidator.java`

### 修改文件 (3 个)
- `src/main/java/com/nocobase/data/DynamicRepository.java` — SQL executor 集成 + 主键硬编码替换
- `src/main/java/com/nocobase/runtime/CollectionDefinition.java` — 可配置主键
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java` — allowlist 更新

**是否修改前端文件:** 否