# NocoBase Java Backend - Operation Guide

## 1. Profile Differences (dev / test / prod)

| Aspect | dev | test | prod |
|--------|-----|------|------|
| **Active profile** | `dev` (default) | `test` | `prod` |
| **Config file** | `application-dev.yml` | `application.yml` (test classpath) | `application-prod.yml` (create if needed) |
| **Database** | H2 file-based (`./storage/db/nocobase`) | H2 in-memory (`jdbc:h2:mem:testdb`) | PostgreSQL (recommended) |
| **H2 Console** | Enabled (`/h2-console`) | Disabled | Disabled |
| **JPA ddl-auto** | `update` | `none` (Flyway managed) | `validate` (recommended) |
| **Flyway** | Disabled | Enabled | Enabled (recommended) |
| **SQL logging** | DEBUG (Hibernate SQL + params) | WARN (root), DEBUG (nocobase) | WARN or ERROR |
| **Data init** | Creates system roles/collections/settings/schemas | Requires test fixtures | Creates system roles/collections/settings/schemas |

### Running with a specific profile

```bash
# Development (default)
mvn spring-boot:run

# Test profile
mvn spring-boot:run -Dspring-boot.run.profiles=test

# Production
mvn spring-boot:run -Dspring-boot.run.profiles=prod
# Or via JAR:
java -jar target/nocobase-java-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod
```

---

## 2. Main Datasource, External Datasource, SQL Collection, Dynamic DDL Boundaries

### 2.1 Main Datasource

- **Configured via** `spring.datasource.*` in `application.yml`.
- **Key**: reserved as `"main"` (auto-created from `spring.datasource`).
- **Purpose**: stores all metadata (collections, fields, users, roles, ACL, UI schemas, system settings).
- **JPA/Flyway**: only the main datasource participates in JPA entity management and Flyway migrations.
- **Writable**: the main datasource accepts writes (DDL and DML).
- **Cannot be configured explicitly** under `nocobase.data-sources.main` -- this will throw an error.

### 2.2 External Datasource

- **Configured via**:
  - `application.yml` under `nocobase.data-sources.<key>` (for static config), OR
  - Runtime API `POST /api/dataSources:create` (managed in `external_data_sources` table).
- **Key format**: `[A-Za-z][A-Za-z0-9_-]{0,63}` (e.g., `analytics`, `legacy_db`, `reporting_pg`).
- **Key cannot be** `"main"` or `"default"` (reserved).
- **Dialect**: only `h2` and `postgresql` are supported. MySQL is explicitly unsupported.
- **Always read-only**: external datasources use `HikariDataSource.setReadOnly(true)`. Setting `readOnly=false` is rejected.
- **No JPA/Flyway/DDL**: external datasources never participate in JPA entity management, Flyway migrations, or DDL operations.
- **Connection pool**: minimal (max 5 connections, 10s timeout, 5min idle, 10min max lifetime).
- **Failure isolation**: if an external datasource fails to connect, it is marked "unavailable" but the main system continues operating. SQL collections referencing it become invalid.

### 2.3 SQL Collection

- **Type**: `"sql"` in the `collections` table.
- **Purpose**: maps a raw SQL query to a read-only virtual collection.
- **Configuration**: stores the SQL text in the `sql` column and the `dataSourceKey` in the `options` JSON.
- **Read-only**: SQL collections are always read-only. Writes are rejected.
- **Parameter support**: supports `{{paramName}}` placeholder syntax with type declarations in `options.parameters`.
- **Validation**: SQL is validated at load time for safety (only SELECT statements allowed, no destructive operations).
- **Field metadata**: field metadata must match the SQL result set columns. Validated at load time.
- **dataSourceKey**: defaults to `"main"`. Can reference an external datasource by key.

### 2.4 Dynamic DDL Boundaries

- **Only for physical collections**: DDL operations (CREATE TABLE, ALTER TABLE ADD/DROP COLUMN, DROP TABLE) are only executed for `type=physical` collections.
- **View/SQL collections**: `type=view` and `type=sql` collections do NOT trigger any DDL.
- **System fields**: `id`, `created_at`, `updated_at` are immutable (cannot be dropped via the API).
- **System collections**: collections with `system=true` cannot be deleted.
- **Concurrency**: per-collection-name locks prevent concurrent DDL operations on the same collection.
- **DDL before reload**: DDL is always executed BEFORE the runtime registry is reloaded, ensuring consistency.

---

## 3. Required Environment Variables and Forbidden Default Configs

### 3.1 Required Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `NOCOBASE_JWT_SECRET` | JWT signing secret (min 32 chars / 256 bits) | **Yes** (production) |
| `SPRING_DATASOURCE_URL` | Main database JDBC URL | **Yes** |
| `SPRING_DATASOURCE_USERNAME` | Main database username | **Yes** |
| `SPRING_DATASOURCE_PASSWORD` | Main database password | **Yes** |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | Main database driver class | No (auto-detected) |

### 3.2 Forbidden Default Configs

The following configurations will cause the application to **refuse to start**:

