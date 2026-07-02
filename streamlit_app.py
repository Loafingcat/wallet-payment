from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from enum import Enum
from uuid import uuid4

import streamlit as st


APP_STATE_VERSION = "simple-demo-v3"
APP_DISPLAY_VERSION = "간편 체험판 v3"


class LedgerType(Enum):
    CHARGE = ("충전", 1)
    PAYMENT = ("결제", -1)
    REFUND = ("환불", 1)
    TRANSFER_OUT = ("송금 출금", -1)
    TRANSFER_IN = ("송금 입금", 1)

    @property
    def label(self) -> str:
        return self.value[0]

    @property
    def sign(self) -> int:
        return self.value[1]


class PaymentStatus(Enum):
    PENDING_PG = "PENDING_PG"
    APPROVED = "APPROVED"
    FAILED = "FAILED"


class OutboxStatus(Enum):
    PENDING = "PENDING"
    PUBLISHED = "PUBLISHED"


@dataclass
class Wallet:
    id: int
    user: str
    balance: int


@dataclass
class LedgerEntry:
    id: int
    wallet_id: int
    type: LedgerType
    amount: int
    balance_after: int
    idempotency_key: str
    merchant: str | None = None
    refund_of_entry_id: int | None = None
    created_at: datetime = field(default_factory=datetime.now)

    @property
    def signed_amount(self) -> int:
        return self.amount * self.type.sign


@dataclass
class Payment:
    id: int
    wallet_id: int
    merchant: str
    amount: int
    idempotency_key: str
    status: PaymentStatus
    ledger_entry_id: int | None = None
    reason: str | None = None
    created_at: datetime = field(default_factory=datetime.now)


@dataclass
class OutboxEvent:
    id: int
    event_type: str
    payload: str
    status: OutboxStatus = OutboxStatus.PENDING
    created_at: datetime = field(default_factory=datetime.now)
    published_at: datetime | None = None


def next_id(name: str) -> int:
    key = f"next_{name}_id"
    value = st.session_state[key]
    st.session_state[key] += 1
    return value


def format_won(amount: int) -> str:
    return f"{amount:,}원"


def new_key(prefix: str) -> str:
    return f"{prefix}-{uuid4().hex[:8]}"


def init_state(force: bool = False) -> None:
    if not force and st.session_state.get("app_state_version") == APP_STATE_VERSION and "wallets" in st.session_state:
        return

    for key in list(st.session_state.keys()):
        del st.session_state[key]

    st.session_state.app_state_version = APP_STATE_VERSION
    st.session_state.next_wallet_id = 1
    st.session_state.next_ledger_id = 1
    st.session_state.next_payment_id = 1
    st.session_state.next_outbox_id = 1
    st.session_state.wallets: dict[int, Wallet] = {}
    st.session_state.ledger: list[LedgerEntry] = []
    st.session_state.payments: list[Payment] = []
    st.session_state.outbox: list[OutboxEvent] = []
    st.session_state.pg_store: dict[str, str] = {}
    st.session_state.processed_events: set[int] = set()
    st.session_state.settlements: dict[tuple[str, date], dict[str, int | str]] = {}
    st.session_state.activity: list[str] = []

    wallet_a = create_wallet("사용자 A", 100_000)
    wallet_b = create_wallet("사용자 B", 30_000)
    add_ledger(wallet_a.id, LedgerType.CHARGE, 100_000, new_key("seed-charge-a"))
    add_ledger(wallet_b.id, LedgerType.CHARGE, 30_000, new_key("seed-charge-b"))


def reset_state() -> None:
    init_state(force=True)
    flash("데모 데이터를 초기화했습니다.")


def flash(message: str) -> None:
    st.session_state.activity.insert(0, f"{datetime.now().strftime('%H:%M:%S')}  {message}")
    st.session_state.activity = st.session_state.activity[:8]


