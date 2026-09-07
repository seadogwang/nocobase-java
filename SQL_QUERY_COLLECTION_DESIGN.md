# SQL Query Collection 设计文档

> 日期: 2026-08-29  
> 状态: **Phase 1 已实现** (list, get, validator, executor), **Phase 2 已实现** (命名参数支持, static 和 currentUser 来源), **Phase 3 已实现** (dataSourceKey, 外部数据源隔离, 方言抽象, 字段元数据校验, 连接验证, 恢复策略, 查询治理, 可观测性日志, 统一标识符校验, 数据源配置集中校验)
>
> **MySQL 明确不支持。** 支持的数据源方言: H2 和 PostgreSQL。

---

## 一、目标

在 NocoBase Java 后端中支持 SQL query collection 类型，允许管理员配置自定义 SQL 查询作为数据源，同时保持前端 API 兼容。

---

## 二、核心设计原则

1. **SQL 不散落在 Controller/Service** — 所有 SQL 必须进入专用 executor
2. **前端 API 不变** — `/api/{collection}:list` 等接口形态不变
3. **权限闭环** — action 权限、scope、字段权限在 SQL 结果集上仍然生效
4. **参数绑定** — 用户 SQL 参数必须绑定，不能拼接
5. **只读** — SQL collection 不支持 create/update/destroy
6. **失败隔离** — 外部数据源连接失败不影响主系统

---

## 三、数据模型

### CollectionEntity 配置

```json
{
  "name": "active_users",
  "type": "sql",
  "sql": "SELECT id, email, nickname, status FROM users WHERE status = 'active'",
  "options": {
    "dataSourceKey": "main"
  },
  "fields": [
    {"name": "id", "type": "bigInt"},
    {"name": "email", "type": "string"},
    {"name": "nickname", "type": "string"},
    {"name": "status", "type": "string"}
  ]
}
```

- `type: "sql"` — 标记为 SQL collection
- `sql` — 用户配置的 SQL 查询
- `fields` — 必须显式声明所有字段（SQL collection 没有物理表结构）
- `dataSourceKey` — 可选，默认为 `"main"`。`"main"` 是标准值，`"default"` 是已弃用的别名（自动规范化为 `"main"`）。外部数据源 key 格式：`[A-Za-z][A-Za-z0-9_-]{0,63}`

**重要: 配置 SQL 支持命名参数 `:param`（Phase 2 已实现）。** 不支持 `?` 占位符（JDBC 风格）。所有过滤和分页参数通过外层 WHERE/LIMIT 包裹实现。

---

## 四、查询执行流程

```
Client Request:
  GET /api/active_users:list?filter={"status":"active"}&sort=email&page=1&pageSize=20

DynamicRepository.list("active_users", filter, sort, page, pageSize, fields):
  1. 获取 CollectionDefinition
  2. Capability check: readable=true, writable=false
  3. ACL action check: list
  4. 获取 ACL scope filter
  5. 构建 SQL:
     SELECT * FROM (
       <用户配置的 SQL>
     ) AS _sub
     WHERE (<ACL scope>) AND (<用户 filter>)
     ORDER BY <sort>
     LIMIT <pageSize> OFFSET <offset>
  6. 参数绑定执行
  7. 字段权限过滤
  8. 返回分页结果
```

---

## 五、SQL 包裹策略

### 外层包裹

```sql
SELECT <fields>
FROM (
  -- 用户配置的 SQL
  SELECT id, email, nickname, status FROM users WHERE status = 'active'
) AS _nocobase_sub
WHERE <scope_filter> AND <user_filter>
ORDER BY <sort>
LIMIT ? OFFSET ?
```

### 规则

- 用户 SQL 作为子查询 `_nocobase_sub`
- 外层 WHERE 追加 scope + user filter
- 外层 ORDER BY 替代子查询排序
- 外层 LIMIT/OFFSET 控制分页
- 用户 SQL 禁止包含 `;` 和 `--` 注释
- 参数使用 `?` 绑定，禁止拼接

