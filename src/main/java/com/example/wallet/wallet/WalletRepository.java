package com.example.wallet.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

	// 락도 없고, @Version 체크도 안 받는다. JPQL 벌크 UPDATE는 영속성 컨텍스트를 거치지 않고
	// DB에 직접 SQL을 날리기 때문에, Wallet에 @Version이 있어도 이 메서드는 그걸 검사하지 않는다.
	// 동시성 보호장치가 "전혀 없는" 상태를 의도적으로 재현하기 위한 메서드 — S2 1~2단계에서만 쓰고,
	// 3단계(비관적 락 적용)에서는 더 이상 쓰지 않는다.
	@Modifying
	@Query("UPDATE Wallet w SET w.balance = w.balance - :amount WHERE w.id = :id")
	int deductBalanceWithoutLock(@Param("id") Long id, @Param("amount") long amount);
}
