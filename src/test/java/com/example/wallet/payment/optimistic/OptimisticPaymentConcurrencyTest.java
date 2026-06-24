package com.example.wallet.payment.optimistic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.InsufficientBalanceException;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.support.IntegrationTestSupport;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;

// PaymentConcurrencyTest(비관적 락)와 같은 시나리오(잔액 10000에 6000원 결제 2건 동시
// 요청)를 낙관적 락 버전으로 돌려본다. 결과는 똑같이 "하나만 성공, 잔액은 음수 아님"이지만,
// 진 쪽이 실패하는 *이유*가 다르다: 비관적 락은 같은 자리에서 기다렸다가 잔액부족으로
// 거부되고, 낙관적 락은 첫 시도에서 버전 충돌로 한 번 튕긴 뒤 재시도에서 최신 잔액을 보고
// 잔액부족으로 거부된다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OptimisticPaymentConcurrencyTest extends IntegrationTestSupport {

	@Autowired
	private OptimisticPaymentService optimisticPaymentService;

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
	void 낙관적_락도_동시_결제_중_하나만_성공하고_잔액은_음수가_되지_않는다() throws InterruptedException {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletService.charge(wallet.getId(), 10_000L, UUID.randomUUID().toString());
		Long walletId = wallet.getId();

		int threadCount = 2;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger();
		AtomicInteger failCount = new AtomicInteger();
		AtomicReference<Throwable> lastFailure = new AtomicReference<>();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				readyLatch.countDown();
				try {
					startLatch.await();
					optimisticPaymentService.pay(walletId, 100L, 6_000L, UUID.randomUUID().toString());
					successCount.incrementAndGet();
				} catch (Exception e) {
					lastFailure.set(e);
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

		assertThat(successCount.get()).isEqualTo(1);
		assertThat(failCount.get()).isEqualTo(1);
		assertThat(result.getBalance()).isEqualTo(4_000L);
		assertThat(result.getBalance()).isNotNegative();
		// 비관적 락 버전과 결과는 같지만, 실패 사유는 재시도 끝에 "잔액부족"으로 정리된다 —
		// 버전 충돌(ObjectOptimisticLockingFailureException) 자체가 클라이언트에 노출되지
		// 않고 재시도 내부에서 흡수된다.
		assertThat(lastFailure.get()).isInstanceOf(InsufficientBalanceException.class);
	}
}
