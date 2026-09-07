# SQL Collection Phase 4 Production Governance - Next Development Tasks

> 面向 Claude 的后端开发任务清单。
> 前端界面、前端路由、现有 API 响应结构不能改变；本批任务只改 Java 后端。

## 0. Review 结论

`SQL_COLLECTION_PHASE3_REAL_DATASOURCE_REVIEW_FIX_COMPLETION_SUMMARY.md` 对应实现整体可以接受，真实多数据源 SQL collection 链路已经进入可运行状态：

- `SqlDataSourceResolver` 已支持外部 datasource 注册表与第二 H2 数据源集成测试。
- `validateFields()` 已从正则替换改为 `SqlNamedParameterParser` + typed dummy binding。
- 外部 datasource 支持连接验证、恢复、关闭生命周期。
- SQL collection 查询治理已有 `maxPageSize/queryTimeout/validationTimeout` 入口。
- `mvn -q test` 已复跑通过，surefire 报告为 `422 tests, 0 failures, 0 errors`。

但进入下一阶段前，仍有几个生产级风险需要修复：

- `SqlQueryCollectionExecutor.executeList/executeGet` 和 `SqlDataSourceResolver.resolve()` 仍存在 `log.debug(..., e)` 原始异常堆栈，debug 日志可能重新泄露 SQL、参数、JDBC URL。
- 外部 datasource 创建或连接验证失败时，需要确认临时创建的 `HikariDataSource` 一定关闭，否则失败路径会泄露连接池资源。
- `JdbcTemplate.setQueryTimeout()` 是共享实例可变状态，list/get/validateFields 并发时 query timeout 与 validation timeout 会互相覆盖。
- `pageSize <= 0` 未治理；relation/appends 内部使用 `Integer.MAX_VALUE`，对 SQL collection 会被 cap 到 200，可能静默截断关联数据。
- 字段元数据不匹配异常包含 `Available columns`，会把外部 datasource schema 细节写入 invalid reason 或日志。
- `quoteIdentifier()` 只是拼接双引号，缺少统一 identifier 校验/转义边界；relation foreignKey/sourceKey/targetKey 也需要纳入校验。
- 默认 `application.yml` 示例仍展示 unsupported `mysql` datasource，容易误导后续实现。
- PostgreSQL 方言目前主要是代码实现和单元测试，还没有独立 PostgreSQL 集成验收。

## 1. 全局架构约束

- 不修改前端代码，不改变现有 API 的 path、query 参数、响应 envelope、分页结构、字段名。
- 公开数据访问继续统一走 `DynamicRepository`；controller、relation、association 不允许直接访问 `JdbcTemplate`。
- SQL collection 继续只读，`create/update/destroy` 必须拒绝。
- 主数据源继续承载 metadata、ACL、用户、角色、插件状态等系统表。
- 外部 datasource 只能作为 SQL collection 的只读查询数据源，不参与 JPA、Flyway、DDL synchronizer。
- SQL collection 的 ACL scope、field permission、filter、sort、pagination 必须继续由 Java 后端统一追加和绑定。
- 禁止拼接运行时参数值；所有 value 必须通过 JDBC 参数绑定。

## 2. P0-A: 清除 SQL/datasource 原始异常日志

### 目标

SQL collection 和外部 datasource 路径中，任何日志级别都不能直接输出原始异常堆栈。

### 开发要求

- 移除或改造以下模式：
  - `log.debug("SQL execution error details ...", e)`。
  - `log.debug("Data source ... creation failure details", e)`。
  - SQL collection/datasource 异常继续向上抛出时，不要携带会被 `GlobalExceptionHandler` 打印的原始 cause。
- 新增统一异常类型或错误对象，例如：
  - `SqlCollectionExecutionException`：client message 固定，log category 可控。
  - `DataSourceUnavailableException`：包含 dataSourceKey 与 sanitized reason，不包含 raw cause。
- `GlobalExceptionHandler` 对这类异常只记录 sanitized message，不打印 raw stack。
- 保留开发排障信息时，只允许输出：
  - collection name。
  - dataSourceKey。
  - action。
  - sanitized category。
  - exception class name。
- 不记录 SQL 原文、绑定参数、JDBC URL、username、password。

### 测试要求

- 增加日志捕获测试，覆盖：
  - SQL runtime 查询失败。
  - validateFields 失败。
  - 外部 datasource 创建失败。
