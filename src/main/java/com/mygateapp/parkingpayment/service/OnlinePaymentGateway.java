package com.mygateapp.parkingpayment.service;

import org.springframework.stereotype.Component;

/**
 * Integration point for an online payment provider (checkout + server-verified webhook). No provider has been
 * selected and no credentials exist, so the only implementation is {@link Disabled}: online payments are NOT
 * operational and {@code onlinePaymentEnabled} is always false. A real implementation must confirm payments only from
 * a signature-verified provider callback, never from a client-side success callback.
 */
public interface OnlinePaymentGateway {

    boolean isAvailable();

    String providerName();

    @Component
    class Disabled implements OnlinePaymentGateway {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String providerName() {
            return "none";
        }
    }
}
