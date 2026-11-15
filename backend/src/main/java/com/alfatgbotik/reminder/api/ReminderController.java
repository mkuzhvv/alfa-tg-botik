package com.alfatgbotik.reminder.api;

import com.alfatgbotik.identity.model.AuthenticatedSession;
import com.alfatgbotik.reminder.service.ReminderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    private final ReminderService reminders;
    private final ReminderCreation creation;

    public ReminderController(ReminderService reminders, ReminderCreation creation) {
        this.reminders = reminders;
        this.creation = creation;
    }

    @GetMapping
    public ReminderPage list(@AuthenticationPrincipal AuthenticatedSession session,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String offset,
                             @RequestParam(required = false) String limit) {
        return reminders.list(session.userId(), ReminderRequests.status(status),
                ReminderRequests.pageNumber(offset == null ? "0" : offset, "offset", 0, Integer.MAX_VALUE),
                ReminderRequests.pageNumber(limit == null ? "20" : limit, "limit", 1, 100));
    }

    @GetMapping("/{reminderId}")
    public ReminderDto get(@AuthenticationPrincipal AuthenticatedSession session, @PathVariable String reminderId) {
        return reminders.getById(session.userId(), ReminderRequests.uuid(reminderId, "reminderId"));
    }

    @PostMapping
    public ResponseEntity<ReminderDto> create(@AuthenticationPrincipal AuthenticatedSession session,
                                             @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                             @RequestBody JsonNode body) {
        ReminderDto result = creation.create(session.userId(), ReminderRequests.uuid(key, "Idempotency-Key"), body);
        return ResponseEntity.created(URI.create("/api/v1/reminders/" + result.id())).body(result);
    }

    @PatchMapping("/{reminderId}")
    public ReminderDto update(@AuthenticationPrincipal AuthenticatedSession session, @PathVariable String reminderId,
                              @RequestBody JsonNode body) {
        return reminders.update(session.userId(), ReminderRequests.uuid(reminderId, "reminderId"), ReminderRequests.update(body));
    }
}
