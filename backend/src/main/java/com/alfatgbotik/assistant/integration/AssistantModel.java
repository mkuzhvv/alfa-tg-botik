package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.ConsultationDeadline;

public interface AssistantModel {

    ModelReply generate(AssistantContext context, String correction, ConsultationDeadline deadline);

    record ModelReply(String text, String modelName, String modelRevision) {
    }
}
