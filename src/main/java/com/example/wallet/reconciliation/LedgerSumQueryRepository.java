package com.example.wallet.reconciliation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.example.wallet.ledger.LedgerType;
import com.example.wallet.ledger.QLedgerEntry;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

// 지갑마다 원장 전체를 Java로 끌어와 LedgerEntry.signedAmount()로 더할 수도 있지만,
// 지갑 수 × 평균 원장 건수만큼 엔티티를 통째로 메모리에 올려야 한다. 합계는 DB가 훨씬
// 잘하는 일이라 그룹별 SUM을 SQL에서 끝낸다 — 이게 이 배치에 QueryDSL을 쓰는 이유다.
// CASE의 "어느 타입이 +인지"는 LedgerType.sign()을 그대로 읽어서 만든다 — 타입이
// 추가/변경돼도 이 쿼리를 따로 고칠 필요가 없다(LedgerType만 고치면 됨).
@Repository
@RequiredArgsConstructor
public class LedgerSumQueryRepository {

	private final JPAQueryFactory queryFactory;

	public Map<Long, Long> sumSignedAmountByWalletId() {
		QLedgerEntry entry = QLedgerEntry.ledgerEntry;

		List<LedgerType> positiveTypes = Arrays.stream(LedgerType.values())
				.filter(type -> type.sign() > 0)
				.toList();
		NumberExpression<Long> signedSum = new CaseBuilder()
				.when(entry.type.in(positiveTypes)).then(entry.amount)
				.otherwise(entry.amount.negate())
				.sum();

		List<Tuple> rows = queryFactory
				.select(entry.walletId, signedSum)
				.from(entry)
				.groupBy(entry.walletId)
				.fetch();

		Map<Long, Long> sumsByWalletId = new HashMap<>();
		for (Tuple row : rows) {
			sumsByWalletId.put(row.get(entry.walletId), row.get(signedSum));
		}
		return sumsByWalletId;
	}
}
