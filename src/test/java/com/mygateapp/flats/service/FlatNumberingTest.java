package com.mygateapp.flats.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FlatNumberingTest {

    @ParameterizedTest
    @CsvSource({"12,4,1204", "1,1,101", "0,3,G03", "-1,2,B102", "-2,10,B210", "14,12,1412"})
    void bulkNumberingScheme(int floor, int unit, String expected) {
        assertThat(FlatService.flatNumber(floor, unit)).isEqualTo(expected);
    }
}
