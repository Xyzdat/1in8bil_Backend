package com.life.life_backend.session;

import com.life.life_backend.engine.BirthDetails;
import com.life.life_backend.engine.CharacterProfile;
import com.life.life_backend.engine.local.LocalSimulation;

/** Keep the creation request immutable for idempotency, independently of the edited birth card. */
record CharacterCustomization(CharacterProfile character, BirthDetails birthDetails, LocalSimulation.BirthRequest originalBirthRequest) { }
