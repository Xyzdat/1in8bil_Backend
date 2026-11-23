package com.life.life_backend.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
	@ExceptionHandler(ApiFailure.class)
	ResponseEntity<ErrorBody> apiFailure(ApiFailure failure) {
		return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
				.body(new ErrorBody(failure.code(), failure.getMessage()));
	}

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HttpMessageNotReadableException.class,
			MissingRequestHeaderException.class,
			MethodArgumentTypeMismatchException.class
	})
	ResponseEntity<ErrorBody> invalidRequest() {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(new ErrorBody("INVALID_REQUEST", "Check the request fields and headers."));
	}

	record ErrorBody(String code, String message) {
	}
}