def create_wallet(user: str, balance: int = 0) -> Wallet:
    wallet = Wallet(next_id("wallet"), user, balance)
    st.session_state.wallets[wallet.id] = wallet
    return wallet


def find_ledger_by_key(key: str) -> LedgerEntry | None:
    return next((entry for entry in st.session_state.ledger if entry.idempotency_key == key), None)


def add_ledger(
    wallet_id: int,
    ledger_type: LedgerType,
    amount: int,
    idempotency_key: str,
    merchant: str | None = None,
    refund_of_entry_id: int | None = None,
) -> LedgerEntry:
    wallet = st.session_state.wallets[wallet_id]
    entry = LedgerEntry(
        id=next_id("ledger"),
        wallet_id=wallet_id,
        type=ledger_type,
        amount=amount,
        balance_after=wallet.balance,
        merchant=merchant,
        refund_of_entry_id=refund_of_entry_id,
        idempotency_key=idempotency_key,
    )
    st.session_state.ledger.append(entry)
    return entry


def charge(wallet_id: int, amount: int, key: str) -> LedgerEntry:
    existing = find_ledger_by_key(key)
    if existing:
        flash(f"멱등키 재사용: 기존 충전 #{existing.id} 결과를 반환했습니다.")
        return existing

    wallet = st.session_state.wallets[wallet_id]
    wallet.balance += amount
    entry = add_ledger(wallet_id, LedgerType.CHARGE, amount, key)
    flash(f"{wallet.user} 지갑에 {format_won(amount)} 충전")
    return entry


def pay(wallet_id: int, merchant: str, amount: int, key: str) -> LedgerEntry | None:
    existing = find_ledger_by_key(key)
    if existing:
        flash(f"멱등키 재사용: 기존 결제 #{existing.id} 결과를 반환했습니다.")
        return existing

    wallet = st.session_state.wallets[wallet_id]
    if wallet.balance < amount:
        flash(f"잔액 부족: {wallet.user} 결제 {format_won(amount)} 거절")
        return None

    wallet.balance -= amount
    entry = add_ledger(wallet_id, LedgerType.PAYMENT, amount, key, merchant=merchant)
    st.session_state.outbox.append(
        OutboxEvent(
            id=next_id("outbox"),
            event_type="PaymentCompleted",
            payload=f"ledgerEntryId={entry.id}, walletId={wallet_id}, amount={amount}",
        )
    )
    flash(f"{merchant} 결제 승인: {format_won(amount)}")
    return entry


def refund(payment_entry_id: int, amount: int, key: str) -> LedgerEntry | None:
    existing = find_ledger_by_key(key)
    if existing:
        flash(f"멱등키 재사용: 기존 환불 #{existing.id} 결과를 반환했습니다.")
        return existing

    payment = next((entry for entry in st.session_state.ledger if entry.id == payment_entry_id), None)
    if not payment or payment.type is not LedgerType.PAYMENT:
        flash("환불 대상 결제를 찾지 못했습니다.")
        return None

    refunded = sum(
        entry.amount
        for entry in st.session_state.ledger
        if entry.type is LedgerType.REFUND and entry.refund_of_entry_id == payment_entry_id
    )
    if refunded + amount > payment.amount:
        flash("환불 누적액이 원결제 금액을 초과해 거절했습니다.")
        return None

    wallet = st.session_state.wallets[payment.wallet_id]
    wallet.balance += amount
    entry = add_ledger(
        payment.wallet_id,
        LedgerType.REFUND,
        amount,
        key,
        merchant=payment.merchant,
        refund_of_entry_id=payment_entry_id,
    )
    flash(f"결제 #{payment_entry_id} 환불: {format_won(amount)}")
    return entry


