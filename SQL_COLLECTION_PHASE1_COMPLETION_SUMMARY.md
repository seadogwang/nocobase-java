# NocoBase Java 后端 — SQL Collection / Metadata Hardening 完成总结

> 日期: 2026-08-29  
> 测试命令: `mvn test`  
> 测试结果: **102 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 返工真实 belongsToMany association 测试 | ⏳ 已修复 through 链路，测试待完善 |
| **P0-B** | 修正 association 权限语义测试 | ⏳ 基础已就绪 |
| **P0-C** | 完成真正可配置的主键元数据抽象 | ✅ 完成 |
| **P0-D** | 替换 DynamicRepository 剩余 id 硬编码 | ✅ 完成 |
| **P0-E** | 统一字段权限过滤逻辑 | ✅ 完成 |
| **P1-F** | View Collection 做成真实只读视图抽象 | ✅ 完成 |
| **P1-G** | 清理测试隔离与弱断言 | ⏳ 部分完成 |
| **P1-H** | 收紧架构边界和 SQL 存放规则 | ✅ 完成 |
| **P2-I** | 实现 SQL Query Collection Phase 1 | ⏳ 设计已完成，实现待后续 |
| **P2-J** | SQL 安全校验和参数策略 | ⏳ 设计已完成 |
| **P2-K** | 补 SQL Query Collection 设计文档 | ✅ 完成 |

---

## 二、各任务详情

### P0-C: 完成真正可配置的主键元数据抽象

**修改文件:**
- `runtime/CollectionDefinition.java` — 新增 `primaryKeyFieldName` 字段 + Builder 方法
- `runtime/CollectionRuntimeService.java` — `parsePrimaryKey()` 从 options.primaryKey 解析

**主键配置来源（优先级）:**
1. `CollectionEntity.options.primaryKey` → 指定主键字段名
2. 默认 physical collection → `"id"`
3. view/sql collection → 无默认主键（`hasPrimaryKey=false`）

**是否修改前端文件:** 否

---

### P0-D: 替换 DynamicRepository 剩余 id 硬编码

**修改文件:**
- `data/DynamicRepository.java` — 10+ 处硬编码替换

| 位置 | 修复前 | 修复后 |
|------|--------|--------|
| `create()` generated key | `new String[]{"id"}` | `new String[]{def.getPrimaryKeyColumnName()}` |
| `createLink()` generated key | `new String[]{"id"}` | `new String[]{def.getPrimaryKeyColumnName()}` |
| `list()` field filtering | `"id"` | `pkField(def)` |
| `get()` field filtering | `"id"` | `pkField(def)` |
| `readAfterWrite()` minimal return | `"id"` | `pkField(def)` + `hasPrimaryKey()` check |
| `update()` WHERE | `pk(def)` | ✅ (前批次已修复) |
| `destroy()` WHERE | `pk(def)` | ✅ (前批次已修复) |
| `existsInScope()` WHERE | `pk(def)` | ✅ (前批次已修复) |

**仍硬编码 `"id"` 的位置:**
| 位置 | 原因 |
|------|------|
| `listLinks()` SELECT column | through 表通常有 id，作为内部标识列 |
| `FilterCompiler.resolveColumn("id")` | 系统列白名单，通过主键元数据动态判断（待后续） |

**是否修改前端文件:** 否

---

### P0-E: 统一字段权限过滤逻辑

**修改文件:**
- `data/DynamicRepository.java` — `list()`、`get()`、`readAfterWrite()` 统一使用 `pkField(def)`

**关键修复:**
- `FieldPermission.none()` 时返回 `{pkField: value}` 或空 map（无主键时）
- 所有字段过滤统一保留主键字段（不再硬编码 `"id"`）

**是否修改前端文件:** 否

---

### P1-F: View Collection 做成真实只读视图抽象

**修改文件:**
- `ddl/DdlSynchronizer.java` — `createCollection()` 跳过 view/sql collection 的 DDL

**关键修复:**
- view/sql collection 不创建物理表（不再执行 `CREATE TABLE`）
- 映射到已存在 table/view 的 view collection 可以 list
- create/update/destroy 通过 capability 拒绝

**测试更新:**
- `DataLayerIntegrationTest.viewCollectionRejectsWrites` — 映射到现有表
- `AclPermissionTest.viewCollectionCreateReturnsForbidden` — 映射到现有表
- `P0P1FixTest.viewCollectionRejectsWrites` — 映射到现有表

**是否修改前端文件:** 否

---

### P1-H: 收紧架构边界

**修改文件:**
- `test/.../ArchitectureBoundaryTest.java` — 递归扫描 + allowlist 最小化

**Allowlist:**
- `DynamicRepository.java` — 统一数据访问层
- `DdlSynchronizer.java` — DDL 操作
- `DialectAdapterFactory.java` — 方言检测
- `CollectionManagerService.java` — @Deprecated 遗留

**是否修改前端文件:** 否

---

## 三、主键元数据来源

```
CollectionDefinition 主键决策:
  1. options.primaryKey: "my_id" → getPrimaryKeyFieldName() = "my_id"
  2. physical collection, options 无 primaryKey → 默认 "id"
  3. view/sql collection, options 无 primaryKey → hasPrimaryKey() = false

CollectionRuntimeService.parsePrimaryKey():
  - 读取 entity.options JSON → 提取 "primaryKey"
  - 默认 physical → "id"
  - view/sql → null (no primary key)
```

---

## 四、View Collection 支持范围

| 能力 | 状态 |
|------|------|
| DDL | ✅ 不创建物理表 |
| list | ✅ 映射到已有 view/table |
| get | ✅ 需配置 primaryKey |
| create/update/destroy | ✅ 被 capability 拒绝 |
| scope | ✅ 通过 mergeScopeFilter 应用 |
| readable fields | ✅ 通过 FieldPermission 过滤 |

---

## 五、SQL Query Collection 设计

见 `SQL_QUERY_COLLECTION_DESIGN.md`。

Phase 1 支持范围:
- list: 外层包装 `SELECT * FROM (<sql>) _sub`
- filter/sort/page/count
- action scope + readable fields
- 暂缓: named parameters, get 支持

---

## 六、测试汇总

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

## 七、文件变更清单

### 修改文件 (6 个)
- `runtime/CollectionDefinition.java` — 可配置主键
- `runtime/CollectionRuntimeService.java` — parsePrimaryKey
- `data/DynamicRepository.java` — 替换硬编码 id, pkField
- `ddl/DdlSynchronizer.java` — view/sql 跳过 DDL
- `test/.../DataLayerIntegrationTest.java` — view 测试适配
- `test/.../AclPermissionTest.java` — view 测试适配
- `test/.../P0P1FixTest.java` — view 测试适配

**是否修改前端文件:** 否