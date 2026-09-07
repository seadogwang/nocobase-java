# SQL Collection Phase 3 Real Datasource - Next Development Tasks

> 面向 Claude 的后端开发任务清单。
> 前端界面、前端路由、现有 API 响应结构不能改变；本批任务只改 Java 后端。

## 0. Review 结论

`SQL_COLLECTION_PHASE3_DATASOURCE_AND_RUNTIME_FIXES_COMPLETION_SUMMARY.md` 对应开发结果整体通过，可以进入真实多数据源阶段。

已确认：

- `mvn -q test` 本地复跑成功。
- surefire 报告显示当前累计 `344 tests, 0 failures, 0 errors`。
- `currentUser` 参数校验、reload invalid 生命周期、SQL options fail-fast、参数 resolver 收敛、SQL dataSourceKey 骨架均已落地。

但当前 Phase 3 仍只是入口骨架，不是真实多数据源能力：

- `SqlDataSourceResolver` 目前只返回主 `JdbcTemplate`，非 `main` 直接 `UnsupportedOperationException`。
- `application.yml` 仍只有单个 `spring.datasource`，没有 `nocobase.data-sources` 配置模型。
- `SQL_QUERY_COLLECTION_DESIGN.md` 中 `dataSourceKey` 默认值仍有 `default` 与代码 `main` 不一致的问题。
- `SqlErrorSanitizer` 仍保留部分表名/字段名细节，且对小写 SQL 片段、复杂 bracket SQL 的处理不够系统。
- `application.yml` 默认开启 `org.hibernate.SQL=DEBUG` 与 binder `TRACE`，生产默认日志会泄露 SQL 和参数值。
- `CollectionRuntimeService` 的 `loadAll/reload` 在 error 级别仍输出完整异常堆栈，后续接入外部数据源后可能泄露 JDBC URL、库表名或驱动错误细节。

## 1. 全局架构约束

- 不修改前端代码，不改变现有 API 的 path、query 参数、响应 envelope、分页结构、字段名。
- 公开数据访问继续统一走 `DynamicRepository`；controller、relation、association 不允许直接访问 `JdbcTemplate`。
- SQL collection 继续只读，`create/update/destroy` 必须拒绝。
- 主数据源仍承担 metadata、ACL、用户、角色、插件状态等系统表。
- 外部数据源只允许作为 SQL collection 的查询数据源，不允许自动 DDL，不允许写入。
- 外部 SQL collection 的 ACL scope、field permission、filter、sort、pagination 仍必须由 Java 后端统一追加和绑定。
- 禁止拼接运行时参数值；所有 value 必须通过 JDBC 参数绑定。

## 2. P0-A: 统一 dataSourceKey 语义与文档

### 目标

消除 `main/default` 两套命名，避免后续多数据源配置混乱。

### 开发要求

- 以 `main` 作为系统主数据源的唯一规范 key。
- 更新 `SQL_QUERY_COLLECTION_DESIGN.md` 中所有 `default` 默认数据源描述为 `main`。
- 如需要兼容历史配置，可在解析层支持 `default -> main` alias，但必须在文档中标明 deprecated。
- `CollectionDefinition.getDataSourceKey()` 默认仍为 `main`。
- `SqlDataSourceResolver.resolve(null)` 与 `resolve("")` 的行为保持返回主数据源。

### 测试要求

- 增加或调整测试确认：
  - 未配置 `dataSourceKey` 时为 `main`。
  - 配置 `dataSourceKey: "main"` 时正常。
  - 如支持 alias，则 `default` 被归一化为 `main`。
  - 文档中不再出现把主数据源称为默认 key `default` 的段落。

### 验收标准

- 代码、测试、设计文档中主数据源 key 语义一致。

## 3. P0-B: 收紧默认日志，禁止生产默认 SQL/参数泄露

### 目标

默认配置不能在生产环境输出 SQL 原文或绑定参数值。

### 开发要求

- 修改 `src/main/resources/application.yml`：
  - `org.hibernate.SQL` 默认不得为 `DEBUG`。
  - `org.hibernate.type.descriptor.sql.BasicBinder` 默认不得为 `TRACE`。
  - `com.nocobase` 默认建议为 `INFO`，调试日志由本地 profile 单独开启。
