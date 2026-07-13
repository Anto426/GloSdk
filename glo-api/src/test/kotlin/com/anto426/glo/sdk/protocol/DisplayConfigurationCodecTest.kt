package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.DisplaySlot
import com.anto426.glo.sdk.model.GloBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayConfigurationCodecTest {
    @Test
    fun `decodes captured packed ASCII display configuration`() {
        val configuration = DisplayConfigurationCodec.decode(
            GloBytes.fromHex(
                """
                0a 03 31 2e 30
                12 11 0a 06 66 72 61 6d 65 30 12 07 0a 05 49 6e 74 72 6f
                12 1c 0a 06 66 72 61 6d 65 31 12 12 0a 08 47 72 65 65 74 69 6e 67 12 06 50 72 6f 6e 74 6f
                12 17 0a 06 66 72 61 6d 65 32 12 0d 0a 05 4f 75 74 72 6f 12 04 43 69 61 6f
                """.trimIndent(),
            ),
        )

        assertEquals("1.0", configuration.version)
        assertEquals("", configuration[DisplaySlot.STARTUP])
        assertEquals("Pronto", configuration[DisplaySlot.READY])
        assertEquals("Ciao", configuration[DisplaySlot.SESSION_END])
    }

    @Test
    fun `decodes packed Unicode code points including supplementary characters`() {
        val configuration = DisplayConfigurationCodec.decode(
            GloBytes.fromHex(
                """
                0a 03 31 2e 30
                12 1e
                  0a 06 66 72 61 6d 65 31
                  12 14
                    0a 08 47 72 65 65 74 69 6e 67
                    12 08 43 69 61 6f 20 a5 ea 07
                    18 06
                """.trimIndent(),
            ),
        )

        assertEquals("Ciao \uD83D\uDD25", configuration[DisplaySlot.READY])
    }

    @Test
    fun `decodes non-packed Unicode code points`() {
        val configuration = DisplayConfigurationCodec.decode(
            GloBytes.fromHex(
                """
                0a 03 31 2e 30
                12 1a
                  0a 06 66 72 61 6d 65 30
                  12 10
                    0a 05 49 6e 74 72 6f
                    10 a9 07
                    10 c2 ec 07
                    18 02
                """.trimIndent(),
            ),
        )

        assertEquals("\u03A9\uD83D\uDE42", configuration[DisplaySlot.STARTUP])
    }
}
