# 下一批开发任务：SQL Collection Phase 2 核心能力

> 面向 Claude 的后端开发任务清单。只修改 Java 后端，前端界面、前端调用方式和 API 形态保持不变。
> 本文基于 `SQL_COLLECTION_PHASE2_ENTRY_COMPLETION_SUMMARY.md` 的架构 review。

## Review 结论

本轮 Phase 2 entry 有实际进展，`mvn -q test` 本地通过，当前结果为 `146 tests, 0 failures, 0 errors`。已确认：

- `AclFilterInjector` 已用 `$alwaysFalse` 替换固定 `id` no-result filter。
- `FilterCompiler` 已支持 `$alwaysFalse -> 1 = 0`。
- SQL collection `get` 成功路径、get scope、get readable fields、无 get 权限等测试已补齐。
- SQL collection custom primaryKey、readable none 场景已补齐基础测试。
- `ActionScopeRelationReviewTest` 已移除 `@TestMethodOrder`，association 主路径已走 `AssociationActionService.add/list/remove/set`。
- 新增 `DdlBoundaryTest`，覆盖 SQL collection DDL 边界和 primaryKey fail-fast 基础回归。

但仍有几个收口问题必须先处理，再实现 named parameter：

- 测试中的 member 用户创建仍有隐患：用户已存在时 catch 后没有重新查询并设置 `memberUserId`，`authAsMember()` 可能保持 admin 身份。
- `DdlBoundaryTest` 仍使用 `@TestMethodOrder` 和共享静态 collection 名，部分测试会修改 metadata，重复运行同一上下文时可能污染后续测试。
- `ArchitectureBoundaryTest` 仍主要检查 `JdbcTemplate`，没有真正禁止 controller/relation/association import `SqlQueryCollectionExecutor`。
- `ArchitectureBoundaryTest` 读取文件失败时仍吞掉 `IOException`，会隐藏扫描失败。
- `SQL_QUERY_COLLECTION_DESIGN.md` 当前配置示例仍出现 `:status`，容易误导为 Phase 1 已支持 named parameter。
- `SqlQueryCollectionExecutor` 仍是 list/get 各自拼 SQL 的雏形，进入 named parameter 前需要整理 query plan。

## 全局约束

- 只改 Java 后端，不改前端。
- 前端继续使用现有 collection action，例如 `/api/{collection}:list`、`/api/{collection}:get`。
- SQL collection 入口保持 `Controller -> DynamicRepository -> SqlQueryCollectionExecutor`。
- `DynamicRepository` 仍是 action 权限、scope 和字段权限的统一出口。
- `SqlQueryCollectionExecutor` 只负责 SQL collection 查询执行，不判断 action 权限。
- Phase 2 可以支持 configured SQL named parameter，但参数来源必须来自后端 metadata/context，不从前端新增协议直传。

## P0-A：修复测试用户身份切换隐患

**目标**

ACL 测试必须确定切换到了 member 身份，不能因为测试用户已存在而继续沿用 admin。

**开发要求**

- 在 `SqlQueryCollectionTest`、`ActionScopeRelationReviewTest`、`AclPermissionTest` 中统一修复 member 创建逻辑：
  - 先按 email 查询已有用户。
  - 存在则复用并设置 `memberUserId`。
  - 不存在才创建。
  - 确保 member role 绑定存在；不存在则创建绑定。
- `authAsMember()` 中如果 `memberUserId == null` 必须 `fail(...)`，不能静默跳过。
- 不要吞掉创建用户、绑定角色时的异常；确实可忽略的重复绑定要用查询判断规避。

**验收标准**

- 故意让 member 用户预先存在时，测试仍然以 member 身份执行。
- `SqlQueryCollectionTest`、`ActionScopeRelationReviewTest`、`AclPermissionTest` 单独运行通过。
- 全量 `mvn -q test` 通过。

## P0-B：修复 DDLBoundaryTest 隔离性

**目标**

DDL 和 primaryKey 测试不依赖执行顺序，不污染同一 Spring 上下文下的后续测试。

**开发要求**

- 移除 `DdlBoundaryTest` 的 `@TestMethodOrder`。
- 每个测试使用独立 collection/table 名，或使用测试方法名后缀生成唯一名称。
- 避免在一个测试中修改共享 collection metadata 后影响另一个测试。
- 对会 drop metadata 的测试，明确验证 runtime registry 更新。
- 对 fail-fast 测试，finally 中恢复 metadata 或使用一次性 collection。

**验收标准**

- `DdlBoundaryTest` 单独运行两次通过。
- 打乱测试顺序仍通过。
- 全量 `mvn -q test` 通过。

