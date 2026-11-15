package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.integration.AssistantPrompt;
import com.alfatgbotik.assistant.persistence.ActionProposalRepository;
import com.alfatgbotik.assistant.persistence.ConversationRepository;
import com.alfatgbotik.identity.api.ProfileUpdateRequest;
import com.alfatgbotik.identity.service.BusinessProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.UUID;

@Service
public class ConversationCompletionService {

    private final ConversationRepository conversations;
    private final ActionProposalRepository proposals;
    private final BusinessProfileService profiles;
    private final Clock clock;

    public ConversationCompletionService(ConversationRepository conversations, ActionProposalRepository proposals,
                                          BusinessProfileService profiles, Clock clock) {
        this.conversations = conversations;
        this.proposals = proposals;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional
    public void complete(UUID userId, UUID requestId, ConsultationPipeline.Result result) {
        if (!result.response().profilePatch().isEmpty()) {
            ObjectNode patch = ((ObjectNode) result.response().profilePatch()).deepCopy();
            patch.put("expectedVersion", result.profileVersion());
            profiles.updateProfile(userId, ProfileUpdateRequest.toCommand(patch));
        }
        proposals.create(userId, requestId, result.response(), clock.instant());
        conversations.complete(requestId, result.response(), result.text(), result.modelName(), result.modelRevision(),
                AssistantPrompt.VERSION, result.datasetVersion(), clock.instant());
    }
}
