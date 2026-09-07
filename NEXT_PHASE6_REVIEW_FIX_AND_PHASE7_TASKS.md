# NocoBase Java 后端 - Phase6 Review Fix 与 Phase7 开发任务

> 日期: 2026-09-02  
> 范围: 只改 Java 后端、测试、后端文档；前端界面和前端代码保持不变。  
> Review 对象: `PHASE6_REVIEW_FIX_AND_SYSTEM_MODULES_COMPLETION_SUMMARY.md`  
> 本地验证: `mvn -q clean test` / Surefire 汇总 `673 tests, 0 failures, 0 errors, 0 skipped`。  
> 下一份完成总结: `PHASE7_SYSTEM_MODULES_COMPLETION_SUMMARY.md`

## 一、Review 结论

### 可以接受

- Phase6 已经把 `users`、`roles`、`acl`、`ui-schema-storage`、`system-settings`、`application-plugins` 从种子数据推进为后端系统模块骨架。
- 默认值模型、PostgreSQL 真实链路、插件 registry、用户/角色/ACL 管理 API 均已有测试覆盖，当前 673 个用例全绿。
- 前端文件未变更，接口仍保持 NocoBase 风格的 `resource:action` 与 `/resource/action` 双路由兼容。

### 必须继续修正

- `SystemSettingsController.update()` 目前没有显式 admin 校验，敏感 key 只做精确大小写匹配，且对象值被 `toString()` 扁平化，不能作为生产级系统设置模块。
- `UiSchemaController` 写接口缺少显式权限边界，`getTree()` 取第一个 root 不稳定，`afterBegin/beforeEnd` 子节点插入顺序仍需用兼容测试锁定。
- `PluginModuleRegistry.syncOnStartup()` 声明是 authoritative registry，但实际只补缺失记录，不修正已存在但被错误禁用、错误 `builtIn`、错误 `packageName/version` 的系统插件记录。
- `UsersController` / `RolesController` 仍直接使用 repository 并做内存分页，用户角色更新会先删除再重建，需要补最后 admin/root 保护和服务层边界。
- `AclController` 只校验 scope JSON 可解析，未校验 role/resource/action 语义、重复 action/scope、字段权限有效性，且错误信息仍拼接 `e.getMessage()`。
- Phase6 原任务中的 P2-H Collection Manager API 兼容增强、P2-I 架构边界测试扩展仍未完成，应进入下一批。

## 二、下一批并行任务

### P0-A: System Settings 安全与结构化存储

负责人: Claude-A  
可并行: 是  
依赖: 无

目标:
- `systemSettings:update` 必须显式要求 admin 权限。
- 系统设置支持结构化 JSON 值，不能把 object/array 变成 Java `toString()`。
- 敏感 key 过滤和拒绝更新必须大小写不敏感，并覆盖 `secret/password/token/privateKey/credential/jwt/database` 等路径片段。

修改范围:
- `src/main/java/com/nocobase/controller/SystemSettingsController.java`
- `src/main/java/com/nocobase/entity/SystemSettings.java`
- `src/main/java/com/nocobase/repository/SystemSettingsRepository.java`
- `src/test/java/com/nocobase/P1FixApiTest.java` 或新增 `SystemSettingsApiTest.java`

验收标准:
- 非 admin 调用 `/api/systemSettings:update` 返回 403。
- admin 可局部更新 `title/logo/locale/theme/storage` 等配置，未知字段保留。
- object/array 配置写入后再次读取仍是 JSON object/array，不是字符串。
- `JWT_SECRET`、`jwt.secret`、`database.password`、`accessToken`、`privateKey` 等 key 不能读取或更新。
- 错误响应不包含密钥原文、token 原文或完整异常栈。

### P0-B: UI Schema 写权限与插入语义加固

负责人: Claude-B  
可并行: 是  
依赖: 无

目标:
- UI Schema 读接口保持前端可用，写接口必须有明确权限边界。
- 修正并锁定 `beforeBegin/afterBegin/beforeEnd/afterEnd` 语义。
- 多 root、排序、递归删除、patch 深合并行为稳定。

修改范围:
- `src/main/java/com/nocobase/controller/UiSchemaController.java`
- `src/main/java/com/nocobase/service/UiSchemaService.java`
- `src/main/java/com/nocobase/repository/UiSchemaRepository.java`
- 新增或扩展 UI Schema API 测试

