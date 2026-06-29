#!/usr/bin/env bash
# 2~3분 데모 영상용 스크립트. 직접 타이핑하다 실수하는 걸 막고, 녹화 중에는
# 설명(내레이션)에만 집중할 수 있게 명령어를 미리 다 짜놨다. 각 구간 시작 전에
# Enter를 누를 때까지 멈추므로, 그 사이에 화면에 뜬 설명을 보면서 말하면 된다.
#
# 사전 준비: docker compose up -d 로 MySQL/RabbitMQ/fake-pg가 떠 있어야 하고,
# ./gradlew bootRun --args='--spring.profiles.active=local' 로 앱이 떠 있어야 한다.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-wallet-mysql}"
MYSQL_USER="${MYSQL_USER:-wallet}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-wallet1234}"
MYSQL_DB="${MYSQL_DB:-wallet}"

BOLD='\033[1m'; CYAN='\033[36m'; GREEN='\033[32m'; RESET='\033[0m'

banner() {
	echo
	echo -e "${BOLD}${CYAN}================================================================${RESET}"
	echo -e "${BOLD}${CYAN}$1${RESET}"
	echo -e "${BOLD}${CYAN}================================================================${RESET}"
}

pause() {
	read -rp "$(echo -e "${GREEN}▶ 설명하고 Enter 누르면 실행${RESET}") "
}

# 예쁘게 출력하되, 입력이 비어있거나 python이 무슨 이유로든(환경에 따라 python이
# 없거나, Microsoft Store 스텁이거나, json이 아니거나) 실패하면 원본 텍스트라도
# 그대로 보여준다 — 포맷팅 단계가 죽어서 데모 전체가 멈추는 일은 없게 한다.
pp() {
	local input
	input="$(cat)"
	if [ -z "$input" ]; then
		echo "(빈 응답 — 서버가 응답하지 않았거나 연결에 실패했을 수 있습니다)"
		return
	fi
	if command -v python >/dev/null 2>&1 && printf '%s' "$input" | python -m json.tool 2>/dev/null; then
		return
	fi
	if command -v python3 >/dev/null 2>&1 && printf '%s' "$input" | python3 -m json.tool 2>/dev/null; then
		return
	fi
	echo "$input"
}

# mysql 에러를 숨기지 않는다 — 예전엔 2>/dev/null로 죽였다가, user_id UNIQUE
# 제약을 다시 건드려서(재실행 시 충돌) 아무 메시지 없이 스크립트가 조용히 죽는
# 사고가 났다(set -e 때문에 첫 INSERT가 실패하면 그 자리에서 바로 멈춘다).
mysql_exec() {
	docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" -N -e "$1"
}

# Wallet.userId는 UNIQUE 제약이 있다. 8001/8002처럼 고정된 값을 쓰면 이 스크립트를
# 두 번째 돌릴 때부터 항상 충돌한다(리허설할 때 특히 자주 겪는다) — 매번 새 값을
# 쓰도록 현재 시각(초)을 시드로 둔다.
SEED=$(date +%s)
USER_ID_A=$((SEED * 10 + 1))
USER_ID_B=$((SEED * 10 + 2))

# 앱이 안 떠 있으면 모든 curl이 빈 응답을 내고, 그게 사람 눈엔 "원인 모를 빈 응답"
# 으로만 보인다 — 시작하자마자 바로 확인해서 헛갈리지 않게 한다.
echo "앱 상태 확인 중... ($BASE_URL/health)"
if ! curl -sf "$BASE_URL/health" > /dev/null; then
	echo "오류: $BASE_URL 에 연결할 수 없습니다."
	echo "  - 앱이 떠 있는지 확인: ./gradlew bootRun --args='--spring.profiles.active=local'"
	echo "  - docker compose up -d 로 MySQL/RabbitMQ/fake-pg가 떠 있는지 확인"
	exit 1
fi
echo "앱 정상 응답 확인."

# ── 0. 데모용 데이터 준비 (화면에 안 보여줘도 됨, 미리 한번 돌려놓기) ──
banner "0. 데모 데이터 준비 (지갑 2개, 가맹점 1개)"
echo "녹화 전에 한 번 실행해서 ID를 확보해두세요. 녹화 중엔 생략해도 됩니다."
pause

mysql_exec "INSERT INTO wallet (user_id, balance, version, created_at, updated_at) VALUES ($USER_ID_A, 0, 0, NOW(), NOW());"
WALLET_A=$(mysql_exec "SELECT id FROM wallet WHERE user_id=$USER_ID_A ORDER BY id DESC LIMIT 1;")

mysql_exec "INSERT INTO wallet (user_id, balance, version, created_at, updated_at) VALUES ($USER_ID_B, 0, 0, NOW(), NOW());"
WALLET_B=$(mysql_exec "SELECT id FROM wallet WHERE user_id=$USER_ID_B ORDER BY id DESC LIMIT 1;")