- 日志断言不包含：
  - `SELECT`、`WITH`、`FROM`。
  - 测试物理表名。
  - JDBC URL、端口、username、password。
- controller 响应 envelope 不变。

### 验收标准

- `mvn -q test` 输出中不再因为 SQL/datasource 失败场景出现完整 SQL statement 或 raw JDBC stack。

## 3. P0-B: 修复失败路径 DataSource 资源泄露

### 目标

外部 datasource 无论创建成功、连接失败、只读验证失败、刷新失败，都必须关闭临时连接池。

### 开发要求

- `SqlDataSourceResolver.createExternalDataSourceHolder()` 或调用方必须保证：
  - 创建 holder 后，任何后续验证失败都会关闭 holder 内的 `HikariDataSource`。
  - 未放入 registry 的 datasource 不会悬挂。
- `refreshDataSource()`：
  - 关闭旧 holder。
  - 新 holder 创建失败时关闭新 holder。
  - 不影响 main datasource。
- 并发 `resolve(dataSourceKey)` 要避免重复创建多个连接池：
  - 可以使用 per-key lock、`computeIfAbsent` 配合显式失败清理，或更简单的 synchronized 临界区。
- `destroy()` 和 `clearUnavailable()` 关闭 registry 后要清空 registry，并能重复调用不报错。

### 测试要求

- 创建失败后 `HikariDataSource.isClosed()` 为 true。
- 连接验证失败后临时 datasource 被关闭。
- 并发 resolve 同一个 key 时只创建一个外部 datasource。
- `destroy()`、`clearUnavailable()` 重复调用安全。

### 验收标准

- 成功路径、失败路径、并发路径、销毁路径都没有外部连接池泄露。

## 4. P0-C: 改为操作级 query timeout

### 目标

不能通过修改共享 `JdbcTemplate` 实例来设置 query timeout。

### 开发要求

- 移除 `jdbc.setQueryTimeout(queryTimeoutSeconds)` 和 `jdbcTemplate.setQueryTimeout(validationTimeoutSeconds)` 这类运行时修改。
- 为 SQL executor 增加内部查询执行 helper：
  - list count query 设置 query timeout。
  - list data query 设置 query timeout。
  - get query 设置 query timeout。
  - validateFields query 设置 validation timeout。
- 推荐使用 `PreparedStatementCreator` 或 `JdbcTemplate.execute`，在每个 statement 上调用 `setQueryTimeout()`。
- helper 必须继续保持参数绑定顺序：
  - named SQL params。
  - ACL/filter params。
  - pagination params。
- 不改变 `SqlQueryPlan` 对外测试含义。

### 测试要求

- 用 mock/fake JdbcTemplate 或可观测 statement 验证：
  - list/get 使用 query timeout。
  - validateFields 使用 validation timeout。
  - 并发 list 与 validateFields 不会互相覆盖 timeout。
- 旧 SQL collection 集成测试全部通过。

### 验收标准

- timeout 是 statement 级或操作级，不再污染共享 JdbcTemplate。

## 5. P0-D: 完整治理 page/pageSize 与 relation appends 批量

### 目标

分页参数和 relation appends 不能导致负数 limit、超大查询或静默截断。

### 开发要求

- SQL collection list：
  - `page < 1` 统一规范化为 1。
  - `pageSize < 1` 统一规范化为默认值或最小值 1，需保持 API 响应结构不变。
  - `pageSize > maxPageSize` 继续 cap。
  - `maxPageSize <= 0` 配置必须 fail-fast 或回退安全默认值，并有日志说明。
- physical collection 也要评估是否需要同样治理，避免 SQL 与 physical 行为不一致。
- relation/appends 当前使用 `Integer.MAX_VALUE` 查询目标：
  - 不能依赖 SQL executor cap 后静默只返回前 200 条。
  - 改为分批 `$in` 查询或引入 internal relation batch limit。
  - 对 belongsTo/hasOne/hasMany/belongsToMany 分别测试大于 200 的关联数据。
- 不改变前端 appends 返回结构。

### 测试要求

- SQL collection `pageSize=0`、负数、超大值都有明确行为。
- physical collection 如同步治理，也需要相同测试。
- SQL collection 作为 relation target 时，append 超过 200 条不会静默丢数据。
- association list 不因内部 cap 返回不完整数据。

### 验收标准

- 分页边界稳定，relation/appends 不被 SQL maxPageSize 意外截断。

