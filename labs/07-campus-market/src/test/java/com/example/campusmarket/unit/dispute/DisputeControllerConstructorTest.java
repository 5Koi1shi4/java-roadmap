package com.example.campusmarket.unit.dispute;

import com.example.campusmarket.dispute.api.DisputeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DisputeControllerConstructorTest {
    @Test
    void marksProductionConstructorForSpringAndKeepsLegacyPublicSignature() throws Exception {
        Constructor<?> production = Arrays.stream(DisputeController.class.getConstructors())
            .filter(c -> c.getParameterCount() == 4)
            .findFirst()
            .orElseThrow();
        Constructor<?> legacy = DisputeController.class.getConstructor(
            com.example.campusmarket.dispute.application.DisputeService.class,
            com.example.campusmarket.dispute.application.EvidenceStorage.class);

        assertThat(production.isAnnotationPresent(Autowired.class)).isTrue();
        assertThat(legacy).isNotNull();
        assertThat(legacy.isAnnotationPresent(Autowired.class)).isFalse();
    }
}
