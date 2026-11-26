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

	public record Option(String id, String label, OptionEffects effects) {
		public Option(String id, String label) { this(id, label, null); }
	}

	/** Immediate changes for this saved state, after clamping; null effects mean unknown, not zero. */
	public record OptionEffects(String scope, java.util.Map<String, Integer> stats, java.util.Map<String, Integer> closeness) {
		public OptionEffects {
			stats = java.util.Map.copyOf(stats);
			closeness = java.util.Map.copyOf(closeness);
		}
	}

	public record HistoryEvent(String id, int age, String title, String text, Consequences consequences,
			com.life.life_backend.highlights.EventLink link, Integer importance, String importanceSource) {
		public HistoryEvent(String id, int age, String title, String text, Consequences consequences,
				com.life.life_backend.highlights.EventLink link) { this(id, age, title, text, consequences, link, null, null); }
		public HistoryEvent(String id, int age, String title, String text, Consequences consequences) { this(id, age, title, text, consequences, null); }
		public HistoryEvent(String id, int age, String title, String text) { this(id, age, title, text, null); }
	}
	public record Consequences(int aura, java.util.Map<String, Integer> closeness) { }
}
