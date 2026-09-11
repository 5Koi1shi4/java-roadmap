package com.example.campusmarket.unit.identity;

import com.example.campusmarket.identity.domain.CampusEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampusEmailTest {
    @ParameterizedTest
    @ValueSource(strings = {"a@stu.example.edu.cn.evil.com", "a@fake-stu.example.edu.cn", "a@"})
    void rejectsSuffixTricks(String email) {
        assertThatThrownBy(() -> CampusEmail.parse(email, Set.of("stu.example.edu.cn")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesDomainBeforeExactMatch() {
        CampusEmail email = CampusEmail.parse("student@STU.EXAMPLE.EDU.CN", Set.of("stu.example.edu.cn"));

        assertThat(email.value()).isEqualTo("student@stu.example.edu.cn");
        assertThat(email.domain()).isEqualTo("stu.example.edu.cn");
    }
}
