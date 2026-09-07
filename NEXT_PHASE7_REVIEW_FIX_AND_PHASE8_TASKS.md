# NocoBase Java 后端 - Phase7 Review Fix 与 Phase8 开发任务

> 日期: 2026-09-02  
> 范围: 只改 Java 后端、测试、后端文档；前端界面和前端代码保持不变。  
> Review 对象: `PHASE7_SYSTEM_MODULES_COMPLETION_SUMMARY.md`  
> 本地验证: Surefire 汇总 `700 tests, 0 failures, 0 errors, 0 skipped`。  
> 下一份完成总结: `PHASE8_BACKEND_HARDENING_COMPLETION_SUMMARY.md`

## 一、Review 结论

### 可以接受

- Phase7 已完成大部分系统必要模块骨架，`users/roles/acl/uiSchemas/systemSettings/applicationPlugins/collection manager` 均有后端 API。
- `PluginModuleRegistry` 已从补缺失升级为权威同步，会修正系统插件的 `enabled/installed/builtIn/packageName/version`。
- `UserManagementService`、`RoleManagementService` 已开始把用户/角色逻辑从 Controller 中剥离，分页也从内存截取改为数据库分页。
- UI Schema 插入语义、系统设置结构化 JSON、ACL 基础语义校验均已有测试覆盖。

### 必须继续修正

- `SecurityConfig` 仍把 `/api/collections:create`、`/api/collections:destroy`、`/api/fields:create`、`/api/fields:destroy` 设置为 `permitAll()`，这是高危入口，会绕过 collection manager 权限。
- `CollectionController` 的 create/destroy/addField/dropField/dryRun 没有显式 admin/ACL 检查；collection manager 是元数据和 DDL 入口，必须收紧。
- 旧 `PluginController` 仍直接操作 `PluginRepository`，可绕过新的 `PluginModuleRegistry` 生命周期保护，应废弃或桥接到 registry。
- `DataInitializer` 仍硬编码 `admin@nocobase.com / admin123` 并输出到控制台；生产 profile 不允许默认密码和凭据日志。
- `DataInitializer` 在 `userRepository.count() > 0` 时直接 return，会导致已有用户但缺角色、缺系统设置、缺 UI Schema、缺系统集合时无法幂等补齐。
- `AclController.updateRoleResource()` 未复用 create 的 role/resource/deduplicate 校验，`updateAction()` 未检查 action 重名，scope 也缺少重复语义约束。
- `SystemSettingsController` 只对 object/array 做 JSON 序列化，number/boolean 再读出会变成 string，后续真实前端合同可能不稳定。
- `RolesController` 直接 `(Boolean) body.get("isDefault")`，前端若传 `"true"`、`1` 等兼容格式会 500，而不是 400 或正确解析。
- `UiSchemaController` 的 JSON parse/serialize 错误仍拼接原始 `e.getMessage()`，需要纳入脱敏规则。
- Phase7 总结中 P1-G、P2-H、P2-I 仍待后续，应作为本轮核心收口项。

## 二、下一批并行任务

### P0-A: 收紧 Collection Manager 权限入口

负责人: Claude-A  
可并行: 是  
依赖: 无

目标:
- 移除 collection/field 写接口的 `permitAll()`。
- collection manager 元数据写入、DDL dry-run、物理 DDL 必须由 admin 或明确 ACL 管理权限控制。
- 不改变前端接口路径和响应结构。

修改范围:
- `src/main/java/com/nocobase/config/SecurityConfig.java`
- `src/main/java/com/nocobase/controller/CollectionController.java`
- `src/main/java/com/nocobase/service/CollectionMetadataService.java`
- collection/field API 权限测试

验收标准:
- 未登录调用 `/api/collections:create`、`/api/collections:destroy`、`/api/fields:create`、`/api/fields:destroy`、`/api/collections:dryRun` 均返回 401/403。
- 普通 member 用户不能创建/删除 collection 或 field。
- admin 可继续执行 create/destroy/addField/dropField/dryRun。
- `resource:action` 与 `/resource/action` 双路由权限行为一致。
- view/sql collection 仍只改 metadata，不执行物理 DDL。

### P0-B: 生产启动与初始化治理

负责人: Claude-B  
可并行: 是  
依赖: 无

