package com.life.life_backend.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.life.life_backend.engine.LifeState.Status.ACTIVE;
import static com.life.life_backend.engine.LifeState.Status.COMPLETE;

@Component
public class LocalLifeEngine implements LifeEngine {
	private static final int END_AGE = 22;
	private static final int WORKSHOP_AGE = 3;
	private static final LifeState.Decision WORKSHOP = new LifeState.Decision(
			"first-workshop", "Что попробовать в детской мастерской?",
			List.of(new LifeState.Option("paint", "Рисовать красками"),
					new LifeState.Option("build", "Собрать домик из деталей"))
	);

	@Override
	public String id() {
		return "local";
	}

	public Checkpoint start(CharacterProfile character, StartingStats startingStats) {
		var stats = new LifeState.Stats(startingStats.health(), 80, startingStats.intelligence(),
				90, startingStats.appearance(), 0);
		var birth = new LifeState.HistoryEvent("birth", 0, "Рождение",
				"Место рождения персонажа «" + character.name() + "» — " + character.birthplace().country() + ".");
		return new Checkpoint(new LifeState(0, ACTIVE, stats, null, List.of(birth)), null);
	}

	@Override
	public Checkpoint advance(Checkpoint current) {
		var state = current.state();
		int age = state.age() + 1;
		var decision = age == WORKSHOP_AGE ? WORKSHOP : null;
		var status = age == END_AGE ? COMPLETE : ACTIVE;
		var history = state.history();
		if (status == COMPLETE) {
			history = append(history, new LifeState.HistoryEvent("demo-complete", age, "Новый этап",
					"Персонаж завершил первые 22 года жизни."));
		}
		return new Checkpoint(new LifeState(age, status, state.stats(), decision, history), null);
	}

	@Override
	public Checkpoint answer(Checkpoint current, String optionId) {
		var state = current.state();
		var stats = state.stats();
		var outcome = switch (optionId) {
			case "paint" -> new WorkshopOutcome(Math.min(100, stats.happiness() + 4), stats.intelligence(),
					"В мастерской появился первый рисунок, который захотелось забрать домой.");
			case "build" -> new WorkshopOutcome(stats.happiness(), Math.min(100, stats.intelligence() + 3),
					"Из деталей получился домик с крышей и маленьким крыльцом.");
			default -> throw new IllegalArgumentException("Unknown workshop option");
		};
		var updatedStats = new LifeState.Stats(stats.health(), outcome.happiness(), outcome.intelligence(),
				stats.relationships(), stats.appearance(), stats.aura());
		var event = new LifeState.HistoryEvent(WORKSHOP.id(), state.age(), "Детская мастерская", outcome.text());
		return new Checkpoint(new LifeState(state.age(), ACTIVE, updatedStats, null, append(state.history(), event)),
				null);
	}

	private List<LifeState.HistoryEvent> append(List<LifeState.HistoryEvent> history, LifeState.HistoryEvent event) {
		var updated = new ArrayList<>(history);
		updated.add(event);
		return updated;
	}

	private record WorkshopOutcome(int happiness, int intelligence, String text) {
	}
}
