package com.digitalt3.commons.sdk;

/**
 * Transport failure for Kafka REST Proxy or Event Hubs HTTPS delivery.
 *
 * @since 0.1.0
 */
public final class KafkaTransportError extends Dt3SdkException {

    public KafkaTransportError(String label, int status) {
        super(
            label + " export request failed with status " + status,
            null,
            Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
            true,
            Dt3ErrorPhase.DELIVERY
        );
    }

    public KafkaTransportError(String label, Throwable cause) {
        super(
            label + " export request failed",
            cause,
            Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
            true,
            Dt3ErrorPhase.DELIVERY
        );
    }
}