目标:
- 去掉生产环境硬编码默认管理员密码。
- 初始化从“一次性全量 return”改为按资源幂等补齐。
- 所有启动日志不得输出密码、token、连接串、密钥。

修改范围:
- `src/main/java/com/nocobase/config/DataInitializer.java`
- `src/main/resources/application.yml`
- 必要时新增 bootstrap 配置类
- 初始化/启动测试

验收标准:
- production/default profile 不再硬编码创建 `admin123`。
- 测试 profile 可保留测试账号，但必须通过 profile 或配置隔离。
- 当已有用户但缺 `admin/root/member` 角色时，启动能幂等补齐角色，不覆盖已有用户密码。
- 当已有用户但缺系统 collection/settings/uiSchema/plugins 时，启动能按项补齐。
- 控制台和日志不打印默认密码、token、数据库连接串或密钥。

### P0-C: 旧 PluginController 生命周期收敛

负责人: Claude-C  
可并行: 是  
依赖: 无

目标:
- 旧 `/api/plugins:*` 入口不能再绕过 `PluginModuleRegistry`。
- 系统必要插件在所有插件 API 中都不能被禁用、卸载、删除。

修改范围:
- `src/main/java/com/nocobase/controller/PluginController.java`
- `src/main/java/com/nocobase/controller/ApplicationPluginController.java`
- `src/main/java/com/nocobase/plugin/PluginModuleRegistry.java`
- 插件 API 兼容测试

验收标准:
- `/api/plugins:disable`、`/api/plugins:uninstall` 对系统插件返回 403。
- `/api/plugins:enable/disable/install/uninstall/list/enabled` 与 `applicationPlugins` 的状态来源一致，不能维护两套互相冲突的插件表。
- 如保留 `PluginEntity`，必须明确用途并与 `ApplicationPlugin` 同步；否则迁移到 registry 并废弃旧 repository 直写。
- 非系统插件生命周期仍可用，响应字段保持前端兼容。

### P0-D: ACL 管理更新路径语义补齐

负责人: Claude-D  
可并行: 是  
依赖: 无

目标:
- ACL create/update 两条路径必须执行同等语义校验。
- 防止重复 action/scope、非法 role/resource、非法字段权限进入数据库。

修改范围:
- `src/main/java/com/nocobase/controller/AclController.java`
- `src/main/java/com/nocobase/acl/AclService.java`
- `src/main/java/com/nocobase/repository/RoleResourceActionRepository.java`
- `src/main/java/com/nocobase/repository/RoleResourceScopeRepository.java`
- ACL 管理 API 测试

验收标准:
- `roleResources:update` 修改 `roleName/resourceName` 时校验存在性和重复组合。
- `roleResourceActions:update` 修改 action 时检查同一 roleResource 下不能重复。
- `roleResourceScopes:create/update` 同一 roleResource/action/scope 语义不能重复。
- 字段权限校验覆盖 create/update 两条路径。
- 所有 JSON/字段错误响应脱敏且稳定。

### P1-E: System Settings 类型合同补齐

负责人: Claude-E  
可并行: 是  
依赖: 无

目标:
- 系统设置持久化时保留 JSON 类型语义，避免 boolean/number 被读回 string。
- 保持旧 string 配置兼容。

修改范围:
- `src/main/java/com/nocobase/controller/SystemSettingsController.java`
- `src/main/java/com/nocobase/entity/SystemSettings.java`
- system settings API 测试

验收标准:
- 写入 `true` 读回 boolean，写入 `123` 读回 number，写入 object/array 读回原结构。
- 写入普通字符串仍读回字符串。
- 敏感 key 过滤继续大小写不敏感。
- 响应结构不变。

### P1-F: API 参数兼容与类型解析

负责人: Claude-F  
可并行: 是  
依赖: 无

目标:
- 系统模块 controller 对 NocoBase 前端常见参数格式更稳健，不能因类型转换抛 500。

修改范围:
- `src/main/java/com/nocobase/controller/UsersController.java`
- `src/main/java/com/nocobase/controller/RolesController.java`
- `src/main/java/com/nocobase/controller/AclController.java`
- 必要时新增 request parsing helper
- API 兼容测试

