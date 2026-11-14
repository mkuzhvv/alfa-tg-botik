package com.alfatgbotik.catalog.api;

import com.alfatgbotik.catalog.model.MaterialKind;

import java.util.UUID;

public record MaterialCardDto(UUID id, String title, MaterialKind kind, String description,
                              String recommendationReason, boolean isPaid, String duration, String url) {
}