- 如需要保留开发调试配置，新增单独 profile，例如 `application-dev.yml`，但不要让默认配置泄露 SQL。
- 测试配置 `src/test/resources/application.yml` 可以保留测试所需日志级别，但不要输出参数值。

### 测试要求

- 增加配置级测试或轻量单元测试，确认默认 profile 下：
  - `logging.level.org.hibernate.SQL` 不是 `DEBUG/TRACE`。
  - `logging.level.org.hibernate.type.descriptor.sql.BasicBinder` 不是 `TRACE`。

### 验收标准

- 默认启动配置不输出 Hibernate SQL 和参数绑定日志。

## 4. P0-C: Runtime metadata 日志脱敏

### 目标

`CollectionRuntimeService.loadAll/reload` 不能在 error 级别输出完整异常堆栈和原始 driver/Jackson 错误。

### 开发要求

- `loadAll()` 构建 collection 失败时：
  - error/warn 级别只记录 collection name、sanitized reason、exception class。
  - 不直接传 `e` 到 error/warn 日志。
  - 如保留堆栈，只能 debug 级别输出，并确保不包含 SQL 原文、JDBC URL、密码、参数值。
- `reload()` 失败时采用同样策略。
- 将 `CollectionRuntimeService.sanitizeErrorMessage()` 与 `SqlErrorSanitizer` 的职责统一：
  - SQL/runtime 数据库错误使用 `SqlErrorSanitizer`。
  - metadata 错误使用单独 sanitizer 或统一 facade，避免两套规则分裂。
- 对 JDBC URL、`password=...`、`username=...`、`user=...` 等连接串敏感信息做基础掩码。

### 测试要求

- 增加 sanitizer 单元测试：
  - 含 SQL 原文的错误被移除。
  - 含 JDBC URL 的错误被掩码。
  - 含 password/user 参数的错误被掩码。
- 如项目可捕获日志，增加 `loadAll/reload` 失败日志不含 SQL/JDBC URL/password 的测试。

### 验收标准

- invalid collection reason、controller error response、默认日志三处都不泄露 SQL 原文、参数值、连接串敏感信息。

## 5. P0-D: SQL 错误响应进一步降噪

### 目标

前端用户不应通过 SQL collection 执行错误看到物理表名、字段名、驱动错误细节。

### 开发要求

- 将 `SqlErrorSanitizer` 区分为两种输出语义：
  - `sanitizeForLog()`：保留安全类别和有限错误码，不包含 SQL、参数值、连接串敏感信息。
  - `sanitizeForClient()`：只返回稳定、低信息量的错误，例如 `SQL collection execution failed` 或 `Database query failed`。
- `SqlQueryCollectionExecutor` 抛给 controller 的异常必须使用 client 级脱敏信息。
- 默认 error log 使用 log 级脱敏信息。
- 处理规则必须大小写不敏感，能清理：
  - `SELECT/select/WITH/with/INSERT/update/delete` 等 bracket SQL。
  - H2 `SQL statement:` 片段。
  - PostgreSQL `Position:`、`Detail:`、`SQL [...]` 片段。
  - Spring `bad SQL grammar [...]` 包装片段。
- 不再在 client-facing message 中保留 `relation "orders" does not exist`、`Column "X" not found` 这类物理结构信息。

### 测试要求

- 调整 `SqlErrorSanitizerTest`：
  - client sanitizer 不包含表名、字段名、SQL 原文。
  - log sanitizer 不包含 SQL 原文、参数值、连接串敏感信息。
  - 小写 SQL 片段也能清理。
- 调整 `SqlCollectionErrorTest`：
  - SQL 执行失败响应不包含物理表名、字段名、SQL 原文。
  - 响应 envelope 保持不变。

### 验收标准

- SQL runtime error 对前端只暴露稳定错误类别，不暴露底层数据库结构。

## 6. P0-E: 实现多数据源配置模型

### 目标

建立 Java 后端可管理的 SQL collection 数据源注册表，先从配置文件读取，不引入前端配置页面。

### 开发要求

