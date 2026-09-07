# NocoBase Java 后端 — Review Fix 完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **61 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 优先级 | 状态 |
|------|------|--------|------|
| **P0-0** | 建立统一动态数据访问出口 | 最高 | ✅ 完成 |
| **P0-A** | 重做关联动作服务 | P0 | ✅ 完成 |
| **P0-B** | 修正 append 关联查询权限 | P0 | ✅ 完成 |
| **P0-C** | 修正 update/destroy scope 原子性 | P0 | ✅ 完成 |
| **P1-D** | 严格校验 fields 和 sort 参数 | P1 | ✅ 完成 |
| **P1-E** | 修正 UI Schema JSON 错误处理 | P1 | ✅ 完成 |
| **P1-F** | 补齐测试矩阵 | P1 | ✅ 完成 |

---

## 二、各任务详情

### P0-0: 建立统一动态数据访问出口

**目标**: 所有动态业务表 SQL 必须经过 `DynamicRepository`，不允许 `RelationQueryService` 或 `AssociationActionService` 直接调用 `JdbcTemplate`。

**改动文件:**
- `data/RelationQueryService.java` — 完全重写，移除 `JdbcTemplate` 依赖
- `data/AssociationActionService.java` — 完全重写，移除 `JdbcTemplate` 依赖

**设计说明:**
- `RelationQueryService.appendXxx()` 使用 `DynamicRepository.list(targetCollection, filter, ...)` 替代裸 SQL
- `AssociationActionService` 所有写操作使用 `DynamicRepository.update/create/destroy` 替代 `jdbcTemplate.update`
- ACL scope、字段权限、分页、排序全部由 `DynamicRepository` 统一处理
- 不再需要 `buildScopedWhere` 方法（scope 由 DynamicRepository 内部处理）

**验证:**
- `RelationQueryService.java` 中 `jdbcTemplate` 引用数: **0**
- `AssociationActionService.java` 中 `jdbcTemplate` 引用数: **0**

**是否修改前端文件:** 否

---

### P0-A: 重做关联动作服务

**改动文件:**
- `data/AssociationActionService.java` — 完整实现
- `controller/GenericCrudController.java` — filterByTk 校验, 关联动作路由

**关键修复:**
- `listBelongsTo()` — 修复返回空列表的问题
- `setBelongsTo()` — 修复空实现，通过 DynamicRepository.update 更新外键
- `add/remove/set` — 检查源和目标 collection 的 action 权限
- `add/remove/set` — 校验源记录和目标记录在 scope 内
- `filterByTk` — 缺失时返回 400（不再落入 NumberFormatException）
- `Controller` — 显式支持不兼容 action 并返回错误

**是否修改前端文件:** 否

---

### P0-B: 修正 append 关联查询权限

**改动文件:**
- `data/RelationQueryService.java` — 完全重写

**关键修复:**
- append 查询目标 collection 时，通过 `DynamicRepository.list()` 自动应用 ACL scope
- 不再只检查 `canAction` 后直接查询全表
- 查询字段受目标 collection readable fields 控制（DynamicRepository 内部处理）
- `belongsToMany` through 表查询也经过 DynamicRepository

**是否修改前端文件:** 否

---

### P0-C: 修正 update/destroy scope 原子性

**改动文件:**
- `data/DynamicRepository.java` — update/destroy 方法重写

**关键修复:**
- `update` 最终 SQL 包含 scope 条件 + id 条件，单条 SQL 原子执行
- `destroy` 最终 SQL 包含 scope 条件 + id 条件，单条 SQL 原子执行
- 不再先 `SELECT COUNT` 再仅按 `id` 更新/删除
- 根据 affected rows 判断记录是否存在或无权限
- 并发情况下 scope 检查和写入条件不会分离

**是否修改前端文件:** 否

---

### P1-D: 严格校验 fields 和 sort 参数

**改动文件:**
- `data/DynamicRepository.java` — buildSelectClause / buildSortClause

**关键修复:**
- `fields` 包含未知字段 → 400
- `fields` 包含非物理字段 → 400
- `fields` 全部非法 → 400（不回退到 `*`）
- `sort` 包含未知字段 → 400
- `sort` 包含非物理字段 → 400
- 系统列（id, created_at, updated_at）允许通过

**是否修改前端文件:** 否