def transfer(from_wallet_id: int, to_wallet_id: int, amount: int, key: str) -> None:
    out_key = f"{key}:out"
    in_key = f"{key}:in"
    if find_ledger_by_key(out_key):
        flash("멱등키 재사용: 기존 송금 결과를 반환했습니다.")
        return

    first_id, second_id = sorted([from_wallet_id, to_wallet_id])
    first = st.session_state.wallets[first_id]
    second = st.session_state.wallets[second_id]
    from_wallet = first if first.id == from_wallet_id else second
    to_wallet = first if first.id == to_wallet_id else second

    if from_wallet.balance < amount:
        flash("송금 잔액 부족으로 거절했습니다.")
        return

    from_wallet.balance -= amount
    add_ledger(from_wallet.id, LedgerType.TRANSFER_OUT, amount, out_key)
    to_wallet.balance += amount
    add_ledger(to_wallet.id, LedgerType.TRANSFER_IN, amount, in_key)
    flash(f"락 순서 고정 후 송금 성공: {from_wallet.user} -> {to_wallet.user}, {format_won(amount)}")


def request_external_payment(wallet_id: int, merchant: str, amount: int, key: str, failure: str) -> Payment:
    existing = next((payment for payment in st.session_state.payments if payment.idempotency_key == key), None)
    if existing:
        flash(f"외부 PG 멱등키 재사용: payment #{existing.id} 반환")
        return existing

    payment = Payment(
        id=next_id("payment"),
        wallet_id=wallet_id,
        merchant=merchant,
        amount=amount,
        idempotency_key=key,
        status=PaymentStatus.PENDING_PG,
    )
    st.session_state.payments.append(payment)

    if failure == "5xx":
        payment.status = PaymentStatus.FAILED
        payment.reason = "PG 5xx: 승인 기록 없음"
        flash("PG 5xx 응답: 돈은 움직이지 않고 FAILED 처리")
        return payment

    if failure == "timeout":
        st.session_state.pg_store[key] = "APPROVED"
        flash("PG 타임아웃: 우리 DB는 PENDING_PG, PG에는 나중에 APPROVED 기록")
        return payment

    if failure == "lost-response":
        st.session_state.pg_store[key] = "APPROVED"
        flash("PG 응답 유실: 우리 DB는 PENDING_PG, PG에는 이미 APPROVED")
        return payment

    st.session_state.pg_store[key] = "APPROVED"
    confirm_external_payment(payment)
    flash("PG 정상 승인: APPROVED와 원장 반영 완료")
    return payment


def confirm_external_payment(payment: Payment) -> Payment:
    if payment.status is not PaymentStatus.PENDING_PG:
        return payment

    entry = pay(payment.wallet_id, payment.merchant, payment.amount, payment.idempotency_key)
    if entry:
        payment.status = PaymentStatus.APPROVED
        payment.ledger_entry_id = entry.id
    else:
        payment.status = PaymentStatus.FAILED
        payment.reason = "잔액 부족"
    return payment


def reconcile_pending_payments() -> None:
    changed = 0
    for payment in st.session_state.payments:
        if payment.status is PaymentStatus.PENDING_PG and st.session_state.pg_store.get(payment.idempotency_key) == "APPROVED":
            confirm_external_payment(payment)
            changed += 1
    flash(f"PG 보정 실행: {changed}건 확정")


def relay_outbox() -> None:
    count = 0
    for event in st.session_state.outbox:
        if event.status is OutboxStatus.PENDING:
            event.status = OutboxStatus.PUBLISHED
            event.published_at = datetime.now()
            if event.event_type == "PaymentCompleted":
                ledger_id = int(event.payload.split("ledgerEntryId=")[1].split(",")[0])
                st.session_state.processed_events.add(ledger_id)
            count += 1
    flash(f"Outbox relay 실행: {count}건 발행")


def run_lost_update_demo() -> None:
    wallet = st.session_state.wallets[1]
    wallet.balance = 10_000
    st.session_state.ledger = [entry for entry in st.session_state.ledger if entry.wallet_id != wallet.id]
    add_ledger(wallet.id, LedgerType.CHARGE, 10_000, new_key("race-seed"))

    both_saw_balance = wallet.balance
    if both_saw_balance >= 6_000:
        wallet.balance -= 6_000
        add_ledger(wallet.id, LedgerType.PAYMENT, 6_000, new_key("race-a"), merchant="A상점")
    if both_saw_balance >= 6_000:
        wallet.balance -= 6_000
        add_ledger(wallet.id, LedgerType.PAYMENT, 6_000, new_key("race-b"), merchant="B상점")
    flash("락 없는 동시 결제 재현: 둘 다 성공해서 잔액이 음수가 됐습니다.")


