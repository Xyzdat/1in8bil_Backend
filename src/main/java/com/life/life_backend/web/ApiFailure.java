package com.life.life_backend.web;

import org.springframework.http.HttpStatus;

public class ApiFailure extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	public ApiFailure(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	HttpStatus status() {
		return status;
	}

	String code() {
		return code;
	}
}