验收标准:
- 非 admin 或无 schema designer 权限的用户不能调用 `insertAdjacent/patch/remove`。
- `beforeBegin/afterEnd` 插入目标节点同级前后。
- `afterBegin` 插入为目标节点第一个子节点，`beforeEnd` 插入为目标节点最后一个子节点。
- `getTree()` root 选择稳定，按明确排序字段返回；存在多个 root 时行为有测试锁定。
- patch 保留未知 JSON 字段，remove 事务性删除整棵子树。

### P0-C: 插件 Registry 权威同步与生命周期 API

负责人: Claude-C  
可并行: 是  
依赖: 无

目标:
- 内置系统插件 registry 必须真正成为权威来源。
- 启动同步不仅补缺失记录，也要修正系统插件的关键元数据。
- 插件生命周期 API 与前端兼容，同时不能破坏必要模块。

修改范围:
- `src/main/java/com/nocobase/plugin/PluginModuleRegistry.java`
- `src/main/java/com/nocobase/controller/ApplicationPluginController.java`
- `src/main/java/com/nocobase/config/DataInitializer.java`
- 新增插件 registry/lifecycle 测试

验收标准:
- 启动时系统插件若已存在但 `enabled=false`、`installed=false`、`builtIn=false`、`packageName/version` 错误，必须自动校准。
- `/api/applicationPlugins:listEnabled` 一定返回全部 enabled 的系统必要插件。
- 禁用、卸载、删除系统插件返回 403，且数据库状态不变。
- 非系统插件可 enable/disable，响应字段包含 `name/packageName/enabled/installed/builtIn/version`。
- 同步逻辑幂等，多次启动不会重复插入。

### P0-D: 用户/角色模块服务层与账号保护

负责人: Claude-D  
可并行: 是  
依赖: 无

目标:
- 把用户、角色、用户角色关系从 controller 直接 repository 操作收敛到系统服务层。
- 补齐分页、排序、基础过滤，避免 `findAll()` 后内存分页。
- 防止删除或降权最后一个可用 admin/root 账号。

修改范围:
- `src/main/java/com/nocobase/controller/UsersController.java`
- `src/main/java/com/nocobase/controller/RolesController.java`
- 新增 `UserManagementService` / `RoleManagementService` 或复用已有服务
- 用户/角色 API 测试

验收标准:
- `users:list`、`roles:list` 支持 page/pageSize/sort/filter 或与当前前端请求兼容的最小子集。
- 列表分页在数据库层完成，不再读取全量记录后截取。
- 创建/更新角色时校验 name 格式和唯一性，保护 `root/admin/member` 内置角色。
- 删除用户、禁用用户、更新用户角色时不能让系统失去最后一个 admin/root 可登录账号。
- 密码永远不返回给前端，错误响应不泄漏密码、hash 或 token。

### P1-E: ACL 管理 API 语义校验

负责人: Claude-E  
可并行: 是  
依赖: P0-D 可并行开发，最终集成时对齐 role 服务

目标:
- ACL 管理 API 不仅能写表，还要保证配置语义有效。
- scope/filter、action、字段权限配置写入后能被 `DynamicRepository` 正确消费。

修改范围:
- `src/main/java/com/nocobase/controller/AclController.java`
- `src/main/java/com/nocobase/service/AclService.java`
- `src/main/java/com/nocobase/acl/AclFilterInjector.java`
- ACL API 与 DynamicRepository 集成测试

验收标准:
- 创建 roleResource 时校验 role 存在，resource 对应 collection 或系统资源存在。
- action 只允许 `list/get/create/update/destroy`，同一 roleResource 下 action 不允许重复。
- scope 的 action 只允许合法动作或明确的通配语义。
- readable/writable fields 必须是目标 collection 的字段，未知字段 fail-fast。
- scope JSON 错误返回稳定脱敏消息，不拼接原始 `e.getMessage()` 给 client。
- 配置变更后立即影响 `DynamicRepository` 的 action/scope/字段权限。

### P1-F: Collection Manager API 兼容增强

负责人: Claude-F  
可并行: 是  
依赖: 无

目标:
- 继续补齐 NocoBase 前端真实 collection manager 调用所需的后端兼容能力。
- 保持 physical/view/sql collection 的元数据边界和 DDL 边界。

