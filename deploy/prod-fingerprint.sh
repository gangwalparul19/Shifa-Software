#!/usr/bin/env bash
# Read-only fingerprint of the production database: row counts and money sums only.
# Run before and after a release; every INVARIANT line must be identical afterwards
# (unless staff legitimately edited an old order during the window). Changes nothing.
SQL=$(mktemp)
cat > "$SQL" <<'EOSQL'
SELECT CONCAT('INVARIANT orders: count=', COUNT(*), ' total_amount=', COALESCE(SUM(total_amount),0), ' amount_received=', COALESCE(SUM(amount_received),0)) FROM orders;
SELECT CONCAT('INVARIANT line_items: count=', COUNT(*), ' quantity=', COALESCE(SUM(quantity),0), ' line_total=', COALESCE(SUM(line_total),0)) FROM line_items;
SELECT CONCAT('INVARIANT payments: count=', COUNT(*), ' amount_received=', COALESCE(SUM(amount_received),0)) FROM payments;
SELECT CONCAT('INVARIANT users: count=', COUNT(*), ' active=', COALESCE(SUM(active),0)) FROM users;
SELECT CONCAT('INFO flyway max applied version=', MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE success = 1;
SELECT CONCAT('INFO users with role CRM_LEAD=', COUNT(*)) FROM users WHERE role = 'CRM_LEAD';
EOSQL
echo "# fingerprint taken $(date '+%Y-%m-%d %H:%M:%S %Z') on $(hostname)"
sudo bash -c 'set -a; . /etc/shifa/shifa.env 2>/dev/null; set +a; MYSQL_PWD="$DB_PASSWORD" mysql -N -u"$DB_USERNAME" "$DB_NAME"' < "$SQL" 2>&1
rm -f "$SQL"