def run_pessimistic_lock_demo() -> None:
    wallet = st.session_state.wallets[1]
    wallet.balance = 10_000
    st.session_state.ledger = [entry for entry in st.session_state.ledger if entry.wallet_id != wallet.id]
    add_ledger(wallet.id, LedgerType.CHARGE, 10_000, new_key("lock-seed"))

    pay(wallet.id, "A상점", 6_000, new_key("lock-a"))
    pay(wallet.id, "B상점", 6_000, new_key("lock-b"))
    flash("비관적 락 시나리오: 첫 결제만 성공하고 두 번째는 최신 잔액 기준으로 거절됩니다.")


def corrupt_balance() -> None:
    wallet = st.session_state.wallets[1]
    wallet.balance += 12_345
    flash("운영 실수 시뮬레이션: 캐시 잔액만 임의로 바꿨습니다.")


def ledger_sum(wallet_id: int) -> int:
    return sum(entry.signed_amount for entry in st.session_state.ledger if entry.wallet_id == wallet_id)


def find_discrepancies() -> list[dict[str, str | int]]:
    rows = []
    for wallet in st.session_state.wallets.values():
        expected = ledger_sum(wallet.id)
        if wallet.balance != expected:
            rows.append(
                {
                    "walletId": wallet.id,
                    "사용자": wallet.user,
                    "캐시 잔액": wallet.balance,
                    "원장 합계": expected,
                    "차이": wallet.balance - expected,
                }
            )
    return rows


def run_settlement(target_date: date) -> None:
    merchant_names = sorted({entry.merchant for entry in st.session_state.ledger if entry.merchant})
    created = 0
    for merchant in merchant_names:
        key = (merchant, target_date)
        if key in st.session_state.settlements:
            continue
        payments = sum(
            entry.amount
            for entry in st.session_state.ledger
            if entry.merchant == merchant and entry.type is LedgerType.PAYMENT
        )
        refunds = sum(
            entry.amount
            for entry in st.session_state.ledger
            if entry.merchant == merchant and entry.type is LedgerType.REFUND
        )
        fee = round(payments * 0.025)
        st.session_state.settlements[key] = {
            "가맹점": merchant,
            "날짜": target_date.isoformat(),
            "총결제액": payments,
            "총환불액": refunds,
            "수수료": fee,
            "정산액": payments - refunds - fee,
        }
        created += 1
    flash(f"정산 실행: 새 스냅샷 {created}건 생성")


def quick_payment_demo() -> None:
    entry = pay(1, "데모 상점", 6_000, new_key("quick-pay"))
    if entry:
        relay_outbox()
        flash("빠른 체험: 결제, 원장 기록, Outbox 발행까지 한 번에 실행했습니다.")


def quick_concurrency_demo() -> None:
    run_lost_update_demo()
    run_pessimistic_lock_demo()
    flash("빠른 체험: 락 없는 버그와 비관적 락 결과를 차례대로 비교했습니다.")


def quick_pg_demo() -> None:
    request_external_payment(1, "PG 데모몰", 8_000, new_key("quick-pg"), "timeout")
    reconcile_pending_payments()
    flash("빠른 체험: PG timeout 이후 보정으로 결제를 확정했습니다.")


def quick_balance_demo() -> None:
    corrupt_balance()
    discrepancies = find_discrepancies()
    flash(f"빠른 체험: 잔액 불일치 {len(discrepancies)}건을 만들고 탐지했습니다.")


