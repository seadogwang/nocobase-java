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

### 3.1.1 Minimum Production Environment Variables

The following table lists the absolute minimum environment variables required for a production deployment. Without these, the application will refuse to start.

| Variable | Description | Required | Example |
|----------|-------------|----------|---------|
| `NOCOBASE_JWT_SECRET` | JWT signing secret (min 32 chars / 256 bits) | **Yes** | `openssl rand -base64 32` |
| `SPRING_DATASOURCE_URL` | Main database JDBC URL (PostgreSQL) | **Yes** | `jdbc:postgresql://db-host:5432/nocobase` |
| `SPRING_DATASOURCE_USERNAME` | Main database username | **Yes** | `nocobase` |
| `SPRING_DATASOURCE_PASSWORD` | Main database password | **Yes** | (secure password) |

**Optional but recommended:**

| Variable | Description | Example |
|----------|-------------|---------|
| `NOCOBASE_ADMIN_EMAIL` | Initial admin email | `admin@example.com` |
| `NOCOBASE_ADMIN_PASSWORD` | Initial admin password | (secure password) |
| `NOCOBASE_ADMIN_NICKNAME` | Initial admin nickname | `Super Admin` |
| `NOCOBASE_DATA_SOURCE_ENCRYPTION_MASTER_KEY` | AES-256-GCM master key for external data source password encryption | `openssl rand -base64 32` |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `prod` |
| `SERVER_PORT` | HTTP server port | `13000` (default) |
| `LOGGING_LEVEL_ROOT` | Root log level | `WARN` |
| `LOGGING_LEVEL_COM_NOCOBASE` | Application log level | `INFO` |

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

### 4.2 PostgreSQL Acceptance & Release Gate

PostgreSQL acceptance (Gate 3) is **mandatory** for release — it is never
silently skipped. The canonical release-gate script supports two modes:

| Mode | When to use | PostgreSQL source | Env vars required |
|---|---|---|---|
| **Testcontainers (default)** | CI / local with Docker | `postgres:15-alpine` started automatically | none |
| **External PostgreSQL** | Pre-provisioned PG | A real PostgreSQL you own | `PG_URL`, `PG_USERNAME`, `PG_PASSWORD` |

```powershell
# Default Testcontainers mode (Docker must be available; no PG env vars needed)
pwsh ./scripts/release-gate.ps1

# External PostgreSQL mode (supply real credentials outside source control)
pwsh ./scripts/release-gate.ps1 -RequireExternalPg
#   $env:PG_URL      = "jdbc:postgresql://host:5432/nocobase"
#   $env:PG_USERNAME = "..."
#   $env:PG_PASSWORD = "..."
```

The script runs all four gates:

1. `mvn test` (H2 default profile) — must pass with 0 failures/errors/skipped.
2. `mvn flyway:validate` — six migrations must validate.
3. `mvn test -Ppostgresql-acceptance` — `PostgreSqlIntegrationTest` must run
   (tests > 0) and pass with 0 failures/errors/skipped, then `ReleaseGateVerifier`
   must report PASS for the surefire report. A skipped Gate 3 is a hard FAIL.
4. Sensitive-code scan of `src/main` — no `System.out.print`,
   `.printStackTrace()`, hardcoded secrets, or sensitive log params.

The script writes `RELEASE_GATE_RESULT.md` from the actual run — do not
hand-edit it. If Docker or external PostgreSQL is unavailable, Gate 3 stays
BLOCKED and the report must not claim success.

To run only the PostgreSQL acceptance outside the full gate:

```bash
# Testcontainers (Docker required)
mvn test -Ppostgresql-acceptance

# External PostgreSQL
mvn test -Ppostgresql-acceptance -Dpostgresql.external.pg=true \
    -DPG_URL=jdbc:postgresql://host:5432/nocobase \
    -DPG_USERNAME=... -DPG_PASSWORD=...
```

(Note: `PG_USERNAME`, not `PG_USER`.)

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

---

## 6. Production Startup

### 6.1 Prerequisites

- **Java 17** or later (OpenJDK or compatible)
- **PostgreSQL 14** or later (recommended for production; H2 is for development only)
- At least **1 GB RAM** allocated to the JVM (`-Xmx1g` recommended minimum)
- Sufficient disk space for the database and application logs

### 6.2 Build

```bash
# Build the application JAR (skip tests for faster builds)
mvn clean package -DskipTests

# The JAR is produced at:
# target/nocobase-java-1.0.0-SNAPSHOT.jar
```

