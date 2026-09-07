# SQL Collection Phase 3 Real Datasource Review Fixes - Next Development Tasks

> 面向 Claude 的后端开发任务清单。
> 前端界面、前端路由、现有 API 响应结构不能改变；本批任务只改 Java 后端。

## 0. Review 结论

`SQL_COLLECTION_PHASE3_REAL_DATASOURCE_COMPLETION_SUMMARY.md` 对应实现整体可以接受，真实外部数据源读取链路已经初步打通：

- `SqlDataSourceResolver` 已从单主库骨架扩展为 datasource 注册表。
- `nocobase.data-sources.*` 配置模型已存在。
- SQL collection 可以通过 `dataSourceKey=analytics` 从第二个 H2 数据源读取数据。
- ACL scope、字段权限、filter、sort、pagination 仍从 `DynamicRepository` 收口。
- `mvn -q test` 已复跑通过，surefire 报告为 `392 tests, 0 failures, 0 errors`。

但本轮还有几个架构风险必须先修：

- `SqlQueryCollectionExecutor.validateFields()` 用 `configuredSql.replaceAll(":\\w+", "0")` 处理参数，会误伤字符串字面量、URL、时间文本和 PostgreSQL `::type` cast，也绕开了现有 `SqlNamedParameterParser`。
- `validateFields()` 的 debug 日志仍可能输出完整异常堆栈，实际测试日志中已经出现 `SELECT * FROM (SELECT * FROM "nonexistent_table_xyz" ...)` 原文。
- 外部 `HikariDataSource` 被缓存后没有关闭生命周期，应用关闭、测试上下文销毁或配置刷新时会泄露连接池资源。
- `SqlDataSourceResolver.resolve()` 创建 `JdbcTemplate` 时没有主动获取连接验证，坏 URL 可能直到字段校验或查询阶段才暴露，`isAvailable()` 对坏连接也会误报可用。
- `resolveDialect()` 对未知或缺失 dialect 默认回退 H2，真实多数据源下这会掩盖配置错误。
- 外部 datasource 的 `url/driverClassName/dialect` 等必填项缺少统一配置校验。
- `unavailableDataSources` 当前是永久标记，临时网络故障后没有生产可用的恢复策略。

## 1. 全局架构约束

- 不修改前端代码，不改变现有 API 的 path、query 参数、响应 envelope、分页结构、字段名。
- 公开数据访问继续统一走 `DynamicRepository`；controller、relation、association 不允许直接访问 `JdbcTemplate`。
- SQL collection 继续只读，`create/update/destroy` 必须拒绝。
- 主数据源继续承载 metadata、ACL、用户、角色、插件状态等系统表。
- 外部数据源只能作为 SQL collection 的只读查询数据源，不参与 JPA、Flyway、DDL synchronizer。
- SQL collection 的 ACL scope、field permission、filter、sort、pagination 必须继续由 Java 后端统一追加和绑定。
- 禁止拼接运行时参数值；所有 value 必须通过 JDBC 参数绑定或安全的 typed dummy binding。

## 2. P0-A: 修复 validateFields 参数处理

### 目标

字段元数据校验必须复用现有 SQL 命名参数解析器，不能用正则替换 SQL 文本。

### 开发要求

- 移除 `configuredSql.replaceAll(":\\w+", "0")`。
- 使用 `SqlNamedParameterParser.parse(configuredSql)` 生成 JDBC `?` SQL 与 ordered parameter names。
- 基于 `SqlParameterMetadata` 构造校验用 dummy values：
  - `string` 使用空字符串或固定字符串。
  - `number` 使用 `0`。
  - `boolean` 使用 `false`。
  - `date` 使用固定 `java.sql.Date`。
  - `datetime` 使用固定 `java.sql.Timestamp`。
- dummy values 只用于 `WHERE 1=0` 的字段元数据校验，不代表真实业务上下文。
- `currentUser` 参数在 metadata validation 中不能依赖真实登录态，必须按声明 type 构造 dummy value。
- 重复参数必须按 parser 返回顺序重复绑定。
- validation SQL 仍包裹成 `SELECT * FROM (...) _nocobase_sub WHERE 1=0`，但参数值必须走 JDBC binding。

