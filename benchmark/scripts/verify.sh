#!/usr/bin/env bash
# 잔액 정합성 검증: Wallet.balance가 그 지갑의 LedgerEntry 합계(부호 보정)와 정확히
# 같은지 확인한다. CHARGE/REFUND는 +amount, PAYMENT는 -amount로 더해야 balance와 맞는다.
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-wallet-mysql}"
MYSQL_USER="${MYSQL_USER:-wallet}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-wallet1234}"
MYSQL_DB="${MYSQL_DB:-wallet}"
WALLET_ID="$1"

RESULT=$(docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" -N -e "
SELECT
  w.balance AS balance,
  COALESCE(SUM(CASE WHEN le.type IN ('CHARGE','REFUND') THEN le.amount
                     WHEN le.type = 'PAYMENT' THEN -le.amount
                     ELSE 0 END), 0) AS ledger_sum,
  COUNT(le.id) AS entry_count
FROM wallet w
LEFT JOIN ledger_entry le ON le.wallet_id = w.id
WHERE w.id = $WALLET_ID
GROUP BY w.id;
" 2>/dev/null)

BALANCE=$(echo "$RESULT" | awk '{print $1}')
LEDGER_SUM=$(echo "$RESULT" | awk '{print $2}')
ENTRY_COUNT=$(echo "$RESULT" | awk '{print $3}')

echo "wallet_id=$WALLET_ID balance=$BALANCE ledger_sum=$LEDGER_SUM entries=$ENTRY_COUNT"

if [ "$BALANCE" = "$LEDGER_SUM" ]; then
	echo "OK: balance == ledger_sum"
	exit 0
else
	echo "FAIL: balance != ledger_sum"
	exit 1
fi
