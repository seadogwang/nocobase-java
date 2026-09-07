# NocoBase Java 后端 — SQL Collection Phase 3 Real Datasource Review Fix 完成总结

> 日期: 2026-08-31  
> 测试命令: `mvn test`  
> 测试结果: **422 tests, 0 failures, 0 errors, BUILD SUCCESS**

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 修复 validateFields 参数处理 | ✅ 完成 |
| **P0-B** | 消除 validateFields 日志 SQL 泄露 | ✅ 完成 |
| **P0-C** | 外部 DataSource 生命周期管理 | ✅ 完成 |
| **P0-D** | 外部 DataSource 配置与连接校验 | ✅ 完成 |
| **P0-E** | 调整不可用 DataSource 恢复策略 | ✅ 完成 |
| **P1-F** | 方言选择不能静默回退 | ✅ 完成 |
| **P1-G** | SQL 查询治理入口 | ✅ 完成 |
| **P1-H** | 外部 datasource 只读边界验证 | ✅ 完成 |
| **P2-I** | SQL collection 可观测性内部指标 | ✅ 完成 |
| **P2-J** | 文档更新 | ✅ 完成 |

---

## 二、核心产出

### P0-A: validateFields 参数处理

- 移除 `replaceAll(":\\w+", "0")` → 使用 `SqlNamedParameterParser` + typed dummy binding
- 重复参数按 parser 顺序绑定，currentUser 用 declared type dummy value

### P0-B: 日志 SQL 泄露消除

- 删除 validation SQL 和 raw exception 日志
- 只记录 collection name, dataSourceKey, error category, exception class

### P0-C: 外部连接池生命周期

- `DataSourceHolder` 缓存 `JdbcTemplate` + `DataSource`
- `@PreDestroy destroy()` 关闭所有外部 `HikariDataSource`
- `clearUnavailable()` 也关闭连接池

### P0-D: 配置与连接校验

- `url` 必填, `dialect` 必须 `h2`/`postgresql`
- `resolve()` 主动获取连接验证，`isAvailable()` 反映真实状态

### P0-E: 恢复策略

- `refreshDataSource()` 关闭旧连接 → 移除 unavailable → 重新创建验证
- `CollectionRuntimeService.reload()` 调用 `refreshDataSource()`

### P1-F: 方言失败策略

- 未知 dialect → 明确错误（不回退 H2）
- 未配置 dialect 时从 URL 推导，无法推导 → 错误

### P1-G: 查询治理

- `maxPageSize=200`, `queryTimeout=30s`, `validationTimeout=5s`
- `page < 1` → 1, `pageSize > max` → capped

### P1-H: 只读边界

- 外部 datasource 创建后验证 `connection.isReadOnly()`
- CTE 内 DML 拒绝（`SqlValidatorTest`）

---

## 三、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| 已有测试类 | ~390 | ✅ |
| SqlDataSourceResolverIntegrationTest | 30 | ✅ |
| SqlDataSourceResolverTest | 9 | ✅ |
| NocobaseDataSourcePropertiesTest | 12 | ✅ |
| SqlQueryCollectionExecutorTest (新增) | 5 | ✅ |
| SqlValidatorTest (新增) | 5 | ✅ |
| **合计** | **422** | **全部通过** |

---

## 四、测试增长

```
102 → 119 → 123 → 129 → 146 → 165 → 177 → 257 → 308 → 344 → 392 → 422
```

## 五、外部 datasource 生命周期

```
创建: resolve(key) → 校验配置 → 创建 HikariDataSource → 验证连接 → 缓存 DataSourceHolder
恢复: refreshDataSource(key) → 关闭旧连接 → 移除 unavailable → 重新创建验证
关闭: @PreDestroy destroy() → 遍历所有外部 holder → HikariDataSource.close()
```

**是否修改前端文件:** 否  
**是否改变 API 响应结构:** 否  
**是否仍存在 SQL/参数/JDBC URL 日志泄露:** 否