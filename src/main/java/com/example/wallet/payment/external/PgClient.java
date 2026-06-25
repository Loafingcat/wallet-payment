package com.example.wallet.payment.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// 절대 규칙 6번과 같은 이유로, 이 클래스를 호출하는 쪽(ExternalPaymentService)은
// @Transactional 메서드 안에서 이걸 부르지 않는다 — 외부 호출은 느릴 수 있고(타임아웃
// 설정 자체가 그걸 전제한다), DB 트랜잭션을 그 시간 동안 붙들고 있으면 안 된다.
//
// connect/read 타임아웃을 짧게(기본 1초) 잡아서, fake-pg가 일부러 느리게 응답하는
// timeout/lost-response 시나리오에서 우리 쪽이 실제로 포기하도록 만든다.
@Component
public class PgClient {

	private static final Logger log = LoggerFactory.getLogger(PgClient.class);

	private final RestClient restClient;

	public PgClient(
			@Value("${pg.base-url}") String baseUrl,
			@Value("${pg.connect-timeout-ms:1000}") int connectTimeoutMs,
			@Value("${pg.read-timeout-ms:1000}") int readTimeoutMs) {

		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMs);
		requestFactory.setReadTimeout(readTimeoutMs);

		this.restClient = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.build();
	}

	public PgApproveResult approve(String idempotencyKey, Long walletId, Long merchantId, long amount,
			String simulateFailure) {
		try {
			RestClient.RequestBodySpec spec = restClient.post()
					.uri("/pg/approve")
					.contentType(MediaType.APPLICATION_JSON)
					.header("PG-Idempotency-Key", idempotencyKey);
			if (simulateFailure != null) {
				spec = spec.header("X-Simulate-Failure", simulateFailure);
			}

			spec.body(new PgApproveRequestBody(walletId, merchantId, amount))
					.retrieve()
					.body(PgApproveResponseBody.class);

			return PgApproveResult.approved();
		} catch (RestClientResponseException e) {
			// PG가 명확한 에러 응답(이 시뮬레이션에서는 5xx)을 줬다 — "확실히 실패"로 본다.
			log.warn("[PG] 명확한 실패 응답: idempotencyKey={}, status={}", idempotencyKey, e.getStatusCode());
			return PgApproveResult.definitelyFailed("PG responded with " + e.getStatusCode());
		} catch (RestClientException e) {
			// 타임아웃/연결 끊김 등 — 결과를 모른다. 절대 "실패"로 단정하지 않는다.
			log.warn("[PG] 호출 실패(결과 모름): idempotencyKey={}, cause={}", idempotencyKey, e.getMessage());
			return PgApproveResult.unknown(e.getMessage());
		}
	}

	public PgQueryResult queryStatus(String idempotencyKey) {
		try {
			restClient.get()
					.uri("/pg/payments/{key}", idempotencyKey)
					.retrieve()
					.body(PgApproveResponseBody.class);
			return PgQueryResult.APPROVED;
		} catch (RestClientResponseException e) {
			if (e.getStatusCode().equals(HttpStatusCode.valueOf(404))) {
				return PgQueryResult.NOT_FOUND;
			}
			return PgQueryResult.UNKNOWN;
		} catch (RestClientException e) {
			return PgQueryResult.UNKNOWN;
		}
	}
}