- 新增配置绑定类，例如 `NocobaseDataSourceProperties`：
  - 前缀建议：`nocobase.data-sources`。
  - 支持多 key 配置：`main`、`analytics`、`reporting` 等。
  - 每个数据源至少包含：`url`、`driverClassName`、`username`、`password`、`enabled`、`dialect`、`readOnly`。
- `main` 数据源必须继续兼容现有 `spring.datasource`：
  - 未配置 `nocobase.data-sources.main` 时，自动使用 Spring Boot 主数据源。
  - 外部数据源配置不能影响 JPA/Flyway metadata 主库。
- 对配置做启动期校验：
  - key 格式沿用 `[A-Za-z][A-Za-z0-9_-]{0,63}`。
  - 禁止覆盖保留 key。
  - `enabled=false` 的数据源不能被 SQL collection 使用。
- 不要新增前端接口，不要改变 collection API。

### 测试要求

- 增加配置绑定测试：
  - 只有 `spring.datasource` 时仍存在 `main`。
  - 配置 `analytics` 时可以读取属性。
  - 非法 key 或 disabled key 会被拒绝或标记不可用。
- 增加 SQL collection metadata 测试：
  - 引用不存在 `dataSourceKey` 时进入 invalid collection。
  - 引用 disabled `dataSourceKey` 时进入 invalid collection。

### 验收标准

- 后端具备多数据源配置入口，但系统 metadata 仍只在主库。

## 7. P0-F: 实现 SqlDataSourceResolver 注册表

### 目标

让 SQL collection 可以按 `dataSourceKey` 获取对应 `JdbcTemplate`，真正支持主库之外的数据源查询。

### 开发要求

- 将当前 `SqlDataSourceResolver` 从单主库骨架扩展为注册表：
  - `main` 返回 Spring Boot 主 `JdbcTemplate`。
  - 外部数据源按配置创建独立 `DataSource` 与 `JdbcTemplate`。
- 外部数据源必须设置只读语义：
  - 连接池或 `DataSource` 层尽量设置 read-only。
  - SQL validator 仍负责禁止 DML/DDL。
- 不要让外部数据源参与 JPA、Flyway、DDL synchronizer。
- resolver 对外提供只读查询能力，不提供任意执行写 SQL 的 API。
- 连接失败时错误必须进入统一脱敏链路。

### 测试要求

- 使用 H2 内存库模拟第二数据源 `analytics`：
  - 初始化独立表和数据。
  - SQL collection 配置 `dataSourceKey=analytics`。
  - list/get 能读取 analytics 数据。
  - ACL scope、filter、sort、pagination 仍生效。
- 验证主库 metadata 与外部数据查询隔离：
  - collection metadata 在主库。
  - 查询数据来自 analytics 库。

### 验收标准

- SQL collection 能真实从第二数据源读取数据，且权限链路不变。

## 8. P1-G: SQL 方言上下文与分页生成

### 目标

当前 SQL collection 外层 SQL 使用固定 `LIMIT/OFFSET` 与双引号标识符。进入多数据源后必须按数据源方言生成外层 SQL。

### 开发要求

- 为 SQL collection 引入轻量方言接口，例如 `SqlDialect`：
  - `quoteIdentifier(identifier)`。
  - `applyPagination(sql, pageSize, offset)`。
  - `buildCountSql(innerSql)`。
- 先支持当前依赖范围内的 H2/PostgreSQL：
  - H2 测试模式兼容 PostgreSQL mode。
  - PostgreSQL 使用双引号和 `LIMIT/OFFSET`。
- `SqlQueryCollectionExecutor.buildListPlan/buildGetPlan` 不再硬编码方言细节。
- 方言由 `dataSourceKey` 解析得到，不影响 DDL 的 `DialectAdapter`，但可以复用思想。

### 测试要求

- 调整 `SqlQueryPlanTest` 覆盖方言生成。
- 确认 H2/PostgreSQL 方言下 list/get/count SQL 符合预期。
- 所有旧 SQL collection 测试继续通过。

### 验收标准

- SQL collection 外层包装、字段引用、排序、分页具备按数据源扩展的方言边界。

## 9. P1-H: 外部数据源字段元数据一致性校验

### 目标

SQL collection 的 fields 是权限控制和前端渲染基础，需要能发现配置字段与实际查询结果不一致的问题。

