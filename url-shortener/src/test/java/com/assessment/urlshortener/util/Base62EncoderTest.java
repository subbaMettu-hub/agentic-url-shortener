package com.assessment.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base62EncoderTest {

    @Test
    void encodesZeroAsFirstAlphabetChar() {
        assertEquals("0", Base62Encoder.encode(0));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 61, 62, 12345, 999_999_999L, Long.MAX_VALUE})
    void roundTripsEncodeDecode(long value) {
        String encoded = Base62Encoder.encode(value);
        assertEquals(value, Base62Encoder.decode(encoded));
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> Base62Encoder.encode(-1));
    }

    @Test
    void rejectsInvalidCharactersOnDecode() {
        assertThrows(IllegalArgumentException.class, () -> Base62Encoder.decode("has space"));
    }
}
