package com.life.life_backend.session;

import com.life.life_backend.economy.FinanceView;
import com.life.life_backend.economy.LocalBudget;
import com.life.life_backend.economy.ProviderEconomy;
import com.life.life_backend.economy.SpendingService;
import com.life.life_backend.engine.BirthDetails;
import com.life.life_backend.engine.CharacterProfile;
import com.life.life_backend.engine.LifeEngine;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.StartingStats;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "life_session")
class LifeSession {
	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID ownerId;

	@Column(nullable = false)
	private UUID creationKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Setup setup;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private LifeState state;

	@Column(nullable = false, length = 40)
	private String engineId;

	@Column(columnDefinition = "text")
	private String engineToken;

	private Instant engineTokenExpiresAt;

	@Column(nullable = false)
	private long revision;

	@Column(nullable = false)
	private Instant savedAt;

	@Column(length = 100)
	private String displayName;

	private Integer customAppearance;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private LocalBudget localBudget;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private ProviderEconomy providerEconomy;

	protected LifeSession() {
	}

	LifeSession(UUID ownerId, UUID creationKey, Setup setup, String engineId,
			LifeEngine.Checkpoint checkpoint) {
		this.id = UUID.randomUUID();
		this.ownerId = ownerId;
		this.creationKey = creationKey;
		this.setup = setup;
		this.engineId = engineId;
		saveCheckpoint(checkpoint);
	}

	void saveCheckpoint(LifeEngine.Checkpoint checkpoint) {
		state = checkpoint.state();
		var cursor = checkpoint.cursor();
		engineToken = cursor == null ? null : cursor.token();
		engineTokenExpiresAt = cursor == null ? null : cursor.expiresAt();
		if (checkpoint.providerEconomy() != null) {
			providerEconomy = checkpoint.providerEconomy();
		} else if (providerEconomy != null && state.status() == LifeState.Status.COMPLETE) {
			providerEconomy = new ProviderEconomy(providerEconomy.age(), providerEconomy.income(),
					providerEconomy.netWorth(), providerEconomy.occupation(), List.of());
		}
		markSaved();
	}

	void settleBudget(int previousAge) {
		int previousMonth = previousAge * 12;
		localBudget = LocalBudget.atAge(localBudget, state.age());
		for (var purchase : localBudget.purchases()) {
			Integer completion = purchase.completionMonth();
			if (completion != null && completion > previousMonth && completion <= localBudget.month()) {
				var effects = new FinanceView.Effects(null, null, 4, null, null);
				addSpendingEvent(purchase.id() + "-completed", "Обучение завершено",
						"Завершён курс «" + purchase.title() + "».", effects);
			}
		}
	}

	SpendingService.LocalContext localEconomy() {
		var budget = LocalBudget.atAge(localBudget, state.age());
		var birthplace = setup.character().birthplace();
		var location = budget.purchases().reversed().stream().map(LocalBudget.Purchase::location)
				.filter(Objects::nonNull).findFirst()
				.orElseGet(() -> new FinanceView.Location(birthplace.countryCode(), birthplace.country(), birthplace.city()));
		return new SpendingService.LocalContext(revision, state, budget, location);
	}

	ProviderEconomy providerEconomy() {
		return providerEconomy;
	}

	LifeState visibleState() {
		var stats = state.stats();
		return customAppearance == null ? state : withStats(new LifeState.Stats(
				stats.health(), stats.happiness(), stats.intelligence(), stats.relationships(), customAppearance,
				stats.aura()));
	}

	void applyPurchase(SpendingService.Receipt receipt) {
		localBudget = receipt.budget();
		var purchase = receipt.purchase();
		String text = purchase.petName() == null ? "Оплачено: " + purchase.title() + "."
				: "В доме появился питомец — " + purchase.petName() + ".";
		if (purchase.location() != null) {
			text = "Новое место жительства: " + purchase.location().country() + ", " + purchase.location().city() + ".";
		}
		addSpendingEvent("purchase-" + purchase.id(), purchase.title(), text, receipt.effects());
		markSaved();
	}

	private void addSpendingEvent(String id, String title, String text, FinanceView.Effects effects) {
		var history = new ArrayList<>(state.history());
		history.add(new LifeState.HistoryEvent(id, state.age(), title, text));
		var stats = effects == null ? state.stats() : effects.apply(state.stats());
		state = new LifeState(state.age(), state.status(), stats, state.pendingDecision(), history);
	}

	void editCharacter(CharacterChanges changes, boolean local) {
		if (changes.name() != null) {
			displayName = changes.name();
		}
		var editedStats = changes.stats();
		if (editedStats != null) {
			if (local) {
				var current = state.stats();
				var updated = new LifeState.Stats(
						editedStats.health() != null ? editedStats.health() : current.health(),
						current.happiness(),
						editedStats.intelligence() != null ? editedStats.intelligence() : current.intelligence(),
						current.relationships(),
						editedStats.appearance() != null ? editedStats.appearance() : current.appearance(),
						current.aura());
				state = withStats(updated);
			} else if (editedStats.appearance() != null) {
				customAppearance = editedStats.appearance();
			}
		}
		markSaved();
	}

	private void markSaved() {
		revision++;
		savedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	LifeEngine.Checkpoint checkpoint() {
		var cursor = engineToken == null ? null : new LifeEngine.Cursor(engineToken, engineTokenExpiresAt);
		return new LifeEngine.Checkpoint(state, cursor);
	}

	boolean matchesSetup(CreateSessionRequest request) {
		return setup.equals(new Setup(request.character(), request.startingStats(), null));
	}

	String engineId() {
		return engineId;
	}

	long revision() {
		return revision;
	}

	SessionView view() {
		var original = setup.character();
		var character = new SessionView.Character(displayName == null ? original.name() : displayName, original.gender(),
				original.birthYear(), original.birthplace(), original.background(), setup.birthDetails());
		return new SessionView(id, character, revision, savedAt, visibleState(), engineId);
	}

	private LifeState withStats(LifeState.Stats stats) {
		return new LifeState(state.age(), state.status(), stats, state.pendingDecision(), state.history());
	}

	SessionView.Summary summary() {
		String name = displayName == null ? setup.character().name() : displayName;
		return new SessionView.Summary(id, name, state.age(), state.status(), savedAt, engineId);
	}

	record Setup(CharacterProfile character, StartingStats startingStats, BirthDetails birthDetails) {
	}
}
