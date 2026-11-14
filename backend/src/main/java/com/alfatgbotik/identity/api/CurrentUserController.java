package com.alfatgbotik.identity.api;

import com.alfatgbotik.identity.model.AuthenticatedSession;
import com.alfatgbotik.identity.service.BusinessProfileService;
import com.alfatgbotik.identity.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {

    private final UserService users;
    private final BusinessProfileService profiles;

    public CurrentUserController(UserService users, BusinessProfileService profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    @GetMapping
    public UserDto getCurrentUser(@AuthenticationPrincipal AuthenticatedSession session) {
        return users.getById(session.userId());
    }

    @PatchMapping("/profile")
    public UserDto updateProfile(@AuthenticationPrincipal AuthenticatedSession session, @RequestBody JsonNode body) {
        profiles.updateProfile(session.userId(), ProfileUpdateRequest.toCommand(body));
        return users.getById(session.userId());
    }
}