---

## 六、ACL 集成

| 层级 | 处理方式 |
|------|----------|
| action 权限 | `canAction("active_users", "list")` |
| scope filter | 编译为 WHERE 条件追加到外层查询 |
| readable fields | 在结果集上过滤，只返回允许字段 |
| writable fields | N/A（SQL collection 只读） |

---

## 七、Capability 定义

```java
SQL Collection:
  readable: true
  writable: false
  schemaMutable: false
  indexMutable: false
  relationSupported: false
```

---

## 八、前端 API 兼容示例

### 示例 1: 列表查询

```
GET /api/active_users:list?filter={"status":"active"}&sort=email&page=1&pageSize=20

Response:
{
  "data": [
    {"id": 1, "email": "user@example.com", "nickname": "User1", "status": "active"},
    ...
  ],
  "meta": {"count": 42, "page": 1, "pageSize": 20}
}
```

### 示例 2: 单条查询

```
GET /api/active_users:get?filterByTk=1

Response:
{
  "data": {"id": 1, "email": "user@example.com", "nickname": "User1", "status": "active"}
}
```

### 示例 3: 写入被拒绝

```
POST /api/active_users:create
Body: {"email": "new@example.com"}

Response:
{
  "errors": [{"message": "Cannot write to sql collection: active_users"}]
}
```

---

## 九、H2/PostgreSQL 差异

| 维度 | H2 | PostgreSQL |
|------|-----|-----------|
| 子查询语法 | `SELECT * FROM (...) AS _sub` | 同 H2 |
| 参数占位符 | `?` | `$1, $2, ...` 或 `?` (jdbc) |
| 命名参数 `:param` | 不支持 | 不支持（需预处理） |
| LIMIT/OFFSET | `LIMIT ? OFFSET ?` | 同 |
| 标识符引用 | `"` (双引号) | `"` (双引号) |

### 方言抽象 (SqlDialect)

`SqlDialect` 接口提供数据库方言抽象，确保 SQL 生成不硬编码特定数据库语法：

```java
public interface SqlDialect {
    String quoteIdentifier(String identifier);   // 返回带引号的标识符
    String getLimitOffsetClause();               // 返回 LIMIT ? OFFSET ? 子句
}
```

**已实现方言：**
- `H2SqlDialect` — 双引号标识符，`LIMIT ? OFFSET ?`
- `PostgreSqlDialect` — 双引号标识符，`LIMIT ? OFFSET ?`（JDBC 将 `$N` 规范化为 `?`）

`SqlDataSourceResolver.resolveDialect(String dataSourceKey)` 根据数据源配置自动选择方言。主数据源通过 JDBC URL 自动检测（`jdbc:postgresql:` → PostgreSQL），外部数据源通过 `dialect` 配置属性指定。

### 字段元数据校验 (validateFields)

在 collection 构建/重载阶段，`SqlQueryCollectionExecutor.validateFields()` 会：

1. 使用 `SqlNamedParameterParser.parse()` 将配置 SQL 中的命名参数 `:param` 转换为 JDBC `?` 占位符（**不是正则替换**：解析器正确跳过字符串字面量、双引号标识符、`::type` 转换、URL 模式和时间字面量）
2. 根据 `SqlParameterMetadata` 中声明的参数类型构建类型匹配的**虚拟绑定值**（string→"", number→0, boolean→false, date→1970-01-01, datetime→1970-01-01 00:00:00）
3. 将处理后的 SQL 包裹为 `SELECT * FROM (...) _nocobase_sub WHERE 1=0`
4. 使用虚拟参数值执行查询，读取 `ResultSetMetaData` 获取列名（不返回任何行）
5. 验证 collection 的字段定义中所有 `effectiveColumnName` 在结果集中存在
6. 校验失败 → collection 进入 invalid 状态，错误日志仅包含脱敏类别（如 syntax_error、schema_error），不含 SQL 文本

### 统一标识符校验 (SqlIdentifier)

