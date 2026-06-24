#!/usr/bin/env bash
# S8 부하 테스트 본체. benchmark/results/env.sh(setup.sh가 만든 지갑/가맹점 id)를 읽어서,
# (비관적/낙관적) x (VU 10/50/100/200) = 8번 k6를 돌리고, 매 회 끝나고 잔액==원장합계를
# 검증한다. 결과는 benchmark/results/*.json(k6 요약)과 results.csv(집계)에 남는다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULTS_DIR="$ROOT_DIR/benchmark/results"
K6_DIR="$ROOT_DIR/benchmark/k6"

# shellcheck disable=SC1091
source "$RESULTS_DIR/env.sh"

export MSYS_NO_PATHCONV=1 # Git Bash가 /scripts/... 를 윈도우 경로로 바꿔버리는 걸 막는다

BASE_URL_FROM_DOCKER="${BASE_URL_FROM_DOCKER:-http://host.docker.internal:8080}"
VU_LEVELS=(10 50 100 200)
RAMP="${RAMP:-5s}"
DURATION="${DURATION:-20s}"
AMOUNT="${AMOUNT:-1}"

run_k6() {
	local lock_type="$1" vus="$2" script="$3" wallet_id="$4"
	local out_json="$RESULTS_DIR/${lock_type}_vu${vus}.json"
	echo "=== $lock_type, VU=$vus ==="
	docker run --rm \
		-e BASE_URL="$BASE_URL_FROM_DOCKER" \
		-e WALLET_ID="$wallet_id" \
		-e MERCHANT_ID="$MERCHANT_ID" \
		-e AMOUNT="$AMOUNT" \
		-e VUS="$vus" \
		-e RAMP="$RAMP" \
		-e DURATION="$DURATION" \
		-v "$K6_DIR:/scripts" \
		grafana/k6 run --summary-export="/scripts/_out.json" "/scripts/$script" \
		> "$RESULTS_DIR/${lock_type}_vu${vus}.log" 2>&1 || true
	# 컨테이너 안에 -v로 마운트된 디렉터리에 쓴 파일이라 호스트에도 바로 보인다.
	mv "$K6_DIR/_out.json" "$out_json" 2>/dev/null || true
}

for vus in "${VU_LEVELS[@]}"; do
	run_k6 "pessimistic" "$vus" "pessimistic.js" "$PESSIMISTIC_WALLET_ID"
	bash "$SCRIPT_DIR/verify.sh" "$PESSIMISTIC_WALLET_ID" > "$RESULTS_DIR/pessimistic_vu${vus}.verify.txt" 2>&1 || true
	cat "$RESULTS_DIR/pessimistic_vu${vus}.verify.txt"

	run_k6 "optimistic" "$vus" "optimistic.js" "$OPTIMISTIC_WALLET_ID"
	bash "$SCRIPT_DIR/verify.sh" "$OPTIMISTIC_WALLET_ID" > "$RESULTS_DIR/optimistic_vu${vus}.verify.txt" 2>&1 || true
	cat "$RESULTS_DIR/optimistic_vu${vus}.verify.txt"
done

echo "모든 실행 완료. benchmark/results/*.json을 parse_results.py로 집계하세요."