def quick_settlement_demo() -> None:
    pay(1, "정산 데모 상점", 10_000, new_key("quick-settlement-pay"))
    run_settlement(date.today())
    flash("빠른 체험: 결제 생성 후 오늘 정산 스냅샷을 만들었습니다.")


def ledger_rows() -> list[dict[str, str | int]]:
    return [
        {
            "ID": entry.id,
            "시간": entry.created_at.strftime("%H:%M:%S"),
            "지갑": st.session_state.wallets[entry.wallet_id].user,
            "유형": entry.type.label,
            "금액": entry.amount,
            "잔액": entry.balance_after,
            "가맹점": entry.merchant or "-",
            "멱등키": entry.idempotency_key,
        }
        for entry in reversed(st.session_state.ledger)
    ]


def payment_rows() -> list[dict[str, str | int]]:
    return [
        {
            "ID": payment.id,
            "지갑": st.session_state.wallets[payment.wallet_id].user,
            "가맹점": payment.merchant,
            "금액": payment.amount,
            "상태": payment.status.value,
            "원장ID": payment.ledger_entry_id or "-",
            "사유": payment.reason or "-",
        }
        for payment in reversed(st.session_state.payments)
    ]


def outbox_rows() -> list[dict[str, str | int]]:
    return [
        {
            "ID": event.id,
            "타입": event.event_type,
            "상태": event.status.value,
            "페이로드": event.payload,
            "발행시각": event.published_at.strftime("%H:%M:%S") if event.published_at else "-",
        }
        for event in reversed(st.session_state.outbox)
    ]


def render_wallets() -> None:
    st.subheader("지갑")
    cols = st.columns(len(st.session_state.wallets))
    for col, wallet in zip(cols, st.session_state.wallets.values()):
        expected = ledger_sum(wallet.id)
        delta = wallet.balance - expected
        col.metric(wallet.user, format_won(wallet.balance), delta=f"원장 차이 {delta:,}원")
        col.caption(f"walletId={wallet.id} · ledger sum {format_won(expected)}")


