# 학습 가이드 — 이 프로젝트를 일주일 동안 내 것으로 만들기

이 문서는 **README.md(채용 포트폴리오용 요약)와는 다른 목적**을 가집니다. README는 "이미 이해한 사람에게 결과를 보여주는" 문서이고, 이 문서는 **"아직 이해하지 못한 사람이 직접 코드를 읽고, 실행하고, 스스로 설명할 수 있게 만드는"** 문서입니다.

## 이 가이드를 쓰는 방법

1. 하루에 한 섹션(Day 1~7)만 봅니다. 욕심내서 한 번에 다 읽지 마세요 — 코드를 직접 열어보고 실행해보는 시간이 읽는 시간보다 더 중요합니다.
2. 각 Day는 같은 순서로 구성됩니다: **왜 필요했나(배경) → 핵심 코드(파일:줄 번호) → 직접 해보기(실습) → 스스로 점검(질문)**. 점검 질문에 막힘 없이 답할 수 있을 때 다음 Day로 넘어가세요.
3. "직접 해보기"는 실제로 터미널에 명령어를 입력하고 결과를 보라는 뜻입니다. 코드를 눈으로만 읽으면 "아 그렇구나"에서 끝나지만, 테스트를 직접 돌려서 실패→통과가 바뀌는 걸 보면 "왜 그런지"가 머리에 남습니다.
4. 모르는 용어가 나오면 맨 아래 [용어집](#용어집)을 먼저 확인하세요.
5. 막히면 `git log --oneline`으로 단계별 커밋을 보고, `git show <커밋해시>`로 그 단계에서 정확히 무엇이 바뀌었는지 diff를 보세요. 이 프로젝트는 **버그를 먼저 만들고 나서 고치는 커밋들이 짝**으로 존재합니다(예: `b0acfa9` → `a9a79a8`). 그 짝을 비교해보는 게 가장 빠른 학습법입니다.

사전 준비: Docker Desktop이 켜져 있어야 합니다(Testcontainers가 MySQL/RabbitMQ를 자동으로 띄웁니다). `./gradlew test`가 한 번 정상적으로 돌아가는지 먼저 확인하세요.

---

## Day 1 — 도메인 기초: 왜 "잔액"이 아니라 "원장"이 진실인가 (S0, S1)

### 왜 필요했나

은행/지갑 시스템에서 가장 흔한 실수는 "잔액 컬럼 하나만 믿는" 설계입니다. `UPDATE wallet SET balance = balance - 1000`처럼 잔액을 직접 깎으면, 그 거래가 **왜** 일어났는지, **언제** 몇 번 일어났는지에 대한 기록이 전혀 안 남습니다. 버그가 나거나 분쟁이 생기면 "잔액이 왜 이 값인지" 아무도 설명할 수 없습니다.

이 프로젝트는 반대로 설계합니다: **거래 기록(원장, `LedgerEntry`)이 진실이고, 잔액(`Wallet.balance`)은 그 기록들을 더한 결과를 캐시해둔 것**입니다.

### 핵심 코드

- [`Wallet.java`](../src/main/java/com/example/wallet/wallet/Wallet.java) — `balance`, `version`(낙관적 락용, Day 4에서 다룹니다) 필드. `charge()`/`pay()` 메서드 두 개만 잔액을 바꿀 수 있습니다(`pay()`는 잔액이 모자라면 예외를 던지는 걸 한 메서드 안에서 같이 합니다 — "확인"과 "차감"이 분리돼 있으면 그 사이에 다른 거래가 끼어들 수 있기 때문입니다. Day 2에서 이게 왜 중요한지 직접 봅니다).
- [`LedgerEntry.java`](../src/main/java/com/example/wallet/ledger/LedgerEntry.java) — **setter가 없습니다.** 생성 후 값을 바꿀 방법이 코드에 없습니다. `type`(`CHARGE`/`PAYMENT`/`REFUND`/...)으로 거래 종류를 구분하고, `amount`는 항상 양수로 저장합니다.
- [`LedgerType.java`](../src/main/java/com/example/wallet/ledger/LedgerType.java) — 각 타입이 잔액을 늘리는지(`sign() > 0`) 줄이는지(`sign() < 0`) 정의합니다.
- [`WalletService.charge()`](../src/main/java/com/example/wallet/wallet/WalletService.java) — `@Transactional` 메서드 하나 안에서 `wallet.charge(amount)`와 `LedgerEntry` INSERT가 같이 일어납니다. 이게 핵심입니다: **둘 중 하나만 반영되는 경우가 구조적으로 없습니다**(트랜잭션이 실패하면 둘 다 롤백).

### 직접 해보기

```bash
# 1. 테스트로 "충전 후 잔액 == 원장 합계"가 항상 성립하는지 확인
./gradlew test --tests "com.example.wallet.wallet.WalletServiceTest"

# 2. 앱을 실행하고 직접 충전을 호출해본다
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'

curl -X POST http://localhost:8080/wallets/1/charge \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen 2>/dev/null || echo key-001)" \
  -d '{"amount": 10000}'
```

(`wallets/1`이 없다고 에러가 나면 — 맞습니다, 아직 지갑을 만드는 API가 없습니다. `docker exec -it wallet-mysql mysql -uwallet -pwallet1234 wallet -e "INSERT INTO wallet (user_id, balance, version, created_at, updated_at) VALUES (1, 0, 0, NOW(), NOW());"`로 직접 한 행 넣어보세요. "지갑 가입" API는 이 프로젝트의 스코프 밖입니다 — 동시성/정합성에 집중하기 위해 일부러 생략했습니다.)

### 스스로 점검

- `Wallet.balance`를 "캐시"라고 부르는 이유를 내 말로 설명할 수 있는가?
- `LedgerEntry`에 setter가 없는 것과 "원장이 진실의 원천"이라는 설계가 어떻게 연결되는가?
- `WalletService.charge()`에서 트랜잭션 경계(`@Transactional`이 어디서 시작하고 끝나는지)를 정확히 가리킬 수 있는가?

---

## Day 2 — 동시성 I: 같은 지갑에 동시 결제가 들어오면? (S2)

### 왜 필요했나

이 프로젝트의 핵심 주제입니다. **같은 지갑에 결제 요청 두 개가 정확히 동시에 들어오면 무슨 일이 생기는가?** 이 질문에 답하려고 일부러 버그를 먼저 만들고, 그다음 고쳤습니다.

### 핵심 코드 — 버그가 있던 버전 (커밋 `b0acfa9`)

```bash
git show b0acfa9:src/main/java/com/example/wallet/payment/PaymentService.java
```

이 버전은 "잔액 확인 → 차감"을 락 없이 했습니다. 두 트랜잭션이 동시에 "잔액 확인" 시점에 들어오면, 둘 다 "충분하다"고 판단하고 둘 다 차감을 시도합니다 — 잔액이 음수가 됩니다.

### 핵심 코드 — 수정된 버전 (지금 코드, 커밋 `a9a79a8` 이후)

- [`WalletRepository.findByIdForUpdate()`](../src/main/java/com/example/wallet/wallet/WalletRepository.java) — `@Lock(PESSIMISTIC_WRITE)` + `SELECT ... FOR UPDATE`. 이 줄이 핵심입니다: 이 row를 읽는 순간 DB가 행 잠금을 걸어서, 같은 지갑을 동시에 노리는 다른 트랜잭션은 **이 SELECT 문 자체에서 멈춰서 기다립니다.**
- [`PaymentService.pay()`](../src/main/java/com/example/wallet/payment/PaymentService.java) 1~2번째 줄(56번 줄 `findByIdForUpdate`) — 잠긴 채로 읽은 `wallet`에 `wallet.pay(amount)`를 호출합니다. 깨어난 트랜잭션은 **갱신된 최신 잔액**을 보고 다시 판단하므로, "확인했던 값이 쓰는 시점엔 이미 낡았다"는 일이 일어날 수 없습니다.

### 직접 해보기

```bash
# 지금 테스트는 "하나만 성공, 잔액은 절대 음수 아님"을 증명한다
./gradlew test --tests "com.example.wallet.payment.PaymentConcurrencyTest"

# 버그 버전과 수정 버전의 차이를 직접 본다 (가장 중요한 실습)
git diff b0acfa9 a9a79a8 -- src/main/java/com/example/wallet/payment/PaymentService.java
git diff b0acfa9 a9a79a8 -- src/test/java/com/example/wallet/payment/PaymentConcurrencyTest.java
```

테스트 코드 diff를 보면, **테스트 시나리오(같은 지갑, 6000원 결제 2건 동시)는 그대로**이고 **기대 결과만 뒤집힌** 걸 볼 수 있습니다. 이게 "버그를 먼저 테스트로 증명하고, 같은 테스트로 수정을 검증한다"는 패턴입니다. 이 프로젝트 전체에서 반복됩니다(S11의 데드락도 똑같은 패턴, Day 7).

### 스스로 점검

- `SELECT ... FOR UPDATE`가 일반 `SELECT`와 다른 점을 DB 레벨에서 설명할 수 있는가?
- 왜 "확인"과 "차감"을 같은 락이 걸린 상태에서 처리해야 하는지(둘이 분리되면 왜 위험한지) 설명할 수 있는가?
- `PaymentConcurrencyTest`가 `CountDownLatch`를 2개(`readyLatch`, `startLatch`) 쓰는 이유는? (힌트: 두 스레드가 "동시에 시작"하게 만들려고)

---

## Day 3 — 멱등성, 환불, 정산 (S3, S4, S5)

### 왜 필요했나

세 가지 서로 다르지만 연결된 문제입니다:
1. **같은 요청이 네트워크 문제로 두 번 전송되면?**(클라이언트 재시도) → 멱등성.
2. **결제를 취소하려면 기존 기록을 고쳐야 하나?** → 원장 불변 원칙을 환불에도 똑같이 적용.
3. **하루 동안의 거래를 가맹점별로 집계하려면?** → 정산 배치.

### 핵심 코드 — 멱등성 (ADR-003)

- [`docs/decisions.md`의 ADR-003](decisions.md) — 별도 테이블 대신 `LedgerEntry.idempotencyKey` + UNIQUE 제약을 쓴 이유.
- [`PaymentService.pay()`](../src/main/java/com/example/wallet/payment/PaymentService.java) 51~54번 줄 — 처리 전에 같은 키의 `LedgerEntry`가 있는지 먼저 확인. 있으면 재계산 없이 그대로 반환.
- 62~67번 줄 — INSERT가 UNIQUE 제약 위반으로 실패하면 `DuplicateIdempotencyKeyException` → 컨트롤러가 이걸 잡아서 [`findPaymentResultByIdempotencyKey()`](../src/main/java/com/example/wallet/payment/PaymentService.java)로 먼저 커밋된 결과를 재조회.

**이해해야 할 핵심 흐름**: 동시에 같은 키로 요청 두 개가 들어오면, 둘 다 "키 없음"을 보고 처리를 시도할 수 있습니다. 늦게 INSERT하는 쪽만 UNIQUE 위반으로 막히고, 그 트랜잭션 전체(잔액 변경 포함)가 롤백됩니다 — "확인 후 처리"가 아니라 **"처리 후 DB가 막아주면 그때 재조회"**하는 패턴입니다.

### 핵심 코드 — 환불 (원장 불변)

- [`RefundService.refund()`](../src/main/java/com/example/wallet/payment/RefundService.java) — 기존 `PAYMENT` 행을 절대 고치지 않고, 반대 방향(`charge`)의 새 `REFUND` 행을 만듭니다(57번 줄, 60~66번 줄). `refundOfEntryId`로 원결제를 가리킬 뿐입니다.
- 52~55번 줄 — "지금까지 이 결제, 얼마나 환불됐나"(`sumRefunded`)를 확인하는 시점과 새 환불을 기록하는 시점 사이에 다른 환불이 끼어들 수 없는 이유: **결제 때 쓴 것과 같은 `findByIdForUpdate` 락**을 그대로 쓰기 때문입니다(49번 줄). Day 2에서 배운 락이 여기서도 그대로 재사용됩니다 — 새 장치를 만들지 않았습니다.

### 핵심 코드 — 정산 (QueryDSL)

- [`SettlementQueryRepository.aggregate()`](../src/main/java/com/example/wallet/settlement/SettlementQueryRepository.java) — `CaseBuilder`로 "PAYMENT면 amount, 아니면 0"을 SQL의 `SUM(CASE ...)`으로 만듭니다. Spring Data의 메서드 이름 규칙(`findByXxx`)으로는 "가맹점 필터가 있을 수도, 없을 수도 있는" 동적 쿼리를 표현할 수 없어서 QueryDSL을 씁니다.
- [`SettlementBatchRunner.run()`](../src/main/java/com/example/wallet/settlement/SettlementBatchRunner.java) — `(merchantId, settlementDate)` UNIQUE 제약으로 같은 날짜를 두 번 정산해도 중복이 안 생깁니다(`DuplicateSettlementException` 잡고 기존 결과 재조회 — 멱등성과 똑같은 패턴!).
- [ADR-004](decisions.md) — 수수료를 `LedgerEntry`로 안 남기는 이유.

### 직접 해보기

```bash
# 멱등성: 같은 키로 두 번 호출해도 처리는 1회뿐인지
./gradlew test --tests "com.example.wallet.payment.PaymentIdempotencyTest"

# 환불: 누적 환불이 원결제액을 못 넘는지
./gradlew test --tests "com.example.wallet.payment.RefundServiceTest"

# 정산: 재실행해도 중복 안 생기는지
./gradlew test --tests "com.example.wallet.settlement.SettlementBatchRunnerTest"

# 가맹점 만들어서 정산 직접 트리거
docker exec -it wallet-mysql mysql -uwallet -pwallet1234 wallet \
  -e "INSERT INTO merchant (name, fee_rate, created_at, updated_at) VALUES ('테스트가맹점', 0.0250, NOW(), NOW());"
curl -X POST "http://localhost:8080/settlements/run?date=2026-06-01"
```

### 스스로 점검

- "동시에 같은 멱등키로 요청 두 개가 오면" 무슨 일이 일어나는지, 어느 쪽이 이기는지, 진 쪽은 어떤 결과를 받는지 순서대로 말할 수 있는가?
- 환불이 결제 락을 "재사용"한다는 게 무슨 뜻인지 설명할 수 있는가?
- QueryDSL을 쓰는 이유를 Spring Data 메서드 이름 규칙의 한계와 연결해서 설명할 수 있는가?

---

## Day 4 — 비동기 메시징과 락 전략 실측 (S6, S7, S8)

### 왜 필요했나

두 가지 독립적인 주제입니다: (1) 결제가 끝난 뒤 "결제완료"를 다른 시스템에 알리는 비동기 메시징, (2) Day 2의 비관적 락이 유일한 답인지 검증하기 위한 낙관적 락 비교.

### 핵심 코드 — 비동기 발행 (S6)

> 참고: S6 당시엔 `@TransactionalEventListener(AFTER_COMMIT)`를 직접 썼지만, **지금 코드는 이미 S10(아웃박스)으로 교체돼 있습니다.** Day 6에서 "왜 교체했는지"를 다룹니다. 여기서는 "왜 커밋 후에 발행해야 하는지"라는 핵심 아이디어만 짚습니다.

- [`PaymentService.pay()`](../src/main/java/com/example/wallet/payment/PaymentService.java) 69~70번 줄 — 결제 트랜잭션이 끝나기 전에 메시지를 미리 보내면, 그 트랜잭션이 롤백됐을 때 "결제 안 됐는데 결제완료 메시지가 나간" 상태가 됩니다. 그래서 항상 **커밋이 확정된 후에만** 발행해야 합니다.
- [`PaymentNotificationListener`](../src/main/java/com/example/wallet/notification/PaymentNotificationListener.java) — 소비자 멱등성: `ProcessedPaymentEvent`(paymentId가 PK)로 같은 메시지를 두 번 받아도 두 번 처리하지 않습니다.

### 핵심 코드 — 비관적 락 vs 낙관적 락 (S7)

- [`OptimisticPaymentService.pay()`](../src/main/java/com/example/wallet/payment/optimistic/OptimisticPaymentService.java) — `@Version` 충돌(`ObjectOptimisticLockingFailureException`)이 나면 최대 5번 재시도, 선형 백오프(`10ms * 시도번호`).
- **self-invocation 문제**(이 프로젝트에 반복해서 등장하는 함정): `OptimisticPaymentService`는 `@Transactional`이 아니고, 실제 DB 작업은 별도 빈인 `OptimisticPaymentWriter`에 있습니다. 같은 클래스 안에서 `this.메서드()`로 `@Transactional` 메서드를 부르면 Spring AOP 프록시를 안 거쳐서 트랜잭션이 전혀 안 걸립니다 — 그래서 항상 "트랜잭션이 필요한 로직"과 "그걸 호출하며 재시도/루프를 도는 로직"을 별도 빈으로 분리합니다. (`SettlementBatchRunner` vs `SettlementService`도 같은 이유로 분리돼 있습니다, Day 3.)
- [`docs/optimistic-vs-pessimistic-lock.md`](optimistic-vs-pessimistic-lock.md) — 전체 비교.

### 핵심 코드 — 실측 (S8)

분석만 하지 않고 k6로 실제로 측정했습니다. [`docs/benchmark.md`](benchmark.md)의 표를 보면: 비관적 락은 경합이 심해져도(VU 200) 에러율 0%, 낙관적 락은 에러율이 88.5%까지 치솟습니다. **왜 그런지** 스스로 설명해보세요(힌트: 낙관적 락은 "충돌 감지 후 재시도"인데, 경합이 심하면 재시도도 또 충돌합니다 — 비관적 락은 충돌 자체가 안 나고 그냥 줄을 서서 기다립니다).

### 직접 해보기

```bash
./gradlew test --tests "com.example.wallet.payment.optimistic.OptimisticPaymentConcurrencyTest"
```

`docs/benchmark.md`를 열어서 표를 본 다음, **이 프로젝트라면 비관적/낙관적 중 어느 걸 기본으로 써야 하는지** 스스로 결론을 내려보세요(정답은 README에 있지만, 보기 전에 먼저 생각해보는 게 핵심입니다).

### 스스로 점검

- "self-invocation 문제"가 무엇이고, 왜 Spring `@Transactional`이 별도 빈 분리를 요구하는지 설명할 수 있는가?
- 낙관적 락에서 재시도할 때마다 지갑을 처음부터 다시 읽는 이유는?
- 비관적 락이 "항상" 더 좋다고 할 수 없는 이유(트레이드오프)는 무엇인가?

---

## Day 5 — 외부 PG 연동과 분산 정합성 (S9)

### 왜 필요했나

지금까지의 결제는 "우리 DB만 바꾸면 끝"이었습니다. 실제 결제는 **외부 PG사 승인**이 끼고, 그 호출은 우리가 통제할 수 없습니다(느릴 수도, 응답이 안 올 수도, "처리는 됐는데 응답만 유실"될 수도 있습니다). 이게 분산 시스템의 본질적인 어려움입니다.

### 핵심 질문 먼저

**"우리 DB 커밋은 됐는데 PG 호출이 실패하면? 반대로 PG는 승인했는데 우리 응답을 못 받으면?"** 이 질문에 2PC(분산 트랜잭션) 없이 답하는 게 S9의 전부입니다. [`docs/distributed-consistency.md`](distributed-consistency.md)에 전체 상태 다이어그램이 있습니다 — Day 5는 이 문서를 코드와 함께 읽기 위한 안내입니다.

### 핵심 코드

- [`Payment.java`](../src/main/java/com/example/wallet/payment/Payment.java) — `LedgerEntry`(불변)와 달리 **의도적으로 가변**입니다. `PENDING_PG → APPROVED/FAILED` 상태를 가집니다. 왜 가변이어야 하는지: 이건 "확정된 사실"이 아니라 "지금까지 파악한 PG 승인 진행 상황"이기 때문입니다.
- [`ExternalPaymentService.requestPayment()`](../src/main/java/com/example/wallet/payment/external/ExternalPaymentService.java) — **이 클래스는 `@Transactional`이 아닙니다.** PG 호출(`pgClient.approve()`, 26번 줄)을 트랜잭션 밖에서 호출합니다 — 절대 규칙 6번(외부 호출은 트랜잭션 안에 두지 않는다)을 코드로 본 것. DB 쓰기는 `ExternalPaymentWriter`의 짧은 트랜잭션 세 개(`createPending`/`confirmApproved`/`markFailed`)가 따로 합니다.
- 28~34번 줄 — PG 응답을 `APPROVED`/`DEFINITELY_FAILED`/`UNKNOWN` 세 가지로만 나눕니다. **`UNKNOWN`(타임아웃, 응답유실)을 "실패"로 단정하지 않고 `PENDING_PG`로 그대로 둡니다** — 이게 이 설계의 핵심입니다.
- [`PaymentReconciliationService`](../src/main/java/com/example/wallet/payment/external/PaymentReconciliationService.java) + [`PaymentReconciliationScheduler`](../src/main/java/com/example/wallet/payment/external/PaymentReconciliationScheduler.java) — `PENDING_PG`로 남은 결제를 10초마다 PG에 직접 다시 물어봐서(`GET /pg/payments/{key}`) 진실을 확정합니다.
- [`fake-pg/src/main/java/com/example/fakepg/PgApproveController.java`](../fake-pg/src/main/java/com/example/fakepg/PgApproveController.java) — `X-Simulate-Failure` 헤더로 `timeout`/`error5xx`/`lost-response` 장애를 **결정적으로**(매번 같게) 재현합니다.

### 직접 해보기

```bash
./gradlew test --tests "com.example.wallet.payment.external.ExternalPaymentFlowTest"

# fake-pg를 별도로 띄우고, 응답유실 시나리오를 직접 만들어본다
./gradlew :fake-pg:bootRun &
curl -X POST http://localhost:8080/payments/external \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ext-001" \
  -H "X-Simulate-Failure: lost-response" \
  -d '{"walletId": 1, "merchantId": 1, "amount": 5000}'
# 응답은 PENDING_PG일 것이다 — 보정을 수동으로 트리거해본다
curl -X POST http://localhost:8080/payments/external/1/reconcile
```

### 스스로 점검

- `Payment`가 가변이고 `LedgerEntry`가 불변인 이유의 차이를 설명할 수 있는가?
- "타임아웃"과 "응답유실"이 클라이언트 입장에서 왜 구분이 안 되는지, 그런데도 왜 같은 처리(`PENDING_PG`로 유보)를 하면 충분한지 설명할 수 있는가?
- 왜 2PC(분산 트랜잭션)를 안 쓰고 이 방식(상태기계+보정)을 택했는지 자기 말로 설명할 수 있는가?

---

## Day 6 — 메시지 유실 방지: Transactional Outbox (S10)

### 왜 필요했나

Day 4에서 본 "커밋 후 발행"(`AFTER_COMMIT`)은 "커밋 전에 발행해서 롤백된 거래가 발행되는" 문제는 막아주지만, **다른 방향의 문제**가 남습니다: **커밋은 됐는데, 발행을 시도하기 직전에 프로세스가 죽으면 그 이벤트는 영원히 사라집니다.** "발행해야 한다"는 사실이 메모리에만(리스너 호출 스택에만) 잠깐 존재했다가 사라지기 때문입니다.

### 핵심 아이디어

"비즈니스 데이터 변경"과 "발행할 이벤트가 있다"는 사실을 **같은 로컬 트랜잭션**에 같이 커밋합니다.

### 핵심 코드

- [`OutboxEvent.java`](../src/main/java/com/example/wallet/outbox/OutboxEvent.java) — `PENDING`/`PUBLISHED` 상태를 가진 엔티티.
- [`PaymentService.pay()`](../src/main/java/com/example/wallet/payment/PaymentService.java) 69~70번 줄 — `LedgerEntry` INSERT와 **같은 트랜잭션 안에서** `OutboxEvent`도 INSERT합니다. 이 메서드가 커밋되는 순간 "발행해야 할 이벤트가 있다"는 사실도 항상 같이 영속화됩니다.
- [`OutboxRelay.relayPending()`](../src/main/java/com/example/wallet/outbox/OutboxRelay.java) — 트랜잭션 **밖**에서, `PENDING` 행을 읽어 RabbitMQ로 발행하고 성공한 것만 `PUBLISHED`로 표시합니다. 실패하면 그냥 `PENDING`으로 남아서 다음 폴링에서 다시 시도됩니다.
- [`OutboxRelayScheduler`](../src/main/java/com/example/wallet/outbox/OutboxRelayScheduler.java) — 2초마다 폴링.

### 가장 중요한 실습: "유실 0"을 어떻게 테스트로 증명하는가

[`OutboxRelayTest.java`](../src/test/java/com/example/wallet/outbox/OutboxRelayTest.java)를 열어서 **재구동을_시뮬레이션한_릴레이_실행은_유실_없이_PENDING_이벤트를_발행한다** 테스트를 읽어보세요. "프로세스가 죽었다가 재시작했다"를 흉내내려고 JVM을 진짜로 죽이는 대신, 그냥 릴레이를 처음 호출합니다 — 릴레이 입장에서는 "이 PENDING 행이 방금 생긴 건지 1분 전에 생긴 건지" 구분할 필요가 없기 때문입니다. **상태가 DB에 있으면, 왜 아직 발행을 못 했는지 몰라도 안전하게 다시 시도할 수 있다**는 게 이 패턴의 핵심입니다.

[`docs/outbox-pattern.md`](outbox-pattern.md)의 비교표(AFTER_COMMIT vs Outbox)를 꼭 읽어보세요.

### 직접 해보기

```bash
./gradlew test --tests "com.example.wallet.outbox.OutboxRelayTest"

curl -X POST http://localhost:8080/outbox/relay   # 수동 발행 트리거
```

### 스스로 점검

- "발행해야 한다는 사실이 메모리에만 있다"는 게 무슨 뜻이고, 아웃박스가 그걸 어떻게 없애는지 설명할 수 있는가?
- 아웃박스가 "중복 발행"을 막지 못한다는 게 왜 문제가 안 되는지(소비자 멱등성과의 관계) 설명할 수 있는가?
- AFTER_COMMIT과 아웃박스, 둘 다 "최소 1회(at-least-once)"인데 왜 굳이 바꿨는지 설명할 수 있는가?

---

## Day 7 — 송금 데드락과 잔액 정합성 점검, 그리고 전체 복습 (S11)

### 왜 필요했나

지갑 간 송금은 결제와 다릅니다 — **지갑 두 개를 한 트랜잭션에서 동시에** 잠가야 합니다. Day 2에서 배운 비관적 락이 여기서는 **새로운 문제(데드락)**를 만들 수 있다는 게 드러납니다. 그리고 "원장 기반 설계라 잔액과 원장이 항상 같다"는 보장이 코드로는 맞아도, 운영 중 정말 한 번도 안 어긋나는지는 따로 확인해야 합니다.

### 핵심 코드 — 데드락

```bash
# 정렬 없이 잠그던 버전(버그)
git show 5c4c23a:src/main/java/com/example/wallet/wallet/TransferService.java
```

A(id=1)→B(id=2) 송금과 B→A 송금이 동시에 들어오면, 인자 순서 그대로 잠그는 구현은 하나는 A를 쥐고 B를 기다리고 다른 하나는 B를 쥐고 A를 기다리는 **순환 대기**에 빠집니다 — MySQL이 둘 중 하나를 강제로 죽입니다(데드락).

- [`TransferService.transfer()`](../src/main/java/com/example/wallet/wallet/TransferService.java) 60~68번 줄 — 지금 코드는 `Math.min`/`Math.max`로 **항상 id가 작은 지갑을 먼저 잠급니다.** 같은 지갑 쌍을 건드리는 모든 송금이 항상 같은 순서로 락을 요청하게 되므로, 순환 대기의 전제 자체가 성립하지 않습니다.
- 55~58번 줄 — `SET innodb_lock_wait_timeout = 3`을 이 트랜잭션의 세션에서만 설정. 락 순서를 고정해도 "데드락은 아니지만 오래 기다리는" 상황은 남기 때문에, 상한을 둡니다. (JPA 표준 `lock.timeout` 힌트를 먼저 시도했다가 MySQL에서 실제로 동작 안 하는 걸 테스트로 확인하고 바꾼 사례 — [`docs/deadlock-prevention.md`](deadlock-prevention.md) 참고.)

### 핵심 코드 — 잔액 정합성 점검

- [`LedgerSumQueryRepository.sumSignedAmountByWalletId()`](../src/main/java/com/example/wallet/reconciliation/LedgerSumQueryRepository.java) — QueryDSL `CASE`로 지갑별 원장 합계를 DB에서 직접 집계.
- [`BalanceReconciliationService.reconcile()`](../src/main/java/com/example/wallet/reconciliation/BalanceReconciliationService.java) — 모든 지갑의 캐시 잔액과 원장 합계를 비교. **다르면 `BalanceDiscrepancy`에 기록만 하고, 자동으로 고치지 않습니다.** 왜 자동으로 안 고치는지가 이 배치에서 가장 중요한 질문입니다 — [`docs/balance-reconciliation.md`](balance-reconciliation.md)를 꼭 읽어보세요(힌트: 증상을 지우면 원인을 못 찾는다, 원장이 항상 옳다고 가정할 수 없다, 감사 관점).

### 직접 해보기

```bash
# 데드락이 더 이상 안 나는지(과거엔 났었다 — git log로 비교)
./gradlew test --tests "com.example.wallet.wallet.TransferDeadlockTest"
./gradlew test --tests "com.example.wallet.wallet.TransferLockTimeoutTest"

# 정합성 점검: 정상 상태 vs 일부러 망가뜨린 상태
./gradlew test --tests "com.example.wallet.reconciliation.BalanceReconciliationServiceTest"

curl -X POST http://localhost:8080/reconciliation/run
```

### 전체 복습 — 이 7일을 하나로 묶기

아래 질문에 코드를 안 보고 답해보세요. 막히면 해당 Day로 돌아가세요.

1. 이 시스템에서 "잔액"이 거짓말을 할 수 없는 이유를 한 문장으로? (Day 1)
2. 비관적 락과 낙관적 락, 결제에 어느 걸 기본으로 쓰고 왜? (Day 2, 4)
3. 멱등성 키가 막아주는 상황을 구체적인 예로 들 수 있는가? (Day 3)
4. 분산 시스템에서 "확신할 수 없는 순간"이 이 프로젝트에 몇 군데 나오고, 각각 어떤 도구로 풀었는가? (PG 응답유실=상태기계+보정, 메시지 유실=아웃박스, 캐시-원장 불일치=정합성 배치) (Day 5, 6, 7)
5. 이 프로젝트에서 "버그를 먼저 테스트로 증명하고, 같은 테스트로 수정을 검증"한 사례를 두 개 이상 들 수 있는가? (Day 2의 결제, Day 7의 송금 데드락)

다섯 개를 막힘 없이 답할 수 있다면, README.md를 다시 읽어보세요 — 이번엔 "이미 아는 내용을 정리된 형태로 다시 보는" 느낌일 것입니다. 그게 이 가이드의 목표입니다.

---

## 용어집

- **트랜잭션(Transaction)**: 여러 DB 작업을 "전부 성공 또는 전부 실패"로 묶는 단위. Spring에서는 `@Transactional`이 붙은 메서드 하나가 보통 트랜잭션 하나의 경계가 됩니다.
- **원장(Ledger)**: 거래 내역을 시간순으로 쌓아두는, 수정/삭제가 금지된 기록. 잔액 같은 "현재 상태"는 원장을 더한 **결과**여야 합니다.
- **멱등성(Idempotency)**: 같은 요청을 여러 번 보내도 결과가 한 번 보낸 것과 같아야 하는 성질. 네트워크 재시도가 있는 모든 API에 필요합니다.
- **비관적 락(Pessimistic Lock)**: "충돌이 날 거다"라고 미리 가정하고, 데이터를 읽는 순간 잠가서 다른 트랜잭션을 기다리게 함(`SELECT ... FOR UPDATE`).
- **낙관적 락(Optimistic Lock)**: "충돌이 안 날 거다"라고 가정하고, 쓰는 시점에 버전(`@Version`)이 그대로인지 확인. 바뀌었으면 충돌 — 재시도 필요.
- **데드락(Deadlock)**: 두 트랜잭션이 서로가 가진 락을 기다리며 영원히 멈추는 상태(순환 대기). DB가 감지해서 한쪽을 강제로 실패시킴.
- **self-invocation 문제**: Spring의 `@Transactional`(또는 다른 AOP 기능)은 프록시를 거쳐야 동작하는데, 같은 클래스 안에서 `this.메서드()`로 호출하면 프록시를 안 거쳐서 무효화됨. 트랜잭션이 필요한 로직을 별도 빈으로 분리해서 해결.
- **2PC(Two-Phase Commit)**: 여러 시스템에 걸친 트랜잭션을 원자적으로 묶으려는 분산 트랜잭션 기법. 이 프로젝트는 일부러 안 씀(외부 PG가 우리 프로토콜을 따를 의무가 없고, 락을 오래 들고 있어 가용성이 떨어짐) — 대신 멱등성 + 상태기계 + 사후 보정으로 풉니다.
- **Transactional Outbox 패턴**: "발행할 이벤트가 있다"는 사실을 비즈니스 데이터와 같은 트랜잭션에 영속화해서, 발행이 언제 실패하거나 프로세스가 죽어도 다시 시도할 수 있게 만드는 패턴.
- **at-least-once(최소 1회)**: 메시지가 0번이 아니라 1번 이상은 반드시 전달됨을 보장. 중복 전달 가능성이 있으므로 소비자가 멱등성을 가져야 함.
- **QueryDSL**: 타입 세이프하게 동적 SQL(조건이 있을 수도 없을 수도 있는 쿼리, 집계)을 코드로 조립하는 라이브러리. Spring Data의 메서드 이름 규칙으로 표현 못 하는 쿼리에 씀.

## 더 보기

- 단계별 커밋 목록: `git log --oneline --reverse`
- 포트폴리오용 요약: [README.md](../README.md)
- 설계 결정(ADR) 전체: [decisions.md](decisions.md)
