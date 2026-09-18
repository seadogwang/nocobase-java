# NocoBase Java Backend

English | [中文](#nocobase-java-后端)

This repository contains a Java/Spring Boot backend migration for NocoBase. The goal is to keep the existing NocoBase frontend contract unchanged while replacing and hardening the backend data layer, permission model, and required system modules on the Java platform.

## Current Status

- Backend-only migration. The existing frontend UI and request paths must remain compatible.
- Java 17 + Spring Boot 3.2.5.
- Main metadata persistence uses Spring Data JPA/Hibernate for fixed system tables.
- Dynamic collection access uses a unified `DynamicRepository` data-layer gateway backed by JDBC/JdbcTemplate-style SQL execution, with ACL, scope, and field permission checks applied at the data layer.
- Flyway manages fixed metadata migrations. Runtime business collection DDL is handled by the dynamic DDL synchronizer.
- SQL collections are read-only and executed as wrapped subqueries so outer ACL, filter, sort, pagination, and count logic can be applied consistently.
- System plugins that are required by NocoBase are treated as backend modules rather than postponed optional plugins.

## Implemented Areas

- Collection metadata, fields, relations, indexes, runtime schema reload, and dynamic create/alter/drop behavior.
- Unified CRUD data gateway with ACL action checks, scope checks, readable/writable field filtering, and internal association semantics.
- SQL collection phases covering validation, parameter binding, runtime context, datasource resolution, read-only boundaries, PostgreSQL acceptance hooks, and production governance.
- Core system module APIs for auth, users, roles, ACL, collection manager, data sources, UI schema storage/templates, system settings, plugins, and audit logs.
- Release hardening for production config guards, request IDs, audit logging, datasource secret encryption, response sanitization, and release readiness documentation.

## Run Locally

```bash
mvn test
mvn spring-boot:run
```

Default server port: `13000`.

The default development database is H2 in PostgreSQL compatibility mode. PostgreSQL acceptance tests are excluded from the default test run and must be executed explicitly:

**Default mode (Testcontainers/Docker):**
```bash
mvn test -Ppostgresql-acceptance
```
No environment variables required. Docker must be available. Testcontainers auto-starts a PostgreSQL container.

**External PostgreSQL mode:**
```bash
PG_URL=jdbc:postgresql://localhost:5432/nocobase_test \
PG_USERNAME=postgres \
PG_PASSWORD=postgres \
mvn test -Ppostgresql-acceptance -Dpostgresql.external.pg=true
```
Use when Docker is unavailable or a dedicated PostgreSQL instance is preferred. All three env vars are required.

## Production Notes

Before using a non-test profile, configure strong secrets:

```bash
export NOCOBASE_JWT_SECRET="replace-with-at-least-32-characters"
export NOCOBASE_DATA_SOURCE_ENCRYPTION_MASTER_KEY="$(openssl rand -base64 32)"
```

Do not rely on default admin credentials. Production bootstrap must use the secure first-admin initialization flow tracked in the next development task list.

## Architecture Notes

The most important backend invariant is the unified data-layer path:

```text
Controller -> DynamicRepository <- RelationQueryService / AssociationActionService
```

Public APIs perform action permission, scope, and field permission checks. Internal relation/association paths may use scope-only or write-scope semantics when required by NocoBase behavior, but they must still go through the data-layer gateway and must not bypass ACL filtering with ad hoc SQL.

---

# NocoBase Java 后端

[English](#nocobase-java-backend) | 中文

本仓库是 NocoBase 后端 Java/Spring Boot 改造版本。目标是在不改变现有 NocoBase 前端界面和调用契约的前提下，将后端数据层、权限模型和必要系统模块迁移到 Java 平台，并逐步达到可发布标准。

## 当前状态

- 只改造后端，前端 UI 和请求路径保持兼容。
- 技术栈为 Java 17 + Spring Boot 3.2.5。
- 固定系统表的元数据持久化使用 Spring Data JPA/Hibernate。
- 动态 collection 访问统一走 `DynamicRepository` 数据层出口，底层使用 JDBC/JdbcTemplate 风格 SQL 执行，并在数据层统一注入 ACL、scope 和字段权限。
- Flyway 管理固定元数据表迁移。运行时业务 collection 的建表/改表/删表由动态 DDL 同步器负责。
- SQL collection 是只读集合，会包装为子查询，再在外层统一应用权限、过滤、排序、分页和 count。
- NocoBase 中属于系统必要能力的插件，在 Java 后端中按功能模块改造，而不是作为后期可选插件搁置。

## 已实现范围

- collection 元数据、fields、关系字段、索引同步、runtime schema reload、动态建表/改表/删表能力。
- 统一 CRUD 数据层出口，覆盖 ACL action、scope、可读/可写字段权限，以及 relation/association 内部权限语义。
- SQL collection 多阶段能力：SQL 校验、参数绑定、运行时上下文、真实 datasource 解析、只读边界、PostgreSQL 验收入口和生产治理。
- 核心系统模块 API：认证、用户、角色、ACL、collection manager、data sources、UI schema storage/templates、system settings、plugins、audit logs。
- 发布加固：生产配置检查、requestId、审计日志、数据源密码加密、响应脱敏和 release readiness 文档。

## 本地运行

```bash
mvn test
mvn spring-boot:run
```

默认服务端口：`13000`。

默认开发数据库为 H2 PostgreSQL 兼容模式。PostgreSQL 验收测试默认排除，需要显式运行：

```bash
PG_URL=jdbc:postgresql://localhost:5432/nocobase_test \
PG_USERNAME=postgres \
PG_PASSWORD=postgres \
mvn test -Ppostgresql-acceptance
```

## 生产说明

非 test profile 启动前必须配置强密钥：

```bash
export NOCOBASE_JWT_SECRET="replace-with-at-least-32-characters"
export NOCOBASE_DATA_SOURCE_ENCRYPTION_MASTER_KEY="$(openssl rand -base64 32)"
```

生产环境不能依赖默认管理员账号密码。安全的首个管理员初始化能力已列入下一批开发任务。

## 架构说明

最核心的后端约束是统一数据层调用路径：

```text
Controller -> DynamicRepository <- RelationQueryService / AssociationActionService
```

公开 API 必须检查 action 权限、scope 和字段权限。relation/association 的内部链路可以按 NocoBase 语义使用 scope-only 或 write-scope，但仍必须经过统一数据层出口，不能用零散 SQL 绕过 ACL 过滤。