验收标准:
- id/filterByTk 支持 number 和 numeric string，非法值返回 400。
- `isDefault` 支持 boolean、`"true"`、`"false"`、`1`、`0`，非法值返回 400。
- roles 支持 id list，非法元素返回 400，不抛 500。
- page/pageSize 超限、负数、非数字时有稳定行为。
- 错误响应不包含 Java 类型转换异常原文。

### P1-G: 架构边界测试扩展

负责人: Claude-G  
可并行: 是，建议最后合并  
依赖: P0-A/P0-C/P0-D 基本完成后

目标:
- 把安全边界和代码分层边界固化为测试，防止后续回退。

修改范围:
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java`
- 必要时新增静态扫描 helper

验收标准:
- `SecurityConfig` 不允许 collection/field/plugin/system 管理写接口 `permitAll()`。
- Controller 不得直接注入 `JdbcTemplate`。
- 普通业务 Controller 不得直接注入 repository；系统 Controller 若短期例外，必须在测试白名单中说明。
- 只有 DDL/dialect 层允许生成 DDL。
- 只有 `DynamicRepository`、SQL executor、DDL 层、测试基建允许执行 SQL。
- Controller 不得把原始 `e.getMessage()` 直接返回给 client。

### P2-H: 前端 API 合同测试包

负责人: Claude-H  
可并行: 是  
依赖: P0/P1 修复后收口

目标:
- 不改前端，把真实前端依赖的后端 API shape 固化为合同测试。

范围:
- `users/roles/acl/applicationPlugins/plugins/uiSchemas/systemSettings/collections/fields/crud`
- `resource:action` 与 `/resource/action`
- query/body 混合参数、`filterByTk`、page/pageSize、sort/filter
- 成功和失败 envelope

验收标准:
- 每个系统模块至少覆盖读、写、权限拒绝、错误参数四类合同。
- slash 路由和 colon 路由行为一致。
- 合同测试不依赖前端仓库，不修改前端文件。
- 发现与前端不兼容的行为必须修后端，不允许调整前端。

### P2-I: UI Schema 存储结构与唯一性治理

负责人: Claude-I  
可并行: 是  
依赖: P0-B 已完成，可并行深化

目标:
- UI Schema 进入可长期维护状态，避免重复 uid、递归删除风险、schemaUid 混乱。

修改范围:
- `src/main/java/com/nocobase/entity/UiSchema.java`
- `src/main/java/com/nocobase/repository/UiSchemaRepository.java`
- `src/main/java/com/nocobase/controller/UiSchemaController.java`
- `src/main/java/com/nocobase/service/UiSchemaService.java`
- UI Schema storage 测试

验收标准:
- `uid`、必要时 `schemaUid` 建立唯一约束或应用层强校验。
- insert 时如果 `x-uid` 已存在必须 fail-fast。
- `schemaUid` 与 `uid` 的关系明确，不能所有新节点无条件 `schemaUid = uid` 而破坏前端语义。
- 递归删除支持深树，不因递归层级导致不可控失败。
- JSON parse/serialize 错误全部脱敏。

## 三、统一开发约束

- 前端不改，所有兼容问题必须由 Java 后端解决。
- collection/field、plugin、system settings、ui schema、ACL 都是系统管理入口，不能无鉴权写入。
- 公开数据 CRUD 继续统一走 `DynamicRepository`；系统管理模块可有 service，但边界必须清晰。
- DDL 只能走统一 DDL/dialect 层，view/sql collection 不执行物理 DDL。
- SQL collection 继续只读，scope/filter/sort/page/count 必须在外层安全拼装并参数化。
- 日志和错误响应不得泄漏完整 SQL、连接串、密码、token、密钥、默认值原文。
- 完成后运行 `mvn test`，并在 `PHASE8_BACKEND_HARDENING_COMPLETION_SUMMARY.md` 中列出任务状态、变更文件、测试命令、测试结果、未完成项。

## 四、建议开发顺序

1. Claude-A、Claude-B、Claude-C、Claude-D 先并行处理 P0。
2. Claude-E、Claude-F 同步补系统设置和参数兼容。
3. Claude-G 在主要改动合并后补架构边界测试。
4. Claude-H、Claude-I 做合同测试和 UI Schema 存储治理，作为 Phase8 收口。
