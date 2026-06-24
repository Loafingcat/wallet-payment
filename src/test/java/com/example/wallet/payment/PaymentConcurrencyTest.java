package com.example.wallet.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.support.IntegrationTestSupport;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;

// 부모(IntegrationTestSupport)의 클래스 레벨 @Transactional을 NOT_SUPPORTED로 덮어써서 끈다.
// 그 @Transactional은 테스트 메서드 전체를 하나의 트랜잭션으로 감싸고 끝나면 rollback하는데,
// 그러면 메인 스레드가 setUp에서 insert한 Wallet이 "커밋되지 않은 상태"로 남아서 워커 스레드의
// 별도 커넥션에서는 그 row가 안 보인다(동시성 테스트가 성립하지 않음). 그래서 이 테스트는 진짜
// 커밋을 사용하고, 끝나고 나서 @AfterEach에서 직접 데이터를 지운다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentConcurrencyTest extends IntegrationTestSupport {

	@Autowired
	private PaymentService paymentService;

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
	void 락_없이_동시에_결제하면_둘_다_성공해서_잔액이_음수가_된다() throws InterruptedException {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletService.charge(wallet.getId(), 10_000L);
		Long walletId = wallet.getId();

		int threadCount = 2;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger();
		AtomicInteger failCount = new AtomicInteger();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				readyLatch.countDown();
				try {
					startLatch.await();
					paymentService.pay(walletId, 100L, 6_000L);
					successCount.incrementAndGet();
				} catch (Exception e) {
					failCount.incrementAndGet();
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

		// 버그 증명: 둘 다 성공하고, 잔액이 음수가 된다(10000 - 6000 - 6000 = -2000).
		assertThat(successCount.get()).isEqualTo(2);
		assertThat(failCount.get()).isZero();
		assertThat(result.getBalance()).isNegative();
	}
}
