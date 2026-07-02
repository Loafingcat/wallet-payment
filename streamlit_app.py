from __future__ import annotations

from datetime import date, datetime
from uuid import uuid4

import pandas as pd
import streamlit as st


APP_STATE_VERSION = "wallet-payment-service-demo-v1"
GITHUB_URL = "https://github.com/Loafingcat/wallet-payment"


def now_text() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def won(amount: int) -> str:
    return f"{amount:,}원"


def new_key(prefix: str) -> str:
    return f"{prefix}-{uuid4().hex[:8]}"


def next_id(kind: str) -> str:
    key = f"next_{kind}_id"
    value = st.session_state[key]
    st.session_state[key] += 1
    return f"{kind.upper()}-{value}"


def clear_state() -> None:
    for key in list(st.session_state.keys()):
        del st.session_state[key]


def init_state(force: bool = False) -> None:
    if not force and st.session_state.get("app_state_version") == APP_STATE_VERSION:
        return

    clear_state()
    st.session_state.app_state_version = APP_STATE_VERSION
    st.session_state.next_w_id = 1001
    st.session_state.next_t_id = 1001
    st.session_state.next_s_id = 1001
    st.session_state.wallets = []
    st.session_state.ledger_entries = []
    st.session_state.settlements = []
    st.session_state.last_result = None


def reset_demo() -> None:
    init_state(force=True)
    st.success("전체 데이터를 초기화했습니다.")


def wallet_by_id(wallet_id: str) -> dict:
    return next(wallet for wallet in st.session_state.wallets if wallet["wallet_id"] == wallet_id)


def wallet_options(wallet_type: str | None = None) -> dict[str, str]:
    wallets = st.session_state.wallets
    if wallet_type:
        wallets = [wallet for wallet in wallets if wallet["wallet_type"] == wallet_type]
    return {f'{wallet["owner_name"]} ({wallet["wallet_id"]})': wallet["wallet_id"] for wallet in wallets}


def has_demo_data() -> bool:
    return bool(st.session_state.wallets)


def add_wallet(owner_name: str, wallet_type: str, balance: int = 0) -> str:
    wallet_id = next_id("w")
    st.session_state.wallets.append(
        {
            "wallet_id": wallet_id,
            "owner_name": owner_name,
            "wallet_type": wallet_type,
            "balance": balance,
            "created_at": now_text(),
        }
    )
    return wallet_id


def add_ledger(
    tx_type: str,
    wallet_id: str,
    amount: int,
    *,
    counterparty_id: str | None = None,
    idempotency_key: str | None = None,
    original_transaction_id: str | None = None,
    status: str = "SUCCESS",
    description: str = "",
) -> dict:
    entry = {
        "transaction_id": next_id("t"),
        "type": tx_type,
        "wallet_id": wallet_id,
        "counterparty_id": counterparty_id,
        "amount": amount,
        "idempotency_key": idempotency_key or "-",
        "original_transaction_id": original_transaction_id,
        "status": status,
        "created_at": now_text(),
        "description": description,
    }
    st.session_state.ledger_entries.append(entry)
    return entry


def create_demo_data() -> None:
    init_state(force=True)

    user_a = add_wallet("민지", "USER")
    user_b = add_wallet("준호", "USER")
    merchant_a = add_wallet("그린카페", "MERCHANT")
    merchant_b = add_wallet("북스토어", "MERCHANT")

    charge_wallet(user_a, 100_000, new_key("seed-charge"))
    charge_wallet(user_b, 70_000, new_key("seed-charge"))
    pay_wallet(user_a, merchant_a, 12_000, new_key("seed-pay"))
    pay_wallet(user_a, merchant_b, 8_500, new_key("seed-pay"))
    pay_wallet(user_b, merchant_a, 6_000, new_key("seed-pay"))
    st.session_state.last_result = ("success", "데모 데이터가 준비되었습니다. 이제 지갑 사용해보기 메뉴에서 직접 충전, 결제, 환불을 해보세요.")


