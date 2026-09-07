# NocoBase Java 后端开发计划

## 总体目标
将 NocoBase 后端从 Node.js/Koa 迁移到 Java/Spring Boot，保持前端不变。

## 技术栈
- Java 17
- Spring Boot 3.2
- Spring Data JPA (系统表)
- H2 (开发) / PostgreSQL (生产)
- Flyway (数据库迁移)
- Spring Security + JWT (认证)
- Maven (构建)

## 开发阶段

### Phase 0: 项目骨架 + 静态文件服务 (1-2天)
- [ ] Maven 项目创建
- [ ] Spring Boot 启动类
- [ ] 静态文件服务 + SPA fallback
- [ ] 运行时配置注入

### Phase 1: 数据库层 (2-3周)
- [ ] 系统表实体定义 (users, roles, collections, fields, uiSchemas, applicationPlugins, systemSettings)
- [ ] 种子数据初始化
- [ ] Repository 层

### Phase 2: 认证 API (1周)
- [ ] POST /api/auth:signIn
- [ ] POST /api/auth:signOut
- [ ] GET /api/auth:check
- [ ] JWT Token 管理

### Phase 3: 核心 API (2-3周)
- [ ] GET /api/uiSchemas:getTree
- [ ] GET /api/applicationPlugins:listEnabled
- [ ] GET /api/collections:list
- [ ] GET /api/systemSettings:get
- [ ] GET /api/users:check

### Phase 4: 通用 CRUD (2-3周)
- [ ] 动态 Collection REST API
- [ ] 查询参数解析 (filter, sort, page, pageSize, appends)
- [ ] ACL 权限控制

### Phase 5: 插件系统 (3-4周)
- [ ] 可选模块加载
- [ ] 工作流引擎
- [ ] 文件管理
- [ ] 通知系统

## 当前进度
- Phase 0: 进行中