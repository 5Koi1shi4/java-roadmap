package com.example.campusmarket.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** AuthFlow 不应依赖方法排序或共享 Redis 停止状态。 */
class AuthFlowIsolationTest {
    @Test
    void authFlowDoesNotDependOnMethodOrderOrInlineRedisShutdown() {
        assertThat(AuthFlowIT.class.isAnnotationPresent(TestMethodOrder.class)).isFalse();
        assertThat(java.util.Arrays.stream(AuthFlowIT.class.getDeclaredMethods())
            .map(Method::getName)
            .noneMatch("returns503WhenRedisIsUnavailable"::equals)).isTrue();
    }
}