def charge_wallet(wallet_id: str, amount: int, key: str) -> dict:
    wallet = wallet_by_id(wallet_id)
    before = wallet["balance"]
    wallet["balance"] += amount
    entry = add_ledger(
        "CHARGE",
        wallet_id,
        amount,
        idempotency_key=key,
        description=f"{wallet['owner_name']} 지갑 충전",
    )
    return {"entry": entry, "before": before, "after": wallet["balance"], "duplicate": False}


def pay_wallet(user_wallet_id: str, merchant_wallet_id: str, amount: int, key: str) -> dict:
    existing = next(
        (
            entry
            for entry in st.session_state.ledger_entries
            if entry["type"] == "PAYMENT" and entry["idempotency_key"] == key
        ),
        None,
    )
    if existing:
        return {"entry": existing, "duplicate": True}

    user_wallet = wallet_by_id(user_wallet_id)
    merchant_wallet = wallet_by_id(merchant_wallet_id)
    before = user_wallet["balance"]
    if before < amount:
        return {"error": "잔액이 부족합니다.", "before": before, "after": before}

    user_wallet["balance"] -= amount
    merchant_wallet["balance"] += amount
    entry = add_ledger(
        "PAYMENT",
        user_wallet_id,
        amount,
        counterparty_id=merchant_wallet_id,
        idempotency_key=key,
        description=f"{user_wallet['owner_name']} -> {merchant_wallet['owner_name']} 결제",
    )
    return {"entry": entry, "before": before, "after": user_wallet["balance"], "duplicate": False}


def refundable_payments() -> list[dict]:
    payments = [entry for entry in st.session_state.ledger_entries if entry["type"] == "PAYMENT"]
    result = []
    for payment in payments:
        refunded = sum(
            entry["amount"]
            for entry in st.session_state.ledger_entries
            if entry["type"] == "REFUND" and entry["original_transaction_id"] == payment["transaction_id"]
        )
        remaining = payment["amount"] - refunded
        if remaining > 0:
            result.append({**payment, "remaining_refundable": remaining})
    return result


def refund_payment(payment_tx_id: str, amount: int) -> dict:
    payment = next(entry for entry in st.session_state.ledger_entries if entry["transaction_id"] == payment_tx_id)
    already_refunded = sum(
        entry["amount"]
        for entry in st.session_state.ledger_entries
        if entry["type"] == "REFUND" and entry["original_transaction_id"] == payment_tx_id
    )
    if already_refunded + amount > payment["amount"]:
        return {"error": "원결제 금액보다 많이 환불할 수 없습니다."}

    user_wallet = wallet_by_id(payment["wallet_id"])
    merchant_wallet = wallet_by_id(payment["counterparty_id"])
    before = user_wallet["balance"]
    user_wallet["balance"] += amount
    merchant_wallet["balance"] -= amount
    entry = add_ledger(
        "REFUND",
        payment["wallet_id"],
        amount,
        counterparty_id=payment["counterparty_id"],
        idempotency_key=new_key("refund"),
        original_transaction_id=payment_tx_id,
        description=f"결제 {payment_tx_id} 환불",
    )
    return {"entry": entry, "before": before, "after": user_wallet["balance"]}


def transfer_wallet(from_wallet_id: str, to_wallet_id: str, amount: int) -> dict:
    if from_wallet_id == to_wallet_id:
        return {"error": "같은 지갑으로는 송금할 수 없습니다."}
    from_wallet = wallet_by_id(from_wallet_id)
    to_wallet = wallet_by_id(to_wallet_id)
    before = from_wallet["balance"]
    if before < amount:
        return {"error": "잔액이 부족합니다.", "before": before, "after": before}

    transfer_key = new_key("transfer")
    from_wallet["balance"] -= amount
    to_wallet["balance"] += amount
    out_entry = add_ledger(
        "TRANSFER_OUT",
        from_wallet_id,
        amount,
        counterparty_id=to_wallet_id,
        idempotency_key=f"{transfer_key}:out",
        description=f"{from_wallet['owner_name']} -> {to_wallet['owner_name']} 송금 출금",
    )
    add_ledger(
        "TRANSFER_IN",
        to_wallet_id,
        amount,
        counterparty_id=from_wallet_id,
        idempotency_key=f"{transfer_key}:in",
        description=f"{to_wallet['owner_name']} 송금 입금",
    )
    return {"entry": out_entry, "before": before, "after": from_wallet["balance"]}


