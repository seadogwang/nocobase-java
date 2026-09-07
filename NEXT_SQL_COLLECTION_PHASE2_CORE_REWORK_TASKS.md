# 下一批开发任务：SQL Collection Phase 2 Core 返工与验收

> 面向 Claude 的后端开发任务清单。只修改 Java 后端，前端界面、前端调用方式和 API 形态保持不变。
> 本文基于对 `NEXT_SQL_COLLECTION_PHASE2_CORE_TASKS.md` 的完成情况 review。

## Review 结论

本次不能验收为“Phase 2 Core 已完成”。

本地执行 `mvn -q test` 通过，当前结果为 `146 tests, 0 failures, 0 errors`，但源码状态仍停留在上一轮 Phase 2 entry：

- 未找到 `SQL_COLLECTION_PHASE2_CORE_COMPLETION_SUMMARY.md`。
- 未发现 `SqlQueryPlan` 或等价 query plan 结构。
- 未发现 `SqlNamedParameterParser` 或等价 named parameter parser。
- 未发现 SQL 参数 metadata schema 实现或完整设计更新。
- `SqlQueryCollectionExecutor` 仍是 list/get 各自字符串拼接。
- `SqlValidator` 仍是简单字符串/正则校验。
- `SQL_QUERY_COLLECTION_DESIGN.md` 当前示例仍包含 `:status`，容易误导为当前已支持 named parameter。
- `SqlQueryCollectionTest`、`ActionScopeRelationReviewTest`、`AclPermissionTest` 仍存在 member 用户已存在时 `memberUserId` 可能不赋值的问题。
- `DdlBoundaryTest` 仍使用 `@TestMethodOrder` 和共享静态 collection 名。
- `ArchitectureBoundaryTest` 仍未明确禁止 controller/service/relation/association import `SqlQueryCollectionExecutor`，且读取失败仍可能被吞掉。

本批任务目标：先完成前一批 P0/P1，再小步进入 named parameter 的后端能力准备。不要继续新增更高层业务功能。

## 全局约束

- 只改 Java 后端，不改前端。
- 前端 API 保持不变，仍使用现有 collection action。
- SQL collection 入口保持 `Controller -> DynamicRepository -> SqlQueryCollectionExecutor`。
- `DynamicRepository` 是 action 权限、scope、字段权限的统一出口。
- `SqlQueryCollectionExecutor` 不做 action 权限判断。
- named parameter 不允许从前端新增协议直传参数；参数必须来自后端 metadata/context。
- 未完成代码和测试前，不要输出“已完成”总结。

## P0-A：补齐交付总结文档

**目标**

每批任务必须有可 review 的完成总结，不能只说任务已完成。

**开发要求**

- 完成本批后新增 `SQL_COLLECTION_PHASE2_CORE_COMPLETION_SUMMARY.md`。
- 总结必须逐项列出：
  - P0/P1/P2 完成状态。
  - 修改文件列表。
  - 是否修改前端，答案必须是“否”。
  - 测试命令和测试结果。
  - 未完成项和原因。

**验收标准**

- review 时能直接打开该总结文件。
- 总结内容与源码实际状态一致。

## P0-B：修复测试 member 身份切换

**目标**

ACL 测试必须确定切换到了 member 身份，不能因为 member 用户已存在而继续沿用 admin。

**开发要求**

- 修改以下测试类：
  - `SqlQueryCollectionTest`
  - `ActionScopeRelationReviewTest`
  - `AclPermissionTest`
- 创建 member 用户前先 `userRepository.findByEmail(...)`。
- 如果用户已存在，复用并设置 `memberUserId`。
- 如果用户不存在，创建后设置 `memberUserId`。
- 确保 member role 绑定存在，不要重复插入导致异常。
- `authAsMember()` 中如果 `memberUserId == null` 必须 `fail(...)`。
- 不要吞掉用户创建或角色绑定异常。

**验收标准**

- 预先存在 member 用户时，测试仍以 member 身份执行。
- 以上 3 个测试类单独运行通过。
- 全量 `mvn -q test` 通过。

## P0-C：修复 DdlBoundaryTest 隔离性

**目标**

DDL/primaryKey 测试不依赖执行顺序，不污染同一 Spring 上下文。

**开发要求**

- 移除 `DdlBoundaryTest` 的 `@TestMethodOrder` 和 `@Order`。
- 每个测试使用独立 collection/table 名，或使用测试方法名生成唯一后缀。
- 不要让一个测试修改共享 collection metadata 后影响其他测试。
- 会 drop metadata 的测试，必须验证 runtime registry 已更新。
- fail-fast 测试必须在 finally 中恢复 metadata，或使用一次性 collection。

