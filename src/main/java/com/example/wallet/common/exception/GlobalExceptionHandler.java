package com.example.wallet.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.wallet.payment.optimistic.OptimisticLockRetriesExhaustedException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(WalletNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleWalletNotFound(WalletNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(PaymentNotFoundException.class)
	public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(MerchantNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleMerchantNotFound(MerchantNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(InsufficientBalanceException.class)
	public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(RefundExceedsPaymentException.class)
	public ResponseEntity<ErrorResponse> handleRefundExceedsPayment(RefundExceedsPaymentException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(IdempotencyKeyReusedException.class)
	public ResponseEntity<ErrorResponse> handleIdempotencyKeyReused(IdempotencyKeyReusedException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(ConcurrentChargeConflictException.class)
	public ResponseEntity<ErrorResponse> handleConcurrentChargeConflict(ConcurrentChargeConflictException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
	}

	// OptimisticPaymentService가 재시도(MAX_ATTEMPTS)를 다 써도 충돌이 안 풀리면 이걸 던진다.
	// 몇 번 시도하고 포기했는지를 X-Attempt-Count 헤더로 그대로 내보낸다 — 성공 응답(컨트롤러)
	// 과 같은 헤더 이름을 쓰므로, 호출자는 성공/실패 어느 쪽이든 같은 헤더만 보면 된다.
	@ExceptionHandler(OptimisticLockRetriesExhaustedException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLockExhausted(OptimisticLockRetriesExhaustedException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.header("X-Attempt-Count", String.valueOf(e.getAttempts()))
				.body(new ErrorResponse(e.getMessage()));
	}

	// charge()는 OptimisticPaymentService와 달리 내부 재시도가 없어서, 충돌이 나면 이게
	// 바로 올라온다(이 시점엔 attempts라는 개념 자체가 없다 — 한 번 시도하고 끝).
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLockConflict(ObjectOptimisticLockingFailureException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("Optimistic lock conflict"));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleInvalidArgument(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(FieldError::getDefaultMessage)
				.orElse("Invalid request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(message));
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
	}
}
