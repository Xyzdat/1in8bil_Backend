package com.life.life_backend.session;

public record SessionCommand(long expectedVersion, String decisionId, String optionId, CharacterChanges changes,
		Purchase purchase, Boolean step, String focusId) {
	public SessionCommand(long expectedVersion,String decisionId,String optionId,CharacterChanges changes,Purchase purchase,Boolean step) {
		this(expectedVersion,decisionId,optionId,changes,purchase,step,null);
	}
	static SessionCommand focus(long version,String id) { return new SessionCommand(version,null,null,null,null,null,id); }
	static SessionCommand advance(long expectedVersion) {
		return new SessionCommand(expectedVersion, null, null, null, null, null);
	}

	static SessionCommand answer(long expectedVersion, String decisionId, String optionId) {
		return new SessionCommand(expectedVersion, decisionId, optionId, null, null, null);
	}

	static SessionCommand editCharacter(long expectedVersion, CharacterChanges changes) {
		return new SessionCommand(expectedVersion, null, null, changes, null, null);
	}

	static SessionCommand purchase(long expectedVersion, String categoryId, String itemId, String petName) {
		return new SessionCommand(expectedVersion, null, null, null,
				new Purchase(categoryId, itemId, petName == null ? null : petName.strip()), null);
	}

	static SessionCommand step(long expectedVersion) {
		return new SessionCommand(expectedVersion, null, null, null, null, true);
	}

	boolean isAdvance() {
		return decisionId == null;
	}

	public record Purchase(String categoryId, String itemId, String petName) {
	}
}
