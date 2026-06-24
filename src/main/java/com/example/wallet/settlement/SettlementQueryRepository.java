package com.example.wallet.settlement;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.example.wallet.ledger.LedgerType;
import com.example.wallet.ledger.QLedgerEntry;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

// 가맹점 필터는 있을 수도(특정 가맹점만 재정산), 없을 수도(전체 가맹점 일괄 정산) 있다.
// Spring Data 메서드 이름 규칙으로는 "조건이 있을 때만 WHERE에 들어가는" 쿼리를 표현할 수
// 없어서, 가맹점 유무에 따라 메서드를 두 개 만들거나 null 비교 트릭을 써야 한다. QueryDSL의
// BooleanBuilder는 조건을 코드(if문)로 조립하므로, 필터가 늘어나도(예: 가맹점 카테고리)
// if문만 추가하면 된다 — 이게 정산 같은 동적 집계 쿼리에 QueryDSL을 쓰는 이유다.
@Repository
@RequiredArgsConstructor
public class SettlementQueryRepository {

	private final JPAQueryFactory queryFactory;

	public List<MerchantAggregate> aggregate(LocalDateTime from, LocalDateTime to, Long merchantId) {
		QLedgerEntry entry = QLedgerEntry.ledgerEntry;

		BooleanBuilder where = new BooleanBuilder();
		where.and(entry.createdAt.goe(from)).and(entry.createdAt.lt(to));
		where.and(entry.type.in(LedgerType.PAYMENT, LedgerType.REFUND));
		if (merchantId != null) {
			where.and(entry.merchantId.eq(merchantId));
		}

		return queryFactory
				.select(Projections.constructor(MerchantAggregate.class,
						entry.merchantId,
						new CaseBuilder().when(entry.type.eq(LedgerType.PAYMENT)).then(entry.amount).otherwise(0L)
								.sum(),
						new CaseBuilder().when(entry.type.eq(LedgerType.REFUND)).then(entry.amount).otherwise(0L)
								.sum()))
				.from(entry)
				.where(where)
				.groupBy(entry.merchantId)
				.fetch();
	}
}