修改范围:
- collection/field controller、metadata service、DDL plan 相关类
- `src/test/java/com/nocobase/ApiCompatibilityTest.java`
- 新增 Collection Manager API 兼容测试

验收标准:
- 创建 collection 时完整保留 `options/schema/filterTargetKey` 等未知扩展字段。
- 创建 field 时完整保留 `options/uiSchema/interface/sort/sourceKey/targetKey/foreignKey/through/otherKey`。
- physical collection 才执行建表/改表，view/sql collection 只保存 metadata。
- dry-run endpoint 只返回 action/table/column/index 摘要，不返回完整 SQL 或注释原文。
- 所有管理 API 响应 envelope 与现有前端兼容。

### P1-G: 架构边界测试扩展

负责人: Claude-G  
可并行: 是，建议最后合并  
依赖: 其他任务基本完成后收口

目标:
- 用测试防止后续开发再次把 SQL、DDL、权限、repository 操作写散。

修改范围:
- `src/test/java/com/nocobase/ArchitectureBoundaryTest.java`
- 必要时新增静态扫描 helper

验收标准:
- Controller 不得直接注入 `JdbcTemplate`。
- 普通业务 Controller 不得直接注入 repository；系统管理 Controller 若暂时例外，必须进入白名单并说明原因。
- 只有 DDL/dialect 层允许生成 DDL。
- 只有 `DynamicRepository`、SQL executor、DDL 层、测试基建允许执行 SQL。
- 不允许 controller 把未脱敏 `e.getMessage()` 直接返回给 client。
- 测试扫描 `src/main/java/com/nocobase`，新增违规代码时失败。

### P2-H: 前端 API 合同测试包

负责人: Claude-H  
可并行: 是  
依赖: P0-A/P0-B/P0-C 建议先合并

目标:
- 在不改前端的前提下，把后端对 NocoBase 前端依赖的 API shape 固化为测试。

范围:
- `resource:action` 与 `/resource/action` 两套路由。
- `filterByTk`、`page/pageSize`、`sort/filter`、body/query 参数混合。
- `{ data, meta, errors }` 或现有项目统一 envelope。
- users/roles/acl/applicationPlugins/uiSchemas/systemSettings/collections/fields。

验收标准:
- 每个系统模块至少有 list/get/create/update/destroy 或实际支持动作的合同测试。
- 错误响应状态码和 envelope 稳定。
- 合同测试不得依赖前端仓库或修改前端代码。

### P2-I: 生产启动与初始化治理

负责人: Claude-I  
可并行: 是  
依赖: P0-C/P0-D 最终需对齐

目标:
- 清理开发期初始化逻辑，避免生产环境默认凭据、重复种子、不可审计启动副作用。

修改范围:
- `src/main/java/com/nocobase/config/DataInitializer.java`
- Spring profile / application 配置
- 初始化与启动测试

验收标准:
- 生产 profile 不允许硬编码默认管理员密码。
- 首次初始化管理员凭据必须来自安全配置、一次性 bootstrap token 或明确安装流程。
- 启动日志不输出密码、token、数据库连接串或敏感配置。
- 种子数据幂等，已有用户/角色/插件/设置不会被错误覆盖。
- 测试 profile 可继续自动创建测试账号，但必须隔离于生产 profile。

## 三、统一开发约束

- 前端不改，不能通过修改前端规避后端兼容问题。
- 公开数据 CRUD 继续统一走 `DynamicRepository`；系统模块可以有 service，但权限语义必须明确。
- SQL collection 继续只读，scope/filter/sort/page/count 必须在外层安全拼装并参数化。
- DDL 只能走统一 DDL/dialect 层，view/sql collection 不执行物理 DDL。
- 所有错误响应和日志不得泄漏完整 SQL、连接串、密码、token、密钥、默认值原文。
- 完成后运行 `mvn test`，并在 `PHASE7_SYSTEM_MODULES_COMPLETION_SUMMARY.md` 中列出任务状态、变更文件、测试命令、测试结果、未完成项。

## 四、建议开发顺序

1. Claude-A、Claude-B、Claude-C、Claude-D 并行处理 P0。
2. Claude-E、Claude-F 与 P0 并行开发，但合并前对齐 service/ACL 边界。
3. Claude-G 在主要改动合并后补架构边界测试。
4. Claude-H、Claude-I 做合同测试和生产启动治理，作为 Phase7 收口。
