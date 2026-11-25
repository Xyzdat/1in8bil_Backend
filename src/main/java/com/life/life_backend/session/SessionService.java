package com.life.life_backend.session;

import com.life.life_backend.economy.FinanceView;
import com.life.life_backend.economy.SpendingService;
import com.life.life_backend.engine.LifeEngine;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.LocalLifeEngine;
import com.life.life_backend.engine.onein8b.OneInEightBillionEngine;
import com.life.life_backend.identity.GuestIdentity;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.life.life_backend.engine.local.CharacterGenerator;
import com.life.life_backend.engine.local.LocalSimulation;
import com.life.life_backend.engine.local.LocalSimulationEngine;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@Service
class SessionService {
	private final LifeSessionRepository sessions;
	private final SessionActionRepository actions;
	private final GuestIdentity identity;
	private final LocalLifeEngine localEngine;
	private final OneInEightBillionEngine externalEngine;
	private final SpendingService spending;
	private final CharacterGenerator generator;
	private final LocalSimulationEngine simulationEngine;
	private final com.life.life_backend.engine.local.BirthContextFactory birthContext;
	private final com.life.life_backend.engine.adult.AdultLifeEngine adultEngine;
	private final com.life.life_backend.highlights.HighlightTracker highlights;

	SessionService(LifeSessionRepository sessions, SessionActionRepository actions, GuestIdentity identity,
			LocalLifeEngine localEngine, OneInEightBillionEngine externalEngine, SpendingService spending,
			CharacterGenerator generator, LocalSimulationEngine simulationEngine,
			com.life.life_backend.engine.local.BirthContextFactory birthContext,
            com.life.life_backend.engine.adult.AdultLifeEngine adultEngine,com.life.life_backend.highlights.HighlightTracker highlights) {
		this.sessions = sessions;
		this.actions = actions;
		this.identity = identity;
		this.localEngine = localEngine;
		this.externalEngine = externalEngine;
		this.spending = spending;
		this.generator = generator;
		this.simulationEngine = simulationEngine;
		this.birthContext = birthContext;
		this.adultEngine = adultEngine;
		this.highlights = highlights;
	}

