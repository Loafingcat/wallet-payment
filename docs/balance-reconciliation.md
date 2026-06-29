# 잔액 정합성 점검 배치 (S11)

## 왜 필요한가

이 프로젝트의 원장 기반 설계(README의 "원장 기반 잔액 설계의 이유")는 `Wallet.balance`와 `LedgerEntry` 합계가 **같은 트랜잭션 안에서 같이** 바뀌도록 만들어서, 둘이 어긋나는 일이 구조적으로 일어나지 않게 했다. 코드 경로가 그렇게 짜여 있다는 것과, 실제로 운영 중에 한 번도 안 어긋났다는 것은 다른 이야기다 — 버그(아직 못 찾은 race, 트랜잭션 경계를 빠뜨린 새 기능), 수동 운영 작업(DB 직접 수정), 마이그레이션 실수 같은 것들이 그 보장을 깰 수 있다.

`Wallet.balance`는 어디까지나 **캐시**다. 진짜 정답은 `LedgerEntry` 합계다. 이 배치는 "캐시가 정답과 여전히 같은가"를 주기적으로 확인하는, **2차 방어선**이다.

## 어떻게 점검하는가

`BalanceReconciliationService.reconcile()`이 하는 일:

1. `LedgerSumQueryRepository`가 QueryDSL로 모든 지갑의 원장 합계를 한 번의 쿼리로 집계한다. `LedgerType.sign()`이 "이 타입이 잔액을 늘리는지 줄이는지"를 이미 알고 있어서(`CHARGE`/`REFUND`/`TRANSFER_IN`은 +, `PAYMENT`/`SETTLEMENT_FEE`/`TRANSFER_OUT`은 -), 쿼리는 그 정보를 그대로 읽어서 `CASE WHEN type IN (...) THEN amount ELSE -amount END`를 동적으로 만든다 — 타입이 늘어나도 이 쿼리를 따로 고칠 필요가 없다.
2. 모든 `Wallet`을 조회해서, 각 지갑의 `balance`(캐시)와 방금 구한 원장 합계를 비교한다.
3. 다르면: `log.error(...)`로 경보를 남기고, `BalanceDiscrepancy` 행 하나를 저장한다(`walletId`, `cachedBalance`, `ledgerSum`, `difference`, `detectedAt`).

왜 모든 `LedgerEntry`를 Java로 끌어와서 더하지 않고 QueryDSL `SUM`을 쓰는가: 지갑이 늘어날수록, 그리고 지갑당 거래가 쌓일수록 모든 행을 엔티티로 역직렬화해 메모리에 올리는 비용이 커진다. 합계 자체는 DB 엔진이 훨씬 잘하는 일이라 그룹별 집계를 SQL에서 끝낸다.

## 왜 자동으로 고치지 않는가 (detection-only)

이 배치는 **탐지만** 한다. 불일치를 발견해도 `Wallet.balance`를 원장 합계로 덮어쓰는 코드가 없다. 의도적인 선택이다:

- **불일치 자체가 "어딘가 버그가 있다"는 신호다.** 캐시를 원장 값으로 자동으로 맞춰버리면, 겉으로 보이는 증상(잔액이 이상함)은 사라지지만 그 증상을 일으킨 근본 원인(어떤 경로가 트랜잭션 경계를 빠뜨렸는지, 어떤 race가 있었는지)은 그대로 남는다. 같은 버그가 계속 불일치를 만들어내는데, 자동 보정이 그걸 매번 조용히 지워버리면 **아무도 그 버그를 알아채지 못한다.**
- **"원장이 항상 옳다"는 가정이 늘 맞지는 않을 수 있다.** 보통은 원장이 진실의 원천이 맞지만, 만약 원장 INSERT 쪽에 버그가 있어서 잘못된 행이 쌓였다면, 원장 합계로 캐시를 "고치는" 행위가 오히려 잘못된 값으로 잔액을 덮어쓰는 꼴이 된다. 실제 돈이 걸린 시스템에서는 이런 결정을 사람이 내역(`BalanceDiscrepancy`)을 보고 판단해야 한다.
- **운영에서는 탐지 즉시 사람이 개입하는 게 맞다.** 자동 보정이 "편의 기능"처럼 보일 수 있지만, 결제 시스템에서 잔액이 자동으로(그것도 조용히) 바뀌는 건 그 자체로 감사(audit) 관점에서 위험하다. `BalanceDiscrepancy` 테이블에 쌓인 기록이 "언제, 어느 지갑이, 얼마나" 어긋났는지 보여주므로, 사람이 원인을 조사하고 필요하면 명시적인 보정 거래(예: 새로운 `LedgerEntry`)로 처리하는 게 안전하다.

## 실행 방식

- **자동**: `BalanceReconciliationScheduler`가 매일 새벽 2시에 전체 지갑을 점검한다.
- **수동**: `POST /reconciliation/run`으로 즉시 트리거할 수 있다(테스트/시연용). 그 시점에 발견된 불일치 목록을 응답으로 바로 돌려준다.

## 테스트

`BalanceReconciliationServiceTest`:

1. **정상 상태**: `WalletService.charge()`로 정상 경로를 거치면 캐시와 원장이 항상 같이 바뀌므로, 점검해도 불일치가 0건이어야 한다.
2. **불일치 상태**: 정상 경로를 거치지 않고, JPQL bulk update(`UPDATE Wallet SET balance = ... WHERE id = ...`)로 캐시 잔액만 직접 망가뜨려 버그/데이터 손상을 흉내낸다. 점검을 돌리면 정확한 `cachedBalance`/`ledgerSum`/`difference` 값으로 `BalanceDiscrepancy`가 하나 기록되는지 확인한다.

(bulk update를 쓴 이유: `Wallet.balance`를 바꾸는 유일한 정상 경로는 `charge()`/`pay()` 메서드뿐이고, 이들은 항상 `LedgerEntry`도 같이 남긴다. 두 값을 일부러 어긋나게 만들려면 정상 경로를 우회해야 하는데, JPQL bulk update는 엔티티의 `@Version` 체크도 거치지 않고 DB에 직접 UPDATE를 내보내므로 "캐시만 따로 바뀐" 상황을 깨끗하게 재현할 수 있다.)
