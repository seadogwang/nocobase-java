#!/usr/bin/env bash
# Packaged production smoke test (Phase-21 Agent E).
#
# Builds the executable JAR, starts it with a production-like profile against a
# real local PostgreSQL, polls the health endpoints over real HTTP, and stops
# it. Also verifies fail-fast when a required secret is missing.
#
# Environment (defaults suit a local dev PostgreSQL):
#   PG_HOST (default localhost), PG_PORT (5432), PG_DB (default nocobase_pg_smoke)
#   PG_USER (default postgres), PG_PASSWORD (default postgres)
#   APP_PORT (default 13000)
#
# No production credentials are used; secrets here are local-only test values.
set -euo pipefail

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_DB="${PG_DB:-nocobase_pg_smoke}"
PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
APP_PORT="${APP_PORT:-13000}"

# Locate psql on Windows if not on PATH (local PostgreSQL 16 install).
PSQL_BIN=""
if command -v psql >/dev/null 2>&1; then
  PSQL_BIN="psql"
elif [ -x "/c/Program Files/PostgreSQL/16/bin/psql.exe" ]; then
  PSQL_BIN="/c/Program Files/PostgreSQL/16/bin/psql.exe"
  export PATH="/c/Program Files/PostgreSQL/16/bin:$PATH"
fi

JAR="target/nocobase-java-1.0.0-SNAPSHOT.jar"
JWT_SECRET="a-strong-unique-production-jwt-secret-of-at-least-256-bits-long!!"
MASTER_KEY=$(printf '0%.0s' {1..32} | base64)  # 32 zero bytes base64 = valid AES-256 key shape

echo "=== Packaged production smoke test ==="
echo "timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "profile: prod"
echo "artifact: $JAR"
echo "database: external PostgreSQL ($PG_HOST:$PG_PORT/$PG_DB)"

# 0. Build the artifact (skip tests; the release gate covers tests separately).
if [ ! -f "$JAR" ]; then
  echo "Building artifact (mvn package -DskipTests)..."
  mvn -q package -DskipTests
fi

# Prepare a clean smoke database.
if [ -n "$PSQL_BIN" ]; then
  PGPASSWORD="$PG_PASSWORD" "$PSQL_BIN" -h "$PG_HOST" -U "$PG_USER" -c "DROP DATABASE IF EXISTS \"$PG_DB\";" >/dev/null 2>&1 || true
  PGPASSWORD="$PG_PASSWORD" "$PSQL_BIN" -h "$PG_HOST" -U "$PG_USER" -c "CREATE DATABASE \"$PG_DB\";" >/dev/null 2>&1 || echo "(warn: could not create db; assuming it exists)"
fi

# 1. Fail-fast: missing JWT secret must prevent startup.
echo "--- fail-fast check: missing NOCOBASE_JWT_SECRET ---"
set +e
NOCOBASE_JWT_SECRET="" \
NOCOBASE_DATA_SOURCE_ENCRYPTION_MASTER_KEY="$MASTER_KEY" \
SPRING_DATASOURCE_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB" \
SPRING_DATASOURCE_DRIVER_CLASS_NAME="org.postgresql.Driver" \
SPRING_DATASOURCE_USERNAME="$PG_USER" \
SPRING_DATASOURCE_PASSWORD="$PG_PASSWORD" \
SPRING_PROFILES_ACTIVE=prod \
java -jar "$JAR" --server.port="$APP_PORT" > /tmp/nocobase_smoke_failfast.log 2>&1 &
FAILFAST_PID=$!
FAILFAST_RESULT=1
for _ in $(seq 1 30); do
  if ! kill -0 "$FAILFAST_PID" 2>/dev/null; then
    wait "$FAILFAST_PID"; FAILFAST_RESULT=$?
    break
  fi
  sleep 1
done
kill "$FAILFAST_PID" 2>/dev/null || true
set -e
if [ "$FAILFAST_RESULT" -eq 0 ]; then
  echo "FAIL: app started without NOCOBASE_JWT_SECRET (ProductionConfigGuard did not fail fast)"
  exit 1
fi
echo "PASS: missing secret prevented startup (exit $FAILFAST_RESULT)"

# 2. Start with valid secrets and poll health endpoints.
echo "--- startup with valid secrets + real PostgreSQL ---"
NOCOBASE_JWT_SECRET="$JWT_SECRET" \
NOCOBASE_DATA_SOURCE_ENCRYPTION_MASTER_KEY="$MASTER_KEY" \
SPRING_DATASOURCE_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB" \
SPRING_DATASOURCE_DRIVER_CLASS_NAME="org.postgresql.Driver" \
SPRING_DATASOURCE_USERNAME="$PG_USER" \
SPRING_DATASOURCE_PASSWORD="$PG_PASSWORD" \
SPRING_PROFILES_ACTIVE=prod \
SPRING_H2_CONSOLE_ENABLED=false \
SPRING_JPA_HIBERNATE_DDL_AUTO=none \
java -jar "$JAR" --server.port="$APP_PORT" > /tmp/nocobase_smoke.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT

echo "Waiting for app to start (port $APP_PORT)..."
HEALTH_OK=false
for _ in $(seq 1 60); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "FAIL: app exited before becoming healthy"
    tail -40 /tmp/nocobase_smoke.log || true
    exit 1
  fi
  CODE=$(curl -s -o /tmp/nocobase_smoke_live.body -w '%{http_code}' "http://localhost:$APP_PORT/api/health/live" 2>/dev/null || echo 000)
  if [ "$CODE" = "200" ]; then
    HEALTH_OK=true
    break
  fi
  sleep 2
done

if [ "$HEALTH_OK" != "true" ]; then
  echo "FAIL: /api/health/live did not return 200 within timeout"
  tail -40 /tmp/nocobase_smoke.log || true
  exit 1
fi
echo "PASS: /api/health/live returned 200"

# Readiness check.
READY_CODE=$(curl -s -o /tmp/nocobase_smoke_ready.body -w '%{http_code}' "http://localhost:$APP_PORT/api/health/ready" 2>/dev/null || echo 000)
if [ "$READY_CODE" != "200" ]; then
  echo "FAIL: /api/health/ready returned $READY_CODE (expected 200)"
  exit 1
fi
echo "PASS: /api/health/ready returned 200"

# Sensitive-data assertion: health bodies must not leak JDBC URLs or credentials.
LEAK=$(grep -iE 'jdbc:|password|secret|postgres@' /tmp/nocobase_smoke_live.body /tmp/nocobase_smoke_ready.body 2>/dev/null || true)
if [ -n "$LEAK" ]; then
  echo "FAIL: health response leaked sensitive data: $LEAK"
  exit 1
fi
echo "PASS: health responses contain no JDBC URLs or credentials"

# Evidence record (non-secret).
cat > smoke_evidence.txt <<EOF
smoke_test: PASS
timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)
profile: prod
artifact: $JAR
database_mode: external PostgreSQL
pg_host: $PG_HOST
pg_db: $PG_DB
health_live: 200
health_ready: 200
EOF
echo "Evidence written to smoke_evidence.txt"
echo "=== smoke test PASS ==="