	@Transactional
	Creation generateLocal(UUID ownerId, UUID requestKey, LocalSimulation.BirthRequest request) {
		identity.lockOwner(ownerId);
		var existing = sessions.findByOwnerIdAndCreationKey(ownerId, requestKey);
		if (existing.isPresent()) {
			var session = existing.get();
			if (session.simulation() == null || !session.simulation().request().equals(request)) throw idempotencyConflict();
			return new Creation(session.view(), false);
		}
		long seed = UUID.nameUUIDFromBytes((ownerId + ":" + requestKey).getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
		var birth = generator.generate(seed, request);
		var session = new LifeSession(ownerId, requestKey, new LifeSession.Setup(birth.character(), null, birth.details()),
				"local", new LifeEngine.Checkpoint(birth.state(), null));
		session.initializeSimulation(birth.simulation());
		sessions.save(session);
		return new Creation(session.view(), true);
	}

	@Transactional
	Creation create(UUID ownerId, UUID requestKey, CreateSessionRequest request) {
		// Serialize starts for this owner before checking whether the key has already been used.
		identity.lockOwner(ownerId);
		var existing = sessions.findByOwnerIdAndCreationKey(ownerId, requestKey);
		if (existing.isPresent()) {
			var session = existing.get();
			if (!localEngine.id().equals(session.engineId()) || !session.matchesSetup(request)) {
				throw idempotencyConflict();
			}
			return new Creation(session.view(), false);
		}
		var checkpoint = localEngine.start(request.character(), request.startingStats());
		var setup = new LifeSession.Setup(request.character(), request.startingStats(), null);
		var session = sessions.save(new LifeSession(ownerId, requestKey, setup, localEngine.id(), checkpoint));
		return new Creation(session.view(), true);
	}

	@Transactional
	Creation generate(UUID ownerId, UUID requestKey) {
		identity.lockOwner(ownerId);
		var existing = sessions.findByOwnerIdAndCreationKey(ownerId, requestKey);
		if (existing.isPresent()) {
			var session = existing.get();
			if (!externalEngine.id().equals(session.engineId())) {
				throw idempotencyConflict();
			}
			return new Creation(session.view(), false);
		}
		var life = externalEngine.generate(ownerId, requestKey);
		var setup = new LifeSession.Setup(life.character(), null, life.birthDetails());
		var session = sessions.save(new LifeSession(ownerId, requestKey, setup, externalEngine.id(), life.checkpoint()));
		return new Creation(session.view(), true);
	}

	@Transactional(readOnly = true)
	SessionView get(UUID ownerId, UUID sessionId) {
		return findSession(ownerId, sessionId).view();
	}

	@Transactional(readOnly = true)
	FinanceView finances(UUID ownerId, UUID sessionId) {
		var session = findSession(ownerId, sessionId);
		return isLocal(session) ? spending.finances(session.localEconomy())
				: spending.providerFinances(session.revision(), session.visibleState(), session.providerEconomy());
	}

	@Transactional(readOnly = true)
	FinanceView.Shop shop(UUID ownerId, UUID sessionId, String categoryId) {
		var session = findSession(ownerId, sessionId);
		return isLocal(session) ? spending.shop(session.localEconomy(), categoryId)
				: spending.providerShop(session.revision(), session.visibleState(), session.providerEconomy(), categoryId);
	}

	private LifeSession findSession(UUID ownerId, UUID sessionId) {
		return sessions.findByIdAndOwnerId(sessionId, ownerId).orElseThrow(this::notFound);
	}

	@Transactional(readOnly = true)
	List<SessionView.Summary> list(UUID ownerId) {
		return sessions.findAllByOwnerIdOrderBySavedAtDescIdAsc(ownerId).stream().map(LifeSession::summary).toList();
	}

	@Transactional
	SessionView apply(UUID ownerId, UUID sessionId, UUID requestKey, SessionCommand command) {
		var session = sessions.lockByIdAndOwnerId(sessionId, ownerId).orElseThrow(this::notFound);
		var previous = actions.findBySessionIdAndRequestKey(sessionId, requestKey);
		if (previous.isPresent()) {
			if (!previous.get().matches(command)) {
				throw idempotencyConflict();
			}
			return session.view();
		}
		if (command.expectedVersion() != session.revision()) {
			throw conflict("VERSION_CONFLICT", "Refresh the session before sending another action.");
		}
		var beforeHighlights=session.highlightFrame();
		if (command.changes() != null) {
			editCharacter(session, command.changes());
		} else if (command.purchase() != null) {
			purchase(session, requestKey, command.purchase());
		} else {
			advanceLife(session, command);
		}
		session.updateHighlights(highlights,beforeHighlights);
		actions.save(new SessionAction(sessionId, requestKey, command));
		return session.view();
	}

	private void editCharacter(LifeSession session, CharacterChanges changes) {
		if (changes.isEmpty()) {
			throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Supply at least one character change.");
		}
		boolean local = isLocal(session);
		if (!local && changes.stats() != null && changes.stats().editsEngineStats()) {
			throw conflict("CHARACTER_FIELDS_READ_ONLY", "This engine controls health and intelligence.");
		}
		session.editCharacter(changes, local);
	}

	private void purchase(LifeSession session, UUID requestKey, SessionCommand.Purchase purchase) {
		if (!isLocal(session)) {
			throw conflict("SPENDING_READ_ONLY", "Покупки для этой жизни выполняет внешний движок.");
		}
		session.applyPurchase(spending.purchase(session.localEconomy(), requestKey, purchase.categoryId(),
				purchase.itemId(), purchase.petName()));
	}

	private boolean isLocal(LifeSession session) {
		return localEngine.id().equals(session.engineId());
	}

	private void advanceLife(LifeSession session, SessionCommand command) {
		var checkpoint = session.checkpoint();
		if (checkpoint.state().status() == LifeState.Status.COMPLETE) {
			throw conflict("SESSION_COMPLETE", "This session is complete.");
		}
		if (session.simulation() != null) {
			session.enrichBirthContext(birthContext);
			boolean adult=session.simulation().month()>=216 && (checkpoint.state().pendingDecision()==null || session.adultState()!=null);
			if(adult) {
				var context=session.localEconomy();
				if(Boolean.TRUE.equals(command.step()))session.applyAdult(adultEngine.step(checkpoint.state(),session.simulation(),session.adultState(),context.budget(),context.location()));
				else if(command.decisionId()!=null)session.applyAdult(adultEngine.answer(checkpoint.state(),session.simulation(),session.adultState(),context.budget(),context.location(),command.decisionId(),command.optionId()));
				else throw conflict("STEP_REQUIRED","This life uses /step instead of /advance.");
				return;
			}
			if (Boolean.TRUE.equals(command.step())) {
				session.applySimulation(simulationEngine.step(checkpoint.state(), session.simulation()));
			} else if (command.decisionId() != null) {
				session.applySimulation(simulationEngine.answer(checkpoint.state(), session.simulation(), command.decisionId(), command.optionId()));
			} else {
				throw conflict("STEP_REQUIRED", "This life uses /step instead of /advance.");
			}
			return;
		}
		if (Boolean.TRUE.equals(command.step())) throw conflict("STEP_UNSUPPORTED", "Use /advance for this life.");
		if (checkpoint.cursor() != null && !checkpoint.cursor().expiresAt().isAfter(Instant.now())) {
			throw conflict("ENGINE_TOKEN_EXPIRED", "The engine token for this session has expired.");
		}
		session.saveCheckpoint(applyAction(engineFor(session.engineId()), checkpoint, command));
		if (isLocal(session) && session.visibleState().age() > checkpoint.state().age()) {
			session.settleBudget(checkpoint.state().age());
		}
	}

	private LifeEngine engineFor(String id) {
		if (localEngine.id().equals(id)) {
			return localEngine;
		}
		if (externalEngine.id().equals(id) && externalEngine.available()) {
			return externalEngine;
		}
		throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_UNAVAILABLE",
				"The engine for this session is unavailable.");
	}

	private LifeEngine.Checkpoint applyAction(LifeEngine engine, LifeEngine.Checkpoint checkpoint, SessionCommand command) {
		var decision = checkpoint.state().pendingDecision();
		if (command.isAdvance()) {
			if (decision != null) {
				throw conflict("DECISION_REQUIRED", "Answer the pending decision before advancing.");
			}
			return engine.advance(checkpoint);
		}
		if (decision == null || !decision.id().equals(command.decisionId())) {
			throw conflict("DECISION_CHANGED", "This decision is not pending.");
		}
		if (decision.options().stream().noneMatch(option -> option.id().equals(command.optionId()))) {
			throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_OPTION", "Choose an option from the pending decision.");
		}
		return engine.answer(checkpoint, command.optionId());
	}

	private ApiFailure notFound() {
		return new ApiFailure(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "The session was not found.");
	}

	private ApiFailure idempotencyConflict() {
		return conflict("IDEMPOTENCY_CONFLICT", "This key was already used for a different request.");
	}

	private ApiFailure conflict(String code, String message) {
		return new ApiFailure(HttpStatus.CONFLICT, code, message);
	}

	record Creation(SessionView session, boolean created) {
	}
}
