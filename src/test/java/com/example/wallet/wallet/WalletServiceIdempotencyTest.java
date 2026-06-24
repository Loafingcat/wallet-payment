package com.example.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.support.IntegrationTestSupport;

// PaymentConcurrencyTest와 같은 이유로 NOT_SUPPORTED — 동시 호출 테스트는 워커 스레드의
// 별도 커넥션이 setUp의 insert를 봐야 해서, 부모의 rollback-only 트랜잭션을 쓸 수 없다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletServiceIdempotencyTest extends IntegrationTestSupport {

	@Autowired
	private WalletService walletService;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@AfterEach
	void tearDown() {
		ledgerEntryRepository.deleteAll();
		walletRepository.deleteAll();
	}

	@Test
	void 같은_키로_두_번_충전해도_차감은_한_번만_일어난다() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		String idempotencyKey = UUID.randomUUID().toString();

		ChargeResponse first = walletService.charge(wallet.getId(), 10_000L, idempotencyKey);
		ChargeResponse second = walletService.charge(wallet.getId(), 10_000L, idempotencyKey);

		Wallet result = walletRepository.findById(wallet.getId()).orElseThrow();
		List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(wallet.getId());

		assertThat(second).isEqualTo(first);
		assertThat(result.getBalance()).isEqualTo(10_000L);
		assertThat(entries).hasSize(1);
	}

	@Test
	void 동시에_같은_키로_충전해도_한_번만_처리된다() throws InterruptedException {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		Long walletId = wallet.getId();
		String idempotencyKey = UUID.randomUUID().toString();

		int threadCount = 2;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				readyLatch.countDown();
				try {
					startLatch.await();
					walletService.charge(walletId, 10_000L, idempotencyKey);
				} catch (Exception ignored) {
					// DuplicateIdempotencyKeyException은 컨트롤러가 처리하는 경로라 서비스 단
					// 테스트에서는 그냥 무시하고, 최종 상태(원장 1건, 잔액 1회분)로 검증한다.
				} finally {
					doneLatch.countDown();
				}
			});
		}

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		Wallet result = walletRepository.findById(walletId).orElseThrow();
		List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId);

		assertThat(entries).hasSize(1);
		assertThat(result.getBalance()).isEqualTo(10_000L);
	}
}