### 测试要求

- SQL 中字符串字面量含 `:notParam` 时不能被替换。
- SQL 中 URL 字符串如 `'http://a:b'` 不能被替换。
- SQL 中 PostgreSQL cast 如 `:userId::bigint` 不能被破坏。
- repeated named parameter 绑定顺序正确。
- `currentUser` required 参数在 `loadAll/reload` 字段校验阶段不要求登录态。

### 验收标准

- `validateFields()` 不再通过正则修改 SQL 语义。
- 字段校验仍能发现字段元数据与结果列不一致的问题。

## 3. P0-B: 消除 validateFields 日志 SQL 泄露

### 目标

字段校验失败时，默认日志和 debug 日志都不能输出 SQL 原文、参数值或完整数据库错误 SQL statement。

### 开发要求

- 删除或改造 `log.debug("Field validation failed ... SQL=[{}] ...")` 这类日志，不记录 validation SQL。
- 不在 debug/error/warn 级别直接传原始异常 `e`，因为驱动堆栈里可能含完整 SQL。
- 如需要 debug 排障，只允许记录：
  - collection name。
  - dataSourceKey。
  - sanitized error category。
  - exception class。
- `SqlErrorSanitizer.sanitizeForLog()` 不应保留物理表名/字段名，除非确认日志不会对租户或普通运维暴露；本项目建议先统一隐藏。
- `CollectionRuntimeService`、`SqlQueryCollectionExecutor`、`SqlDataSourceResolver` 的错误日志策略保持一致。

### 测试要求

- 增加日志捕获测试，验证字段校验失败时日志不包含：
  - `SELECT` / `WITH` / `FROM`。
  - 物理表名，如 `nonexistent_table_xyz`。
  - 参数值。
  - JDBC URL、username、password。
- 如暂时没有日志捕获工具，至少增加 sanitizer 覆盖，并在完成总结中说明未做日志捕获测试的原因。

### 验收标准

- 复跑 `mvn -q test` 时，不再出现 SQL collection 校验失败导致的完整 SQL statement 堆栈。

## 4. P0-C: 外部 DataSource 生命周期管理

### 目标

外部数据源连接池必须可关闭，不能因为 lazy registry 缓存造成资源泄露。

### 开发要求

- `SqlDataSourceResolver` 缓存的对象不要只保存 `JdbcTemplate`，需要保存可关闭的 datasource holder。
- 为 resolver 增加 Spring 生命周期钩子：
  - `@PreDestroy` 或实现 `DisposableBean`。
  - 关闭所有外部 `HikariDataSource`。
- `clearUnavailable()` 如果继续用于测试，也必须关闭并清理已创建的外部连接池，不能只 `registry.clear()`。
- `mainJdbcTemplate` 对应 datasource 由 Spring Boot 管理，resolver 不负责关闭主库。

### 测试要求

- resolver 创建外部 datasource 后，调用清理方法会关闭外部 `HikariDataSource`。
- 多个外部 datasource 都会被关闭。
- 主数据源不会被 resolver 关闭。

### 验收标准

- 外部 datasource 注册表具备明确生命周期，不泄露连接池。

## 5. P0-D: 外部 DataSource 配置与连接校验

### 目标

外部 datasource 的配置错误和连接错误必须在 SQL collection runtime load/reload 阶段稳定暴露并进入 invalid tracking。

### 开发要求

- 对外部 datasource 配置增加必填校验：
  - `url` 必填。
  - `driverClassName` 建议必填；如允许省略，必须能从 URL 推导并有测试。
  - `dialect` 必须是受支持枚举：`h2`、`postgresql`。
  - `enabled=false` 不能被 SQL collection 使用。
