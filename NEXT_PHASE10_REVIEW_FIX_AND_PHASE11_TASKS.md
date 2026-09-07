# NocoBase Java 后端 - Phase10 Review Fix 与 Phase11 开发任务

> 日期: 2026-09-03  
> 范围: 只修改 Java 后端、测试、后端文档；前端界面和前端代码保持不变。  
> Review 对象: `PHASE10_RELEASE_HARDENING_COMPLETION_SUMMARY.md`  
> 本地核验: Surefire 报告为 `774 tests, 0 failures, 0 errors, 0 skipped`。  
> 下一份完成总结: `PHASE11_RELEASE_READINESS_COMPLETION_SUMMARY.md`

## 一、Review 结论

### 可以接受

- Phase10 已经把测试基线恢复到全绿，当前 surefire 汇总确认为 `774 tests, 0 failures, 0 errors, 0 skipped`。
- `ProductionConfigGuard` 已改为 `ApplicationEnvironmentPreparedEvent`，并通过 `META-INF/spring.factories` 注册，方向正确。
- JWT 配置名已统一到 `NOCOBASE_JWT_SECRET`，`JwtUtil` 和 Guard 都会拒绝空 secret、弱 secret、默认 secret。
- 外部数据源密码已引入 AES-256-GCM 加密，运行时按需解密，响应不返回 password 字段。
- 外部数据源 URL/driver allowlist、H2 危险参数拒绝、resolver cache invalidate 已完成基础闭环。
- AuthController 已收敛到 AuthService，Controller 不再直接依赖 UserRepository。
- Flyway V5 已补充关键唯一索引，DataInitializer 已开始复用系统插件 registry 思路，前端 trace replay 已接入测试。

### 必须继续修正

- `application.yml` 仍然内置 `nocobase.data-source-encryption.master-key` 默认值，这会导致非 test profile 即使没有外部 master key 也能启动；这和“生产必须外部配置密钥”的安全目标冲突，属于 P0。
- `ProductionConfigGuard` 当前只检查 JWT、H2 console、ddl-auto，尚未检查外部数据源加密 master key 是否缺失或是否使用开发默认值。
- `DataSourceConfigService#toResponseMap` 仍返回完整 JDBC URL 和 username。虽然 password 不返回，但 URL/用户名同样可能包含内网拓扑或账号信息，需要制定兼容前端的脱敏策略。
- `DataSourceConfigService#loadFromDatabase` 对启动加载异常 catch 过宽，可能把密钥错误、解密失败、配置非法等生产问题降级成 warning。
- Phase10 总结明确留下 `P2-J 审计日志`、`P2-K Release Readiness 文档` 未完成；这两项应进入下一批，不要继续后移。
- PostgreSQL 真实发布验收仍不够硬：当前 `mvn test` 主要证明 H2/MockMvc 通过，还需要把 metadata migration、SQL collection、多数据源路径放进可重复的 PostgreSQL 验收。

## 二、下一批并行任务

### P0-A: 修复数据源加密 master key 生产默认值

负责人: Claude-A  
可并行: 是  
依赖: 无  
目标:
- 移除 `application.yml` 中硬编码的 `nocobase.data-source-encryption.master-key`。
- dev/test 可有隔离的开发密钥；prod/non-test 必须显式配置真实 master key。

修改范围:
- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- `src/test/resources/` 下相关测试配置
- `src/main/java/com/nocobase/service/DataSourcePasswordEncryptor.java`
- `src/main/java/com/nocobase/config/ProductionConfigGuard.java`
- `BACKEND_OPERATION_GUIDE.md`

验收标准:
- 默认 `application.yml` 只能使用 `${NOCOBASE_DATA_SOURCE_ENCRYPTION_MASTER_KEY:}` 或等价空占位，不允许出现可用明文密钥。
- dev/test profile 的开发密钥必须只在 dev/test 配置中出现，且文档明确不能用于生产。
- 非 test profile 缺少 master key 时 fail-fast；使用已知开发默认 master key 时也 fail-fast。
- 日志和异常信息不输出 master key 原文。
- 增加测试覆盖: 缺失 key、非法 base64、长度不足、开发默认 key、合法 32-byte key。