所有 SQL 标识符（字段有效列名、排序列、主键列、关系 foreignKey/sourceKey/targetKey/otherKey）在嵌入 SQL 之前必须通过 `SqlIdentifier` 校验：

```java
// 校验
SqlIdentifier.validate("user_name");  // 通过
SqlIdentifier.validate("col name");   // 拒绝: 包含空格

// 校验并引用
SqlIdentifier.quote("id");  // 返回 "\"id\""
```

**规则:**
- 标识符必须匹配 `[A-Za-z_][A-Za-z0-9_]*`
- 拒绝包含双引号、分号、空格、注释字符的标识符
- `SqlDialect.quoteIdentifier()` 委托给 `SqlIdentifier.quote()`（接口默认方法）
- `RelationDefinition` 在构建时校验所有键字段（foreignKey、sourceKey、targetKey、otherKey）
- `CollectionRuntimeService` 在构建时校验主键的有效列名

### 安全日志与可观测性

所有 SQL collection 操作的结构化日志遵循以下脱敏规则：

**允许记录：**
- collection 名称、dataSourceKey、操作类型（list/get/validateFields）
- **list**: countDurationMs、dataDurationMs、totalDurationMs、returnedRows、page、pageSize
- **get**: durationMs、found（true/false — 是否找到记录）
- **validateFields**: durationMs、status
- 成功/失败状态、错误类别（syntax_error、connection_error 等）

**禁止记录：**
- SQL 文本（配置 SQL 或生成的查询 SQL）
- 参数值（命名参数值、用户 filter 值、ACL scope 值）
- JDBC URL、用户名、密码
- 原始异常消息（统一使用 `SqlErrorSanitizer.sanitizeForLog()` 脱敏）

### 查询超时

| 操作 | 超时时间 | 配置属性 |
|------|--------|----------|
| 列表查询 (list) | 30s | `nocobase.sql.query-timeout-seconds` |
| 单条查询 (get) | 30s | `nocobase.sql.query-timeout-seconds` |
| 字段校验 (validateFields) | 5s | `nocobase.sql.validation-timeout-seconds` |

`JdbcTemplate.setQueryTimeout()` 在每个操作执行前设置，使用独立的 JdbcTemplate 实例以避免修改共享状态。

### 分页

| 参数 | 默认值 | 配置属性 |
|------|--------|----------|
| maxPageSize | 200 | `nocobase.sql.max-page-size` |
| page 归一化 | 最小值 1 | — |
| pageSize 上限 | maxPageSize | — |

分页通过 `LIMIT ? OFFSET ?` 在外层 SQL 中实现（方言抽象）。无效的 page 值（< 1）自动归一化为 1。无效的 maxPageSize（<= 0）回退为 200。

### 关系与 Appends

SQL collection 支持关系字段查询（appends），通过 `RelationQueryService` 实现：

- **belongsTo**: 查询目标 collection 通过 primaryKey 匹配
- **hasOne**: 查询目标 collection 通过 foreignKey 匹配 sourceKey
- **hasMany**: 查询目标 collection 通过 foreignKey 匹配 sourceKey
- **belongsToMany**: 查询中间表（through）和 target collection

所有关系键字段（foreignKey、sourceKey、targetKey、otherKey）在 `RelationDefinition` 构建时通过 `SqlIdentifier` 校验，确保标识符安全。

Appends 查询通过 `DynamicRepository` 执行，自动应用 ACL scope 和字段权限过滤。

### 数据源配置集中校验

`NocobaseDataSourceProperties` 在启动时统一校验所有数据源配置：

| 校验项 | 规则 |
|--------|------|
| key 格式 | `[A-Za-z][A-Za-z0-9_-]{0,63}` |
| url | 外部数据源必填 |
| dialect | 仅支持 `h2` 和 `postgresql`（MySQL 明确不支持） |
| driverClassName | 可从 JDBC URL 自动推导或需显式配置；不匹配时拒绝 |
| readOnly | 外部数据源强制 readOnly=true（false 拒绝） |