def render_usage_guide() -> None:
    st.info(
        "처음이라면 아래의 빠른 체험 버튼부터 눌러보세요. "
        "복잡한 입력 없이 결제, 동시성, PG 장애 처리, 잔액 검증, 정산 흐름을 바로 확인할 수 있습니다."
    )
    st.subheader("가장 쉬운 사용 순서")

    c1, c2, c3, c4 = st.columns(4)
    with c1.container(border=True):
        st.markdown("**1. 결제 흐름**")
        st.caption("돈이 빠지고 원장이 남고 이벤트가 발행되는지 확인합니다.")
    with c2.container(border=True):
        st.markdown("**2. 동시성 비교**")
        st.caption("락이 없을 때와 있을 때 결과가 어떻게 달라지는지 봅니다.")
    with c3.container(border=True):
        st.markdown("**3. PG 장애 처리**")
        st.caption("응답을 못 받은 결제가 보정으로 확정되는지 봅니다.")
    with c4.container(border=True):
        st.markdown("**4. 잔액 검증**")
        st.caption("캐시 잔액과 원장 합계가 어긋나면 잡아내는지 봅니다.")

    st.markdown("**추천 흐름**")
    st.success(
        "`결제 흐름 한 번에 보기` -> `동시성 비교` -> `PG 장애 처리` -> `잔액 검증` -> `정산 만들기`"
    )

    with st.expander("상세 사용법 보기"):
        quick, areas, details = st.tabs(["3분 데모", "화면 구성", "기능별 확인"])

        with quick:
            st.markdown(
                """
                1. 상단 `지갑`에서 두 지갑의 `원장 차이 0원`을 확인합니다.
                2. `거래 실행` -> `결제`에서 결제를 하나 만들고, 아래 `원장` 탭에 결제 행이 생기는지 봅니다.
                3. `Outbox` 탭에서 결제 이벤트가 `PENDING`으로 남은 것을 확인한 뒤 `Outbox 발행`을 누릅니다.
                4. `락 없는 버그`를 눌러 음수 잔액 버그를 보고, 바로 `비관적 락`을 눌러 수정된 결과를 비교합니다.
                5. `PG 결제`에서 `timeout` 또는 `lost-response`를 고른 뒤 요청하고, `PG 보정`을 눌러 `PENDING_PG`가 `APPROVED`로 바뀌는지 확인합니다.
                6. `잔액 깨뜨리기`를 누른 뒤 `잔액 검증` 탭에서 캐시 잔액과 원장 합계 불일치를 확인합니다.
                7. 결제나 환불을 몇 건 만든 뒤 `오늘 정산`을 눌러 가맹점별 정산 스냅샷을 봅니다.
                """
            )

        with areas:
            st.markdown(
                """
                - `지갑`: `Wallet.balance` 캐시와 `LedgerEntry` 합계의 차이를 보여줍니다.
                - `시나리오`: 동시성 버그, 비관적 락, PG 보정, Outbox 발행, 잔액 검증, 정산을 한 번에 재현합니다.
                - `거래 실행`: 충전, 결제, 환불, 송금, PG 결제를 직접 실행합니다.
                - `이벤트 로그`: 방금 실행한 동작이 어떤 결과를 만들었는지 보여줍니다.
                - 하단 탭: 원장, PG 상태, Outbox, 잔액 검증, 정산 결과를 테이블로 확인합니다.
                """
            )

        with details:
            st.markdown(
                """
                - 같은 `Idempotency-Key`로 같은 요청을 다시 보내면 새 원장을 만들지 않고 기존 결과를 반환합니다.
                - `PG 응답`의 `5xx`는 즉시 실패, `timeout`과 `lost-response`는 보정 전까지 `PENDING_PG`로 남습니다.
                - `Outbox 발행`은 실제 RabbitMQ 대신 `PENDING` 이벤트를 `PUBLISHED`로 바꾸는 방식으로 흐름만 재현합니다.
                - `오늘 정산`은 이미 생성된 같은 날짜/가맹점 스냅샷을 다시 계산하지 않습니다.
                - `초기화`를 누르면 데모 상태가 처음 데이터로 돌아갑니다.
                """
            )


def render_reset_bar() -> None:
    left, right = st.columns([3, 1])
    left.caption("데모를 이것저것 눌러보다가 상태가 헷갈리면 오른쪽 버튼으로 처음 상태로 되돌릴 수 있습니다.")
    if right.button("처음 상태로 초기화", use_container_width=True):
        reset_state()
        st.rerun()