### P0-B: 收紧 Production Guard 覆盖与启动测试

负责人: Claude-B  
可并行: 是  
依赖: P0-A 可并行开发，最终合并时对齐 key 规则  
目标:
- 确认所有生产危险配置都在 Web 服务启动前失败。
- 避免“单元测试直接调用方法通过，但真实 Spring Boot 启动链路未覆盖”的风险。

修改范围:
- `src/main/java/com/nocobase/config/ProductionConfigGuard.java`
- `src/main/resources/META-INF/spring.factories`
- `src/test/java/com/nocobase/config/` 新增或完善 Guard 启动链路测试

验收标准:
- 测试验证 `spring.factories` 能加载 `ProductionConfigGuard`。
- 测试验证缺 JWT secret、弱 JWT secret、H2 console 非 dev/test 启用、`ddl-auto=update/create/create-drop`、缺数据源 master key 都会在早期事件阶段失败。
- test profile 明确跳过生产 guard，但 `JwtUtil`/加密器自身测试仍覆盖配置合法性。
- 完成总结中必须说明“早期事件注册方式”和“实际测试方式”。

### P0-C: 建立 Release Readiness 清单与发布门禁

负责人: Claude-C  
可并行: 是  
依赖: P0-A/P0-B 最终补充配置项  
目标:
- 补齐 Phase10 遗留的 `RELEASE_READINESS_CHECKLIST.md`。
- 把后端交付从“测试数量增长”升级为“固定发布门禁”。

修改范围:
- 新增 `RELEASE_READINESS_CHECKLIST.md`
- `BACKEND_OPERATION_GUIDE.md`
- 必要时新增轻量检查脚本或 JUnit smoke test

验收标准:
- 清单覆盖配置、密钥、数据库、Flyway、ACL、SQL collection、外部数据源、日志脱敏、前端合同、PostgreSQL 验收、回滚策略。
- 明确交付前必须运行的命令: `mvn test`、相关 PostgreSQL 验收命令、前端 trace replay 测试。
- 明确哪些 skipped test 可以接受；默认要求 `0 failures, 0 errors`，skipped 必须有原因。
- 文档不得要求修改前端。

### P0-D: 审计日志系统模块

负责人: Claude-D  
可并行: 是  
依赖: 无  
目标:
- 补齐 Phase10 遗留的审计日志能力，用于生产追踪管理操作。

范围:
- collection/field create/update/drop/dryRun
- plugin install/enable/disable/uninstall/delete
- users/roles/acl/systemSettings/uiSchemas/dataSources 写操作

修改范围:
- 新增审计日志 entity/repository/service/migration
- 在相关 service 层写入审计事件
- 必要时新增 admin-only 查询 API，保持前端不变

验收标准:
- 审计记录包含 actor userId、action、resource、resourceKey、status、createdAt、requestId 或 traceId。
- 审计记录不得保存 password、token、secret、完整 SQL、完整 JDBC URL、外部数据源密码。
- 主业务成功但审计写失败时的策略必须明确；建议默认主业务失败并返回稳定错误，除非文档说明采用 best-effort。
- 增加成功和失败操作的审计测试。
- 不能绕过现有 ACL/角色权限。

### P1-E: 外部数据源响应脱敏与兼容策略

负责人: Claude-E  
可并行: 是  
依赖: P0-A 最终配置命名  
目标:
- 不破坏现有前端调用的前提下，降低 dataSources API 泄露 JDBC URL、username、内网信息的风险。

修改范围:
- `src/main/java/com/nocobase/service/DataSourceConfigService.java`
- `src/main/java/com/nocobase/controller/DataSourceController.java`
- data source API 测试
- `BACKEND_OPERATION_GUIDE.md`

验收标准:
- list 接口默认不返回 password，不返回完整嵌入凭据的 JDBC URL。
- 如果为了兼容前端仍保留 `url`/`username` 字段，必须保证不会把密码、token、secret、embedded credentials 返回。
- 新增 `hasPassword`、`maskedUrl`、`maskedUsername` 等兼容字段时，不要求前端立刻使用。
- update 接口必须支持“未传 password 表示保留原密码”，不能因为前端拿到脱敏值后误覆盖真实密码。
- 测试覆盖 list/get/create/update/testConnection 的敏感字段脱敏。