### 6.3 Run

```bash
# Set required environment variables
export NOCOBASE_JWT_SECRET="$(openssl rand -base64 32)"
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/nocobase"
export SPRING_DATASOURCE_USERNAME="nocobase"
export SPRING_DATASOURCE_PASSWORD="your-secure-password"
export SPRING_PROFILES_ACTIVE="prod"

# Start the application
java -Xmx1g -jar target/nocobase-java-1.0.0-SNAPSHOT.jar
```

### 6.4 Verify Startup

After starting, verify the application is healthy:

```bash
# Check the health endpoint
curl http://localhost:13000/api/health

# Expected response:
# {
#   "status": "UP",
#   "timestamp": "2026-09-09T10:00:00",
#   "components": {
#     "database": { "status": "UP", "type": "PostgreSQL" },
#     "flyway": { "status": "MIGRATED", "version": "1.0" },
#     "runtimeRegistry": { "status": "LOADED", "collectionCount": N },
#     "externalDataSources": { "status": "UP", "count": 0, "sources": [] }
#   }
# }
```

If `"status": "DOWN"` or any component is unhealthy, see Section 9 (Troubleshooting).

### 6.5 systemd Service Example

Create `/etc/systemd/system/nocobase.service`:

```ini
[Unit]
Description=NocoBase Java Backend
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=nocobase
Group=nocobase
WorkingDirectory=/opt/nocobase

Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/nocobase"
Environment="SPRING_DATASOURCE_USERNAME=nocobase"
Environment="SPRING_DATASOURCE_PASSWORD=your-secure-password"
Environment="NOCOBASE_JWT_SECRET=your-256-bit-secret"
Environment="SERVER_PORT=13000"

ExecStart=/usr/bin/java -Xmx1g -jar /opt/nocobase/nocobase-java.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=nocobase

# Security hardening
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/opt/nocobase/storage /opt/nocobase/logs

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable nocobase
sudo systemctl start nocobase

# Check status
sudo systemctl status nocobase

# View logs
sudo journalctl -u nocobase -f
```

---

## 7. Upgrade

### 7.1 Pre-Upgrade Checklist

1. **Backup the database**
   ```bash
   pg_dump -U nocobase -h localhost nocobase > nocobase_backup_$(date +%Y%m%d_%H%M%S).sql
   ```

2. **Check the current version**
   ```bash
   curl http://localhost:13000/api/health | jq '.components.flyway'
   ```
   Note the current Flyway version and migration state.

3. **Review the changelog** for any breaking changes, new required environment variables, or configuration changes.

4. **Test the upgrade** in a staging environment before applying to production.

### 7.2 Upgrade Steps

1. **Stop the application**
   ```bash
   sudo systemctl stop nocobase
   # or
   kill <pid>
   ```

2. **Backup the current JAR** (for rollback)
   ```bash
   cp /opt/nocobase/nocobase-java.jar /opt/nocobase/nocobase-java.jar.backup
   ```

3. **Replace the JAR** with the new version
   ```bash
   cp target/nocobase-java-1.0.0-SNAPSHOT.jar /opt/nocobase/nocobase-java.jar
   ```

4. **Start the application**
   ```bash
   sudo systemctl start nocobase
   ```

5. **Verify health**
   ```bash
   curl http://localhost:13000/api/health
   ```
   Wait for the response to show `"status": "UP"`. If Flyway shows `"status": "PENDING"`, it means new migrations are being applied. Wait and re-check until it shows `"status": "MIGRATED"`.

6. **Smoke test**
   - Sign in to the application
   - Verify a few key API endpoints
   - Check that external data sources are still available
   - Verify that collections are accessible

### 7.3 Flyway Migration Notes

- Flyway migrations run automatically on startup when `spring.flyway.enabled=true` (the default in production).
- Pending migrations are applied in order before the application starts serving requests.
- If a migration fails, the application will not start. Check the logs for the error.
- Flyway migrations are **not automatically rolled back** on downgrade. See Section 8 (Rollback).
- Do **not** manually modify the `flyway_schema_history` table unless you fully understand the consequences.

---

## 8. Rollback

### 8.1 Rollback Steps

1. **Stop the application**
   ```bash
   sudo systemctl stop nocobase
   ```

2. **Restore the database** from the pre-upgrade backup
   ```bash
   # Drop and recreate the database (if needed)
   psql -U postgres -c "DROP DATABASE IF EXISTS nocobase;"
   psql -U postgres -c "CREATE DATABASE nocobase OWNER nocobase;"

   # Restore from backup
   psql -U nocobase -h localhost nocobase < nocobase_backup_YYYYMMDD_HHMMSS.sql
   ```

