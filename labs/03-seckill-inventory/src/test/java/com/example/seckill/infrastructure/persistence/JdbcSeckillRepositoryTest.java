package com.example.seckill.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSeckillRepositoryTest {

    @Test
    void lockingReadMustNotDeclareReadOnlyTransaction() throws NoSuchMethodException {
        Transactional transactional = JdbcSeckillRepository.class
                .getDeclaredMethod("findByKeyForUpdate", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }

    @Test
    void idempotencyReadMustJoinCallerTransaction() throws NoSuchMethodException {
        Transactional transactional = JdbcSeckillRepository.class
                .getDeclaredMethod("findByKey", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNull();
    }
}
