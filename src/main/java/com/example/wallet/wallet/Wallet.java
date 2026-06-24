package com.example.wallet.wallet;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.example.wallet.common.exception.InsufficientBalanceException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private Long userId;

	@Column(nullable = false)
	private Long balance;

	@Version
	private Long version;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	public Wallet(Long userId) {
		this.userId = userId;
		this.balance = 0L;
	}

	// 잔액은 이 메서드를 통해서만 바뀐다. 호출하는 쪽(WalletService)이 같은 트랜잭션 안에서
	// LedgerEntry를 함께 남겨야 "잔액 == 원장 합계"가 깨지지 않는다.
	public void charge(long amount) {
		this.balance += amount;
	}

	// 절대 규칙 1번: 잔액은 음수가 될 수 없다. 이 체크와 차감을 한 메서드 안에 묶어서,
	// "확인 후 차감" 사이에 다른 트랜잭션이 끼어들 여지를 호출부가 만들지 못하게 한다.
	public void pay(long amount) {
		if (this.balance < amount) {
			throw new InsufficientBalanceException(this.id);
		}
		this.balance -= amount;
	}
}