3. **Restore the previous JAR**
   ```bash
   cp /opt/nocobase/nocobase-java.jar.backup /opt/nocobase/nocobase-java.jar
   ```

4. **Start the application**
   ```bash
   sudo systemctl start nocobase
   ```

5. **Verify health**
   ```bash
   curl http://localhost:13000/api/health
   ```

### 8.2 Considerations

- **Flyway is not auto-rolled back.** Flyway does not support automatic rollback of applied migrations. If the new version introduced Flyway migrations, those changes are present in the database. Restoring the database from the pre-upgrade backup is the only reliable method.
- **Database restore is the only reliable rollback method.** Do not attempt to manually reverse Flyway migrations or edit the `flyway_schema_history` table unless you are an experienced Flyway user.
- **External data sources** are not affected by the rollback — their configurations are stored in the main database and are restored together with the database backup.
- **Keep the backup JAR** until you have confirmed the new version is stable. Only then remove the backup.

---

## 9. Troubleshooting

### 9.1 Application Won't Start

| Symptom | Possible Cause | Solution |
|---------|---------------|----------|
| `NOCOBASE_JWT_SECRET` not set | Environment variable missing or empty | Set `NOCOBASE_JWT_SECRET` to a secure random string (min 32 chars) |
| JWT secret too short | Secret is less than 32 characters | Generate a new secret: `openssl rand -base64 32` |
| JWT secret is default value | Using the hardcoded default secret | Set a unique `NOCOBASE_JWT_SECRET` environment variable |
| Database connection refused | PostgreSQL is not running or wrong credentials | Verify PostgreSQL is running and credentials are correct |
| Flyway migration failed | Invalid migration or schema conflict | Check application logs for the specific migration error |
| Port already in use | Another process is using port 13000 | Change `SERVER_PORT` or stop the conflicting process |
| Out of memory | JVM heap too small | Increase `-Xmx` (e.g., `-Xmx2g`) |

### 9.2 Health Check Failures

| Component | Status | Meaning | Action |
|-----------|--------|---------|--------|
| `database` | `DOWN` | Main database is unreachable | Check database connectivity, credentials, and that PostgreSQL is running |
| `flyway` | `UNAVAILABLE` | Flyway bean is not available or an error occurred | Check if Flyway is enabled and configured correctly |
| `flyway` | `PENDING` | Pending migrations exist | Wait for migrations to complete, or check logs for migration errors |
| `runtimeRegistry` | `EMPTY` | No collections loaded | This is expected on first startup before data initialization |
| `runtimeRegistry` | `UNAVAILABLE` | Error accessing the registry | Check application logs for collection loading errors |
| `externalDataSources` | `DOWN` | One or more enabled external data sources are unavailable | Check the specific source in the `sources` array; verify connectivity |

### 9.3 Common Runtime Issues

| Symptom | Possible Cause | Solution |
|---------|---------------|----------|
| 401 Unauthorized on all API calls | JWT token expired or invalid | Re-authenticate via `/api/auth:signIn` |
| 503 Service Unavailable | External data source is down | Check the external database connectivity; collections referencing it will be invalidated |
| Slow API responses | Database connection pool exhausted or query performance | Check database load; review slow query logs |
| Collection not found (404) | Collection failed to load or was deleted | Check `runtimeRegistry` in health endpoint; review application logs |
| External data source not available | Connection failed or data source is disabled | Check the `externalDataSources` component in health endpoint; verify network and credentials |

### 9.4 Log Locations

| Environment | Log Location |
|-------------|-------------|
| systemd service | `journalctl -u nocobase -f` |
| Direct Java process | Console output (stdout/stderr) |
| File-based logging | Configure via `logging.file.name` in `application-prod.yml` |
| Default log level | `WARN` for root, `INFO` for `com.nocobase` |

### 9.5 Emergency Recovery

If the application is in an unrecoverable state:

1. **Stop the application immediately**
   ```bash
   sudo systemctl stop nocobase
   ```

2. **Preserve logs** for analysis
   ```bash
   sudo journalctl -u nocobase --no-pager > /tmp/nocobase_crash.log
   ```

3. **Restore from backup** (see Section 8 for full rollback procedure)

4. **Start the application** and verify health

5. **Investigate the root cause** using the preserved logs before re-applying any changes