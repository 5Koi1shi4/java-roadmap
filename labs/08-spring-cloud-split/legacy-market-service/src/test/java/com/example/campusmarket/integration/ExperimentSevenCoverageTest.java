package com.example.campusmarket.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the experiment-seven integration suites visible to Maven Failsafe after
 * the service is split into modules.
 */
class ExperimentSevenCoverageTest {

    private static final String INTEGRATION_PACKAGE = "com.example.campusmarket.integration.";
    private static final String REQUIRED_RESOURCE = "/experiment-seven-required-tests.txt";
    private static final List<String> REQUIRED_SUITES = List.of(
        "InventoryIT",
        "ConcurrentOrderIT",
        "PaymentFlowIT",
        "PaymentGatewayContractIT",
        "HandoffHttpIT",
        "PartialReturnRefundIT",
        "DisputeEvidenceIT",
        "WarrantyHttpAclIT",
        "WarrantyMessagingIT",
        "ReliableMessagingIT",
        "ProductSearchIT",
        "SearchRebuildIT",
        "StorageCleanupIT",
        "CampusMarketJourneyIT",
        "RecoveryDrillIT",
        "RecoveryInvariantStagesIT");

    @Test
    void everyExperimentSevenSuiteIsAConcreteFailsafeCandidateWithTests() throws IOException {
        List<String> suites = loadRequiredSuites();
        assertThat(suites).containsExactlyElementsOf(REQUIRED_SUITES);

        ClassLoader classLoader = getClass().getClassLoader();
        for (String suite : suites) {
            Class<?> testClass = loadWithoutInitialization(classLoader, suite);
            assertThat(testClass.getSimpleName())
                .as("Failsafe naming convention for %s", suite)
                .endsWith("IT");
            assertThat(Modifier.isAbstract(testClass.getModifiers()))
                .as("required suite %s must be executable", suite)
                .isFalse();
            assertThat(testClass.isAnnotationPresent(Disabled.class))
                .as("required suite %s must not be disabled", suite)
                .isFalse();
            assertThat(junitTestMethods(testClass))
                .as("required suite %s must contain a JUnit test", suite)
                .isPositive();
        }
    }

    private static List<String> loadRequiredSuites() throws IOException {
        try (InputStream stream = ExperimentSevenCoverageTest.class.getResourceAsStream(REQUIRED_RESOURCE)) {
            if (stream == null) {
                fail("required resource is missing: " + REQUIRED_RESOURCE);
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        }
    }

    private static Class<?> loadWithoutInitialization(ClassLoader classLoader, String suite) {
        try {
            return Class.forName(INTEGRATION_PACKAGE + suite, false, classLoader);
        } catch (ClassNotFoundException exception) {
            fail("required suite is not on the test classpath: " + suite, exception);
            return Object.class;
        }
    }

    private static long junitTestMethods(Class<?> type) {
        long directMethods = 0;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (isTestMethod(method)) {
                    directMethods++;
                }
            }
        }
        long nestedMethods = Arrays.stream(type.getDeclaredClasses())
            .filter(nested -> nested.isAnnotationPresent(Nested.class))
            .mapToLong(ExperimentSevenCoverageTest::junitTestMethods)
            .sum();
        return directMethods + nestedMethods;
    }

    private static boolean isTestMethod(Method method) {
        return method.isAnnotationPresent(Test.class)
            || method.isAnnotationPresent(ParameterizedTest.class)
            || method.isAnnotationPresent(RepeatedTest.class)
            || method.isAnnotationPresent(TestFactory.class)
            || method.isAnnotationPresent(TestTemplate.class);
    }
}
