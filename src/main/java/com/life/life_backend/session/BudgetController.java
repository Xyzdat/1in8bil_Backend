package com.life.life_backend.session;

import com.life.life_backend.engine.adult.BudgetViews;
import com.life.life_backend.identity.GuestIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping(path="/api/v1/sessions/{sessionId}/budget",produces=MediaType.APPLICATION_JSON_VALUE)
class BudgetController {
    private final GuestIdentity identity;
    private final SessionService sessions;
    BudgetController(GuestIdentity identity,SessionService sessions){this.identity=identity;this.sessions=sessions;}

    @GetMapping("/{section:income|expenses|jobs|side-jobs|businesses|strategies|assets}")
    ResponseEntity<BudgetViews.Page> section(@CookieValue(name=GuestIdentity.COOKIE_NAME,required=false) String token,
            @PathVariable UUID sessionId,@PathVariable String section){
        return current(sessions.budget(identity.requireOwner(token),sessionId,section,null));
    }
    @GetMapping("/assets/{assetId}/sale-preview")
    ResponseEntity<BudgetViews.Page> salePreview(@CookieValue(name=GuestIdentity.COOKIE_NAME,required=false) String token,
            @PathVariable UUID sessionId,@PathVariable String assetId){
        return current(sessions.budget(identity.requireOwner(token),sessionId,"sale-preview",assetId));
    }
    @PostMapping(path="/actions",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionView> act(@CookieValue(name=GuestIdentity.COOKIE_NAME,required=false) String token,
            @PathVariable UUID sessionId,@RequestHeader("Idempotency-Key") UUID requestKey,@Valid @RequestBody Action request){
        return current(sessions.apply(identity.requireOwner(token),sessionId,requestKey,SessionCommand.budget(request.expectedVersion(),request.action(),request.optionId())));
    }
    private <T> ResponseEntity<T> current(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    record Action(@NotNull @Positive Long expectedVersion,@NotBlank @Size(max=40) String action,@NotBlank @Size(max=100) String optionId) { }
}
