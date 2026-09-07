# Changelog

English | [中文](#变更日志)

## 2026-09-07 - Phase 13 Release Gate Fix

- Added release-gate fixes around PostgreSQL acceptance profile behavior, request ID propagation, production log cleanup, datasource response sanitization, and API gap documentation.
- Confirmed default Maven test suite result: `913 tests, 0 failures, 0 errors, 0 skipped`.
- Updated the system module API gap analysis to `113/113` covered endpoints.
- Added the next architecture task list: `NEXT_PHASE13_REVIEW_FIX_AND_PHASE14_TASKS.md`.
- Known release blockers remain: real PostgreSQL acceptance evidence, first-admin bootstrap, datasource query-parameter secret masking, RequestId lifecycle tests, and executable release gate automation.

## 2026-08-29 to 2026-09-04 - Java Backend Migration Phases

- Migrated collection metadata, fields, relations, indexes, dynamic DDL, view/sql collection boundaries, and runtime schema registry behavior.
- Established `DynamicRepository` as the unified data-layer gateway for public CRUD and internal relation/association access.
- Split public permission semantics from internal data-layer semantics for action permissions, scope checks, readable fields, writable fields, read-after-write, and through-table operations.
- Implemented SQL collection support across validation, subquery wrapping, parameter binding, runtime context, datasource resolution, external datasource governance, and PostgreSQL-specific acceptance tests.
- Implemented required system module APIs for auth, users, roles, ACL, system settings, collection manager, UI schema storage/templates, plugins, data sources, and audit logs.
- Added production hardening for JWT secrets, datasource encryption master key checks, Hibernate DDL guardrails, H2 console restrictions, audit sanitization, and release readiness documentation.

---

# 变更日志

[English](#changelog) | 中文

## 2026-09-07 - Phase 13 发布门禁修复

- 完成 PostgreSQL acceptance profile、requestId 传播、生产日志清理、数据源响应脱敏、API gap 文档同步等 release gate 修复。
- 确认默认 Maven 测试结果：`913 tests, 0 failures, 0 errors, 0 skipped`。
- 将系统模块 API gap 分析更新到 `113/113` 端点覆盖。
- 新增下一批架构任务清单：`NEXT_PHASE13_REVIEW_FIX_AND_PHASE14_TASKS.md`。
- 仍保留的发布阻塞项：真实 PostgreSQL 验收证据、首个管理员 bootstrap、数据源 query 参数敏感值脱敏、RequestId 生命周期测试、可执行 release gate 自动化。

## 2026-08-29 至 2026-09-04 - Java 后端迁移阶段

- 迁移 collection 元数据、fields、关系字段、索引、动态 DDL、view/sql collection 边界和 runtime schema registry。
- 确立 `DynamicRepository` 作为公开 CRUD 与内部 relation/association 的统一数据层出口。
- 拆分公开权限语义和内部数据层语义，覆盖 action 权限、scope、可读字段、可写字段、read-after-write 和 through 表操作。
- 实现 SQL collection 多阶段能力，包括 SQL 校验、子查询包装、参数绑定、运行时上下文、datasource 解析、外部数据源治理和 PostgreSQL 验收测试。
- 实现必要系统模块 API：auth、users、roles、ACL、system settings、collection manager、UI schema storage/templates、plugins、data sources、audit logs。
- 增加生产加固：JWT secret、数据源加密 master key、Hibernate DDL guard、H2 console 限制、审计脱敏和 release readiness 文档。
