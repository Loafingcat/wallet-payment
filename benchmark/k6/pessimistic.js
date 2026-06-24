import http from 'k6/http';
import { check } from 'k6';

// 비관적 락 경로(POST /payments, WalletRepository.findByIdForUpdate)를 두드린다.
// optimistic.js와 짝을 이루는 스크립트 — 둘의 차이는 엔드포인트와, 낙관적 쪽에만
// 있는 재시도-횟수 메트릭뿐이다. 나머지(부하 패턴, 페이로드 크기, 헤더)는 동일하게
// 맞춰서 "락 전략" 차이만 측정되게 한다.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WALLET_ID = __ENV.WALLET_ID;
const MERCHANT_ID = __ENV.MERCHANT_ID;
const AMOUNT = __ENV.AMOUNT || '1';
const VUS = Number(__ENV.VUS || 10);
const RAMP = __ENV.RAMP || '5s';
const DURATION = __ENV.DURATION || '20s';

export const options = {
	stages: [
		{ duration: RAMP, target: VUS },
		{ duration: DURATION, target: VUS },
	],
	summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function randomId() {
	return Math.random().toString(36).slice(2) + '-' + Date.now().toString(36);
}

export default function () {
	const idempotencyKey = `pess-${__VU}-${__ITER}-${randomId()}`;
	const res = http.post(
		`${BASE_URL}/payments`,
		JSON.stringify({ walletId: Number(WALLET_ID), merchantId: Number(MERCHANT_ID), amount: Number(AMOUNT) }),
		{ headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey } },
	);
	check(res, { 'status is 200': (r) => r.status === 200 });
}