所有错误消息通过 `SqlErrorSanitizer` 脱敏，不泄露 JDBC URL、用户名、密码等敏感信息。

### 前端错误消息

SQL collection 执行错误向前端返回**通用错误消息**。前端**永远不会**看到：
- SQL 文本
- 表名、列名
- JDBC URL、连接字符串
- 原始异常堆栈跟踪

所有面向客户端的错误消息通过 `SqlErrorSanitizer.sanitizeForClient()` 统一处理，始终返回 `"Database query failed"`。

### PostgreSQL 集成测试

PostgreSQL 集成测试位于 `src/test/java/com/nocobase/postgresql/PostgreSqlIntegrationTest.java`。

**手动运行命令：**
```bash
PG_URL=jdbc:postgresql://localhost:5432/testdb \
PG_USERNAME=postgres \
PG_PASSWORD=postgres \
mvn test -Dtest=PostgreSqlIntegrationTest -Dspring.profiles.active=postgresql
```

**环境变量：**
- `PG_URL` — JDBC URL（必须设置，否则跳过所有测试）
- `PG_USERNAME` — 数据库用户名
- `PG_PASSWORD` — 数据库密码

**测试内容：**
- 外部数据源 SQL collection 的 list/get/filter/sort/page/count/validateFields
- 错误路径脱敏验证
- `SqlErrorSanitizer` 对 PostgreSQL 错误消息的处理

如果环境变量未设置，所有测试通过 `@EnabledIfEnvironmentVariable` 自动跳过。

## 十、外部数据源配置

### 配置方式

外部数据源通过 `nocobase.data-sources` 在 `application.yml` 中配置：

```yaml
nocobase:
  data-sources:
    analytics:
      url: jdbc:postgresql://localhost:5432/analytics
      driver-class-name: org.postgresql.Driver
      username: analytics_user
      password: secret
      enabled: true
      dialect: postgresql
      read-only: true
```

**规则：**
- `"main"` key 是保留的，自动从 `spring.datasource` 创建，不可显式配置
- 外部数据源 key 必须匹配 `[A-Za-z][A-Za-z0-9_-]{0,63}`
- **`url` 是必填字段**，缺失时 Spring 启动失败
- **`dialect` 如果指定，必须是 `h2` 或 `postgresql`**，其他值（如 `mysql`）在启动时拒绝
- 外部数据源**不参与** JPA、Flyway、DDL 操作
- 元数据、ACL、用户、角色始终在主数据库（main）上
- 外部数据源是**只读**的，不支持 DDL 和跨 DB 事务

### 连接验证

当外部数据源首次被解析时（`SqlDataSourceResolver.resolve()`），系统会：

1. 创建 HikariCP 连接池（只读模式，最大 5 连接）
2. 验证驱动是否支持 read-only 模式（`verifyReadOnlyMode`）
3. **主动打开一个连接并立即关闭**（`validateConnection`）以确认连接可用
4. 连接验证失败 → 数据源被标记为不可用（unavailable），抛出已脱敏的错误

### 失败隔离与恢复策略

外部数据源连接失败时：
- 连接错误被脱敏后记录日志（不含 JDBC URL、用户名、密码、SQL 文本）
- 数据源被标记为**不可用**（unavailable），添加到 `unavailableDataSources` 集合
- 引用不可用数据源的 SQL collection → **invalid collection**
- 主系统和其他数据源**不受影响**

**不可用状态不是永久的。** 恢复策略：

1. **`refreshDataSource(String dataSourceKey)`** — 显式刷新数据源：
   - 关闭旧的外部数据源连接池（如果存在）
   - 从不可用集合中移除
   - 重新创建并验证连接
   - 成功 → 清除不可用标记，返回 true
   - 失败 → 重新标记不可用，返回 false

