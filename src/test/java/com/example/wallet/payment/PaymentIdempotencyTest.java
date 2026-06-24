package com.example.wallet.payment;

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
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;

// PaymentConcurrencyTest와 같은 이유로 NOT_SUPPORTED.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentIdempotencyTest extends IntegrationTestSupport {

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
	void 같은_키로_두_번_결제해도_차감은_한_번만_일어난다() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletService.charge(wallet.getId(), 10_000L, UUID.randomUUID().toString());
		String idempotencyKey = UUID.randomUUID().toString();

		PaymentResponse first = paymentService.pay(wallet.getId(), 100L, 6_000L, idempotencyKey);
		PaymentResponse second = paymentService.pay(wallet.getId(), 100L, 6_000L, idempotencyKey);

		Wallet result = walletRepository.findById(wallet.getId()).orElseThrow();

		assertThat(second).isEqualTo(first);
		assertThat(result.getBalance()).isEqualTo(4_000L);
	}

	@Test
	void 동시에_같은_키로_결제해도_한_번만_처리된다() throws InterruptedException {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletService.charge(wallet.getId(), 10_000L, UUID.randomUUID().toString());
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
					paymentService.pay(walletId, 100L, 6_000L, idempotencyKey);
				} catch (Exception ignored) {
					// 위와 동일: UNIQUE 위반은 서비스 메서드 호출자(컨트롤러)가 처리하는 경로다.
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
		List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId).stream()
				.filter(e -> idempotencyKey.equals(e.getIdempotencyKey()))
				.toList();

		assertThat(entries).hasSize(1);
		assertThat(result.getBalance()).isEqualTo(4_000L);
	}
}