## P0-C：补强架构边界测试

**目标**

防止后续开发绕过统一数据层，直接在 controller/service/relation/association 中调用 SQL executor 或 JDBC。

**开发要求**

- `ArchitectureBoundaryTest` 必须递归扫描 `src/main/java/com/nocobase`。
- 新增禁止规则：
  - controller 不得 import/use `SqlQueryCollectionExecutor`。
  - ordinary service 不得 import/use `SqlQueryCollectionExecutor`。
  - `RelationQueryService` 不得 import/use `SqlQueryCollectionExecutor`。
  - `AssociationActionService` 不得 import/use `SqlQueryCollectionExecutor`。
- 继续保留 `JdbcTemplate` allowlist，但 allowlist 要写清楚理由：
  - `DynamicRepository`
  - `SqlQueryCollectionExecutor`
  - `DdlSynchronizer`
  - `DialectAdapterFactory`
  - 暂留的 deprecated `CollectionManagerService`
- 文件读取失败必须 fail；只有文件不存在时才可跳过。
- `CollectionManagerService` 的 deprecated 状态要有测试验证，且不能被新代码注入。

**验收标准**

- 在任意 controller/service 中新增 `SqlQueryCollectionExecutor` import 时测试失败。
- 在 relation/association service 中新增 `JdbcTemplate` import 时测试失败。
- `mvn -q test` 通过。

## P0-D：修正 SQL 设计文档当前能力描述

**目标**

文档必须准确表达当前能力和 Phase 2 计划，避免误导后续开发把未实现能力当成已支持。

**开发要求**

- 修改 `SQL_QUERY_COLLECTION_DESIGN.md`：
  - 当前能力示例不得使用 `:status` 这类 named parameter。
  - Phase 1/当前状态明确写为“不支持 configured SQL 参数”。
  - named parameter 放入 Phase 2 章节。
  - 文档中所有 SQL 示例要区分“当前可运行”和“Phase 2 设计”。
- 明确当前安全边界：
  - configured SQL 只能是 SELECT/WITH SELECT。
  - 拒绝 `?`、`:param`、`;`、注释、DDL/DML。
  - 外层 filter/scope/sort/page 由后端 metadata 编译。
- 明确 Phase 2 不改变前端 API。

**验收标准**

- 文档搜索 `:param`、`:status` 时，所有出现都在 Phase 2 计划或“不支持”语境中。
- 文档中至少保留 3 个前端 API 不变的示例。

## P1-E：整理 SqlQueryCollectionExecutor 为 Query Plan

**目标**

在引入 named parameter 前，把 executor 从直接字符串拼接整理为可测试、可演进的 query plan 构建流程。

**开发要求**

- 新增内部 value object，例如 `SqlQueryPlan`：
  - `String sql`
  - `List<Object> parameters`
  - 可选 `String countSql`
  - 可选 `List<Object> countParameters`
- 将 `executeList()` 拆为清晰步骤：
  - validate configured SQL。
  - build subquery wrapper。
  - build projection。
  - compile outer filter/scope。
  - build sort。
  - build count query。
  - append pagination。
- 将 `executeGet()` 复用相同的 filter/wrapper 构建逻辑。
- `buildSelectClause`、`buildSortClause` 字段校验规则与 `DynamicRepository` 保持一致。
- 不要把 ACL action 检查放进 executor。

**验收标准**

- SQL list/get 行为不回退。
- 现有 SQL collection 测试全部通过。
- 新增 executor query plan 单元测试，覆盖 SQL/params/countSql/countParams。

## P1-F：实现 named parameter 解析器但暂不放开执行

**目标**

先建立可靠的 named parameter 预处理组件，为后续启用 metadata 参数绑定做准备。

**开发要求**

- 新增组件，例如 `SqlNamedParameterParser`。
- 输入 configured SQL，输出：
  - 转换后的 JDBC SQL。
  - 参数名顺序列表。
- 解析规则：
  - 识别 `:name`，name 仅允许 `[A-Za-z_][A-Za-z0-9_]*`。
  - 支持重复参数，按出现顺序输出。
  - 忽略单引号字符串字面量中的 `:name`。
  - 忽略双引号 identifier 中的 `:name`。
  - 不误识别 PostgreSQL `::type`。
  - 不误识别 URL 或时间字面量中的冒号。
- 当前 `SqlValidator` 仍拒绝 named parameter；本任务只做 parser 和测试，不接入 executor。

**验收标准**