2. **`CollectionRuntimeService.reload(collectionName)`** — 重载 SQL collection 时：
   - 自动提取 collection 的 `dataSourceKey`
   - 调用 `refreshDataSource()` 尝试恢复数据源
   - 如果数据源恢复成功，collection 可以正常加载
   - 如果数据源仍然不可用，collection 进入 invalid 状态

### 数据源生命周期

```
配置加载 → 首次 resolve → 连接验证 → 成功: 缓存到 registry / 失败: 标记 unavailable
                                                    ↓
                                          后续 resolve 直接返回缓存
                                                    ↓
                              reload/refresh → 关闭旧连接 → 重新验证 → 更新状态
                                                    ↓
                                         @PreDestroy → 关闭所有外部连接池
```

### 方言选择策略

`SqlDataSourceResolver.resolveDialect(String dataSourceKey)` 按以下优先级确定方言：

1. **显式配置的 `dialect`** — 如果配置了 `h2` 或 `postgresql`，直接使用
2. **JDBC URL 自动检测** — `jdbc:postgresql:` → PostgreSQL, `jdbc:h2:` → H2
3. **主数据源兜底** — 主数据源无法确定方言时，默认 H2 并记录警告
4. **外部数据源严格模式** — 外部数据源无法确定方言时，**抛出 `IllegalArgumentException`（不静默降级为 H2）**

外部数据源不支持 `h2` 和 `postgresql` 以外的方言。配置未知方言值（如 `mysql`）在启动时即被 `NocobaseDataSourceProperties.validateConfigFields()` 拒绝。

### Query Governance 默认值

| 参数 | 默认值 | 配置属性 | 说明 |
|------|--------|----------|------|
| `maxPageSize` | 200 | `nocobase.sql.max-page-size` | 单次查询最大返回行数 |
| `queryTimeout` | 30s | `nocobase.sql.query-timeout-seconds` | SQL 查询超时时间 |
| `validationTimeout` | 5s | `nocobase.sql.validation-timeout-seconds` | 字段校验查询超时时间 |

### 日志脱敏边界

所有 SQL collection 操作的结构化日志遵循以下脱敏规则：

**允许记录：**
- collection 名称、dataSourceKey、操作类型（list/get/validateFields）
- 执行耗时（durationMs）、返回行数（pageSize，仅成功时）
- 成功/失败状态、错误类别（syntax_error、connection_error 等）

**禁止记录：**
- SQL 文本（配置 SQL 或生成的查询 SQL）
- 参数值（命名参数值、用户 filter 值、ACL scope 值）
- JDBC URL、用户名、密码
- 原始异常消息（统一使用 `SqlErrorSanitizer.sanitizeForLog()` 脱敏）

### dataSourceKey 规范值

- 标准值：`"main"`
- 已弃用别名：`"default"`（自动规范化为 `"main"`）
- 缺失时默认：`"main"`

---

## 十一、实现优先级

1. **Phase 1**: SQL collection 基础 list 查询（外层包裹 + 参数绑定）
2. **Phase 2**: filter/sort/page 集成
3. **Phase 3**: ACL scope + 字段权限
4. **Phase 4**: `get` 支持（需 primary key 配置）
5. **Phase 5**: 命名参数 `:param` 支持
6. **Phase 6**: 外部数据源、方言抽象、字段元数据校验、失败隔离

---

## 十二、安全边界

### 数据流安全边界

```
┌─────────────────────────────────────────────────────────────┐
│ 配置层（仅管理员可操作）                                        │
│   SQL 文本 → 来自 CollectionEntity 配置（数据库存储）             │
│   参数元数据 → 来自 options.parameters（管理员声明）               │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ 运行时层                                                      │
│   运行时值 → 仅通过参数绑定（命名参数 → ? 占位符）                  │
│   currentUser 值 → 通过 CurrentUserContext 解析，不可拼接 SQL   │
│   外层 filter/sort/scope → 必须通过字段白名单 + 参数绑定          │
└─────────────────────────────────────────────────────────────┘
```

### 核心规则

