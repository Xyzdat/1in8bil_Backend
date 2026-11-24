package com.life.life_backend.engine;

import java.util.List;

public record LifeState(int age, Status status, Stats stats, Decision pendingDecision, List<HistoryEvent> history) {
	public LifeState {
		history = List.copyOf(history);
	}

	public enum Status {
		ACTIVE, COMPLETE
	}

	public record Stats(int health, int happiness, int intelligence, int relationships, Integer appearance, Integer aura) {
	}

	public record Decision(String id, String title, List<Option> options) {
		public Decision {
			options = List.copyOf(options);
		}
	}

	public record Option(String id, String label) {
	}

	public record HistoryEvent(String id, int age, String title, String text, Consequences consequences) {
		public HistoryEvent(String id, int age, String title, String text) { this(id, age, title, text, null); }
	}
	public record Consequences(int aura, java.util.Map<String, Integer> closeness) { }
}