### P1-F: PostgreSQL 发布验收硬化

负责人: Claude-F  
可并行: 是  
依赖: 无  
目标:
- 把真实 PostgreSQL 从“可选集成测试”提升为发布前必须执行的验收路径。

修改范围:
- `src/test/java/com/nocobase/postgresql/`
- `src/main/resources/db/migration/`
- `RELEASE_READINESS_CHECKLIST.md`
- 必要时 Maven profile 或测试说明

验收标准:
- 空 PostgreSQL 数据库可完整执行 Flyway V1-V5。
- 唯一索引、外键策略、metadata 表结构在 PostgreSQL 中实际验证。
- SQL collection 在 PostgreSQL 主库和 PostgreSQL 外部只读数据源上都能通过 list/get/count/filter/sort/page/scope 测试。
- 测试失败信息必须脱敏，不输出连接串、账号、密码。
- 如果本地无 PostgreSQL 环境，完成总结必须写明使用的环境变量和跳过原因；发布清单中不得把 PostgreSQL 验收视为可选。

### P1-G: 前端真实 trace replay 扩展

负责人: Claude-G  
可并行: 是  
依赖: 无  
目标:
- 继续扩大“不改前端”的后端兼容覆盖，避免只靠手写样例。

修改范围:
- `src/test/resources/frontend-traces/`
- `src/test/java/com/nocobase/ApiCompatibilityTest.java`
- trace schema 文档

验收标准:
- 至少覆盖登录、auth check/user、system settings、application plugins、ui schema、collection manager、field manager、基础 CRUD、ACL 查询、data sources 管理只读路径。
- trace 支持变量提取、请求间依赖、负向断言、字段 shape 验证。
- 失败报告必须定位到 trace 文件名、step name、请求 path、缺失字段。
- 不修改前端代码，不要求前端构建。

### P1-H: 系统模块 API 兼容缺口清单

负责人: Claude-H  
可并行: 是  
依赖: 无  
目标:
- 对照 NocoBase 必要插件，把 Java 后端已实现 API、缺失 API、语义不一致 API 列清楚，并将 P12 任务拆分为可开发项。

范围:
- auth、acl、users、roles、collection-manager、data-source-main、ui-schema-storage、system-settings、application-plugins

产出:
- 新增 `SYSTEM_MODULE_API_GAP_ANALYSIS.md`
- 在文档中给出下一轮可并行任务建议

验收标准:
- 每个系统模块列出 endpoint、请求方法、请求体、响应 shape、权限要求、当前状态。
- 明确哪些 endpoint 已通过 trace/API 测试，哪些只有 service 测试，哪些尚未实现。
- 不安排前端改造项，所有兼容差异都落到 Java 后端。

## 三、统一开发约束

- 前端保持不变，所有兼容问题由 Java 后端解决。
- 公共 CRUD 继续统一走 `DynamicRepository`，ACL、scope、字段权限不能被 relation/association/data source 旁路绕过。
- SQL collection 保持只读，filter/sort/page/count/scope 必须在外层安全拼装并参数化。
- 元数据 schema 由 Flyway 管理，动态业务 collection 仍由 DDL/dialect 层管理。
- 生产默认配置必须安全，开发便利只能放在 dev/test profile。
- 所有新增文档和完成总结必须写明测试命令、测试结果、变更文件、未完成项。

## 四、建议开发顺序

1. Claude-A、Claude-B 优先修生产密钥与 Guard，因为这是发布安全门槛。
2. Claude-C 同步补 Release Readiness，等 A/B 合并后补齐最终配置项。
3. Claude-D 开始审计日志模块，和密钥任务可以并行。
4. Claude-E、Claude-F 分别处理数据源脱敏和 PostgreSQL 验收。
5. Claude-G、Claude-H 扩展前端合同 trace 和系统模块 API 缺口，为下一批功能收口做输入。
