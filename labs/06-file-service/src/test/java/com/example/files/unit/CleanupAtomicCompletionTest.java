package com.example.files.unit;

import com.example.files.application.upload.BlobRepository;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CleanupAtomicCompletionTest {
    @Test
    void taskTokenMismatchRollsBackAndFailsAtomicCompletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE stored_blob"), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE storage_cleanup_task"), any(), any(), any(), any())).thenReturn(0);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(tx.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        });
        JdbcCleanupTaskRepository repository = new JdbcCleanupTaskRepository(jdbc, tx);
        assertThatThrownBy(() -> repository.completeBlobAndTask(mock(BlobRepository.class), 1, 1,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class);
        verify(status).setRollbackOnly();
    }
}