- SQL 文本只能来自管理员配置（`CollectionEntity.sql`），运行时不可修改
- 所有运行时值（用户输入、上下文变量）必须通过参数绑定，禁止拼接
- 外层 filter/sort/scope 必须经过字段白名单校验（`FilterCompiler` + `CollectionDefinition.fields`），并通过 `?` 参数绑定
- SQL 必须通过白名单校验（禁止 DDL、多语句）
- 用户 SQL 只能包含 SELECT 语句
- 禁止 `;` 分隔多语句
- 禁止 `--` 和 `/* */` 注释注入
- 所有用户输入值通过 `?` 参数绑定
- 外层 scope 和 filter 通过 `FilterCompiler` 安全编译

---

## 十三、无效 Collection 策略

当 collection 在构建/加载阶段出现配置错误时，系统采用 **fail-fast 追踪** 策略：

### 进入无效追踪的错误类型

| 错误类型 | 示例 | 行为 |
|---------|------|------|
| **元数据错误** | `CollectionEntity` 数据不完整、字段缺失 | 记录到 `invalidCollections`，跳过该 collection |
| **SQL options JSON 解析错误** | `options` 字段不是合法 JSON | 记录到 `invalidCollections`，跳过该 collection |
| **参数声明错误** | 参数类型不支持、必填参数缺少 defaultValue、SQL 引用未声明参数、声明了但未使用的参数 | 记录到 `invalidCollections`，跳过该 collection |
| **SQL 验证错误** | SQL 包含分号、注释、DDL 关键字 | 记录到 `invalidCollections`，跳过该 collection |

### 核心行为

- **`CollectionRuntimeService.loadAll()`** 遍历所有 collection 实体，构建失败时捕获异常并记录到 `invalidCollections` map，不会中断整个加载流程
- **`CollectionRuntimeService.reload()`** 单个 collection 重载时，验证失败直接抛出异常（caller 自行处理）
- **`getInvalidCollections()`** 返回不可修改的 map，key 为 collection name，value 为已脱敏的错误消息（不含 SQL 文本）
- 每次 `loadAll()` 调用前会清空 `invalidCollections` map

---

## 十四、Phase 2: Named Parameter Support

### 参数元数据 Schema

在 CollectionEntity 配置中新增 `parameters` 字段，描述 SQL 中命名参数的元数据：

```json
{
  "name": "active_users",
  "type": "sql",
  "sql": "SELECT id, email, nickname, status FROM users WHERE status = :status AND role = :role",
  "parameters": [
    {"name": "status", "type": "string", "source": "static", "defaultValue": "active", "required": true},
    {"name": "role", "type": "string", "source": "static", "defaultValue": "user", "required": false}
  ],
  "fields": [
    {"name": "id", "type": "bigInt"},
    {"name": "email", "type": "string"},
    {"name": "nickname", "type": "string"},
    {"name": "status", "type": "string"}
  ]
}
```

### 参数类型

| 类型 | 说明 | 示例 | 底层 Java 类型 |
|------|------|------|---------------|
| `string` | 字符串 | `"active"` | `String` |
| `number` | 数值 | `42`, `3.14` | `Integer`/`Long`/`BigDecimal` |
| `boolean` | 布尔值 | `true`, `false` | `Boolean` |
| `date` | 日期 | `"2026-08-01"` | `java.sql.Date` (ISO_LOCAL_DATE) |
| `datetime` | 日期时间 | `"2026-08-01T12:00:00"` | `java.sql.Timestamp` (ISO_LOCAL_DATE_TIME) |

**注意**: `datetime` 类型目前仅支持 `ISO_LOCAL_DATE_TIME` 格式（如 `"2026-08-01T12:00:00"`），不支持带时区偏移的格式（如 `"2026-08-01T12:00:00+08:00"` 或 `"2026-08-01T12:00:00Z"`）。

### 参数来源

