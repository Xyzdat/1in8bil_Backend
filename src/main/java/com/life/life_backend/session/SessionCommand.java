package com.life.life_backend.session;

public record SessionCommand(long expectedVersion, String decisionId, String optionId, CharacterChanges changes,
		Purchase purchase) {
	static SessionCommand advance(long expectedVersion) {
		return new SessionCommand(expectedVersion, null, null, null, null);
	}

	static SessionCommand answer(long expectedVersion, String decisionId, String optionId) {
		return new SessionCommand(expectedVersion, decisionId, optionId, null, null);
	}

	static SessionCommand editCharacter(long expectedVersion, CharacterChanges changes) {
		return new SessionCommand(expectedVersion, null, null, changes, null);
	}

	static SessionCommand purchase(long expectedVersion, String categoryId, String itemId, String petName) {
		return new SessionCommand(expectedVersion, null, null, null,
				new Purchase(categoryId, itemId, petName == null ? null : petName.strip()));
	}

	boolean isAdvance() {
		return decisionId == null;
	}

	public record Purchase(String categoryId, String itemId, String petName) {
	}
}