**验收标准**

- `DdlBoundaryTest` 单独连续运行两次通过。
- 打乱测试顺序仍通过。
- 全量 `mvn -q test` 通过。

## P0-D：补强 ArchitectureBoundaryTest

**目标**

防止 SQL executor 和 JDBC 访问绕过统一数据层。

**开发要求**

- 递归扫描 `src/main/java/com/nocobase` 下所有 Java 文件。
- 明确禁止以下位置 import/use `SqlQueryCollectionExecutor`：
  - controller
  - ordinary service
  - `RelationQueryService`
  - `AssociationActionService`
- 继续检查 `JdbcTemplate`，allowlist 必须最小化：
  - `DynamicRepository.java`
  - `SqlQueryCollectionExecutor.java`
  - `DdlSynchronizer.java`
  - `DialectAdapterFactory.java`
  - deprecated `CollectionManagerService.java`
- 文件读取失败必须 `fail(...)`。
- 文件不存在可以跳过，但不能吞掉真实 IO 异常。
- 验证 `CollectionManagerService` 标记了 `@Deprecated`，且没有被新代码注入。

**验收标准**

- 在 controller/service/relation/association 中新增 `SqlQueryCollectionExecutor` import 时测试失败。
- 在 relation/association 中新增 `JdbcTemplate` import 时测试失败。
- `mvn -q test` 通过。

## P0-E：修正 SQL 设计文档

**目标**

文档必须准确区分当前能力和 Phase 2 计划能力。

**开发要求**

- 修改 `SQL_QUERY_COLLECTION_DESIGN.md`。
- 当前能力示例不得使用 `:status` 或其他 named parameter。
- 当前状态明确写为：
  - 支持 SQL collection list/get。
  - 支持外层 filter/sort/page/scope/readable fields。
  - configured SQL 当前不支持 `?`。
  - configured SQL 当前不支持 `:param`。
- 将 named parameter 放到 Phase 2 计划章节。
- 所有 `:param`、`:status` 出现必须处于“计划支持”或“当前不支持”语境。
- 保留至少 3 个前端 API 不变示例。

**验收标准**

- `rg -n ':status|:param' SQL_QUERY_COLLECTION_DESIGN.md` 的结果不会误导为当前已支持。
- 文档与 `SqlValidator` 当前行为一致。

## P1-F：整理 SqlQueryCollectionExecutor 为 Query Plan

**目标**

把 SQL executor 从直接字符串拼接整理为可测试、可演进的 query plan 构建流程。

**开发要求**

- 新增 `SqlQueryPlan` 或等价 value object：
  - `sql`
  - `parameters`
  - `countSql`
  - `countParameters`
- `executeList()` 拆分为：
  - validate configured SQL
  - build subquery wrapper
  - build projection
  - compile outer filter/scope
  - build sort
  - build count query
  - append pagination
- `executeGet()` 复用相同 wrapper/filter 构建逻辑。
- 字段名仍必须通过 `CollectionDefinition` 校验。
- ACL action 权限仍留在 `DynamicRepository`。

**验收标准**

- SQL list/get 行为不回退。
- 新增 query plan 单元测试，覆盖 `sql/parameters/countSql/countParameters`。
- 全量 `mvn -q test` 通过。

## P1-G：实现 named parameter parser，但暂不启用执行

**目标**

先建立可靠的 named parameter 预处理组件，为后续 metadata 参数绑定做准备。

**开发要求**

- 新增 `SqlNamedParameterParser` 或等价组件。
- 输入 configured SQL，输出：
  - 转换后的 JDBC SQL。
  - 参数名顺序列表。
- 解析规则：
  - 识别 `:name`。
  - 参数名只允许 `[A-Za-z_][A-Za-z0-9_]*`。
  - 支持重复参数，按出现顺序输出。
  - 忽略单引号字符串中的冒号。
  - 忽略双引号 identifier 中的冒号。
  - 不误识别 PostgreSQL `::type`。
  - 不误识别 URL 或时间字面量中的冒号。
- 当前 `SqlValidator` 仍拒绝 named parameter；本任务只做 parser 和单元测试。

**验收标准**

- 新增纯单元测试，不依赖 Spring。
- 覆盖单参数、重复参数、多参数顺序、字符串、quoted identifier、PostgreSQL cast、URL/time literal。
- SQL collection 当前运行行为不改变。