| source | 说明 | 状态 |
|--------|------|------|
| `static` | 配置时指定的固定值或默认值 | **Phase 2 (已实现)** |
| `currentUser` | 当前用户上下文（`currentUser.id` 和 `currentUser.email`） | **Phase 2 (已实现)** |
| `context` | 请求上下文变量 | **未来（不支持）** |
| `request` | 请求参数/runtime 变量 | **未来（不支持）** |
| `tenant` | 多租户上下文 | **未来（不支持）** |

Phase 2 已实现 `static` 和 `currentUser` 来源。`currentUser` 支持 `path: "id"`（返回 `Long` 类型的用户 ID）和 `path: "email"`（返回 `String` 类型的用户邮箱）。`context`、`request`、`tenant` 来源尚未实现。

### 绑定顺序

SQL 执行时参数按以下顺序绑定到 JDBC `?` 占位符：

1. **配置 SQL 参数** — `SqlNamedParameterParser` 将 `:name` 转换为 `?`，按出现顺序排列
2. **外层 filter/scope 参数** — `FilterCompiler` 编译的 WHERE 条件参数
3. **分页参数** — `LIMIT ? OFFSET ?`

### 错误处理

所有错误采用 **fail-fast** 策略，在启动/配置阶段即拒绝：

- **未声明参数**: 配置 SQL 中出现 `:param` 但 `parameters` 中未声明 → 抛出 `IllegalArgumentException`
- **缺少必填参数**: 参数标记为 `required: true` 但未提供 `defaultValue` 或运行时值 → 抛出 `IllegalArgumentException`
- **类型不匹配**: 参数值类型与声明的 `type` 不一致 → 抛出 `IllegalArgumentException`

### 示例

```sql
-- 配置 SQL（Phase 2, 命名参数）
SELECT id, email, nickname, status FROM users
WHERE status = :status AND created_at > :since

-- 经过 SqlNamedParameterParser 处理后
SELECT id, email, nickname, status FROM users
WHERE status = ? AND created_at > ?

-- 参数列表: [status, since]
```

**注意**: Phase 2 已实现命名参数支持。`SqlValidator` 允许 `:param`，`SqlNamedParameterParser` 将 `:param` 转换为 `?` 占位符，`SqlParameterMetadata` 管理参数元数据和默认值绑定。

---

## 十五、Phase 3: 多数据源支持（部分实现）

### 已实现

- **dataSourceKey** — CollectionEntity 的 `options.dataSourceKey` 支持解析和验证，默认为 `"main"`
- **外部数据源配置** — 通过 `nocobase.data-sources` 在 `application.yml` 中配置（HikariCP 连接池，只读）
- **方言抽象** — `SqlDialect` 接口 + H2/PostgreSQL 实现，用于标识符引用和分页
- **字段元数据校验** — `validateFields()` 在 collection 构建时验证字段列名是否存在于 SQL 结果集中
- **失败隔离** — 外部数据源连接失败记录为不可用，只影响引用该数据源的 SQL collection，主系统不受影响
- **元数据隔离** — 元数据、ACL、users、roles 始终在主数据库上，外部数据源不参与 JPA/Flyway/DDL

### 目标

允许 SQL collection 配置外部数据源（如只读副本、分析数据库、异构数据源），将其查询结果作为 NocoBase collection 暴露给前端。

### 3.1 dataSourceKey 配置

在 CollectionEntity 的 options 中新增 `dataSourceKey` 字段：

```json
{
  "name": "analytics_orders",
  "type": "sql",
  "sql": "SELECT order_id, customer, amount FROM analytics_db.orders WHERE date >= :since",
  "options": {
    "dataSourceKey": "analytics",
    "primaryKey": "order_id",
    "parameters": [
      {"name": "since", "type": "date", "source": "static", "defaultValue": "2026-01-01"}
    ]
  },
  "fields": [
    {"name": "order_id", "type": "bigInt"},
    {"name": "customer", "type": "string"},
    {"name": "amount", "type": "float"}
  ]
}
```

**规则**:
- `dataSourceKey` 为可选字段，缺失时默认使用主数据源（`main`）
- `dataSourceKey` 的值必须与 Spring 配置中定义的数据源 bean 名称匹配
- 一个 SQL collection 只能绑定一个数据源（不支持跨数据源 JOIN）

