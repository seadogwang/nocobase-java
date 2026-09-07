# SQL Collection Phase 3 Datasource And Runtime Fixes - Next Development Tasks

> 面向 Claude 的后端开发任务清单。
> 前端界面与现有 API 响应结构不能改变；本批任务只改 Java 后端。

## 0. Review 结论

`SQL_COLLECTION_PHASE2_RUNTIME_CONTEXT_COMPLETION_SUMMARY.md` 对应实现整体可以进入下一阶段：

- SQL Collection 已支持 `static` 与 `currentUser` 两类运行时参数。
- 参数解析、参数绑定、类型归一化、非法命名参数拒绝、错误响应脱敏、无效 collection 跟踪均已有基础实现。
- `mvn -q test` 的 surefire 报告显示当前累计 `308 tests, 0 failures, 0 errors`。

但还有几处必须在进入真实多数据源之前修掉，否则后续 SQL collection、权限和运行时 reload 复杂度增加后会放大风险：

- `currentUser` 参数缺少 `path` 与 `type` 的兼容性校验。
- `CollectionRuntimeService.reload()` 与 `invalidCollections` 的生命周期不完整，可能留下 stale invalid 状态或 stale runtime definition。
- SQL collection 的 `options` JSON 解析失败目前可能被 warn-and-ignore，不适合作为 SQL collection 元数据策略。
- SQL 执行错误的 client response 已脱敏，但 server log 仍可能记录 JDBC 原始 SQL 错误内容。
- `SQL_QUERY_COLLECTION_DESIGN.md` 仍有 Phase 2 只支持 static、currentUser 未支持的陈旧描述。
- 参数归一化逻辑存在重复，`buildValueList()` 仍绕过 `SqlParameterResolver` 的语义。
- 缺少 `currentUser` 参数与 ACL scope 同时生效的集成测试。

## 1. 全局架构约束

- 不修改前端代码，不改变前端依赖的接口路径、字段名、分页结构、错误 envelope。
- 所有公开数据访问仍必须走 `DynamicRepository`，不能让 controller、relation、association 直接绕过 ACL 访问 `JdbcTemplate`。
- SQL collection 继续保持只读：`create/update/destroy` 必须拒绝，不允许对 SQL collection 做物理 DDL。
- SQL collection 的用户 filter、sort、pagination、ACL scope 必须继续在外层 subquery 上应用。
- SQL 值只能通过参数绑定进入查询，禁止拼接用户输入、上下文值、scope 值。
- 本批任务允许调整后端内部异常类型、日志策略、runtime metadata 生命周期，但不能改变前端可见成功响应结构。

## 2. P0-A: 校验 currentUser path/type 兼容性

### 目标

`source=currentUser` 的参数定义必须在 metadata 阶段就校验清楚，避免声明类型与运行时值类型不一致。

### 开发要求

- 在 `SqlParameterMetadata` 中增加 currentUser source 的 path/type 兼容性校验：
  - `path=id` 只允许 `type=number`。
  - `path=email` 只允许 `type=string`。
- `source=currentUser` 不允许配置 `defaultValue`，避免匿名用户或上下文缺失时产生语义歧义。
- `required` 语义保持不变：
  - `required=true` 且当前上下文没有对应值时，仍由 resolver 抛出未认证或上下文缺失错误。
  - `required=false` 且上下文没有对应值时，可以绑定 `null`。
- 错误信息需要明确指出 collection、parameter name、source/path/type 的冲突，但不能包含 SQL 原文。

### 测试要求

- 在 `SqlParameterMetadataTest` 增加用例：
  - `currentUser.id + number` 合法。
  - `currentUser.email + string` 合法。
  - `currentUser.id + string` 非法。
  - `currentUser.email + number` 非法。
  - `currentUser` 带 `defaultValue` 非法。
- 在 `SqlParameterResolverTest` 确认合法定义解析后的 Java 类型：
  - `id` 输出 `Number` 或项目当前约定的数字类型。
  - `email` 输出 `String`。

### 验收标准

- 非法 currentUser 参数定义在 collection runtime build 阶段失败，并进入 invalid collection 处理链路。
- 所有新增测试通过。

## 3. P0-B: 修正 invalidCollections 与 reload 生命周期

### 目标

单个 collection reload 的行为必须与 `loadAll()` 一致，不能因为 reload 成功或失败留下过期状态。

### 开发要求

- `CollectionRuntimeService.reload(collectionName)` 成功时：
  - 更新 runtime registry。
  - 移除 `invalidCollections[collectionName]`。
- `reload(collectionName)` 失败时：
  - 写入或更新 `invalidCollections[collectionName]`，错误信息使用已脱敏版本。
  - 从 runtime registry 移除该 collection 的旧 definition，避免继续暴露 stale collection。
  - 对调用方继续抛出异常，保留管理端感知 reload 失败的能力。
- `loadAll()` 当前清理 invalid 状态后重建 registry 的行为保持不变。
- `invalidCollections` 对外仍只暴露不可变视图或副本。

