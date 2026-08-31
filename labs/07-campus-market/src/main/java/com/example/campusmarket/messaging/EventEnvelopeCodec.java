package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** 可靠事件的严格 JSON 编解码器。 */
public final class EventEnvelopeCodec {
    private final ObjectMapper objectMapper;

    public EventEnvelopeCodec() {
        this(new ObjectMapper());
    }

    public EventEnvelopeCodec(ObjectMapper ignored) {
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE);
    }

    public byte[] encode(DomainEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        try {
            return objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("事件编码失败", e);
        }
    }

    public String encodeToString(DomainEvent event) {
        return new String(encode(event), StandardCharsets.UTF_8);
    }

    public DomainEvent decode(byte[] jsonUtf8) {
        Objects.requireNonNull(jsonUtf8, "事件 JSON 不能为空");
        try {
            return objectMapper.readValue(jsonUtf8, DomainEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("事件 JSON 无效", e);
        }
    }

    public DomainEvent decode(String json) {
        Objects.requireNonNull(json, "事件 JSON 不能为空");
        return decode(json.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> decodePayload(String json) {
        Objects.requireNonNull(json, "payload 不能为空");
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("事件 payload 无效", e);
        }
    }
}
