package com.vodplatform.progress.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProgressWriteValuesTest {
    @ParameterizedTest
    @CsvSource(value={"89,false,100,89,100,false", "90,false,100,90,100,true",
        "150,false,100,100,100,true", "20,true,100,100,100,true", "0,false,0,0,0,true",
        "2147483647,false,2147483647,2147483647,2147483647,true",
        "1932735282,false,2147483647,1932735282,2147483647,false",
        "1932735283,false,2147483647,1932735283,2147483647,true",
        "2147483647,false,NULL,2147483647,NULL,false", "40,true,NULL,40,NULL,true",
        "2147483647,false,-1,2147483647,NULL,false", "40,true,-1,40,NULL,true"},nullValues="NULL")
    void derivesServerValues(int position, boolean ended, Integer duration, int expectedPosition,
                            Integer expectedDuration, boolean finished) {
        assertThat(ProgressWriteValues.derive(position, ended, duration))
                .isEqualTo(new ProgressWriteValues(expectedPosition, expectedDuration, finished));
    }
}