### 测试要求

- 在 `CollectionRuntimeServiceTest` 增加：
  - 先通过 `loadAll()` 产生 invalid collection，修复 metadata 后调用 `reload()`，invalid 状态被清除且 registry 可读取。
  - 已存在有效 collection，破坏 SQL metadata 后调用 `reload()`，该 collection 被标记 invalid，且不能继续通过 runtime registry 访问旧 definition。
  - 连续两次 reload 失败时，invalid reason 被更新，不累积旧错误。

### 验收标准

- reload 成功、失败、重复失败、loadAll 后 reload 的 invalid 状态一致。
- 不改变 controller 对缺失 collection 的现有 404 响应结构。

## 4. P0-C: SQL collection options JSON 解析失败必须 fail-fast

### 目标

SQL collection 的 `options` 是查询 SQL、primaryKey、parameters 等核心配置入口，解析失败不能静默降级为空配置。

### 开发要求

- 调整 collection definition 构建逻辑：
  - 对 `type=sql` 的 collection，`options` JSON 解析失败必须抛出 metadata/config 异常。
  - 对 `type=view` 如当前也依赖 options 描述 source/query，也应采用同样 fail-fast 策略。
  - 对 physical collection 是否继续保持 warn-and-ignore 可按现有兼容性保留，但需要在代码中明确边界。
- 抛出的异常必须进入 invalid collection tracking。
- client response 与日志中的错误信息不能包含 SQL 原文。

### 测试要求

- 增加 SQL collection `options` 非法 JSON 的 runtime 测试：
  - `loadAll()` 标记 invalid。
  - controller 访问返回现有 404/错误 envelope。
  - 错误响应不包含 SQL 原文。
- 增加 `reload()` 场景测试，覆盖非法 JSON 进入 invalid 状态。

### 验收标准

- SQL/view collection options 解析失败不会被当成空配置继续运行。
- 不影响 physical collection 当前已通过的 metadata 测试。

## 5. P0-D: SQL 错误日志也必须脱敏

### 目标

当前 client response 已脱敏，但生产 server log 中不能默认记录完整 SQL、参数值或底层数据库拼出的查询文本。

### 开发要求

- `SqlQueryCollectionExecutor` 捕获 JDBC/SQL 执行异常时，默认 error log 只记录：
  - collection name。
  - sanitized error category/message。
  - exception class。
- 不在 error 级别输出 `e.getMessage()` 的原始内容。
- 如确实需要调试细节，只允许在 debug/trace 级别输出，并评估是否仍需脱敏；默认实现优先完全不输出 SQL 原文。
- 复用或抽取统一的 SQL error sanitizer，避免 controller response 与 log 使用两套不一致逻辑。

### 测试要求

- 为 sanitizer 增加单元测试，覆盖：
  - H2/PostgreSQL 风格错误中包含 SQL 原文。
  - 错误包含表名、字段名、参数占位符。
  - 已经是安全错误信息时保持可读。
- 如项目已有日志捕获测试工具，增加一条 executor 日志不包含 SQL 原文的测试；如果没有，至少保证 sanitizer 测试覆盖。

### 验收标准

- SQL 执行失败时，前端响应与默认 server error log 都不暴露 SQL 原文或参数值。

## 6. P0-E: 补齐 currentUser 参数与 ACL scope 交集测试

### 目标

证明 SQL collection 的运行时参数过滤与 ACL scope 是同时生效的，而不是二选一。

### 开发要求

- 使用现有 SQL collection 测试夹具，构造 `currentUser` 参数作为 SQL 内层条件，例如 `owner_id = :userId`。
- 再配置一个 ACL scope 作为外层条件，例如 `status = 'active'` 或另一个不会与 owner 条件混淆的字段。
- 验证 list/get 两条链路：
  - SQL 内层 currentUser 条件命中。
  - 外层 ACL scope 继续收窄结果。
  - get 不满足 scope 时返回现有语义的 not found/forbidden 结果，不能泄露记录存在性。

### 测试要求

- 在 `SqlQueryCollectionTest` 或 ACL 集成测试中增加：
  - admin/member 使用不同 currentUser id 返回不同集合。
  - 同一用户下 active/inactive 数据被 ACL scope 正确过滤。
  - `executeGet()` 同时应用 pk、currentUser SQL 条件、get action scope。

### 验收标准

- 能明确证明 SQL 参数绑定、ACL scope、field permission 三者在 SQL collection list/get 上没有互相绕过。

## 7. P1-F: 收敛参数归一化与 resolver 入口

### 目标

降低后续增加 request/context/tenant 参数源时出现重复逻辑和绕过 resolver 的风险。

### 开发要求

- 将 `SqlParameterMetadata` 中重复的 normalize 逻辑收敛为单一路径。
- 检查 `buildValueList()` 是否仍有生产调用：
  - 如无生产调用，删除或降低可见性，并调整测试只验证 resolver。
  - 如仍需保留，内部必须委托 `SqlParameterResolver` 或明确只支持 static，并在方法名/注释中体现边界。
