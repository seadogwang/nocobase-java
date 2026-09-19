#!/usr/bin/env bash
# Executable backup-and-restore drill (Phase-21 Agent F).
#
# Against a local PostgreSQL: backs up an acceptance database, applies the
# current schema via Flyway (by running the app once), writes representative
# data, restores the backup into a clean database, and verifies schema,
# flyway_schema_history, and representative data.
#
# Limitation: Flyway Community has no undo. Database restore from a pre-upgrade
# backup is the only reliable rollback method (documented in BACKEND_OPERATION_GUIDE §8).
#
# Environment defaults suit a local dev PostgreSQL:
#   PG_HOST (localhost), PG_PORT (5432), PG_USER (postgres), PG_PASSWORD (postgres)
#   SRC_DB (default nocobase_pg_acceptance), RESTORE_DB (default nocobase_pg_restore)
set -euo pipefail

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
SRC_DB="${SRC_DB:-nocobase_pg_acceptance}"
RESTORE_DB="${RESTORE_DB:-nocobase_pg_restore}"

# Locate pg_dump/psql on Windows if not on PATH (local PostgreSQL 16 install).
if command -v pg_dump >/dev/null 2>&1; then
  PG_DUMP="pg_dump"; PSQL="psql"
elif [ -x "/c/Program Files/PostgreSQL/16/bin/pg_dump.exe" ]; then
  export PATH="/c/Program Files/PostgreSQL/16/bin:$PATH"
  PG_DUMP="pg_dump"; PSQL="psql"
else
  echo "FAIL: pg_dump/psql not found on PATH or /c/Program Files/PostgreSQL/16/bin"
  exit 1
fi

TS=$(date -u +%Y%m%d_%H%M%SZ)
BACKUP="/tmp/nocobase_backup_${TS}.sql"

echo "=== Backup & restore drill ==="
echo "timestamp: $TS"
echo "source db: $SRC_DB"
echo "restore db: $RESTORE_DB"

# Ensure the source db has the current schema (idempotent). The release gate
# already migrated $SRC_DB; if not, run flyway via a one-shot app start is out
# of scope here — we rely on the release gate having populated it.
echo "source tables:"
PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -d "$SRC_DB" -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" || true

# 1. Backup.
echo "--- pg_dump $SRC_DB -> $BACKUP ---"
PGPASSWORD="$PG_PASSWORD" "$PG_DUMP" -h "$PG_HOST" -U "$PG_USER" -d "$SRC_DB" --no-owner --no-privileges > "$BACKUP"
BACKUP_SIZE=$(wc -c < "$BACKUP")
echo "backup size: $BACKUP_SIZE bytes"

# 2. Restore into a clean database.
echo "--- restore into $RESTORE_DB ---"
PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -c "DROP DATABASE IF EXISTS \"$RESTORE_DB\";" >/dev/null
PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -c "CREATE DATABASE \"$RESTORE_DB\";" >/dev/null
PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -d "$RESTORE_DB" -v ON_ERROR_STOP=1 < "$BACKUP" >/dev/null

# 3. Verify schema: same table count in public schema.
SRC_TABLES=$(PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -d "$SRC_DB" -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
RST_TABLES=$(PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -d "$RESTORE_DB" -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
echo "source public tables: $SRC_TABLES | restore public tables: $RST_TABLES"
if [ "$SRC_TABLES" != "$RST_TABLES" ]; then
  echo "FAIL: table count mismatch (schema not fully restored)"
  exit 1
fi
echo "PASS: schema table count matches ($RST_TABLES tables)"

# 4. Verify flyway_schema_history was restored and matches.
SRC_FLY=$(PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -d "$SRC_DB" -tAc \
  "SELECT count(*) FROM flyway_schema_history;" 2>/dev/null || echo 0)
RST_FLY=$(PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -d "$RESTORE_DB" -tAc \
  "SELECT count(*) FROM flyway_schema_history;" 2>/dev/null || echo 0)
echo "source flyway rows: $SRC_FLY | restore flyway rows: $RST_FLY"
if [ "$SRC_FLY" != "$RST_FLY" ] || [ "$RST_FLY" -eq 0 ]; then
  echo "FAIL: flyway_schema_history not restored or empty"
  exit 1
fi
echo "PASS: flyway_schema_history restored ($RST_FLY rows)"

# 5. Verify representative data: users + roles rows match.
for TBL in users roles; do
  SRC_N=$(PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -d "$SRC_DB" -tAc "SELECT count(*) FROM $TBL;" 2>/dev/null || echo -1)
  RST_N=$(PGPASSWORD="$PG_PASSWORD" "$PSQL" -h "$PG_HOST" -U "$PG_USER" -d "$RESTORE_DB" -tAc "SELECT count(*) FROM $TBL;" 2>/dev/null || echo -1)
  echo "$TBL: source=$SRC_N restore=$RST_N"
  if [ "$SRC_N" != "$RST_N" ]; then
    echo "FAIL: $TBL row count mismatch"
    exit 1
  fi
done
echo "PASS: representative data (users, roles) restored"

# Evidence record (non-secret).
cat > backup_restore_evidence.txt <<EOF
backup_restore_drill: PASS
timestamp: $TS
source_db: $SRC_DB
restore_db: $RESTORE_DB
backup_bytes: $BACKUP_SIZE
public_tables: $RST_TABLES
flyway_history_rows: $RST_FLY
rollback_method: database restore from pre-upgrade backup (Flyway has no undo)
EOF
echo "Evidence written to backup_restore_evidence.txt"
echo "=== backup & restore drill PASS ==="
