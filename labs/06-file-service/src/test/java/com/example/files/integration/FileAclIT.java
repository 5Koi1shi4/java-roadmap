package com.example.files.integration;

import com.example.files.application.access.FileAccessService;
import com.example.files.application.access.ResourceHiddenException;
import com.example.files.application.audit.CorrelationId;
import com.example.files.infrastructure.persistence.JdbcAuditRecorder;
import com.example.files.infrastructure.persistence.JdbcFileAccessRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 MySQL 验证 ACL、审计回滚、引用计数和清理任务幂等。 */
class FileAclIT extends SharedMySqlContainer {
    private static JdbcTemplate jdbc;
    private static FileAccessService access;
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        access = new FileAccessService(new JdbcFileAccessRepository(jdbc), new JdbcAuditRecorder(jdbc), tx);
    }

    @BeforeEach
    void cleanRows() {
        jdbc.update("DELETE FROM file_audit_event");
        jdbc.update("DELETE FROM storage_cleanup_task");
        jdbc.update("DELETE FROM file_grant");
        jdbc.update("DELETE FROM stored_file");
        jdbc.update("DELETE FROM stored_blob");
    }

    @Test
    void ownerGrantReaderReadAndDeleteArePrivateAndIdempotent() {
        UUID fileId = seedFile(7L);
        CorrelationId c = CorrelationId.random();
        access.grantRead(7L, fileId, 8L, c);
        access.grantRead(7L, fileId, 8L, c);
        assertThat(access.getMetadata(8L, fileId, c).fileId()).isEqualTo(fileId);
        assertThatThrownBy(() -> access.grantRead(8L, fileId, 9L, c)).isInstanceOf(ResourceHiddenException.class);
        access.delete(7L, fileId, c);
        access.delete(7L, fileId, c);
        assertThat(jdbc.queryForObject("SELECT reference_count FROM stored_blob", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM stored_blob", String.class)).isEqualTo("PENDING_DELETE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_grant", Integer.class)).isZero();
    }

    @Test
    void auditFailureRollsBackDeleteAndReferenceChange() {
        UUID fileId = seedFile(11L);
        FileAccessService failing = new FileAccessService(new JdbcFileAccessRepository(jdbc), event -> {
            throw new IllegalStateException("audit unavailable");
        }, new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            dataSource)));
        assertThatThrownBy(() -> failing.delete(11L, fileId, CorrelationId.random())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM stored_file WHERE file_id=?", String.class, fileId.toString())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT reference_count FROM stored_blob", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task", Integer.class)).isZero();
    }

    private UUID seedFile(long owner) {
        long blob = jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM stored_blob", Long.class);
        String hash = String.format("%064x", blob + owner);
        jdbc.update("INSERT INTO stored_blob(content_hash,object_key,size_bytes,media_type,reference_count,status,generation,created_at,updated_at) VALUES(?,?,4,'text/plain',1,'READY',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            hash, "blobs/" + UUID.randomUUID());
        long id = jdbc.queryForObject("SELECT id FROM stored_blob WHERE content_hash=?", Long.class, hash);
        UUID file = UUID.randomUUID();
        jdbc.update("INSERT INTO stored_file(file_id,owner_id,blob_id,display_name,status,created_at) VALUES(?,?,?,'note.txt','ACTIVE',CURRENT_TIMESTAMP(6))",
            file.toString(), owner, id);
        return file;
    }
}