- `resolve()` 或专门的 `validateConnection(dataSourceKey)` 必须主动获取连接验证，不要仅创建 `JdbcTemplate`。
- 连接验证失败必须：
  - 标记该 datasource 暂时不可用。
  - 抛出脱敏异常。
  - 让引用它的 SQL collection 进入 invalid collection。
- `isAvailable(dataSourceKey)` 不能只看配置存在和 enabled，必须反映实际可连接状态或最近验证状态。

### 测试要求

- 缺少 URL 的 datasource 配置被拒绝。
- 未知 dialect 被拒绝，不能回退 H2。
- disabled datasource 被 SQL collection 引用时进入 invalid collection。
- 坏 JDBC URL 被 SQL collection 引用时进入 invalid collection，主系统和其他 collection 正常。
- 错误信息不包含完整 JDBC URL、username、password。

### 验收标准

- 配置错、连接错、禁用 datasource 三类失败都有确定行为和测试。

## 6. P0-E: 调整不可用 DataSource 恢复策略

### 目标

临时连接失败不能永久污染 datasource 状态，修复配置或数据库恢复后必须有后端恢复路径。

### 开发要求

- 不要让 `unavailableDataSources` 永久阻止后续连接尝试。
- 选择一种简单恢复策略：
  - 方案 A：每次 `reload(collectionName)` 对对应 `dataSourceKey` 允许重新验证。
  - 方案 B：给 unavailable 状态增加短 TTL。
  - 方案 C：提供后端内部 `refresh(dataSourceKey)` 方法，由 runtime reload 调用。
- 推荐方案 A 或 C，保持行为可测试、简单。
- datasource 恢复成功后：
  - 清理 unavailable 状态。
  - SQL collection reload 成功。
  - invalid collection entry 被移除。

### 测试要求

- 首次连接失败后，collection invalid。
- 修复 datasource 配置或恢复 H2 数据库后，调用 `runtimeService.reload(collectionName)` 能重新加载成功。
- 恢复后 `isAvailable(dataSourceKey)` 为 true。

### 验收标准

- 不需要重启应用即可从外部 datasource 临时故障中恢复。

## 7. P1-F: 方言选择不能静默回退

### 目标

未知方言必须显式失败，不能默认 H2 掩盖配置错误。

### 开发要求

- `resolveDialect(dataSourceKey)` 对外部 datasource：
  - 配置了未知 `dialect` 时抛出明确配置错误。
  - 未配置 dialect 时可按 URL 推导。
  - URL 也无法推导时抛出明确配置错误。
- 主数据源可以从 `spring.datasource.url` 推导；无法推导时才允许使用项目启动默认方言，但需要记录清晰边界。
- 方言错误进入 invalid collection，不影响其他 collection。

### 测试要求

- `dialect=mysql` 当前被拒绝。
- 未配置 dialect 但 URL 为 H2/PostgreSQL 时能推导。
- 无法推导时 invalid collection。

### 验收标准

- 多数据源方言行为可预测，不再静默回退 H2。

## 8. P1-G: SQL 查询治理入口

### 目标

外部 datasource 查询必须有基本治理能力，防止慢查询或超大分页拖垮后端。

### 开发要求

- 增加 SQL collection 查询配置边界：
  - `maxPageSize`：默认 200 或沿用项目现有分页上限。
  - `queryTimeoutSeconds`：默认 30 秒，可按 datasource 或全局配置。
  - `validationTimeoutSeconds`：字段校验默认更短，例如 5 秒。
- `DynamicRepository.list()` 或 SQL executor 必须限制 page/pageSize：
  - `page < 1` 规范化或拒绝，按现有 API 语义决定。
  - `pageSize` 超过上限时截断或拒绝，不能改变前端成功响应结构。
- `JdbcTemplate` 或 statement 层设置 query timeout。
- 字段校验 SQL 也必须有 timeout，避免启动/reload 被慢外部库拖住。

### 测试要求

- pageSize 超过上限时行为明确且有测试。
- SQL query timeout 配置被应用到外部 datasource。
- validation timeout 配置被应用。
- 不改变 list 响应结构。

### 验收标准

