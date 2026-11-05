package com.istarvin.cloudstream.data

import org.junit.Assert.*
import org.junit.Test

class ServerConfigTest {
    @Test fun addressEntryDoesNotAcceptCredentialsOrPairingLinks() {
        assertEquals("http://192.168.1.10:6969", ServerConfig.parse("192.168.1.10:6969")!!.baseUrl)
        assertEquals("", ServerConfig.parse("https://stream.example/")!!.token)
        for (address in listOf("https://user:password@stream.example", "https://stream.example/pair?token=secret", "https://stream.example/?token=secret", "https://stream.example/#secret", "ftp://stream.example"))
            assertNull(address, ServerConfig.parse(address))
    }
}
