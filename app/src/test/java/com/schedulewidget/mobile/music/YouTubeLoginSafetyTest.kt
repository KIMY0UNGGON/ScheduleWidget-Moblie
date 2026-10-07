package com.schedulewidget.mobile.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class YouTubeLoginSafetyTest {
    @Test
    fun loopbackCallbackLineIsCapped() {
        assertEquals("GET /?state=x HTTP/1.1", exchange("GET /?state=x HTTP/1.1\r\n", maxBytes = 64))
        assertNull(exchange("A".repeat(65) + "\r\n", maxBytes = 64))
    }

    @Test
    fun slowSenderCannotExtendThePerConnectionDeadline() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.localPort))
                server.accept().use { accepted ->
                    val sender = Thread {
                        try {
                            "GET /".forEach {
                                client.getOutputStream().write(it.code)
                                client.getOutputStream().flush()
                                Thread.sleep(100)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    sender.start()
                    assertNull(YouTubeLogin.readBoundedRequestLine(accepted, maxBytes = 64, timeoutMs = 150))
                    sender.interrupt()
                    sender.join(1000)
                }
            }
        }
    }

    private fun exchange(line: String, maxBytes: Int): String? =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.localPort))
                server.accept().use { accepted ->
                    client.getOutputStream().write(line.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()
                    YouTubeLogin.readBoundedRequestLine(accepted, maxBytes)
                }
            }
        }
}