MERCHANT_NAME="demo-merchant-$SEED"
mysql_exec "INSERT INTO merchant (name, fee_rate, created_at, updated_at) VALUES ('$MERCHANT_NAME', 0.0250, NOW(), NOW());"
MERCHANT_ID=$(mysql_exec "SELECT id FROM merchant WHERE name='$MERCHANT_NAME' ORDER BY id DESC LIMIT 1;")

echo "WALLET_A=$WALLET_A, WALLET_B=$WALLET_B, MERCHANT_ID=$MERCHANT_ID"

echo "지갑 A에 10,000원 충전..."
curl -s -X POST "$BASE_URL/wallets/$WALLET_A/charge" \
	-H "Content-Type: application/json" -H "Idempotency-Key: demo-charge-$(date +%s%N)" \
	-d '{"amount": 10000}' | pp

# ── 1. 동시성: 같은 지갑에 결제 2건을 동시에 ──
banner "1. 동시성 — 같은 지갑(잔액 10,000원)에 6,000원 결제 2건을 동시에"
echo "기대: 하나는 성공(잔액 4,000원), 하나는 잔액부족 실패. 절대 음수 안 됨."
echo "(비관적 락 SELECT ... FOR UPDATE로 직렬화됨 — README '동시성' 섹션)"
pause

curl -s -X POST "$BASE_URL/payments" \
	-H "Content-Type: application/json" -H "Idempotency-Key: demo-pay-A-$(date +%s%N)" \
	-d "{\"walletId\": $WALLET_A, \"merchantId\": $MERCHANT_ID, \"amount\": 6000}" > /tmp/demo-pay-1.json &
curl -s -X POST "$BASE_URL/payments" \
	-H "Content-Type: application/json" -H "Idempotency-Key: demo-pay-B-$(date +%s%N)" \
	-d "{\"walletId\": $WALLET_A, \"merchantId\": $MERCHANT_ID, \"amount\": 6000}" > /tmp/demo-pay-2.json &
wait

echo "--- 요청 1 결과 ---"; cat /tmp/demo-pay-1.json | pp
echo "--- 요청 2 결과 ---"; cat /tmp/demo-pay-2.json | pp
echo "--- 최종 지갑 잔액 ---"
mysql_exec "SELECT balance FROM wallet WHERE id=$WALLET_A;"

# ── 2. 멱등성: 같은 키로 두 번 ──
banner "2. 멱등성 — 같은 Idempotency-Key로 충전을 두 번 요청"
echo "기대: 두 응답이 완전히 동일. 차감/충전은 1회만 반영."
pause

IDEMPOTENCY_KEY="demo-idem-$(date +%s%N)"
echo "--- 1차 요청 ---"
curl -s -X POST "$BASE_URL/wallets/$WALLET_B/charge" \
	-H "Content-Type: application/json" -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
	-d '{"amount": 5000}' | pp
echo "--- 2차 요청 (같은 키) ---"
curl -s -X POST "$BASE_URL/wallets/$WALLET_B/charge" \
	-H "Content-Type: application/json" -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
	-d '{"amount": 5000}' | pp

# ── 3. 외부 PG 응답유실 + 보정 ──
banner "3. 외부 PG 응답유실 — 처리는 끝났는데 응답만 못 받은 상황"
echo "기대: 1차 응답은 PENDING_PG(실패로 단정 안 함). 보정 트리거 후 APPROVED로 확정."
pause

EXT_KEY="demo-ext-$(date +%s%N)"
echo "--- 결제 요청 (X-Simulate-Failure: lost-response) ---"
PAYMENT_RESPONSE=$(curl -s -X POST "$BASE_URL/payments/external" \
	-H "Content-Type: application/json" -H "Idempotency-Key: $EXT_KEY" \
	-H "X-Simulate-Failure: lost-response" \
	-d "{\"walletId\": $WALLET_B, \"merchantId\": $MERCHANT_ID, \"amount\": 3000}")
echo "$PAYMENT_RESPONSE" | pp
PAYMENT_ID=$(echo "$PAYMENT_RESPONSE" | python -c "import sys,json; print(json.load(sys.stdin)['paymentId'])" 2>/dev/null || echo "")

echo
echo "지금 상태: 우리 쪽은 응답을 못 받아서 PENDING_PG. 그런데 PG는 이미 승인 처리해뒀다."
pause

echo "--- 보정 수동 트리거 ---"
curl -s -X POST "$BASE_URL/payments/external/$PAYMENT_ID/reconcile" | pp

banner "데모 끝 — 자세한 설명은 README.md / docs/ 참고"
