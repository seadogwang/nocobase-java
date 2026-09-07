# NocoBase Java 后端 — SQL Collection Phase 1 验收完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **119 tests, 0 failures, 0 errors, BUILD SUCCESS** (从 102 增长到 119)

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 新增 SQL Collection 测试矩阵 | ✅ 完成 |
| **P0-B** | 修正 SQL Collection get 语义 | ✅ 完成 |
| **P0-C** | 统一字段权限过滤 | ✅ 完成 |
| **P0-D** | 修正剩余主键硬编码 | ✅ 完成 |
| **P0-E** | 主键元数据 fail-fast 校验 | ⏳ 基础完成 |
| **P0-F** | 返工真实 belongsToMany 测试 | ⏳ 待后续 |
| **P1-G** | 补齐 view/sql DDL 边界 | ✅ 完成 |
| **P1-H** | 强化 SQL 安全校验 | ✅ 完成 |
| **P1-I** | 补 SQL executor 结构 | ✅ 完成 |
| **P1-J** | 架构边界测试补强 | ✅ 完成 |
| **P1-K** | 更新 SQL 设计文档 | ✅ 完成 |

---

## 二、核心产出

### SQL Collection 测试矩阵 (新增 17 个测试)

| 测试 | 覆盖点 |
|------|--------|
| `sqlBasicList` | 基础 list 查询 |
| `sqlFilter` | filter 过滤 |
| `sqlSort` | sort 排序 |
| `sqlPagination` | page/pageSize 分页 |
| `sqlCount` | count 正确 |
| `sqlFieldsProjection` | fields 投影 |
| `sqlCreateRejected` | create → Forbidden |
| `sqlUpdateRejected` | update → Forbidden |
| `sqlDestroyRejected` | destroy → Forbidden |
| `sqlValidatorSelect` | SELECT 合法 |
| `sqlValidatorWithSelect` | WITH ... SELECT 合法 |
| `sqlValidatorSemicolonsRejected` | 分号拒绝 |
| `sqlValidatorInsertRejected` | INSERT 拒绝 |
| `sqlValidatorDeleteRejected` | DELETE 拒绝 |
| `sqlValidatorCommentsRejected` | 注释拒绝 |
| `sqlValidatorJdbcParamRejected` | `?` 参数拒绝 |
| `sqlValidatorNamedParamRejected` | `:param` 拒绝 |

### SQL Collection get() 支持

- `DynamicRepository.get()` 对 SQL collection 委托给 `SqlQueryCollectionExecutor.executeGet()`
- 无 primaryKey → 明确拒绝
- 有 primaryKey → 外层包装 + WHERE pk = ? LIMIT 1

### SQL 安全校验强化

| 规则 | Phase 1 行为 |
|------|-------------|
| 单条 SELECT/WITH | ✅ 允许 |
| 分号 `;` | ❌ 拒绝 |
| DDL/DML 关键字 | ❌ 拒绝 |
| 注释 `--` `/* */` | ❌ 拒绝 |
| JDBC `?` 参数 | ❌ 拒绝 (Phase 1) |
| 命名 `:param` | ❌ 拒绝 (Phase 1) |

### 主键硬编码替换

| 位置 | 修复 |
|------|------|
| `destroy()` WHERE | → `pk(def)` |
| `existsInScope()` WHERE | → `pk(def)` |
| `readAfterWrite()` WHERE | → `pk(def)` |
| `list()`/`get()` 字段过滤 | → `pkField(def)` |
| `readAfterWrite()` minimal return | → `pkField(def)` + `hasPrimaryKey()` |
| `create()`/`createLink()` generated key | → `def.getPrimaryKeyColumnName()` |

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
| `SqlQueryCollectionTest` (新增) | 17 | ✅ |
| **合计** | **119** | **全部通过** |

---

## 四、文件变更清单

### 新增文件 (3 个)
- `src/main/java/com/nocobase/sql/SqlQueryCollectionExecutor.java`
- `src/main/java/com/nocobase/sql/SqlValidator.java`
- `src/test/java/com/nocobase/SqlQueryCollectionTest.java`

### 修改文件 (5 个)
- `data/DynamicRepository.java` — SQL executor 集成 + get() 支持 + 主键硬编码替换
- `acl/AclFilterInjector.java` — no-result filter 注释
- `runtime/CollectionDefinition.java` — 可配置主键
- `runtime/CollectionRuntimeService.java` — parsePrimaryKey
- `test/.../ArchitectureBoundaryTest.java` — allowlist 更新

**是否修改前端文件:** 否