### 开发要求

- 为 SQL collection 增加可选的 metadata validation 能力：
  - 对配置 SQL 包一层 `SELECT * FROM (...) _nocobase_sub WHERE 1=0` 或方言等价查询。
  - 使用 `ResultSetMetaData` 获取列名。
  - 校验 collection fields 的 effective column name 能在结果集中找到。
- validation 失败进入 invalid collection。
- 该能力默认只在 runtime load/reload 时执行轻量校验，不拉取真实数据。
- 对无法连接的外部数据源，错误必须脱敏并进入 invalid collection。

### 测试要求

- SQL fields 与查询结果一致时 load/reload 成功。
- 配置了不存在的 field column 时进入 invalid collection。
- `fields` 权限过滤仍基于 collection fields，不基于任意结果列。

### 验收标准

- SQL collection metadata 与实际结果集列具备基础一致性保障。

## 10. P1-I: 外部数据源连接健康与失败隔离

### 目标

外部数据源不可用时不能拖垮主系统启动，也不能影响其他 collection。

### 开发要求

- data source resolver 初始化失败策略要明确：
  - 主数据源失败仍由 Spring Boot 处理。
  - 外部数据源失败不应导致整个应用无法启动，除非配置显式要求 fail-fast。
- 对引用不可用外部数据源的 SQL collection：
  - `loadAll()` 标记 invalid。
  - 其他 collection 正常加载。
  - controller 访问该 collection 保持现有错误 envelope。
- 提供内部只读方法用于健康检查，例如 `isAvailable(dataSourceKey)`，不新增前端接口。

### 测试要求

- 配置一个不可连接的外部数据源：
  - 应用 context 可启动。
  - 引用它的 SQL collection invalid。
  - 不引用它的 SQL collection 正常。
- 错误信息不包含完整 JDBC URL、用户名、密码。

### 验收标准

- 多数据源失败隔离清晰，不破坏主系统可用性。

## 11. P2-J: 文档更新与边界声明

### 目标

让后续开发明确哪些多数据源能力已经支持，哪些仍不支持。

### 开发要求

- 更新 `SQL_QUERY_COLLECTION_DESIGN.md`：
  - `dataSourceKey` 规范值为 `main`。
  - 说明配置文件方式支持外部只读 SQL datasource。
  - 说明 metadata、ACL、用户、角色仍在主库。
  - 说明外部数据源不支持 DDL、不支持写操作、不参与跨库事务。
  - 说明当前支持的方言范围。
- 增加或更新开发完成总结模板要求。

### 验收标准

- 文档与代码行为一致，Claude 后续不会继续按旧的 `default` 或“未实现多数据源”描述开发。

## 12. 建议并行分工

- Claude 任务 1：P0-A + P0-B，修 dataSourceKey 一致性和默认日志安全。
- Claude 任务 2：P0-C + P0-D，统一 runtime/SQL 错误脱敏。
- Claude 任务 3：P0-E，建立多数据源配置模型。
- Claude 任务 4：P0-F，基于配置模型实现 resolver 注册表和第二 H2 数据源集成测试。
- Claude 任务 5：P1-G + P1-H，做 SQL 方言上下文和结果列校验。
- Claude 任务 6：P1-I + P2-J，做失败隔离和文档更新。

依赖关系：

- P0-F 依赖 P0-E。
- P1-G 最好在 P0-F 后做，因为方言应来自 datasource metadata。
- P1-H 依赖 P0-F 与 P1-G。
- P0-C/P0-D 可独立并行，但应在 P0-F 接入外部连接前合并。

## 13. 提交与完成要求

- 每个任务完成后运行相关测试；最终合并前运行 `mvn -q test`。
- 完成后输出总结文档：`SQL_COLLECTION_PHASE3_REAL_DATASOURCE_COMPLETION_SUMMARY.md`。
- 总结文档必须包含：
  - 修改文件列表。
  - 新增/调整测试列表。
  - `mvn -q test` 结果。
  - 是否修改前端，预期答案必须为否。
  - 是否改变 API 响应结构，预期答案必须为否。
  - 当前支持的数据源类型、方言范围、失败隔离策略。
