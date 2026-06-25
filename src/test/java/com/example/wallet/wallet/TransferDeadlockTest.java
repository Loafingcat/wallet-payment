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

// S11 1단계 "버그 재현": TransferService.transfer()가 fromWalletId→toWalletId 순서
// 그대로(정렬 없이) 락을 건다. A→B 송금과 B→A 송금이 동시에 들어오면, 하나는 A를 먼저
// 잠그고 B를 기다리고, 다른 하나는 B를 먼저 잠그고 A를 기다리는 순환 대기가 생길 수 있다 —
// MySQL InnoDB가 그 순환을 감지해서 둘 중 하나를 강제로 죽인다(데드락 희생자). 다음 커밋
// (락 순서를 지갑 id 오름차순으로 고정)에서 이 문제를 없앤다.
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
	void 락_순서를_고정하지_않으면_반대_방향_동시_송금이_데드락을_일으킨다() throws InterruptedException {
		Wallet walletA = new Wallet(9101L);
		walletA.charge(1_000_000L);
		walletRepository.save(walletA);

		Wallet walletB = new Wallet(9102L);
		walletB.charge(1_000_000L);
		walletRepository.save(walletB);

		Long aId = walletA.getId();
		Long bId = walletB.getId();

		// 한 쌍(A→B, B→A)만으로는 둘 다 "첫 번째 락"을 잡기 전에 한쪽이 먼저 끝나버려서
		// 데드락 없이 그냥 순차 처리될 수 있다(타이밍 의존적). 여러 쌍을 동시에 쏴서 적어도
		// 한 쌍은 반드시 겹치는 타이밍 창에 들어가게 만든다.
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

		// 수정 확인: 락 순서가 고정돼 있지 않으면 적어도 한 번은 데드락(상호 대기)이 발생한다.
		assertThat(deadlocks).isNotEmpty();
		assertThat(otherFailures).isEmpty();
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
