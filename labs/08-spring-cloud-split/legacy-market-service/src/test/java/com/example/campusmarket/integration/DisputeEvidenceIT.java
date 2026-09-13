package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.dispute.application.EvidenceStorage;
import com.example.campusmarket.dispute.infrastructure.JdbcDisputeRepository;
import com.example.campusmarket.storage.PrivateObjectStorage;
import com.example.campusmarket.storage.StorageCleanupScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.apache.tika.Tika;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class DisputeEvidenceIT extends DisputeEvidenceContainers {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final byte[] JPEG = Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/AP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAT8Af//Z");
    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBAAAADQAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAAA=");
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    // A real one-frame H.264 MP4 produced by ffmpeg; a bare ftyp box is not Tika-identifiable as video/mp4.
    private static final byte[] MP4 = Base64.getDecoder().decode("AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAL9bW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAid0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAGfbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABSm1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAQpzdGJsAAAApnN0c2QAAAAAAAAAAQAAAJZhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAABAAEABIAAAASAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGP//AAAAMGF2Y0MBZAAK/+EAF2dkAAqs2V7ARAAAAwAEAAADAAg8SJZYAQAGaOvjyyLAAAAAEHBhc3AAAAABAAAAAQAAABhzdHRzAAAAAAAAAAEAAAABAABAAAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAUc3RzegAAAAAAAALFAAAAAQAAABRzdGNvAAAAAAAAAAEAAAMtAAAAYnVkdGEAAABabWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAtaWxzdAAAACWpdG9vAAAAHWRhdGEAAAABAAAAAExhdmY1OC4yOS4xMDAAAAAIZnJlZQAAAs1tZGF0AAACrQYF//+p3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE1OCByMjk4NCAzNzU5ZmNiIC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAxOSAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM9Y2FiYWM9MSByZWY9MyBkZWJsb2NrPTE6MDowIGFuYWx5c2U9MHgzOjB4MTEzIG1lPWhleCBzdWJtZT03IHBzeT0xIHBzeV9yZD0xLjA6MC4wMCBtaXhlZF9yZWY9MSBtX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MCBjb219PTEgZGVhZHpvbWU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0xIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZD0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2lhcz0wIGRpcmVjdD0xIHdlaWdodGI9MSBvcGVuX2dvcD0wIHdlaWdodHA9MiBrZXlpbnQ9MjUwIGtleWludF9taW49MSBzY2VuZWN1dD00MCBpbnRyYV9yZWZfcmVmcmVzaD0wIHJjPWNyZiBtYnRyZWU9MSBjcmY9MjMuMCBxY29tcD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBxYT0xOjEuMDAgYXFvPTAuMDAgAACAAAAQZYiEABX//vfJ78Cm69vfgQ==");
    private static final byte[] MP4_VALID = Base64.getDecoder().decode("AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAL9bW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAid0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAGfbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABSm1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAQpzdGJsAAAApnN0c2QAAAAAAAAAAQAAAJZhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAABAAEABIAAAASAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGP//AAAAMGF2Y0MBZAAK/+EAF2dkAAqs2V7ARAAAAwAEAAADAAg8SJZYAQAGaOvjyyLAAAAAEHBhc3AAAAABAAAAAQAAABhzdHRzAAAAAAAAAAEAAAABAABAAAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAUc3RzegAAAAAAAALFAAAAAQAAABRzdGNvAAAAAAAAAAEAAAMtAAAAYnVkdGEAAABabWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAtaWxzdAAAACWpdG9vAAAAHWRhdGEAAAABAAAAAExhdmY1OC4yOS4xMDAAAAAIZnJlZQAAAs1tZGF0AAACrQYF//+p3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE1OCByMjk4NCAzNzU5ZmNiIC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAxOSAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM9Y2FiYWM9MSB0cmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWQ9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb21wYXRpYmlsPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgcmVwbGljYXRlZD0wIHFwPTEwMCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjAwIHRocmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWQ9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1pbj0wIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBxYT0xOjEuMDAgYXFvPTAuMDAgAACAAAAQZYiEABX//vfJ78Cm69vfgQ==");
    private static final byte[] MP4_MINIMAL = new byte[] {0,0,0,24,'f','t','y','p','m','p','4','1',0,0,2,0,'i','s','o','m',0,0,0,8,'m','o','o','v'};
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcDisputeRepository disputeRepository;
    @Autowired private EvidenceStorage evidenceStorage;
    @Autowired private PrivateObjectStorage objectStorage;
    @Autowired private StorageCleanupScheduler cleanupScheduler;
    @Autowired private DataSource dataSource;

    @Test
    void caseParticipantsCanReadButOtherUsersReceiveIdentical404() throws Exception {
        UUID buyer = user("buyer"); UUID seller = user("seller"); UUID other = user("other");
        UUID assignedAdmin = user("assigned-admin"); UUID unassignedAdmin = user("unassigned-admin");
        UUID listing = listing(seller); UUID order = order(listing, buyer, seller);
        UUID dispute = UUID.randomUUID();
        jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
        HttpResponse<String> upload = multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "failure.png", "image/png", PNG);
        assertThat(upload.statusCode()).isEqualTo(201);
        String evidenceId = field(upload.body(), "evidenceId");
        assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(buyer)).statusCode()).isEqualTo(200);
        assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(seller)).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE dispute_case SET assigned_admin_id=? WHERE id=?", assignedAdmin.toString(), dispute.toString());
        assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", tokenWithRole(assignedAdmin, "ROLE_ADMIN")).statusCode()).isEqualTo(200);
        HttpResponse<String> denied = get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(other));
        HttpResponse<String> unassignedAdminResponse = get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", tokenWithRole(unassignedAdmin, "ROLE_ADMIN"));
        HttpResponse<String> missing = get("/api/disputes/" + dispute + "/evidence/" + UUID.randomUUID() + "/content", token(other));
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(unassignedAdminResponse.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        UUID deniedCorrelation = assertNotFoundError(denied);
        UUID unassignedAdminCorrelation = assertNotFoundError(unassignedAdminResponse);
        UUID missingCorrelation = assertNotFoundError(missing);
        assertThat(List.of(deniedCorrelation, unassignedAdminCorrelation, missingCorrelation))
            .doesNotHaveDuplicates();
        assertThat(upload.body()).doesNotContain("object_key", "dispute-evidence/");
    }

    @Test
    void rejectsDeclaredTypeForgeryAndLimitPlusOne() throws Exception {
        UUID buyer = user("buyer"); UUID seller = user("seller"); UUID listing = listing(seller); UUID order = order(listing, buyer, seller); UUID dispute = dispute(order, buyer);
        assertThat(multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "bad.pdf", "application/pdf", PNG).statusCode()).isEqualTo(400);
        byte[] oversized = new byte[10 * 1024 * 1024 + 1]; System.arraycopy(PNG, 0, oversized, 0, PNG.length);
        assertThat(multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "large.png", "image/png", oversized).statusCode()).isEqualTo(400);
    }

    @Test
    void minioDisconnectMapsTo503() throws Exception {
        UUID buyer = user("buyer"); UUID seller = user("seller"); UUID listing = listing(seller); UUID order = order(listing, buyer, seller); UUID dispute = dispute(order, buyer);
        MINIO_PROXY.setConnectionCut(true);
        try { assertThat(multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "failure.png", "image/png", PNG).statusCode()).isEqualTo(503); }
        finally { MINIO_PROXY.setConnectionCut(false); }
    }

    @Test
    void disputeJsonNullMalformedAndUnknownFieldsAreUtf8Json400() throws Exception {
        UUID buyer = user("json-buyer");
        UUID admin = user("json-admin");
        UUID randomOrder = UUID.randomUUID();
        UUID randomCase = UUID.randomUUID();
        String[][] requests = {
            {"/api/orders/" + randomOrder + "/disputes", token(buyer), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\",\"unknown\":true}"},
            {"/api/disputes/" + randomCase + "/responses", token(buyer), "null"},
            {"/api/disputes/" + randomCase + "/assignments", token(admin), "{\"adminId\":\"" + admin + "\",\"unknown\":true}"},
            {"/api/disputes/" + randomCase + "/decisions", token(admin), "{"},
        };
        for (String[] request : requests) {
            HttpResponse<String> response = jsonPost(request[0], request[1], "json-" + UUID.randomUUID(), request[2]);
            assertThat(response.statusCode()).as(request[0]).isEqualTo(400);
            assertJsonUtf8(response);
            assertApiError(response);
        }
    }

    @Test
    void evidenceContentReturnsStoredBytesAndContentTypeAndMinioReadFailureIs503() throws Exception {
        UUID buyer = user("content-buyer"); UUID seller = user("content-seller");
        UUID listing = listing(seller); UUID order = order(listing, buyer, seller); UUID dispute = dispute(order, buyer);
        HttpResponse<String> upload = multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "content.png", "image/png", PNG);
        assertThat(upload.statusCode()).isEqualTo(201);
        String evidenceId = field(upload.body(), "evidenceId");
        HttpResponse<byte[]> content = getBytes("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(buyer));
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).containsExactly(PNG);
        assertMediaType(content, "image/png");

        MINIO_PROXY.setConnectionCut(true);
        try {
            assertThat(getBytes("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(buyer)).statusCode()).isEqualTo(503);
        } finally { MINIO_PROXY.setConnectionCut(false); }
    }

    @Test
    void acceptsEachSupportedEvidenceSignatureAndTikaType() throws Exception {
        UUID buyer = user("types-buyer"); UUID seller = user("types-seller");
        UUID dispute = dispute(order(listing(seller), buyer, seller), buyer);
        Object[][] fixtures = {
            {"photo.jpg", "image/jpeg", JPEG}, {"photo.webp", "image/webp", WEBP},
            {"document.pdf", "application/pdf", PDF}, {"clip.mp4", "video/mp4", MP4_MINIMAL}
        };
        for (Object[] fixture : fixtures) {
            assertThat(new Tika().detect((byte[]) fixture[2])).as("Tika fixture: " + fixture[0]).isEqualTo(fixture[1]);
            HttpResponse<String> response = multipart("/api/disputes/" + dispute + "/evidence", token(buyer),
                (String) fixture[0], (String) fixture[1], (byte[]) fixture[2]);
            assertThat(response.statusCode()).as((String) fixture[0]).isEqualTo(201);
            assertThat(response.body()).contains("\"mediaType\":\"" + fixture[1] + "\"");
        }
    }

    @Test
    void rejectsEachTypeAtItsOwnLimitPlusOneUsingStreamingBodies() throws Exception {
        UUID buyer = user("bounds-buyer"); UUID seller = user("bounds-seller");
        UUID dispute = dispute(order(listing(seller), buyer, seller), buyer);
        Object[][] fixtures = {
            {"bound.jpg", "image/jpeg", JPEG, 10L * 1024 * 1024 + 1},
            {"bound.webp", "image/webp", WEBP, 10L * 1024 * 1024 + 1},
            {"bound.pdf", "application/pdf", PDF, 20L * 1024 * 1024 + 1},
            {"bound.mp4", "video/mp4", MP4_MINIMAL, 100L * 1024 * 1024 + 1}
        };
        for (Object[] fixture : fixtures) {
            HttpResponse<String> response = streamingMultipart("/api/disputes/" + dispute + "/evidence", token(buyer),
                (String) fixture[0], (String) fixture[1], (byte[]) fixture[2], (Long) fixture[3]);
            assertThat(response.statusCode()).as((String) fixture[0]).isEqualTo(400);
        }
    }

    @Test
    void expiryWinsAgainstAnInFlightCompletionUsingMySqlRowLock() throws Exception {
        UUID actor = user("fencing-owner");
        UUID session = UUID.randomUUID(); String key = "dispute-evidence/fencing-" + UUID.randomUUID(); String claim = "claim-" + UUID.randomUUID();
        disputeRepository.createSession(session, actor, key, claim);
        jdbc.update("UPDATE object_upload_session SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", session.toString());
        CountDownLatch expiryUpdated = new CountDownLatch(1);
        CountDownLatch allowExpiryCommit = new CountDownLatch(1);
        CountDownLatch completionStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> expiry = pool.submit(() -> expireWithHeldRowLock(session, expiryUpdated, allowExpiryCommit));
        assertThat(expiryUpdated.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Integer> completion = pool.submit(() -> {
            completionStarted.countDown();
            return disputeRepository.completeSession(session, actor, claim);
        });
        assertThat(completionStarted.await(10, TimeUnit.SECONDS)).isTrue();
        allowExpiryCommit.countDown();
        expiry.get(10, TimeUnit.SECONDS);
        assertThat(completion.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM object_upload_session WHERE id=?", String.class, session.toString())).isEqualTo("EXPIRED");
        pool.shutdownNow();
    }

    @Test
    void successfulMinioPutFollowedByDatabaseFailureLeavesDurableCleanup() throws Exception {
        UUID buyer = user("cleanup-buyer"); UUID seller = user("cleanup-seller");
        UUID dispute = dispute(order(listing(seller), buyer, seller), buyer);
        String constraint = "fail_dispute_evidence_" + UUID.randomUUID().toString().replace('-', '_');
        jdbc.execute("ALTER TABLE dispute_evidence ADD CONSTRAINT " + constraint + " CHECK (dispute_case_id <> '" + dispute + "')");
        try {
            assertThatThrownBy(() -> evidenceStorage.attach(dispute, buyer, "forced.png", "image/png", new ByteArrayInputStream(PNG)))
                .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE dispute_evidence DROP CHECK " + constraint);
        }
        String sessionId = jdbc.queryForObject("SELECT id FROM object_upload_session WHERE submitted_by=? AND purpose='DISPUTE_EVIDENCE' ORDER BY created_at DESC LIMIT 1", String.class, buyer.toString());
        String objectKey = jdbc.queryForObject("SELECT object_key FROM object_upload_session WHERE id=?", String.class, sessionId);
        assertThat(jdbc.queryForObject("SELECT status FROM object_upload_session WHERE id=?", String.class, sessionId)).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE cleanup_business_key=?", Integer.class, "dispute-upload:" + sessionId)).isEqualTo(1);
        cleanupScheduler.runOnce(10);
        assertThat(jdbc.queryForObject("SELECT status FROM storage_cleanup_task WHERE cleanup_business_key=?", String.class, "dispute-upload:" + sessionId)).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> objectStorage.open(objectKey)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void createUsesSameResourceReplayAndRejectsCrossOrderKeyReuse() throws Exception {
        UUID buyer = user("create-buyer"); UUID seller = user("create-seller");
        UUID firstOrder = order(listing(seller), buyer, seller);
        UUID secondOrder = order(listing(seller), buyer, seller);
        String body = "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}";
        HttpResponse<String> first = jsonPost("/api/orders/" + firstOrder + "/disputes", token(buyer), "create-key", body);
        HttpResponse<String> replay = jsonPost("/api/orders/" + firstOrder + "/disputes", token(buyer), "create-key", body);
        HttpResponse<String> crossOrder = jsonPost("/api/orders/" + secondOrder + "/disputes", token(buyer), "create-key", body);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(crossOrder.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=?", Integer.class, firstOrder.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=?", Integer.class, secondOrder.toString())).isZero();
    }

    @Test
    void sellerResponseReplayReturnsPersistedResponseAndCrossCaseReuseConflicts() throws Exception {
        UUID buyer = user("respond-buyer"); UUID seller = user("respond-seller");
        UUID firstOrder = order(listing(seller), buyer, seller);
        UUID firstCase = UUID.fromString(field(jsonPost("/api/orders/" + firstOrder + "/disputes", token(buyer), "open-respond-" + UUID.randomUUID(), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}").body(), "disputeId"));
        UUID secondOrder = order(listing(seller), buyer, seller);
        UUID secondCase = UUID.fromString(field(jsonPost("/api/orders/" + secondOrder + "/disputes", token(buyer), "open-respond-" + UUID.randomUUID(), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}").body(), "disputeId"));
        String body = "{\"response\":\"已核实并接受退货\"}";
        HttpResponse<String> first = jsonPost("/api/disputes/" + firstCase + "/responses", token(seller), "respond-key", body);
        HttpResponse<String> replay = jsonPost("/api/disputes/" + firstCase + "/responses", token(seller), "respond-key", body);
        HttpResponse<String> crossCase = jsonPost("/api/disputes/" + secondCase + "/responses", token(seller), "respond-key", body);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(first.body()).contains("已核实并接受退货");
        assertThat(jdbc.queryForObject("SELECT seller_response FROM dispute_case WHERE id=?", String.class, firstCase.toString())).isEqualTo("已核实并接受退货");
        assertThat(crossCase.statusCode()).isEqualTo(409);
    }

    @Test
    void decisionReplayIsStableAndCrossCaseKeyReuseConflicts() throws Exception {
        UUID buyer = user("decision-buyer"); UUID seller = user("decision-seller"); UUID admin = user("decision-admin");
        UUID firstOrder = order(listing(seller), buyer, seller);
        UUID firstCase = UUID.fromString(field(jsonPost("/api/orders/" + firstOrder + "/disputes", token(buyer), "open-decision-" + UUID.randomUUID(), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}").body(), "disputeId"));
        UUID secondOrder = order(listing(seller), buyer, seller);
        UUID secondCase = UUID.fromString(field(jsonPost("/api/orders/" + secondOrder + "/disputes", token(buyer), "open-decision-" + UUID.randomUUID(), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}").body(), "disputeId"));
        String body = "{\"decision\":\"REFUND_ONLY\",\"approvedQuantity\":1}";
        HttpResponse<String> first = jsonPost("/api/disputes/" + firstCase + "/decisions", tokenWithRole(admin, "ROLE_ADMIN"), "decision-key", body);
        HttpResponse<String> replay = jsonPost("/api/disputes/" + firstCase + "/decisions", tokenWithRole(admin, "ROLE_ADMIN"), "decision-key", body);
        HttpResponse<String> crossCase = jsonPost("/api/disputes/" + secondCase + "/decisions", tokenWithRole(admin, "ROLE_ADMIN"), "decision-key", body);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(crossCase.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, firstCase.toString())).isEqualTo("RESOLVED");
    }

    private UUID user(String name) { return UUID.randomUUID(); }
    private UUID listing(UUID seller) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,created_at,updated_at) VALUES (?,?, '键盘','二手','电子',100,0,'SOLD_OUT',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), seller.toString()); return id; }
    private UUID order(UUID listing, UUID buyer, UUID seller) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,t0,acceptance_deadline,trial_deadline,paid_amount_fen,status,created_at,updated_at) VALUES (?,?,?,?, '键盘','二手',100,1,100,DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR),DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 71 HOUR),DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 167 HOUR),100,'AFTERSALE_WINDOW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), buyer.toString(), seller.toString(), listing.toString()); return id; }
    private UUID dispute(UUID order, UUID buyer) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), order.toString(), buyer.toString()); return id; }
    private String token(UUID user) { return ResourceServerTestSupport.token(user, Set.of("ROLE_USER")); }
    private String tokenWithRole(UUID user, String role) { return ResourceServerTestSupport.token(user, Set.of(role)); }
    private HttpResponse<String> get(String path, String token) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private HttpResponse<byte[]> getBytes(String path, String token) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).GET().build(), HttpResponse.BodyHandlers.ofByteArray()); }
    private HttpResponse<String> jsonPost(String path, String token, String key, String body) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Idempotency-Key", key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private static void assertJsonUtf8(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("Content-Type").orElseThrow();
        MediaType mediaType = MediaType.parseMediaType(contentType);
        assertThat(mediaType.getType()).isEqualTo("application");
        assertThat(mediaType.getSubtype()).isEqualTo("json");
        assertThat(mediaType.getCharset()).isEqualTo(StandardCharsets.UTF_8);
    }
    private static void assertApiError(HttpResponse<String> response) {
        assertThat(response.body()).startsWith("{").contains("\"code\":\"INVALID_REQUEST\"")
            .contains("\"message\":").contains("\"correlationId\":").doesNotContain("\"error\"");
        Matcher matcher = Pattern.compile("\\\"correlationId\\\":\\\"([^\\\"]+)\\\"").matcher(response.body());
        assertThat(matcher.find()).isTrue();
        assertThat(UUID.fromString(matcher.group(1))).isNotNull();
    }
    private static UUID assertNotFoundError(HttpResponse<String> response) {
        assertJsonUtf8(response);
        assertThat(response.body()).contains("\"code\":\"RESOURCE_NOT_FOUND\"")
            .contains("\"message\":\"证据不存在\"").doesNotContain("\"error\"");
        return UUID.fromString(field(response.body(), "correlationId"));
    }
    private static void assertMediaType(HttpResponse<?> response, String expected) {
        MediaType mediaType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
        MediaType expectedType = MediaType.parseMediaType(expected);
        assertThat(mediaType.getType()).isEqualTo(expectedType.getType());
        assertThat(mediaType.getSubtype()).isEqualTo(expectedType.getSubtype());
    }
    private HttpResponse<String> multipart(String path,String token,String filename,String type,byte[] bytes) throws Exception { String b="----campus"+UUID.randomUUID(); byte[] p=("--"+b+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8), s=("\r\n--"+b+"--\r\n").getBytes(StandardCharsets.UTF_8), all=new byte[p.length+bytes.length+s.length]; System.arraycopy(p,0,all,0,p.length); System.arraycopy(bytes,0,all,p.length,bytes.length); System.arraycopy(s,0,all,p.length+bytes.length,s.length); return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+b).POST(HttpRequest.BodyPublishers.ofByteArray(all)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private HttpResponse<String> streamingMultipart(String path, String token, String filename, String type, byte[] signature, long size) throws Exception {
        String boundary = "----campus" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
            + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        InputStream body = new SequenceInputStream(new ByteArrayInputStream(prefix),
            new SequenceInputStream(new FixedLengthZeroStream(signature, size), new ByteArrayInputStream(suffix)));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static final class FixedLengthZeroStream extends InputStream {
        private final byte[] prefix; private final long total; private long position;
        private FixedLengthZeroStream(byte[] prefix, long total) { this.prefix = prefix; this.total = total; }
        @Override public int read() { if (position >= total) return -1; return valueAt(position++); }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= total) return -1; int count = (int) Math.min(length, total - position);
            for (int i = 0; i < count; i++) buffer[offset + i] = (byte) valueAt(position++); return count;
        }
        private int valueAt(long index) { return index < prefix.length ? prefix[(int) index] & 0xff : 0; }
    }
    private void expireWithHeldRowLock(UUID session, CountDownLatch updated, CountDownLatch allowCommit) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
            "UPDATE object_upload_session SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN' AND expires_at<=CURRENT_TIMESTAMP(6)")) {
            connection.setAutoCommit(false); statement.setString(1, session.toString()); statement.executeUpdate(); updated.countDown();
            if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("expiry commit gate timeout");
            connection.commit();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static String field(String json,String field){java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+field+"\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json); if(!m.find()) throw new AssertionError(json); return m.group(1);}
}
