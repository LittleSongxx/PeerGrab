package com.peergrab.domain.errand.ports;

/**
 * A publisher-scoped, durable idempotency claim. The claim and the task/funds must
 * commit in one local transaction. A competing request waits on the unique key.
 */
public interface PublishRequestRepository {

    record Claim(String payloadHash, Long errandId) {}

    /** Insert if absent, then lock and return the latest claim in the caller's transaction. */
    Claim claimAndLock(long publisherId, String requestId, String payloadHash);

    /** Attach the committed task ID to a new claim before the caller commits. */
    int complete(long publisherId, String requestId, long errandId);
}