def get_counterparty_name(counterparty_id: str | None) -> str:
    if not counterparty_id:
        return "-"
    return wallet_by_id(counterparty_id)["owner_name"]


def ledger_df(entries: list[dict] | None = None) -> pd.DataFrame:
    entries = entries if entries is not None else st.session_state.ledger_entries
    rows = []
    for entry in reversed(entries):
        wallet = wallet_by_id(entry["wallet_id"])
        rows.append(
            {
                "거래 ID": entry["transaction_id"],
                "거래 유형": entry["type"],
                "지갑": f'{wallet["owner_name"]} ({entry["wallet_id"]})',
                "상대 지갑 또는 가맹점": get_counterparty_name(entry["counterparty_id"]),
                "금액": entry["amount"],
                "요청 키": entry["idempotency_key"],
                "상태": entry["status"],
                "생성 시간": entry["created_at"],
                "설명": entry["description"],
            }
        )
    return pd.DataFrame(rows)


def wallet_df() -> pd.DataFrame:
    return pd.DataFrame(st.session_state.wallets)


def settlement_df() -> pd.DataFrame:
    return pd.DataFrame(st.session_state.settlements)


def sum_by_type(tx_type: str) -> int:
    return sum(entry["amount"] for entry in st.session_state.ledger_entries if entry["type"] == tx_type)


def render_notice() -> None:
    st.caption("실제 금융 서비스가 아니라 프로젝트 데모입니다. 데이터는 현재 브라우저 세션에만 저장됩니다.")


def render_sidebar() -> str:
    st.sidebar.title("Wallet Payment")
    menu = st.sidebar.radio(
        "메뉴",
        ["시작하기", "지갑 사용해보기", "거래내역과 정산", "프로젝트 구조"],
    )
    st.sidebar.divider()
    if st.sidebar.button("전체 초기화", use_container_width=True):
        reset_demo()
        st.rerun()
    st.sidebar.caption("전체 구현 코드는 GitHub에서 확인할 수 있습니다.")
    st.sidebar.link_button("GitHub 저장소", GITHUB_URL, use_container_width=True)
    return menu


def require_demo_data() -> bool:
    if has_demo_data():
        return True
    st.warning("먼저 시작하기 메뉴에서 데모 데이터를 만들어주세요.")
    if st.button("데모 데이터 만들기", type="primary"):
        create_demo_data()
        st.rerun()
    return False


def render_start() -> None:
    st.title("Wallet Payment")
    st.subheader("선불 지갑 기반 충전·결제·환불·정산 데모")
    st.write("지갑에 금액을 충전하고, 가맹점에 결제하고, 필요하면 환불과 정산까지 확인할 수 있는 선불 지갑 서비스입니다.")
    render_notice()

    col_a, col_b = st.columns([1, 1])
    if col_a.button("데모 데이터 만들기", type="primary", use_container_width=True):
        create_demo_data()
        st.rerun()
    if col_b.button("처음부터 다시 시작", use_container_width=True):
        reset_demo()
        st.rerun()

    if st.session_state.last_result:
        level, message = st.session_state.last_result
        getattr(st, level)(message)

    if not has_demo_data():
        st.info("데모 데이터 만들기를 누르면 사용자 지갑 2개, 가맹점 지갑 2개, 샘플 거래가 생성됩니다.")
        return

    user_count = len([wallet for wallet in st.session_state.wallets if wallet["wallet_type"] == "USER"])
    merchant_count = len([wallet for wallet in st.session_state.wallets if wallet["wallet_type"] == "MERCHANT"])
    total_payment = sum_by_type("PAYMENT")

    m1, m2, m3, m4 = st.columns(4)
    m1.metric("사용자 지갑 수", user_count)
    m2.metric("가맹점 수", merchant_count)
    m3.metric("총 거래 수", len(st.session_state.ledger_entries))
    m4.metric("전체 결제 금액", won(total_payment))

    st.markdown("### 서비스 흐름")
    steps = st.columns(5)
    for col, title, body in zip(
        steps,
        ["1. 지갑 생성", "2. 충전", "3. 결제", "4. 환불", "5. 정산"],
        ["사용자와 가맹점 지갑을 만듭니다.", "사용자 지갑에 금액을 넣습니다.", "가맹점에 결제합니다.", "필요하면 반대 거래를 남깁니다.", "가맹점 지급 금액을 계산합니다."],
    ):
        with col.container(border=True):
            st.markdown(f"**{title}**")
            st.caption(body)

    st.info("모든 금액 변화는 원장 거래로 기록됩니다. 그래서 잔액이 어떻게 바뀌었는지 추적할 수 있습니다.")


