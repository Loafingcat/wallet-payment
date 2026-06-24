# Architecture Decision Records

## ADR-001: 금액 타입 — long (KRW 최소 화폐 단위)

- **날짜**: 2026-06-24
- **결정**: `Wallet.balance`, `LedgerEntry.amount`/`balanceAfter`는 `BigDecimal`이 아닌 `long`(원 단위 정수)으로 다룬다.
- **이유**: 원화는 소수점이 없는 최소 화폐 단위(1원)라 정수로 정확히 표현 가능하다. `long`은 `BigDecimal`보다 연산이 단순하고 반올림 정책을 둘 필요가 없어 정합성 버그 여지가 적다. DB 컬럼도 `BIGINT` 하나로 단순해진다.
- **트레이드오프**: 향후 해외 결제 등 소수 단위 화폐를 지원해야 하면 타입을 다시 설계해야 한다. 현재 스코프(원화 단일 통화)에서는 문제 없음.

## ADR-002: LedgerEntry.amount는 항상 양수 + type으로 방향 표현

- **날짜**: 2026-06-24
- **결정**: `amount`는 항상 양수(절대값)로 저장하고, 거래가 잔액을 늘렸는지 줄였는지는 `type`(CHARGE/PAYMENT/REFUND/SETTLEMENT_FEE)으로 표현한다.
- **이유**: 부호를 amount에 같이 넣으면 거래 방향을 알기 위해 매번 type과 amount 부호를 같이 봐야 한다. type만 보고 방향을 판단할 수 있게 하면 정산/집계 로직에서 실수가 줄어든다.

## ADR-003: 멱등성 키는 별도 테이블이 아니라 LedgerEntry.idempotencyKey + UNIQUE 제약으로 관리

- **날짜**: 2026-06-24
- **후보**:
  1. 별도 `idempotency_key` 테이블에 요청/응답을 통째로 저장. 실패 응답까지 재생 가능하지만 테이블과 트랜잭션 관리가 하나 더 늘어난다.
  2. S1에서 만든 `LedgerEntry.idempotencyKey` 컬럼에 UNIQUE 제약을 걸어서 그대로 쓴다.
- **결정**: 2번. `LedgerEntry.idempotencyKey`를 `nullable = false, unique = true`로 바꾸고, 같은 키로 다시 INSERT가 들어오면 DB가 막아준다.
- **이유**: 충전/결제 응답은 `{walletId, balance}`뿐이라 LedgerEntry 한 행에서 그대로 재구성된다 — 응답을 별도로 저장할 이유가 없다. 새 테이블 없이 기존 컬럼(S1에서 "nullable 지금은"으로 남겨뒀던 것)을 완성하는 것만으로 충분하다.
- **트레이드오프**: 멱등성이 "성공한 거래"에만 적용된다. 첫 시도가 잔액부족 등으로 실패하면 LedgerEntry가 안 남으므로 같은 키로 재시도해도 실패 응답이 그대로 재생되지 않고 처음부터 다시 시도된다. 실패한 시도는 부작용이 없으므로 다시 시도해도 안전하다고 보고 이 정도 단순화는 받아들인다.
- **동시에 같은 키로 요청이 들어올 때**: 멱등키 존재 여부를 먼저 확인하고, 없으면 정상 처리 후 `idempotencyKey`를 채워 INSERT한다. 두 요청이 동시에 "존재 안 함"을 보고 둘 다 처리를 시도하면, 둘 중 하나의 INSERT는 UNIQUE 제약을 위반해 실패한다 — 그 트랜잭션 전체(잔액 변경 포함)를 롤백하고, 먼저 커밋된 거래의 결과를 다시 조회해서 반환한다.

## ADR-004: Merchant 수수료율 — 2.5%, BigDecimal, 정산 시점에만 계산(LedgerEntry 미기록)

- **날짜**: 2026-06-24
- **결정**: `Merchant.feeRate`는 `BigDecimal`(예: `0.0250`)로 둔다. 기본값은 **2.5%**(사용자 결정). 정산액 계산은 `settlementAmount = totalPaymentAmount - totalRefundAmount - feeAmount`이고, `feeAmount = round(totalPaymentAmount * feeRate)`다 — 수수료는 환불 이후의 순액이 아니라 **결제 총액(gross) 기준**으로 계산한다.
- **이유**: `BigDecimal`은 금액(ADR-001에서 `long`으로 고정)이 아니라 비율이라서 같은 제약을 받지 않는다. 비율은 정수로 표현하면(예: basis point) 가독성이 떨어지고, `BigDecimal`이 "2.5%"라는 의도를 그대로 드러낸다. 수수료의 계산 기준(gross)은 문제에서 준 공식("총결제액 − 환불 − 수수료")이 수수료를 환불과 독립적으로 빼는 형태라서 그대로 따랐다.
- **LedgerEntry로 기록하지 않는 이유**: `LedgerType.SETTLEMENT_FEE`가 S1부터 enum에 있었지만, LedgerEntry는 `walletId`(사용자 지갑) 기준으로 잔액을 추적하는 원장이다. 수수료는 사용자 지갑에서 빠지는 돈이 아니라 "플랫폼이 가맹점에 지급할 금액에서 미리 떼는 몫"이라, 사용자 지갑에 묶일 이유가 없다. 그래서 수수료는 Settlement 집계 시점에 계산만 하고 별도 LedgerEntry를 만들지 않는다. 가맹점 자체의 정산용 원장(가맹점별 지급 내역)이 필요해지면 그때 `merchantId` 기준의 새로운 원장 개념을 따로 설계해야 한다.

## ADR-005: 충전 경로의 낙관적 락 충돌 — 서버 재시도 대신 명확한 에러 응답

- **날짜**: 2026-06-24
- **배경**: `WalletService.charge()`는 `findById`(락 없음)로 지갑을 읽고 `@Version`에만 의존한다. 동시에 같은 지갑을 충전하면 늦게 flush되는 쪽이 `ObjectOptimisticLockingFailureException`을 받는데, 지금까지 이걸 잡는 코드가 없어서 일관되지 않은 500 에러로 새고 있었다.
- **후보**:
  1. 서버가 내부적으로 재시도(횟수/백오프 명시) — `payment/optimistic/`의 패턴을 재사용.
  2. `ConcurrentChargeConflictException`(409)으로 변환해서 클라이언트에게 즉시 알리고, 재시도는 클라이언트가 같은 Idempotency-Key로 한다.
- **결정**: 2번(사용자 결정).
- **이유**: 결제 경로(`PaymentService`)는 충돌을 비관적 락으로 미리 막거나, 막을 수 없는 비즈니스 실패(잔액부족)는 재시도 없이 즉시 명확한 예외로 던진다 — "서버가 알아서 다시 시도한다"는 동작이 결제 경로엔 없다. 충전에 서버 재시도를 넣으면 같은 시스템 안에 두 가지 다른 실패 처리 철학이 생긴다. 또한 충돌이 난 트랜잭션은 잔액 변경과 LedgerEntry insert가 통째로 롤백되므로(흔적이 전혀 안 남으므로), 클라이언트가 **같은 Idempotency-Key로 재시도**하기만 하면 이중 충전 없이 안전하게 끝난다 — 서버가 재시도 루프를 또 만들 필요가 없다.
- **멱등성과의 관계**: 충돌 시점에 `LedgerEntry`가 INSERT된 적이 없으므로(트랜잭션 전체 롤백), 재시도가 서버이든 클라이언트이든 "같은 키로 다시 시도 = 새 시도"이고 결과는 항상 정확히 1건만 반영된다. `WalletChargeConcurrencyTest`로 검증했다.
