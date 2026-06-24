#!/usr/bin/env bash
# S8 부하 테스트용 데이터 준비. 지갑 2개(비관적/낙관적 경로 전용, 따로 둬서 두 부하
# 테스트가 서로의 결과에 영향을 주지 않게 한다)와 가맹점 1개를 만들고, 실제 충전
# API(POST /wallets/{id}/charge)로 충분한 잔액을 채운다 — "충전 LedgerEntry 없이
# balance만 SQL로 박아넣기"를 하지 않는 이유는, 그러면 시작부터 잔액==원장합계 invariant가
# 깨진 상태로 출발하게 되기 때문이다.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-wallet-mysql}"
MYSQL_USER="${MYSQL_USER:-wallet}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-wallet1234}"
MYSQL_DB="${MYSQL_DB:-wallet}"
INITIAL_BALANCE="${INITIAL_BALANCE:-100000000}" # 1억원, 부하 테스트 총량보다 충분히 크게

mysql_exec() {
	docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" -N -e "$1" 2>/dev/null
}

echo "지갑/가맹점 생성 중..."
mysql_exec "INSERT INTO wallet (user_id, balance, version, created_at, updated_at) VALUES (9001, 0, 0, NOW(), NOW());"
PESSIMISTIC_WALLET_ID=$(mysql_exec "SELECT id FROM wallet WHERE user_id=9001 ORDER BY id DESC LIMIT 1;")

mysql_exec "INSERT INTO wallet (user_id, balance, version, created_at, updated_at) VALUES (9002, 0, 0, NOW(), NOW());"
OPTIMISTIC_WALLET_ID=$(mysql_exec "SELECT id FROM wallet WHERE user_id=9002 ORDER BY id DESC LIMIT 1;")

mysql_exec "INSERT INTO merchant (name, fee_rate, created_at, updated_at) VALUES ('benchmark-merchant', 0.0250, NOW(), NOW());"
MERCHANT_ID=$(mysql_exec "SELECT id FROM merchant WHERE name='benchmark-merchant' ORDER BY id DESC LIMIT 1;")

echo "충전 중 (지갑당 ${INITIAL_BALANCE}원)..."
curl -s -X POST "$BASE_URL/wallets/$PESSIMISTIC_WALLET_ID/charge" \
	-H "Content-Type: application/json" -H "Idempotency-Key: bench-setup-pess-$(date +%s%N)" \
	-d "{\"amount\": $INITIAL_BALANCE}" > /dev/null

curl -s -X POST "$BASE_URL/wallets/$OPTIMISTIC_WALLET_ID/charge" \
	-H "Content-Type: application/json" -H "Idempotency-Key: bench-setup-opt-$(date +%s%N)" \
	-d "{\"amount\": $INITIAL_BALANCE}" > /dev/null

echo "PESSIMISTIC_WALLET_ID=$PESSIMISTIC_WALLET_ID"
echo "OPTIMISTIC_WALLET_ID=$OPTIMISTIC_WALLET_ID"
echo "MERCHANT_ID=$MERCHANT_ID"

# 다음 스크립트가 source해서 쓸 수 있게 파일로도 남긴다.
cat > "$(dirname "$0")/../results/env.sh" <<EOF
export PESSIMISTIC_WALLET_ID=$PESSIMISTIC_WALLET_ID
export OPTIMISTIC_WALLET_ID=$OPTIMISTIC_WALLET_ID
export MERCHANT_ID=$MERCHANT_ID
EOF

echo "완료. benchmark/results/env.sh에 저장됨."
