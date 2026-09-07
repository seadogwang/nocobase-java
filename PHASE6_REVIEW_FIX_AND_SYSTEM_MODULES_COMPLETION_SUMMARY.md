# NocoBase Java 后端 — Phase6 Review Fix & System Modules 完成总结

> 日期: 2026-09-02  
> 测试命令: `mvn test`  
> 测试结果: **673 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS

---

## 一、任务完成状态

| 任务 | 描述 | 状态 |
|------|------|------|
| **P0-A** | 默认值模型返工与脱敏 | ✅ 完成 |
| **P0-B** | PostgreSQL 验收改为真实后端链路 | ✅ 完成 |
| **P0-C** | 插件模块注册表后端契约 | ✅ 完成 |
| **P1-D** | 用户与角色模块 API | ✅ 完成 |
| **P1-E** | ACL 管理模块 API | ✅ 完成 |
| **P1-F** | UI Schema Storage 模块 | ✅ 完成 |
| **P1-G** | System Settings 模块 | ✅ 完成 |
| P2-H | Collection Manager API 兼容 | ⏳ 待后续 |
| P2-I | 架构边界测试扩展 | ⏳ 待后续 |

---

## 二、核心产出

### P0-A: 默认值返工

- Literal 不再过度拦截：`it's ok`、`A;B`、`select plan` 全部接受
- Expression 保持白名单：CURRENT_TIMESTAMP/CURRENT_DATE/NOW()
- `DefaultValue.toString()` 不输出真实值

### P0-B: PostgreSQL 真实链路

- 全部改用 `CollectionMetadataService → DdlSynchronizer → Runtime → DynamicRepository`
- PG_URL 脱敏为 hostname + database

### P0-C: 插件注册表

- `PluginModuleRegistry` — 8 个内置模块，启动时 idempotent sync
- 禁用内置模块 → 403

### P1-D/E: 用户/角色/ACL API

- `UsersController` + `RolesController` + `AclController`
- 密码永不返回，admin 保护，角色保护
- ACL 配置立即影响 DynamicRepository

### P1-F/G: UI Schema / System Settings

- `getTreeByUid`/`getTreeBySchemaUid`，insertAdjacent 语义修正
- 部分更新，敏感配置保护

---

## 三、测试汇总

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| ApiCompatibilityTest | 28 | ✅ |
| P1FixApiTest (新增) | 25 | ✅ |
| DefaultValueAndDialectDiffTest | 35 | ✅ |
| 其他所有测试 | ~585 | ✅ |
| **合计** | **673** | **0 failures, 0 errors** |

---

## 四、文件变更

### 新增 (7)
- `plugin/PluginModuleRegistry.java`
- `controller/UsersController.java`
- `controller/RolesController.java`
- `controller/AclController.java`
- `service/UiSchemaService.java`
- `test/.../P1FixApiTest.java`

### 修改 (10)
- `field/FieldOptions.java` — default value 返工
- `field/FieldOptionsParser.java` — 适配
- `controller/ApplicationPluginController.java` — 使用 registry
- `controller/UiSchemaController.java` — 完整 CRUD
- `controller/SystemSettingsController.java` — partial update
- `config/DataInitializer.java` — idempotent plugins
- `config/NocobaseUrlFilter.java` — users/roles/acl 排除
- `repository/UiSchemaRepository.java` — deleteByUid
- `repository/UserRoleRepository.java` — findByRoleId
- `test/.../postgresql/PostgreSqlIntegrationTest.java` — 真实链路
- `test/.../DefaultValueAndDialectDiffTest.java` — 更新测试

**是否修改前端文件:** 否  
**是否改变 API 响应结构:** 否