## 6. P0-E: 避免外部 schema 细节泄露

### 目标

字段元数据校验失败时，不向前端、invalid reason、默认日志暴露外部 datasource 的表名、字段名、列清单。

### 开发要求

- `validateFields()` 字段不匹配异常不能包含 `Available columns`。
- client-facing message 使用稳定泛化描述，例如 `SQL collection field metadata does not match query result`。
- log message 可以保留 collection name、field name、dataSourceKey、错误类别，但不输出实际结果列清单。
- 如确实需要内部排障列清单，必须放在专门的安全诊断通道，当前阶段不实现前端入口。
- 更新 `SqlErrorSanitizerTest`，不再保留表名/字段名作为 log sanitizer 预期。

### 测试要求

- 字段不存在时：
  - invalidCollections reason 不包含 available columns。
  - controller 响应不包含外部列名。
  - 日志不包含结果列清单。
- 保证字段权限过滤仍以 collection fields 为准。

### 验收标准

- 外部 schema 细节不会通过错误路径泄露。

## 7. P0-F: 统一 SQL identifier 校验与引用

### 目标

所有由 metadata 进入 SQL 片段的 identifier 都必须有统一校验/引用策略。

### 开发要求

- 为 SQL collection 查询层增加统一工具，例如 `SqlIdentifier`：
  - 校验 identifier：`[A-Za-z_][A-Za-z0-9_]*`。
  - 或安全转义双引号；本项目当前建议先拒绝复杂 identifier，保持与 FieldEntity 规则一致。
- `SqlDialect.quoteIdentifier()` 必须调用统一校验或转义逻辑，不能只做字符串拼接。
- 校验范围至少包括：
  - field effective column name。
  - sort field column。
  - primary key column。
  - relation foreignKey/sourceKey/targetKey/otherKey/through。
- 对 SQL collection 的 relation field，如果 effective column 来自 `foreignKey`，也必须校验。

### 测试要求

- 含双引号、分号、空格、注释符的 foreignKey/sourceKey/targetKey 被拒绝。
- SQL collection sort/filter 字段的 effective column name 不可注入。
- 现有合法字段名继续通过。

### 验收标准

- metadata identifier 注入风险有统一边界，不再散落在各服务里。

## 8. P1-G: Datasource 配置校验集中化

### 目标

外部 datasource 配置错误应在配置层或 runtime load 阶段给出一致错误，而不是分散在 resolver/dialect/Hikari 异常里。

### 开发要求

- `NocobaseDataSourceProperties` 增加统一配置校验：
  - key 格式。
  - `url` 必填。
  - `dialect` 仅支持 `h2/postgresql`，或为空时必须能从 URL 推导。
  - `driverClassName` 为空时必须能由 JDBC driver manager 推导，或明确拒绝。
  - `readOnly=false` 对外部 datasource 是否允许要明确；当前建议外部统一强制 read-only。
- `application.yml` 示例删除 unsupported `mysql`，或明确写为暂不支持且 disabled 不代表可用。
- 错误信息统一走 sanitizer，不包含完整 URL 和账号信息。

### 测试要求

- 缺少 URL、未知 dialect、无法推导 dialect、readOnly=false 都有明确测试。
- 配置层错误不会影响 main datasource 自动创建。

### 验收标准

- datasource 配置错误路径一致、可测试、可脱敏。

## 9. P1-H: 明确 SQL collection relation/appends 合约

### 目标

SQL collection 作为 source/target 参与关系查询时，需要明确支持范围和只读边界。

### 开发要求

- 定义并实现后端合约：
  - SQL collection 作为 relation source：允许 list/get appends，只读。
  - SQL collection 作为 relation target：允许被 appends/list 查询，只读。
  - SQL collection 参与 association add/remove/set：必须拒绝写操作，错误语义与 SQL collection create/update/destroy 一致。
  - 跨 datasource 不做 SQL join，只允许通过 `DynamicRepository` 分步查询。
- `RelationQueryService` 和 `AssociationActionService` 继续只通过 `DynamicRepository`。
- 补充 no-leak 语义：关联目标被 ACL scope 过滤时，不泄露记录存在性。

### 测试要求

- SQL source -> physical target 的 belongsTo/hasMany appends。
- physical source -> SQL target 的 belongsTo/hasMany appends。
- external SQL target 的 ACL scope 与字段权限继续生效。
- SQL collection 上 association add/remove/set 被拒绝。
- 关联数据超过 maxPageSize 时不丢数据，配合 P0-D。