def render_actions() -> None:
    st.subheader("직접 거래 실행")
    st.caption("원하는 값을 바꿔서 직접 눌러보고 싶을 때 사용하는 상세 조작 영역입니다.")
    wallet_options = {f"{wallet.user} (#{wallet.id})": wallet.id for wallet in st.session_state.wallets.values()}

    tab_charge, tab_pay, tab_refund, tab_transfer, tab_pg = st.tabs(["충전", "결제", "환불", "송금", "PG 결제"])

    with tab_charge:
        with st.form("charge-form"):
            wallet_id = st.selectbox("지갑", wallet_options, key="charge-wallet")
            amount = st.number_input("충전액", min_value=1_000, value=20_000, step=1_000)
            key = st.text_input("Idempotency-Key", value=new_key("charge"))
            submitted = st.form_submit_button("충전")
        if submitted:
            charge(wallet_options[wallet_id], int(amount), key)
            st.rerun()

    with tab_pay:
        with st.form("pay-form"):
            wallet_id = st.selectbox("지갑", wallet_options, key="pay-wallet")
            merchant = st.selectbox("가맹점", ["카페 정산", "문구점", "온라인 스토어"])
            amount = st.number_input("결제액", min_value=1_000, value=6_000, step=1_000)
            key = st.text_input("Idempotency-Key", value=new_key("pay"))
            submitted = st.form_submit_button("결제")
        if submitted:
            pay(wallet_options[wallet_id], merchant, int(amount), key)
            st.rerun()

    with tab_refund:
        payments = [entry for entry in st.session_state.ledger if entry.type is LedgerType.PAYMENT]
        if payments:
            labels = {
                f"#{entry.id} {entry.merchant} {format_won(entry.amount)}": entry.id
                for entry in reversed(payments)
            }
            with st.form("refund-form"):
                payment_label = st.selectbox("원결제", labels)
                amount = st.number_input("환불액", min_value=1_000, value=3_000, step=1_000)
                key = st.text_input("Idempotency-Key", value=new_key("refund"))
                submitted = st.form_submit_button("환불")
            if submitted:
                refund(labels[payment_label], int(amount), key)
                st.rerun()
        else:
            st.info("환불 가능한 결제가 아직 없습니다.")

    with tab_transfer:
        with st.form("transfer-form"):
            from_label = st.selectbox("출금 지갑", wallet_options, key="transfer-from")
            to_label = st.selectbox("입금 지갑", wallet_options, index=1, key="transfer-to")
            amount = st.number_input("송금액", min_value=1_000, value=5_000, step=1_000)
            key = st.text_input("Idempotency-Key", value=new_key("transfer"))
            submitted = st.form_submit_button("송금")
        if submitted:
            if wallet_options[from_label] == wallet_options[to_label]:
                flash("같은 지갑으로는 송금할 수 없습니다.")
            else:
                transfer(wallet_options[from_label], wallet_options[to_label], int(amount), key)
            st.rerun()

    with tab_pg:
        with st.form("pg-form"):
            wallet_id = st.selectbox("지갑", wallet_options, key="pg-wallet")
            merchant = st.selectbox("가맹점", ["PG 제휴몰", "예약 플랫폼", "구독 서비스"])
            amount = st.number_input("승인금액", min_value=1_000, value=8_000, step=1_000)
            failure = st.selectbox("PG 응답", ["정상", "timeout", "lost-response", "5xx"])
            key = st.text_input("Idempotency-Key", value=new_key("pg"))
            submitted = st.form_submit_button("PG 승인 요청")
        if submitted:
            request_external_payment(wallet_options[wallet_id], merchant, int(amount), key, failure)
            st.rerun()


def render_scenarios() -> None:
    st.subheader("빠른 체험")
    st.caption("복잡한 입력 없이 버튼 하나로 대표 시나리오를 실행합니다. 아래 표에서 결과를 바로 확인하세요.")

    col1, col2, col3 = st.columns(3)
    if col1.button("결제 흐름 한 번에 보기", type="primary"):
        quick_payment_demo()
        st.rerun()
    col1.caption("결제 -> 원장 기록 -> Outbox 발행까지 자동 실행")

    if col2.button("동시성 비교"):
        quick_concurrency_demo()
        st.rerun()
    col2.caption("락 없는 버그와 비관적 락 결과를 순서대로 재현")

    if col3.button("PG 장애 처리"):
        quick_pg_demo()
        st.rerun()
    col3.caption("timeout 결제를 만들고 보정으로 승인 확정")

    col4, col5, col6 = st.columns(3)
    if col4.button("잔액 검증"):
        quick_balance_demo()
        st.rerun()
    col4.caption("잔액을 일부러 어긋나게 만들고 탐지")

    if col5.button("정산 만들기"):
        quick_settlement_demo()
        st.rerun()
    col5.caption("결제 데이터를 만들고 가맹점별 정산 스냅샷 생성")

    if col6.button("처음으로 되돌리기"):
        reset_state()
        st.rerun()
    col6.caption("모든 데모 데이터를 초기 상태로 복원")

    with st.expander("개별 시나리오를 따로 실행하기"):
        c1, c2, c3, c4 = st.columns(4)
        if c1.button("락 없는 버그만"):
            run_lost_update_demo()
            st.rerun()
        if c2.button("비관적 락만"):
            run_pessimistic_lock_demo()
            st.rerun()
        if c3.button("PG 보정만"):
            reconcile_pending_payments()
            st.rerun()
        if c4.button("Outbox 발행만"):
            relay_outbox()
            st.rerun()


