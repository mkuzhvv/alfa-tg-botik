package com.alfatgbotik.plan;

import com.alfatgbotik.catalog.service.MaterialService;
import com.alfatgbotik.plan.model.PlanProposal;
import com.alfatgbotik.plan.persistence.PlanRepository;
import com.alfatgbotik.plan.service.PlanService;
import com.alfatgbotik.plan.service.PlanValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PlanValidationTest {

    private final PlanRepository plans = mock(PlanRepository.class);
    private final MaterialService materials = mock(MaterialService.class);
    private final PlanService service = new PlanService(plans, materials, Clock.systemUTC());

    @ParameterizedTest
    @MethodSource("invalidProposals")
    void rejectsInvalidProposalBeforeDatabaseChanges(PlanProposal proposal) {
        assertThatThrownBy(() -> service.saveProposal(UUID.randomUUID(), proposal, Map.of(), null))
                .isInstanceOf(PlanValidationException.class);
        verifyNoInteractions(plans, materials);
    }

    static Stream<PlanProposal> invalidProposals() {
        UUID material = UUID.randomUUID();
        PlanProposal.Step valid = new PlanProposal.Step("Step", "Step description", List.of(material));
        return Stream.of(
                new PlanProposal(" ", Collections.nCopies(3, valid)),
                new PlanProposal("x".repeat(251), Collections.nCopies(3, valid)),
                new PlanProposal("Goal", Collections.nCopies(2, valid)),
                new PlanProposal("Goal", Collections.nCopies(6, valid)),
                new PlanProposal("Goal", Arrays.asList(valid, null, valid)),
                new PlanProposal("Goal", Collections.nCopies(3, new PlanProposal.Step("", "Text", List.of(material)))),
                new PlanProposal("Goal", Collections.nCopies(3, new PlanProposal.Step("Step", "x".repeat(601), List.of(material)))),
                new PlanProposal("Goal", Collections.nCopies(3, new PlanProposal.Step("Step", "Text", List.of()))),
                new PlanProposal("Goal", Collections.nCopies(3,
                        new PlanProposal.Step("Step", "Text", List.of(material, material)))),
                new PlanProposal("Goal", Collections.nCopies(3,
                        new PlanProposal.Step("Step", "Text", Arrays.asList(material, null))))
        );
    }
}