### 验收标准

- SQL collection 与关系层的支持边界清晰，并由测试固定。

## 10. P1-I: PostgreSQL 集成验收入口

### 目标

确认 PostgreSQL 方言不只是代码存在，而是具备可执行验收路径。

### 开发要求

- 增加独立 profile 或可选集成测试：
  - 通过环境变量配置 PostgreSQL URL、username、password。
  - 默认不影响本地 `mvn -q test`。
  - 有环境变量时执行 PostgreSQL SQL collection list/get/filter/sort/page/count/validateFields。
- 如暂不引入 Testcontainers，文档必须说明手动运行方式。
- PostgreSQL 测试不修改前端，不需要外部 API。

### 测试要求

- PostgreSQL 外部 datasource 可读取。
- PostgreSQL quoted identifier、pagination、field metadata validation 通过。
- 错误路径脱敏。

### 验收标准

- 有明确命令能验证 PostgreSQL 外部 SQL collection。

## 11. P2-J: SQL collection 可观测性收敛

### 目标

现有结构化日志需要补齐实际 rowCount、count 查询耗时、data 查询耗时，并避免敏感信息。

### 开发要求

- list 操作分别记录：
  - countDurationMs。
  - dataDurationMs。
  - totalDurationMs。
  - returnedRows。
  - page/pageSize。
- get 操作记录：
  - durationMs。
  - found true/false。
- validateFields 记录：
  - durationMs。
  - status。
- 所有日志只包含 collection、dataSourceKey、action、数字指标、状态，不包含 SQL/filter/params。
- 如未来接入 Micrometer，本阶段先保持结构化日志即可。

### 测试要求

- 成功和失败日志不包含 SQL、filter、参数值。
- list 日志包含 returnedRows，不只是 pageSize。

### 验收标准

- 能用后端日志定位慢 SQL collection，但不会泄露业务查询内容。

## 12. P2-K: 文档更新

### 目标

让 `SQL_QUERY_COLLECTION_DESIGN.md` 与 Phase 4 修复后的真实行为一致。

### 开发要求

- 更新字段校验、安全日志、timeout、pagination、relation/appends、多 datasource 生命周期说明。
- 明确 PostgreSQL 集成测试运行方式。
- 明确暂不支持 MySQL，删除容易误导的示例。
- 明确 SQL collection 错误对前端只返回泛化错误。

### 验收标准

- 文档不再描述已废弃或不安全行为。

## 13. 建议并行分工

- Claude 任务 1：P0-A + P0-E，清理错误路径日志和 schema 泄露。
- Claude 任务 2：P0-B，修 datasource 失败路径资源释放与并发创建。
- Claude 任务 3：P0-C，改造 operation-level query timeout。
- Claude 任务 4：P0-D + P1-H，治理分页与 relation/appends 大结果集。
- Claude 任务 5：P0-F + P1-G，统一 identifier 和 datasource 配置校验。
- Claude 任务 6：P1-I + P2-J + P2-K，补 PostgreSQL 验收、可观测性和文档。

依赖关系：

- P0-A/P0-B/P0-E 可以立即并行。
- P0-C 与 P0-D 可以并行，但最终需要共同验证 list/get/validateFields。
- P1-H 依赖 P0-D，避免 relation appends 继续使用 `Integer.MAX_VALUE`。
- P1-G 建议和 P0-F 一起做，因为配置校验与 identifier/dialect 错误边界相关。
- P1-I 建议等 P0-A 到 P0-F 合并后再跑，避免 PostgreSQL 验收建立在旧错误路径上。

## 14. 提交与完成要求

- 每个任务完成后运行相关测试；最终合并前运行 `mvn -q test`。
- 完成后输出总结文档：`SQL_COLLECTION_PHASE4_PRODUCTION_GOVERNANCE_COMPLETION_SUMMARY.md`。
- 总结文档必须包含：
  - 修改文件列表。
  - 新增/调整测试列表。
  - `mvn -q test` 结果。
  - 是否修改前端，预期答案必须为否。
  - 是否改变 API 响应结构，预期答案必须为否。
  - 是否仍存在 SQL 原文、参数值、JDBC URL、用户名、密码日志泄露。
  - datasource 失败路径是否关闭连接池。
  - relation/appends 大结果集是否会被分页治理误截断。