def render_wallet_use() -> None:
    st.title("지갑 사용해보기")
    render_notice()
    if not require_demo_data():
        return

    users = wallet_options("USER")
    merchants = wallet_options("MERCHANT")
    tabs = st.tabs(["충전", "결제", "환불", "송금"])

    with tabs[0]:
        st.markdown("충전은 지갑 잔액을 늘리고, `CHARGE` 거래를 기록합니다.")
        with st.form("charge-form"):
            wallet_label = st.selectbox("충전할 사용자 지갑", list(users.keys()))
            amount = st.number_input("충전 금액", min_value=1_000, value=30_000, step=1_000)
            submitted = st.form_submit_button("충전하기", type="primary")
        if submitted:
            result = charge_wallet(users[wallet_label], int(amount), new_key("charge"))
            st.success(f"충전 완료: {won(result['before'])} -> {won(result['after'])}")

    with tabs[1]:
        st.markdown("같은 결제 요청이 두 번 들어와도 거래가 중복으로 생성되지 않도록 요청 키를 확인합니다.")
        default_key = st.session_state.get("payment_key", new_key("pay"))
        with st.form("payment-form"):
            user_label = st.selectbox("결제할 사용자 지갑", list(users.keys()))
            merchant_label = st.selectbox("결제할 가맹점", list(merchants.keys()))
            amount = st.number_input("결제 금액", min_value=1_000, value=10_000, step=1_000)
            key = st.text_input("요청 키", value=default_key)
            submitted = st.form_submit_button("결제하기", type="primary")
        st.session_state.payment_key = key
        if submitted:
            result = pay_wallet(users[user_label], merchants[merchant_label], int(amount), key)
            if result.get("error"):
                st.error(result["error"])
            elif result["duplicate"]:
                st.warning("이미 처리된 요청입니다. 기존 결과를 반환합니다.")
                st.dataframe(ledger_df([result["entry"]]), use_container_width=True, hide_index=True)
            else:
                st.success(f"결제 완료: {won(result['before'])} -> {won(result['after'])}")
                st.info("네트워크 오류로 사용자가 버튼을 다시 눌러도 같은 요청 키라면 금액이 두 번 빠져나가지 않습니다.")

    with tabs[2]:
        st.markdown("환불은 기존 결제를 지우지 않고, 반대 방향의 `REFUND` 거래를 새로 남깁니다.")
        payments = refundable_payments()
        if not payments:
            st.info("환불 가능한 결제 거래가 없습니다.")
        else:
            labels = {
                f'{entry["transaction_id"]} · {get_counterparty_name(entry["counterparty_id"])} · 남은 환불 가능 {won(entry["remaining_refundable"])}': entry["transaction_id"]
                for entry in payments
            }
            with st.form("refund-form"):
                payment_label = st.selectbox("환불할 결제", list(labels.keys()))
                amount = st.number_input("환불 금액", min_value=1_000, value=5_000, step=1_000)
                submitted = st.form_submit_button("환불하기", type="primary")
            if submitted:
                result = refund_payment(labels[payment_label], int(amount))
                if result.get("error"):
                    st.error(result["error"])
                else:
                    st.success(f"환불 완료: {won(result['before'])} -> {won(result['after'])}")
                    st.info("원래 PAYMENT 거래는 그대로 두고 REFUND 거래를 추가해 이력이 사라지지 않게 했습니다.")

    with tabs[3]:
        st.markdown("송금은 두 지갑의 잔액이 함께 바뀌기 때문에 실제 백엔드에서는 동시에 여러 요청이 들어오는 상황을 조심해야 합니다.")
        with st.form("transfer-form"):
            from_label = st.selectbox("보내는 사용자 지갑", list(users.keys()))
            to_label = st.selectbox("받는 사용자 지갑", list(users.keys()), index=1)
            amount = st.number_input("송금 금액", min_value=1_000, value=5_000, step=1_000)
            submitted = st.form_submit_button("송금하기", type="primary")
        if submitted:
            result = transfer_wallet(users[from_label], users[to_label], int(amount))
            if result.get("error"):
                st.error(result["error"])
            else:
                st.success(f"송금 완료: {won(result['before'])} -> {won(result['after'])}")
                st.info("보내는 지갑에는 TRANSFER_OUT, 받는 지갑에는 TRANSFER_IN 거래가 기록됩니다.")