- SQL collection 查询具备最小生产保护。

## 9. P1-H: 外部 datasource 只读边界验证

### 目标

只读不仅依赖 SQL validator，也要在 datasource/connection 层尽量加固。

### 开发要求

- 外部 `HikariDataSource` 继续设置 read-only。
- 对外部 datasource 创建连接后校验 `Connection.isReadOnly()`，如驱动不支持需记录并测试 fallback。
- SQL validator 的 DML/DDL 禁止规则继续保留。
- 增加对 CTE 内 DML、`SELECT ... INTO`、数据库特定写语法的 deny list 评估；当前至少补充明确测试。

### 测试要求

- 外部 datasource connection read-only 状态被设置。
- SQL collection 配置 DDL/DML 仍 invalid。
- CTE 内包含写操作的 SQL 被拒绝或明确说明当前 validator 行为。

### 验收标准

- 外部 SQL collection 的只读边界有 datasource 层和 validator 层双重保障。

## 10. P2-I: SQL collection 可观测性内部指标

### 目标

为后续生产运行提供内部可观测性，不暴露新前端页面。

### 开发要求

- 增加内部日志或轻量 metrics 入口：
  - collection name。
  - dataSourceKey。
  - action：list/get/validateFields。
  - durationMs。
  - rowCount/pageSize。
  - success/failure category。
- 不记录 SQL 原文、参数值、用户输入 filter 原文。
- 如果项目暂未接入 Micrometer，可先用结构化日志。

### 测试要求

- 至少验证成功/失败日志不包含 SQL 和参数值。
- 不改变 API 响应。

### 验收标准

- 后端可以定位慢 SQL collection 和失败数据源，同时不泄露敏感信息。

## 11. P2-J: 文档更新

### 目标

让设计文档与修复后的真实行为一致。

### 开发要求

- 更新 `SQL_QUERY_COLLECTION_DESIGN.md`：
  - 字段校验使用 parser + dummy binding，不再正则替换。
  - 外部 datasource 生命周期、恢复策略、连接校验策略。
  - 方言选择失败策略。
  - 查询治理默认值。
  - 日志脱敏边界。
- 更新完成总结模板，要求列出：
  - 是否修改前端。
  - 是否改变 API。
  - 是否出现 SQL/参数/JDBC URL 日志泄露。
  - datasource 恢复策略。

### 验收标准

- 文档不再描述已经废弃的行为或不安全实现。

## 12. 建议并行分工

- Claude 任务 1：P0-A + P0-B，修字段校验参数处理和日志泄露。
- Claude 任务 2：P0-C，修外部连接池生命周期。
- Claude 任务 3：P0-D + P0-E，修 datasource 配置/连接校验和恢复策略。
- Claude 任务 4：P1-F，修方言选择失败策略。
- Claude 任务 5：P1-G + P1-H，做查询治理和只读边界加固。
- Claude 任务 6：P2-I + P2-J，补可观测性和设计文档。

依赖关系：

- P0-A/P0-B/P0-C 可以并行。
- P0-D 依赖 P0-C 的 datasource holder 设计更稳。
- P0-E 依赖 P0-D。
- P1-F 可独立做，但需要和 P0-D 的配置校验保持一致。
- P1-G/P1-H 建议在 P0-A 到 P0-E 合并后做。

## 13. 提交与完成要求

- 每个任务完成后运行相关测试；最终合并前运行 `mvn -q test`。
- 完成后输出总结文档：`SQL_COLLECTION_PHASE3_REAL_DATASOURCE_REVIEW_FIX_COMPLETION_SUMMARY.md`。
- 总结文档必须包含：
  - 修改文件列表。
  - 新增/调整测试列表。
  - `mvn -q test` 结果。
  - 是否修改前端，预期答案必须为否。
  - 是否改变 API 响应结构，预期答案必须为否。
  - 是否仍存在 SQL 原文、参数值、JDBC URL、用户名、密码日志泄露。
  - 外部 datasource 生命周期与恢复策略说明。