1. **JWT secret not configured**: if `nocobase.jwt.secret` is empty or missing, the application fails with a clear error.
2. **JWT secret is default value**: the hardcoded default `"default-secret-key-for-nocobase-java-backend-must-be-at-least-256-bits"` is rejected.
3. **JWT secret too short**: secrets shorter than 32 characters (256 bits) are rejected.
4. **External datasource missing URL**: `url` is required for all external data sources.
5. **External datasource with unsupported dialect**: only `h2` and `postgresql` are accepted.
6. **External datasource with `readOnly=false`**: external data sources must be read-only.
7. **Invalid datasource key format**: keys must match `[A-Za-z][A-Za-z0-9_-]{0,63}`.
8. **Explicit `main` datasource configuration**: the `main` key is reserved.
9. **Missing `spring.datasource.url`**: the main datasource URL is required for auto-creating the `main` key.

### 3.3 Production Configuration Example

```yaml
# application-prod.yml
spring:
  datasource:
    url: jdbc:postgresql://db-host:5432/nocobase
    driver-class-name: org.postgresql.Driver
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true

nocobase:
  jwt:
    secret: ${NOCOBASE_JWT_SECRET}    # REQUIRED: set via environment variable
    expiration: 86400000                # 24 hours

  data-sources:
    analytics:
      url: jdbc:postgresql://analytics-host:5432/analytics
      driver-class-name: org.postgresql.Driver
      username: ${ANALYTICS_DB_USER}
      password: ${ANALYTICS_DB_PASSWORD}
      enabled: true
      dialect: postgresql
      read-only: true

logging:
  level:
    root: WARN
    com.nocobase: INFO
```

---

## 4. Commands

### 4.1 Run Tests

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=CollectionAndFieldMetadataTest

# Run a specific test method
mvn test -Dtest=CollectionAndFieldMetadataTest#testCreateCollectionWithFields

# Skip tests
mvn package -DskipTests
```

### 4.2 PostgreSQL Integration Test

The PostgreSQL integration test requires a running PostgreSQL instance.

```bash
# Set PostgreSQL connection details
export PG_URL=jdbc:postgresql://localhost:5432/nocobase_test
export PG_USER=postgres
export PG_PASSWORD=postgres

# Run the PostgreSQL integration test
mvn test -Dtest=PostgreSqlIntegrationTest
```

The test is automatically skipped if the PostgreSQL connection is not available (uses `@EnabledIf` condition).

### 4.3 Local Startup

```bash
# Development mode (H2 database, auto-schema update)
mvn spring-boot:run

# Or with explicit profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Build and run JAR
mvn package -DskipTests
java -jar target/nocobase-java-1.0.0-SNAPSHOT.jar

# The application starts on port 13000 by default
# Access: http://localhost:13000
# H2 Console (dev only): http://localhost:13000/h2-console
```

---

## 5. Frontend-Not-Modified API Compatibility Rules

The NocoBase Java backend maintains protocol compatibility with the NocoBase frontend. The following rules ensure the frontend can work without modification:

### 5.1 Response Format

- **Success**: `{ "data": ... }` (single object) or `{ "data": [...], "meta": { "count": N, "page": N, "pageSize": N } }` (list)
- **Error**: `{ "errors": [{ "message": "..." }] }`
- All responses use `application/json` content type.

### 5.2 URL Conventions

- Both colon-style and slash-style paths are supported for all resource actions:
  - `POST /api/collections:create` and `POST /api/collections/create`
  - `GET /api/collections:list` and `GET /api/collections/list`
  - `POST /api/auth:signIn` and `POST /api/auth/signIn`
- The frontend can use either convention; both are accepted.

### 5.3 Authentication

- Token-based (JWT Bearer): `Authorization: Bearer <token>`
- Sign-in returns `{ "data": { "token": "...", "user": { "id": ..., "email": ..., "nickname": ... } } }`
- Token check returns `{ "data": { "id": ..., "email": ..., "nickname": ... } }`
- 401 Unauthorized for missing/invalid/expired tokens (stable error message, never leaks token text)

### 5.4 Collection/Field API

- Collection names are case-sensitive and must match the frontend convention.
- `filterByTk` parameter is supported as an alternative to `id` for resource identification.
- Field types use the NocoBase type system (e.g., `string`, `bigInt`, `boolean`, `hasMany`, `belongsTo`).
- `options` and `uiSchema` fields are preserved as JSON objects (round-trip compatible).

### 5.5 Error Messages

- Error messages are stable and non-technical. They never contain:
  - SQL text or table/column names
  - Stack traces
  - JDBC URLs, usernames, or passwords
  - JWT token text
  - Internal class names or package paths
- HTTP status codes follow REST conventions:
  - 200: Success
  - 400: Bad Request (validation error)
  - 401: Unauthorized
  - 403: Forbidden
  - 404: Not Found
  - 409: Conflict (duplicate resource)
  - 500: Internal Server Error (sanitized message)
  - 503: Service Unavailable (data source unavailable)

### 5.6 Pagination

- `page` and `pageSize` query parameters (1-based page numbering).
- Response includes `meta.count` (total items), `meta.page`, `meta.pageSize`.
- Default page size: 20, max: 200.

### 5.7 What is NOT Supported (Frontend Differences)

- MySQL dialect: only H2 and PostgreSQL are supported.
- WebSocket/real-time updates: not implemented.
- File upload/storage plugin: not implemented.
- Workflow engine: not implemented.