def render_history_and_settlement() -> None:
    st.title("거래내역과 정산")
    render_notice()
    if not require_demo_data():
        return

    m1, m2, m3, m4 = st.columns(4)
    m1.metric("총 충전 금액", won(sum_by_type("CHARGE")))
    m2.metric("총 결제 금액", won(sum_by_type("PAYMENT")))
    m3.metric("총 환불 금액", won(sum_by_type("REFUND")))
    m4.metric("총 송금 금액", won(sum_by_type("TRANSFER_OUT")))

    st.markdown("### 거래내역")
    type_values = sorted({entry["type"] for entry in st.session_state.ledger_entries})
    wallet_labels = wallet_options()
    c1, c2 = st.columns(2)
    selected_type = c1.selectbox("거래 유형 필터", ["전체"] + type_values)
    selected_wallet_label = c2.selectbox("지갑 필터", ["전체"] + list(wallet_labels.keys()))

    entries = st.session_state.ledger_entries
    if selected_type != "전체":
        entries = [entry for entry in entries if entry["type"] == selected_type]
    if selected_wallet_label != "전체":
        selected_wallet_id = wallet_labels[selected_wallet_label]
        entries = [
            entry
            for entry in entries
            if entry["wallet_id"] == selected_wallet_id or entry["counterparty_id"] == selected_wallet_id
        ]
    st.dataframe(ledger_df(entries), use_container_width=True, hide_index=True)

    st.markdown("### 시각화")
    chart1, chart2 = st.columns(2)
    by_type = (
        pd.DataFrame(st.session_state.ledger_entries)
        .groupby("type", as_index=False)["amount"]
        .sum()
        .rename(columns={"type": "거래 유형", "amount": "총액"})
    )
    chart1.bar_chart(by_type, x="거래 유형", y="총액")

    balances = wallet_df().rename(columns={"owner_name": "지갑", "balance": "잔액"})
    chart2.bar_chart(balances, x="지갑", y="잔액")

    st.markdown("### 정산")
    st.caption("정산 수수료율은 데모 수수료율입니다.")
    merchants = wallet_options("MERCHANT")
    with st.form("settlement-form"):
        merchant_label = st.selectbox("정산할 가맹점", list(merchants.keys()))
        fee_rate = st.number_input("데모 수수료율 (%)", min_value=0.0, max_value=30.0, value=2.5, step=0.1)
        submitted = st.form_submit_button("정산 계산하기", type="primary")

    if submitted:
        merchant_id = merchants[merchant_label]
        today = date.today().isoformat()
        existing = next(
            (
                settlement
                for settlement in st.session_state.settlements
                if settlement["merchant_wallet_id"] == merchant_id and settlement["settlement_date"] == today
            ),
            None,
        )
        if existing:
            st.warning("이미 같은 날짜, 같은 가맹점에 대해 정산이 실행되었습니다. 기존 결과를 보여줍니다.")
            settlement = existing
        else:
            payments = sum(
                entry["amount"]
                for entry in st.session_state.ledger_entries
                if entry["type"] == "PAYMENT" and entry["counterparty_id"] == merchant_id
            )
            refunds = sum(
                entry["amount"]
                for entry in st.session_state.ledger_entries
                if entry["type"] == "REFUND" and entry["counterparty_id"] == merchant_id
            )
            fee = round(payments * (fee_rate / 100))
            settlement = {
                "settlement_id": next_id("s"),
                "merchant_wallet_id": merchant_id,
                "settlement_date": today,
                "total_payment_amount": payments,
                "total_refund_amount": refunds,
                "fee_amount": fee,
                "settlement_amount": payments - refunds - fee,
                "created_at": now_text(),
            }
            st.session_state.settlements.append(settlement)
            add_ledger(
                "SETTLEMENT",
                merchant_id,
                settlement["settlement_amount"],
                idempotency_key=f"settlement-{merchant_id}-{today}",
                description=f"{wallet_by_id(merchant_id)['owner_name']} 정산 계산",
            )
            st.success("정산 계산이 완료되었습니다.")

        r1, r2, r3, r4 = st.columns(4)
        r1.metric("총 결제 금액", won(settlement["total_payment_amount"]))
        r2.metric("총 환불 금액", won(settlement["total_refund_amount"]))
        r3.metric("수수료", won(settlement["fee_amount"]))
        r4.metric("최종 정산 금액", won(settlement["settlement_amount"]))
        st.info("정산은 가맹점의 결제와 환불 내역을 모아 최종 지급 금액을 계산하는 과정입니다. 같은 정산이 중복 실행되지 않도록 막아야 합니다.")

    if st.session_state.settlements:
        st.markdown("#### 정산 기록")
        st.dataframe(settlement_df(), use_container_width=True, hide_index=True)