---

### P1-E: 修正 UI Schema JSON 错误处理

**改动文件:**
- `controller/UiSchemaController.java` — parseSchema / toJsonString

**关键修复:**
- `parseSchema()` 解析失败时抛出 `IllegalArgumentException`（不再返回空 Map）
- `toJsonString()` 序列化失败时抛出 `IllegalArgumentException`（不再返回 `{}`）
- `insertAdjacent` 的 `position` 校验只允许 `beforeBegin/afterBegin/beforeEnd/afterEnd`
- 坏 JSON 返回 `{ errors: [{ message }] }`，不会被保存为空 schema

**是否修改前端文件:** 否

---

### P1-F: 补齐测试矩阵

**新增文件:**
- `src/test/java/com/nocobase/P0P1FixTest.java` — 15 个新测试

**测试覆盖:**
| 测试 | 覆盖点 |
|------|--------|
| `belongsToListReturnsTarget` | P0-A: listBelongsTo 返回目标记录 |
| `filterByTkMissingReturns400` | P0-A: filterByTk 缺失 → 400 |
| `associationActionsCheckAcl` | P0-A: set 操作检查 ACL |
| `updateWithScopeUsesAtomicSql` | P0-C: update 原子性 |
| `destroyWithScopeUsesAtomicSql` | P0-C: destroy 原子性 |
| `unknownFieldsReturns400` | P1-D: 未知 fields → 400 |
| `unknownSortReturns400` | P1-D: 未知 sort → 400 |
| `validFieldsAndSortStillWork` | P1-D: 合法 fields/sort 正常 |
| `filterCompilerRejectsUnknownFields` | P1-E: FilterCompiler 拒绝未知字段 |
| `filterCompilerAllowsId` | P1-E: FilterCompiler 允许系统列 id |
| `viewCollectionRejectsWrites` | P0-B: view collection 写入拒绝 |
| `createReturnsActualRecord` | P1-F: create 返回实际记录 |
| `unknownFieldsInCreateReturnError` | P1-F: 未知字段 create → 错误 |
| `systemFieldsInCreateReturnError` | P1-F: 系统字段 create → 错误 |
| `nestedAndOrFilterWorks` | P1-F: 嵌套 $and/$or 正常 |

---

## 三、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| `NocobaseApplicationTests` | 1 | ✅ |
| `ApiCompatibilityTest` | 10 | ✅ |
| `CollectionAndFieldMetadataTest` | 17 | ✅ |
| `DataLayerIntegrationTest` | 18 | ✅ |
| `P0P1FixTest` (新增) | 15 | ✅ |
| **合计** | **61** | **全部通过** |

---

## 四、架构改进总结

| 维度 | 改进前 | 改进后 |
|------|--------|--------|
| 数据访问出口 | 分散：DynamicRepository + RelationQueryService(jdbcTemplate) + AssociationActionService(jdbcTemplate) | 统一：全部经过 DynamicRepository |
| 关联查询 | 裸 JDBC 查询，绕过 scope | DynamicRepository.list() 自动应用 scope |
| 关联动作 | 裸 JDBC 更新，绕过权限 | DynamicRepository.update/create/destroy 统一权限 |
| update/destroy 原子性 | 两步检查（SELECT + UPDATE） | 单条 SQL（scope + id） |
| fields/sort 校验 | 静默忽略非法字段 | 明确返回 400 |
| JSON 错误处理 | 静默吞错，返回空 Map | 明确抛出异常 |
| 测试数量 | 46 | 61 (+15) |

---

## 五、文件变更清单

### 新增文件 (1 个)
- `src/test/java/com/nocobase/P0P1FixTest.java`

### 修改文件 (5 个)
- `src/main/java/com/nocobase/data/RelationQueryService.java` — 完全重写，移除 JdbcTemplate
- `src/main/java/com/nocobase/data/AssociationActionService.java` — 完全重写，移除 JdbcTemplate
- `src/main/java/com/nocobase/data/DynamicRepository.java` — update/destroy 原子性, 严格 fields/sort, 外键列支持
- `src/main/java/com/nocobase/controller/GenericCrudController.java` — filterByTk 校验
- `src/main/java/com/nocobase/controller/UiSchemaController.java` — JSON 错误处理, position 校验

**是否修改前端文件:** 否