### 3.2 数据源注册与发现

在 Spring 配置中定义多个 `DataSource` bean：

```yaml
# application.yml
spring:
  datasource:
    primary:
      url: jdbc:postgresql://localhost:5432/nocobase
      username: nocobase
      password: ...
    analytics:
      url: jdbc:postgresql://localhost:5433/analytics
      username: readonly
      password: ...
```

对应的 Java 配置：

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.primary")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.analytics")
    public DataSource analyticsDataSource() {
        return DataSourceBuilder.create().build();
    }
}
```

### 3.3 Executor 选择

在 `SqlQueryCollectionExecutor` 中，根据 `dataSourceKey` 选择对应的 `JdbcTemplate`：

```java
@Component
public class SqlQueryCollectionExecutor {

    // 主数据源（默认）
    private final JdbcTemplate jdbcTemplate;

    // 额外数据源的 JdbcTemplate 注册表
    private final Map<String, JdbcTemplate> dataSourceTemplates;

    public SqlQueryCollectionExecutor(
            JdbcTemplate jdbcTemplate,
            @Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSourceTemplates = Map.of("analytics", analyticsTemplate);
    }

    /**
     * 根据 collection 的 dataSourceKey 选择 JdbcTemplate。
     */
    private JdbcTemplate selectJdbcTemplate(CollectionDefinition def) {
        String key = def.getDataSourceKey();
        if (key == null || key.equals("main")) {
            return jdbcTemplate;
        }
        JdbcTemplate template = dataSourceTemplates.get(key);
        if (template == null) {
            throw new IllegalArgumentException(
                    "Unknown dataSourceKey '" + key + "' for collection '" + def.getName() + "'");
        }
        return template;
    }
}
```

**设计要点**:
- 选择逻辑在 `executeList()` 和 `executeGet()` 方法入口处调用
- 所有后续 SQL 执行（count、data query）使用同一个选定的 JdbcTemplate
- 验证逻辑（`SqlValidator.validate()`）与数据源无关，在主数据源或任意数据源上均可执行

### 3.4 ACL 行为

对于外部数据源的 SQL collection，ACL 行为与主数据源一致：

| 层级 | 行为 |
|------|------|
| action 权限 | 与主数据源 collection 相同，通过 `canAction()` 检查 |
| scope filter | 编译为 WHERE 条件追加到子查询外层 |
| readable fields | 在结果集上过滤（与数据源无关） |
| writable fields | N/A（SQL collection 始终只读） |

**安全边界**（对多数据源场景特别重要）:

1. **外部 SQL 仍然是只读的** — 不支持 create/update/destroy
2. **SQL 验证与数据源无关** — `SqlValidator` 在所有数据源上执行相同的安全检查
3. **参数绑定机制不变** — 命名参数解析、static/currentUser 来源解析在 executor 选择数据源之前完成
4. **外部数据源需独立配置数据库凭证** — 建议使用只读凭证
5. **跨数据源事务** — 不保证（Phase 3 不解决分布式事务问题）

### 3.5 未实现部分

- 实际的 Spring DataSource 配置（`application.yml` 多数据源配置）
- `JdbcTemplate` 的自动注册与发现机制
- 数据源连接池隔离
- 数据源健康检查
- 外部数据源的表结构同步（外部数据源由 DBA 管理 schema，NocoBase 不管理）
- 跨数据源事务

### 3.6 实现优先级

Phase 3 的实现建议分步进行：

1. **Phase 3a**: 在 `CollectionEntity` / `CollectionDefinition` 中支持 `dataSourceKey` 字段的解析
2. **Phase 3b**: 实现 `SqlQueryCollectionExecutor` 中的多 `JdbcTemplate` 选择逻辑
3. **Phase 3c**: 实现 Spring 多数据源配置
4. **Phase 3d**: 集成测试（多数据源场景）