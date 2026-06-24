package com.example.wallet.wallet;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

	// SELECT ... FOR UPDATE. 이 row를 읽는 즉시 DB가 행 잠금을 걸어서, 같은 지갑을 동시에
	// 결제하려는 다른 트랜잭션은 이 트랜잭션이 commit(또는 rollback)할 때까지 자기 SELECT에서
	// 대기한다. 그래서 "읽고 확인한 값"이 쓰기 시점까지 다른 트랜잭션 때문에 바뀔 수 없다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT w FROM Wallet w WHERE w.id = :id")
	Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
