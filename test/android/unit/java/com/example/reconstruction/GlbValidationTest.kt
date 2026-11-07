package com.example.reconstruction

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class GlbValidationTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun glb(json:String="""{"asset":{"version":"2.0"}}"""):ByteArray {
        val raw=json.toByteArray();val padded=raw.copyOf((raw.size+3)/4*4)
        for(i in raw.size until padded.size)padded[i]=32
        return ByteBuffer.allocate(20+padded.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(20+padded.size).putInt(padded.size).putInt(0x4E4F534A).put(padded).array()
    }
    private fun accepted(bytes:ByteArray):Boolean {
        val file=temporary.newFile().apply{writeBytes(bytes)}
        return runCatching{validateDownloadedGlb(file)}.isSuccess
    }
    @Test fun validVersionTwoWithEmbeddedDataIsAccepted(){
        assertTrue(accepted(glb()))
        assertTrue(accepted(glb("""{"asset":{"version":"2.0"},"images":[{"uri":"data:image/png;base64,AAAA"}]}""")))
    }
    @Test fun invalidHeaderLengthVersionAndChunkBoundsAreRejected(){
        assertFalse(accepted("glTF".toByteArray()))
        for(offset in listOf(0,4,8,12,16)){
            val bytes=glb();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,-1)
            assertFalse("header offset $offset",accepted(bytes))
        }
        assertFalse(accepted(glb().dropLast(4).toByteArray()))
    }
    @Test fun badJsonAssetVersionAndExternalResourcesAreRejected(){
        assertFalse(accepted(glb("invalid json")))
        assertFalse(accepted(glb("""{"asset":{"version":"1.0"}}""")))
        assertFalse(accepted(glb("""{"asset":{"version":"2.0"},"images":[{"uri":"https://example.com/texture.png"}]}""")))
        assertFalse(accepted(glb("""{"asset":{"version":"2.0"},"buffers":[{"uri":"../private.bin"}]}""")))
    }
}
