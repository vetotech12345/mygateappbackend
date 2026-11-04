package com.mygateapp.auth.sms;

/** Delivers OTP codes by SMS. Implementations must never log the code. */
public interface SmsSender {

    /** @throws com.mygateapp.exception.ApiException with OTP_DELIVERY_FAILED when the message cannot be sent */
    void sendOtp(String countryCode, String mobile, String code, long validMinutes);

    String name();
}
