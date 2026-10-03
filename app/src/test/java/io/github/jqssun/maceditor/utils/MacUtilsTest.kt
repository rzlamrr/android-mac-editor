package io.github.jqssun.maceditor.utils

import io.github.jqssun.maceditor.utils.MacUtils.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacUtilsTest {
    @Test fun validUnicast() = assertEquals(ValidationResult.VALID, MacUtils.validate("02:11:22:AA:BB:CC"))
    @Test fun badLength() = assertEquals(ValidationResult.BAD_LENGTH, MacUtils.validate("02:11:22"))
    @Test fun badChars() = assertEquals(ValidationResult.BAD_LENGTH, MacUtils.validate("0G:11:22:AA:BB:CC"))
    @Test fun allZeros() = assertEquals(ValidationResult.ALL_ZEROS, MacUtils.validate("00:00:00:00:00:00"))
    @Test fun multicastRejected() = assertEquals(ValidationResult.ODD_FIRST_OCTET, MacUtils.validate("03:11:22:AA:BB:CC"))

    @Test fun generatedIsAlwaysValid() {
        repeat(200) { assertEquals(ValidationResult.VALID, MacUtils.validate(MacUtils.generateRandom())) }
    }

    @Test fun collisionIsCaseInsensitive() {
        assertTrue(MacUtils.collides("02:11:22:AA:BB:CC", listOf("", "02:11:22:aa:bb:cc")))
    }

    @Test fun noCollision() {
        assertFalse(MacUtils.collides("02:11:22:AA:BB:CC", listOf("", "02:11:22:AA:BB:CD")))
        assertFalse(MacUtils.collides("", listOf("")))
    }
}
