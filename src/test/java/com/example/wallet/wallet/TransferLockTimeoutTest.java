package com.example.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.support.IntegrationTestSupport;

// S11-2: 락 순서를 고정해서 데드락은 없앴지만(TransferDeadlockTest), 한쪽 트랜잭션이 오래
// 락을 들고 있으면 다른 트랜잭션은 여전히 "대기"해야 한다 — TransferService.transfer()가
// 락을 잡기 전에 자기 세션의 innodb_lock_wait_timeout을 3초로 줄여서 그 대기에 상한을
// 둔다. 이 테스트는 한 스레드가 지갑 락을 일부러 오래 들고 있는 상태를 만들고, 다른
// 스레드의 송금이 MySQL 기본값(innodb_lock_wait_timeout=50초)까지 무한정 기다리지 않고
// 훨씬 짧게 타임아웃으로 끝나는지 확인한다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransferLockTimeoutTest extends IntegrationTestSupport {

	@Autowired
	private TransferService transferService;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void tearDown() {
		ledgerEntryRepository.deleteAll();
		walletRepository.deleteAll();
	}

	@Test
	void 락_대기가_타임아웃을_넘기면_50초를_기다리지_않고_예외로_끝난다() throws InterruptedException {
		Wallet walletA = new Wallet(9301L);
		walletA.charge(100_000L);
		walletRepository.save(walletA);

		Wallet walletB = new Wallet(9302L);
		walletB.charge(100_000L);
		walletRepository.save(walletB);

		Long aId = walletA.getId();
		Long bId = walletB.getId();
		// TransferService도 작은 id를 먼저 잠그므로, 같은 지갑을 미리 잠가둬야 그 잠금
		// 시도에서 곧바로 막힌다.
		Long firstLockId = Math.min(aId, bId);

		CountDownLatch lockAcquired = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		TransactionTemplate lockerTx = new TransactionTemplate(transactionManager);

		Thread locker = new Thread(() -> lockerTx.executeWithoutResult(status -> {
			walletRepository.findByIdForUpdate(firstLockId);
			lockAcquired.countDown();
			try {
				releaseLock.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		locker.start();
		lockAcquired.await();

		long start = System.currentTimeMillis();
		try {
			assertThatThrownBy(() -> transferService.transfer(aId, bId, 1_000L, UUID.randomUUID().toString()))
					.isInstanceOf(PessimisticLockingFailureException.class);
		} finally {
			releaseLock.countDown();
			locker.join(10_000);
		}
		long elapsedMs = System.currentTimeMillis() - start;

		// 3초 타임아웃을 설정했으니, MySQL 기본값(50초)보다 한참 짧게 끝나야 한다.
		assertThat(elapsedMs).isLessThan(10_000L);
	}
}
