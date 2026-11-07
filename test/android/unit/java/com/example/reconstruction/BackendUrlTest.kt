package com.example.reconstruction

import org.junit.Assert.*
import org.junit.Test

class BackendUrlTest{
    @Test fun acceptsUsbLanAndHttps(){
        assertEquals("http://127.0.0.1:8000/",validatedBackendUrl("http://127.0.0.1:8000"))
        assertEquals("http://192.168.1.2:8000/",validatedBackendUrl("http://192.168.1.2:8000/"))
        assertEquals("https://example.com/",validatedBackendUrl("https://example.com"))
    }
    @Test fun rejectsTokenLeakThroughPublicHttpOrEmbeddedCredentials(){
        listOf("http://example.com","http://172.40.0.1:8000","https://user:secret@example.com","https://example.com/?token=abc","https://example.com/jobs").forEach{url->
            assertThrows(IllegalArgumentException::class.java){validatedBackendUrl(url)}
        }
    }
}
