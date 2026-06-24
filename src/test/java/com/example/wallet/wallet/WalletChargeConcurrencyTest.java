package com.example.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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

import com.example.wallet.common.exception.ConcurrentChargeConflictException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.support.IntegrationTestSupport;

// PaymentConcurrencyTest와 같은 이유로 NOT_SUPPORTED — 워커 스레드의 별도 커넥션이
// setUp에서 만든 Wallet을 봐야 한다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletChargeConcurrencyTest extends IntegrationTestSupport {

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
	void 동시_충전이_낙관적_락_충돌로_거부되면_같은_키로_재시도해도_이중_충전되지_않는다() throws InterruptedException {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		Long walletId = wallet.getId();

		// 스레드 2개로는 둘의 findById 읽기가 우연히 시간차를 두고 일어나면 충돌이 안 날 수도
		// 있다. 5개를 동시에 같은 지갑에 꽂으면, 다같이 version=0을 읽고 그중 하나만 commit에
		// 성공하므로 나머지 4개는 거의 항상 충돌한다 — 재현을 안정적으로 만들기 위한 선택이다.
		int threadCount = 5;
		List<String> keys = List.of(
				UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
				UUID.randomUUID().toString(), UUID.randomUUID().toString());

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger();
		List<String> conflictedKeys = new CopyOnWriteArrayList<>();

		for (String key : keys) {
			executor.submit(() -> {
				readyLatch.countDown();
				try {
					startLatch.await();
					walletService.charge(walletId, 1_000L, key);
					successCount.incrementAndGet();
				} catch (ConcurrentChargeConflictException e) {
					conflictedKeys.add(key);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		// 적어도 하나는 충돌해야 핸들러가 실제로 동작했다고 말할 수 있다.
		assertThat(conflictedKeys).isNotEmpty();
		assertThat(successCount.get() + conflictedKeys.size()).isEqualTo(threadCount);

		Wallet afterFirstRound = walletRepository.findById(walletId).orElseThrow();
		assertThat(afterFirstRound.getBalance()).isEqualTo(successCount.get() * 1_000L);

		// 충돌한 키들을 "클라이언트가 같은 Idempotency-Key로 재시도"하는 상황으로 재현한다.
		// 이번엔 경쟁자가 없으니 전부 정상적으로(딱 한 번씩만) 성공해야 한다.
		for (String conflictedKey : conflictedKeys) {
			walletService.charge(walletId, 1_000L, conflictedKey);
		}

		Wallet result = walletRepository.findById(walletId).orElseThrow();
		List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId);

		// threadCount번의 "논리적 충전 의도" 중 최종적으로 정확히 threadCount번만 반영돼야
		// 한다 — 충돌로 롤백된 시도가 재시도 후 두 번 반영되거나(이중 충전), 한 번도 반영
		// 안 되는 일(누락)이 없어야 한다.
		assertThat(result.getBalance()).isEqualTo(threadCount * 1_000L);
		assertThat(entries).hasSize(threadCount);
		assertThat(entries).extracting(LedgerEntry::getIdempotencyKey)
				.containsExactlyInAnyOrderElementsOf(keys);
	}
}
