package com.example.campusmarket.product.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/** 只识别严格的 replay 完成控制消息；普通商品快照交给快照解码器。 */
@Component
public final class ProductReplayCompleteDecoder {
    private static final int MAX_EVENT_BYTES = 64 * 1024;

    private final JsonMapper mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .addModule(new JavaTimeModule())
        .build();

    public Optional<ProductReplayCompleteEvent> decodeIfPresent(byte[] jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.length == 0 || jsonUtf8.length > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("商品事件长度无效");
        }
        try {
            JsonNode root = mapper.readValue(jsonUtf8, JsonNode.class);
            if (root == null) {
                throw new IllegalArgumentException("replay 完成事件不能为空");
            }
            JsonNode type = root.isObject() ? root.get("eventType") : null;
            if (type == null || !type.isTextual()
                || !ProductReplayCompleteEvent.EVENT_TYPE.equals(type.textValue())) {
                return Optional.empty();
            }
            JsonNode completedAt = root.get("completedAt");
            if (completedAt == null || !completedAt.isTextual()
                || !completedAt.textValue().endsWith("Z")) {
                throw new IllegalArgumentException("replay 完成时间必须使用 UTC Z 表示");
            }
            return Optional.of(mapper.treeToValue(root, ProductReplayCompleteEvent.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("replay 完成事件无效", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("replay 完成事件无效", exception);
        }
    }
}
