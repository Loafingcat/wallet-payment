import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

// 낙관적 락 경로(POST /payments/optimistic, OptimisticPaymentService 재시도)를 두드린다.
// pessimistic.js와 짝. X-Attempt-Count 응답 헤더를 attemptCount Trend로 모아서,
// k6 요약에서 재시도율(평균 시도 횟수, 1보다 큰 비율 등)을 바로 볼 수 있게 한다.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WALLET_ID = __ENV.WALLET_ID;
const MERCHANT_ID = __ENV.MERCHANT_ID;
const AMOUNT = __ENV.AMOUNT || '1';
const VUS = Number(__ENV.VUS || 10);
const RAMP = __ENV.RAMP || '5s';
const DURATION = __ENV.DURATION || '20s';

export const attemptCount = new Trend('optimistic_attempt_count');

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
	const idempotencyKey = `opt-${__VU}-${__ITER}-${randomId()}`;
	const res = http.post(
		`${BASE_URL}/payments/optimistic`,
		JSON.stringify({ walletId: Number(WALLET_ID), merchantId: Number(MERCHANT_ID), amount: Number(AMOUNT) }),
		{ headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey } },
	);
	check(res, { 'status is 200': (r) => r.status === 200 });
	const attempts = Number(res.headers['X-Attempt-Count'] || '1');
	attemptCount.add(attempts);
}