def render_tables() -> None:
    tab_ledger, tab_pg, tab_outbox, tab_reconcile, tab_settlement = st.tabs(
        ["원장", "PG 상태", "Outbox", "잔액 검증", "정산"]
    )

    with tab_ledger:
        st.dataframe(ledger_rows(), use_container_width=True, hide_index=True)

    with tab_pg:
        rows = payment_rows()
        st.dataframe(rows, use_container_width=True, hide_index=True)

    with tab_outbox:
        st.dataframe(outbox_rows(), use_container_width=True, hide_index=True)
        st.caption(f"소비자 멱등 처리 완료 이벤트: {len(st.session_state.processed_events)}건")

    with tab_reconcile:
        discrepancies = find_discrepancies()
        if discrepancies:
            st.error("Wallet.balance 캐시와 LedgerEntry 합계가 다릅니다.")
            st.dataframe(discrepancies, use_container_width=True, hide_index=True)
        else:
            st.success("모든 지갑의 캐시 잔액과 원장 합계가 일치합니다.")

    with tab_settlement:
        rows = list(st.session_state.settlements.values())
        st.dataframe(rows, use_container_width=True, hide_index=True)


def render_activity() -> None:
    st.subheader("이벤트 로그")
    if not st.session_state.activity:
        st.caption("아직 실행된 이벤트가 없습니다.")
    for item in st.session_state.activity:
        st.code(item, language=None)


def main() -> None:
    st.set_page_config(
        page_title="Wallet Payment Demo",
        page_icon="💳",
        layout="wide",
        initial_sidebar_state="collapsed",
    )
    if st.query_params.get("reset") == "1":
        reset_state()
        st.query_params.clear()
    else:
        init_state()

    st.markdown(
        """
        <style>
        .block-container { padding-top: 1.4rem; }
        .guide-hero {
            border: 1px solid #cbd5e1;
            border-radius: 8px;
            padding: 18px 20px;
            background: #eef6ff;
            margin: 10px 0 14px 0;
        }
        .guide-hero h2 {
            margin: 2px 0 8px 0;
            font-size: 1.35rem;
            color: #0f172a;
        }
        .guide-hero p {
            margin: 0;
            color: #334155;
            line-height: 1.55;
        }
        .guide-kicker {
            font-size: 0.78rem;
            font-weight: 700;
            color: #1d4ed8;
            margin-bottom: 2px;
        }
        .guide-card {
            min-height: 92px;
            border: 1px solid #d9e2ec;
            border-radius: 8px;
            padding: 12px 13px;
            background: #ffffff;
            margin-bottom: 12px;
        }
        .guide-card b {
            display: block;
            margin-bottom: 7px;
            color: #0f172a;
            font-size: 0.98rem;
        }
        .guide-card span {
            display: block;
            color: #475569;
            font-size: 0.9rem;
            line-height: 1.45;
        }
        div[data-testid="stMetric"] {
            border: 1px solid #d9e2ec;
            border-radius: 8px;
            padding: 12px 14px;
            background: #ffffff;
        }
        div[data-testid="stMetricDelta"] svg { display: none; }
        .stButton button { width: 100%; border-radius: 6px; }
        </style>
        """,
        unsafe_allow_html=True,
    )

    st.title("Wallet Payment Demo")
    st.caption(f"{APP_DISPLAY_VERSION} · 선불 지갑 결제·정산 시스템의 핵심 정합성 흐름을 체험하는 데모입니다.")

    render_usage_guide()
    render_reset_bar()
    render_wallets()
    render_scenarios()

    left, right = st.columns([2, 1], gap="large")
    with left:
        render_actions()
    with right:
        render_activity()

    render_tables()


if __name__ == "__main__":
    main()