def render_project_structure() -> None:
    st.title("프로젝트 구조")
    render_notice()
    st.markdown("### 이 프로젝트에서 신경 쓴 부분")

    cards = [
        ("정합성", "돈이 오가는 서비스에서는 잔액이 한 번이라도 어긋나면 안 됩니다. 모든 금액 변화를 원장 거래로 남겨 잔액을 추적할 수 있게 만들었습니다."),
        ("중복 요청 방지", "사용자가 같은 결제 버튼을 다시 눌러도 금액이 두 번 빠져나가지 않도록 요청 키를 기준으로 이미 처리된 요청인지 확인합니다."),
        ("동시성 처리", "여러 결제 요청이 동시에 들어와도 잔액이 음수가 되지 않도록 백엔드에서는 락을 사용해 순서대로 처리합니다."),
        ("테스트와 검증", "동시 결제, 중복 결제, 환불, 정산 같은 주요 상황을 테스트로 확인해 문제가 생기지 않도록 검증했습니다."),
    ]
    cols = st.columns(2)
    for index, (title, body) in enumerate(cards):
        with cols[index % 2].container(border=True):
            st.markdown(f"**{title}**")
            st.write(body)

    st.markdown("### 간단한 흐름")
    st.code("사용자 요청 -> Spring Boot API -> 서비스 로직 -> 원장 기록 -> 잔액 변경 -> 정산/조회", language=None)
    st.link_button("전체 구현 코드는 GitHub에서 확인할 수 있습니다.", GITHUB_URL)


def main() -> None:
    st.set_page_config(page_title="Wallet Payment", page_icon="💳", layout="wide")
    init_state()

    menu = render_sidebar()
    if menu == "시작하기":
        render_start()
    elif menu == "지갑 사용해보기":
        render_wallet_use()
    elif menu == "거래내역과 정산":
        render_history_and_settlement()
    else:
        render_project_structure()


if __name__ == "__main__":
    main()
