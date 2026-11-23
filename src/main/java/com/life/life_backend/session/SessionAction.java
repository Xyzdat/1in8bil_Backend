package com.life.life_backend.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "life_session_action")
class SessionAction {
	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID sessionId;

	@Column(nullable = false)
	private UUID requestKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private SessionCommand command;

	protected SessionAction() {
	}

	SessionAction(UUID sessionId, UUID requestKey, SessionCommand command) {
		this.id = UUID.randomUUID();
		this.sessionId = sessionId;
		this.requestKey = requestKey;
		this.command = command;
	}

	boolean matches(SessionCommand request) {
		return command.equals(request);
	}
}