- `SqlNamedParameterParser.Result` 返回的 parameter names 应改为不可变列表，避免调用方误改。

### 测试要求

- 现有参数类型测试全部保留。
- 增加不可变 parameter names 的小测试。
- 确认没有生产链路绕过 `SqlParameterResolver` 做参数值构造。

### 验收标准

- 参数定义、默认值归一化、运行时解析的职责边界清晰。

## 8. P1-G: 更新 SQL collection 设计文档

### 目标

让 `SQL_QUERY_COLLECTION_DESIGN.md` 与当前实现一致，避免 Claude 后续按旧语义继续开发。

### 开发要求

- 更新 Phase 2 状态：
  - `static` 已支持。
  - `currentUser.id`、`currentUser.email` 已支持。
  - `request`、`context`、`tenant` 等来源仍未支持，不能误写成已实现。
- 明确 datetime 当前支持策略：
  - 如果当前实现只支持 `ISO_LOCAL_DATE_TIME`，文档必须这么写。
  - 如果要支持 offset datetime，需要同步补实现与测试。
- 更新安全边界：
  - SQL 原文只来自管理员配置。
  - 运行时值只允许绑定参数。
  - 外层 filter/sort/scope 必须通过字段白名单与参数绑定。
- 更新 invalid collection 策略：
  - metadata 错误、SQL options JSON 错误、参数声明错误都会进入 invalid tracking。

### 验收标准

- 文档中不再出现 Phase 2 只支持 static 或 currentUser 未支持的陈旧描述。

## 9. P1-H: Phase 3 多数据源入口元数据

### 目标

开始为 SQL collection 真实多数据源做后端入口，但本任务不要求连接外部数据库。

### 开发要求

- 在 SQL collection options 中预留并解析 `dataSourceKey`：
  - 缺省值为主数据源，例如 `main` 或项目现有默认命名。
  - 值必须满足安全命名规则，例如 `[A-Za-z][A-Za-z0-9_-]{0,63}`。
- `CollectionDefinition` 或 SQL 专用 metadata 中暴露 dataSourceKey。
- 当前 executor 仍只允许主数据源：
  - 非主数据源先 fail-fast，错误进入 invalid collection 或执行期配置错误，具体按当前 runtime 架构选择一种一致策略。
  - 不要在本任务引入真实外部连接配置、连接池、动态 credential 管理。
- 为后续 `SqlDataSourceResolver` 留出接口边界，但不要过度抽象。

### 测试要求

- 未配置 `dataSourceKey` 时使用主数据源。
- 合法主数据源 key 可执行现有 SQL collection 测试。
- 非法 key 格式被拒绝。
- 当前不支持的非主数据源 key 被明确拒绝，且错误不包含 SQL 原文。

### 验收标准

- Phase 3 的 metadata 入口存在，行为保守，不影响当前主数据源 SQL collection。

## 10. P2-I: SqlDataSourceResolver 骨架

### 目标

为下一批真实多数据源执行器做最小骨架，不引入尚未需要的复杂配置。

### 开发要求

- 定义一个很薄的 resolver 接口，例如按 `dataSourceKey` 返回 `JdbcTemplate` 或内部执行上下文。
- 当前实现只注册主数据源。
- `SqlQueryCollectionExecutor` 通过 resolver 获取执行用 `JdbcTemplate`，但默认行为必须与当前完全一致。
- 非主 key 的错误必须可测试、可脱敏、可进入当前错误处理链路。

### 测试要求

- 主数据源路径保持所有现有 SQL tests 通过。
- 非主 key 返回明确的 unsupported/unconfigured 错误。
- 确认没有 controller 直接依赖新的 resolver。

### 验收标准

- 多数据源切换点已经在后端内部形成，但不改变外部 API。

## 11. 建议并行分工

- Claude 任务 1：P0-A + P1-F，聚焦参数 metadata/resolver。
- Claude 任务 2：P0-B + P0-C，聚焦 runtime reload 与 invalid collection。
- Claude 任务 3：P0-D + P0-E，聚焦 SQL 错误脱敏与权限交集测试。
- Claude 任务 4：P1-G，单独更新设计文档，避免和代码任务互相阻塞。
- Claude 任务 5：P1-H + P2-I，在 P0 合并后开始，避免多数据源入口建立在不稳定 runtime 语义上。

## 12. 提交与完成要求

- 每个任务提交前运行相关测试；最终合并前运行 `mvn -q test`。
- 完成后输出总结文档：`SQL_COLLECTION_PHASE3_DATASOURCE_AND_RUNTIME_FIXES_COMPLETION_SUMMARY.md`。
- 总结文档必须包含：
  - 修改文件列表。
  - 新增/调整测试列表。
  - `mvn -q test` 结果。
  - 是否改变 API 响应结构，预期答案应为否。
  - 尚未实现的 Phase 3 多数据源能力边界。