## P1-H：设计 SQL 参数 metadata schema

**目标**

明确 Phase 2 参数来源和类型系统，避免未来直接拼接前端输入。

**开发要求**

- 更新 `SQL_QUERY_COLLECTION_DESIGN.md`，新增 `parameters` metadata schema。
- schema 至少包含：
  - 参数名。
  - 类型：string/number/boolean/date/datetime。
  - 来源：static/currentUser/context。
  - 默认值。
  - required。
- 明确初版启用范围：
  - 支持 static 参数。
  - currentUser/context 可作为后续扩展，若本轮不做必须写清楚。
  - 不支持前端任意传参。
- 明确参数绑定顺序：
  - configured SQL named parameters。
  - outer filter/scope parameters。
  - pagination parameters。
- 明确错误处理：
  - SQL 使用未声明参数时 fail-fast。
  - required 参数缺失时 fail-fast。
  - 类型不匹配时 fail-fast。

**验收标准**

- 文档包含 metadata JSON 示例。
- 文档明确前端 API 不变。
- 文档与 parser/resolver 计划一致。

## P2-I：metadata 参数绑定最小闭环

**前置条件**

必须等 P1-F、P1-G、P1-H 完成后再做。

**目标**

在不改前端的前提下，启用 SQL collection configured SQL 的最小 named parameter 能力。

**开发要求**

- 新增 `SqlQueryParameterResolver` 或等价组件。
- 从 `CollectionDefinition.options` 读取 SQL 参数 metadata。
- 初版只支持 static 参数：
  - string
  - number
  - boolean
- parser 将 `:param` 转成 `?`。
- resolver 根据 parser 输出顺序绑定参数值。
- `SqlValidator` 调整为：
  - 仍拒绝 JDBC `?`。
  - 允许 named parameter 只在 parser+resolver 闭环中执行。
  - 仍拒绝 `;`、注释、DDL/DML。
- `executeList/executeGet/count` 参数顺序必须稳定：
  - configured SQL named parameters。
  - outer filter/scope parameters。
  - pagination parameters。

**验收标准**

- SQL collection configured SQL 使用 static `:status` 可 list/get。
- 缺少参数声明时 fail-fast。
- required 参数缺失时 fail-fast。
- 参数类型不匹配时 fail-fast。
- count query 与 data query 参数顺序一致。
- 前端 API 不变。

## P2-J：补 SQL validator 词法边界

**目标**

将 validator 从纯正则提升到轻量词法扫描，避免明显误杀和漏判。

**开发要求**

- 增加 lightweight lexical scanner：
  - 跳过单引号字符串。
  - 跳过双引号 identifier。
  - 识别 comments。
  - 识别 semicolon。
  - 识别 forbidden keywords。
- forbidden keyword 检测不能误杀字符串字面量或 quoted identifier。
- `WITH` 查询不能只看前缀，必须确认内部没有 DML/DDL。
- 保守拒绝复杂边界可以接受，但必须在文档说明。

**验收标准**

- `SELECT 'drop table' AS text` 不误杀。
- `SELECT "delete" FROM t` 不误杀。
- `WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` 必须拒绝。
- comments、semicolon、DDL/DML 仍被拒绝。

## 并行开发建议

- 并行组 1：P0-A、P0-B、P0-C。测试基建和架构边界，优先完成。
- 并行组 2：P0-D、P1-H。文档一致性和 metadata schema 可并行。
- 并行组 3：P1-F、P1-G。query plan 与 named parser 可并行，但合并时要统一接口。
- 并行组 4：P2-I、P2-J。必须等 P1-F/P1-G/P1-H 完成后再启用。

## Claude 交付要求

完成后输出总结文档：`SQL_COLLECTION_PHASE2_CORE_COMPLETION_SUMMARY.md`。

总结必须包含：

- 是否修改前端，答案必须是“否”。
- 每个 P0/P1/P2 任务的完成状态。
- 修改文件列表。
- SQL collection list/get 最终调用链。
- 测试用户身份切换修复说明。
- DDLBoundaryTest 隔离修复说明。
- ArchitectureBoundaryTest 新增规则说明。
- SQL design 文档修正说明。
- Query plan 结构说明。
- Named parameter parser 测试矩阵。
- Metadata 参数 schema 说明。
- 如果实现 P2-I，说明 named parameter 绑定顺序和错误处理。
- `mvn -q test` 的总测试数、失败数、错误数。
