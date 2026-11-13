package com.mygateapp.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.vehicle.enums.VehicleEnums.NumberFormat;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import org.junit.jupiter.api.Test;

class VehicleNumbersTest {

    @Test
    void normalisesLikeTheFlutterApp() {
        assertThat(VehicleNumbers.normalize("mh 12-ab 1234")).isEqualTo("MH12AB1234");
        assertThat(VehicleNumbers.normalize(" dl.3c.af.0012 ")).isEqualTo("DL3CAF0012");
        assertThat(VehicleNumbers.normalize(null)).isEmpty();
    }

    @Test
    void acceptsIndianAndBharatSeriesNumbers() {
        assertThat(VehicleNumbers.isValid("MH12AB1234", NumberFormat.INDIA)).isTrue();
        assertThat(VehicleNumbers.isValid("DL3CAF0012", NumberFormat.INDIA)).isTrue();
        assertThat(VehicleNumbers.isValid("KA011234", NumberFormat.INDIA)).isTrue();
        assertThat(VehicleNumbers.isValid("22BH1234AA", NumberFormat.INDIA)).isTrue();
        assertThat(VehicleNumbers.isValid("ABC123", NumberFormat.INDIA)).isFalse();
        assertThat(VehicleNumbers.isValid("ABC123", NumberFormat.GENERIC)).isTrue();
        assertThat(VehicleNumbers.isValid("AB1", NumberFormat.GENERIC)).isFalse();
        assertThat(VehicleNumbers.isValid("ABCDEFGHIJKLMN", NumberFormat.GENERIC)).isFalse();
    }

    @Test
    void rejectsInvalidNumbersWithAFieldError() {
        assertThatThrownBy(() -> VehicleNumbers.requireValid("MH-12", NumberFormat.INDIA, "registrationNumber"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.code()).isEqualTo(ErrorCode.INVALID_VEHICLE_NUMBER);
                    assertThat(api.fieldErrors()).extracting("field").containsExactly("registrationNumber");
                });
        assertThat(VehicleNumbers.requireValid("mh 12 ab 1234", NumberFormat.INDIA, "n")).isEqualTo("MH12AB1234");
    }

    @Test
    void twoWheelersShareTwoWheelerSlots() {
        assertThat(VehicleType.SCOOTER.fits(VehicleType.BIKE)).isTrue();
        assertThat(VehicleType.BIKE.fits(VehicleType.SCOOTER)).isTrue();
        assertThat(VehicleType.BIKE.fits(VehicleType.CAR)).isFalse();
        assertThat(VehicleType.CAR.fits(VehicleType.BIKE)).isFalse();
        assertThat(VehicleType.CAR.fits(null)).isTrue();
    }
}
