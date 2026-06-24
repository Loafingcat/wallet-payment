package com.example.wallet.wallet;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
}