- 新增纯单元测试，不依赖 Spring。
- 覆盖单参数、重复参数、多参数顺序、字符串字面量、quoted identifier、PostgreSQL cast、URL/time literal。
- 当前 SQL collection 行为不改变。

## P1-G：设计 SQL 参数 metadata schema

**目标**

明确 Phase 2 参数来源和类型系统，避免未来把前端输入直接拼入 configured SQL。

**开发要求**

- 更新 `SQL_QUERY_COLLECTION_DESIGN.md`，新增 `parameters` metadata schema：
  - 参数名。
  - 类型：string/number/boolean/date/datetime。
  - 来源：static/currentUser/context，前端 request 暂缓。
  - 默认值。
  - required。
- 明确初版启用范围：
  - 支持 static 参数。
  - 支持 currentUser.id/currentUser.roleNames 可作为后续或本轮可选项。
  - 不支持前端任意传参。
- 明确错误处理：
  - SQL 中出现未声明参数时 fail-fast。
  - metadata 声明未使用参数时允许或警告，需文档定论。
  - 类型不匹配时 fail-fast。

**验收标准**

- 文档包含 metadata JSON 示例。
- 文档说明参数绑定顺序：configured SQL 参数先于 outer filter/scope 参数，pagination 参数最后。
- 文档明确前端 API 不变。

## P2-H：实现 metadata 参数绑定的最小闭环

**目标**

在不改前端的前提下，启用 SQL collection configured SQL 的最小 named parameter 能力。

**前置条件**

必须等 P1-E、P1-F、P1-G 完成后再做。

**开发要求**

- 新增参数 resolver，例如 `SqlQueryParameterResolver`。
- 从 `CollectionDefinition.options` 读取 SQL 参数 metadata。
- 初版只支持 static 参数：
  - string
  - number
  - boolean
- parser 将 `:param` 转为 `?`。
- resolver 根据 parser 输出顺序绑定参数值。
- `SqlValidator` 调整为：
  - 继续拒绝 JDBC `?`。
  - 允许 named parameter 只在 parser+resolver 闭环中执行。
  - 仍拒绝 `;`、注释、DDL/DML。
- `executeList/executeGet/count` 参数顺序：
  - configured SQL named parameters。
  - outer filter/scope parameters。
  - pagination parameters。

**验收标准**

- SQL collection configured SQL 使用 static `:status` 可 list/get。
- 缺少参数声明时 fail-fast。
- 参数类型不匹配时 fail-fast。
- count query 与 data query 参数顺序一致。
- 前端 API 不变。

## P2-I：补 SQL validator 词法边界

**目标**

当前 validator 是字符串/正则策略，Phase 2 至少要避免明显误判和漏判。

**开发要求**

- 增加轻量 lexical scanner：
  - 跳过单引号字符串。
  - 跳过双引号 identifier。
  - 识别 comments、semicolon、forbidden keywords。
- 保守策略仍可拒绝复杂边界，但必须文档化。
- forbidden keyword 检测不要因为字符串字面量里的 `drop`、`delete` 误杀普通查询。
- `WITH` 查询必须确认最终仍是只读查询；不能只看前缀。

**验收标准**

- `SELECT 'drop table' AS text` 不因字符串字面量误判。
- `SELECT "delete" FROM t` 不因 quoted identifier 误判。
- `WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` 必须拒绝。
- comments、semicolon、DDL/DML 仍被拒绝。

## 并行开发建议

- 并行组 1：P0-A、P0-B。测试基建收口，优先做。
- 并行组 2：P0-C、P0-D。架构边界和文档一致性，可并行。
- 并行组 3：P1-E、P1-F。executor query plan 与 named parser 可并行，但合并时要对齐接口。
- 并行组 4：P1-G。文档 schema 可独立推进。
- 并行组 5：P2-H、P2-I。必须在 P1-E/P1-F/P1-G 后执行。

## Claude 交付要求

完成后输出总结文档：`SQL_COLLECTION_PHASE2_CORE_COMPLETION_SUMMARY.md`。

总结必须包含：

- 是否修改前端，答案必须是“否”。
- 每个 P0/P1/P2 任务的完成状态。
- 修改文件列表。
- SQL collection list/get 最终调用链。
- 测试用户身份切换修复说明。
- DDL/primaryKey 测试隔离修复说明。
- 架构边界 allowlist 与违规扫描说明。
- SQL query plan 结构说明。
- named parameter parser 测试矩阵。
- metadata 参数 schema 与启用范围。
- 如实现 P2-H，说明参数绑定顺序和错误处理。
- `mvn -q test` 的总测试数、失败数、错误数。
