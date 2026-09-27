package com.peergrab.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Versioned wire payloads. Missing schemaVersion denotes a message emitted before versioning. */
public final class MessagePayloadCodec {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int VERSION = 1;

    private MessagePayloadCodec() {}

    public record Timeout(Integer schemaVersion, Long errandId, Integer round, Long version) {}
    public record AutoSettle(Integer schemaVersion, Long errandId) {}
    public record CacheEvict(Integer schemaVersion, Long errandId) {}
    public record FundEvent(Integer schemaVersion, String bizNo, String type, Long errandId,
                            Long publisherId, Long runnerId, Long amountCents, Long commissionCents) {}

    public static String timeout(long errandId, int round, long version) {
        return encode(new Timeout(VERSION, errandId, round, version));
    }

    public static Timeout readTimeout(String body) {
        Timeout value = decode(body, Timeout.class);
        checkVersion(value.schemaVersion());
        positive(value.errandId(), "errandId");
        if (value.round() == null || value.round() < 0) bad("round");
        if (value.version() == null || value.version() < 0) bad("version");
        return value;
    }

    public static String autoSettle(long errandId) {
        return encode(new AutoSettle(VERSION, errandId));
    }

    public static AutoSettle readAutoSettle(String body) {
        AutoSettle value = decode(body, AutoSettle.class);
        checkVersion(value.schemaVersion());
        positive(value.errandId(), "errandId");
        return value;
    }

    public static String cacheEvict(long errandId) {
        return encode(new CacheEvict(VERSION, errandId));
    }

    public static CacheEvict readCacheEvict(String body) {
        CacheEvict value = decode(body, CacheEvict.class);
        checkVersion(value.schemaVersion());
        positive(value.errandId(), "errandId");
        return value;
    }

    public static String fundEvent(String bizNo, String type, long errandId, long publisherId,
                                   long runnerId, long amountCents, long commissionCents) {
        return encode(new FundEvent(VERSION, bizNo, type, errandId, publisherId, runnerId,
                amountCents, commissionCents));
    }

    public static FundEvent readFundEvent(String body) {
        FundEvent value = decode(body, FundEvent.class);
        checkVersion(value.schemaVersion());
        if (value.bizNo() == null || value.bizNo().isBlank()) bad("bizNo");
        if (!"SETTLED".equals(value.type()) && !"REFUNDED".equals(value.type())
                && !"ARBITRATED".equals(value.type())) bad("type");
        positive(value.errandId(), "errandId");
        positive(value.publisherId(), "publisherId");
        if (value.runnerId() == null || value.runnerId() < 0) bad("runnerId");
        if (value.amountCents() == null || value.amountCents() < 0) bad("amountCents");
        if (value.commissionCents() == null || value.commissionCents() < 0) bad("commissionCents");
        return value;
    }

    private static <T> T decode(String body, Class<T> type) {
        try {
            return JSON.readValue(body, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " message", e);
        }
    }

    private static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode message", e);
        }
    }

    private static void checkVersion(Integer version) {
        if (version != null && version != VERSION) bad("schemaVersion");
    }

    private static void positive(Long value, String field) {
        if (value == null || value <= 0) bad(field);
    }

    private static void bad(String field) {
        throw new IllegalArgumentException("Missing or invalid message field: " + field);
    }
}
