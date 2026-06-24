package com.example.wallet.merchant;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "merchant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant {

	// ADR-004: 기본 수수료율 2.5%. 비율이라 ADR-001(금액은 long)의 대상이 아니다.
	public static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0250");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, precision = 5, scale = 4)
	private BigDecimal feeRate;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	public Merchant(String name) {
		this(name, DEFAULT_FEE_RATE);
	}

	public Merchant(String name, BigDecimal feeRate) {
		this.name = name;
		this.feeRate = feeRate;
	}
}
