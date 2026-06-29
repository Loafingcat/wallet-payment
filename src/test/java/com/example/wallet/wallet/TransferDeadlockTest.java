package com.example.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.support.IntegrationTestSupport;

// S11-2: 락 순서를 지갑 id 오름차순으로 고정한 뒤, 같은 시나리오(A→B/B→A 동시 송금
// 여러 쌍)를 다시 돌려서 데드락이 더 이상 발생하지 않는지 확인한다. 이 테스트의 이전
// 버전(git log의 S11-1 커밋 참고)은 정렬 없는 락이 데드락을 일으킨다는 걸 증명했었다 —
// 지금은 반대로 "데드락이 발생하지 않는다"와 "모든 송금이 끝까지 성공한다"를 증명한다.
//
// 부모의 클래스 레벨 @Transactional을 끄는 이유는 PaymentConcurrencyTest와 같다: 워커
// 스레드가 메인 스레드와 별도 커넥션을 쓰므로, 메인 스레드의 변경이 진짜 커밋돼 있어야
// 워커 스레드 눈에 보인다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransferDeadlockTest extends IntegrationTestSupport {

	@Autowired
	private TransferService transferService;

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
	void 락_순서를_고정하면_반대_방향_동시_송금에서도_데드락이_발생하지_않는다() throws InterruptedException {
		Wallet walletA = new Wallet(9101L);
		walletA.charge(1_000_000L);
		walletRepository.save(walletA);

		Wallet walletB = new Wallet(9102L);
		walletB.charge(1_000_000L);
		walletRepository.save(walletB);

		Long aId = walletA.getId();
		Long bId = walletB.getId();

		// 정렬 전(S11-1) 데드락을 재현했던 것과 똑같은 부하(양방향 10쌍 동시) — 같은
		// 조건에서 더 이상 데드락이 안 난다는 걸 보여줘야 의미가 있다.
		int pairsPerDirection = 10;
		int threadCount = pairsPerDirection * 2;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		List<Exception> deadlocks = new CopyOnWriteArrayList<>();
		List<Exception> otherFailures = new CopyOnWriteArrayList<>();

		for (int i = 0; i < pairsPerDirection; i++) {
			executor.submit(
					() -> runTransfer(aId, bId, readyLatch, startLatch, doneLatch, deadlocks, otherFailures));
			executor.submit(
					() -> runTransfer(bId, aId, readyLatch, startLatch, doneLatch, deadlocks, otherFailures));
		}

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await(30, TimeUnit.SECONDS);
		executor.shutdown();

		assertThat(deadlocks).isEmpty();
		assertThat(otherFailures).isEmpty();

		// 같은 금액(10)을 양방향으로 같은 횟수(10번씩) 주고받았으니 순환 상쇄돼서 둘 다
		// 원래 잔액(1,000,000)으로 돌아와야 한다 — 락이 제대로 걸렸다는 간접 증거.
		Wallet resultA = walletRepository.findById(aId).orElseThrow();
		Wallet resultB = walletRepository.findById(bId).orElseThrow();
		assertThat(resultA.getBalance()).isEqualTo(1_000_000L);
		assertThat(resultB.getBalance()).isEqualTo(1_000_000L);
	}

	private void runTransfer(Long fromId, Long toId, CountDownLatch readyLatch, CountDownLatch startLatch,
			CountDownLatch doneLatch, List<Exception> deadlocks, List<Exception> otherFailures) {
		readyLatch.countDown();
		try {
			startLatch.await();
			transferService.transfer(fromId, toId, 10L, UUID.randomUUID().toString());
		} catch (PessimisticLockingFailureException e) {
			deadlocks.add(e);
		} catch (Exception e) {
			otherFailures.add(e);
		} finally {
			doneLatch.countDown();
		}
	}
}
