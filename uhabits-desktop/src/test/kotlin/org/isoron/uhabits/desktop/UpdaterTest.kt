package org.isoron.uhabits.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    private fun asset(tag: String, name: String) =
        """{"name":"$name","browser_download_url":"https://github.com/o/r/releases/download/$tag/$name"}"""

    private fun release(tag: String, vararg names: String) =
        """{"tag_name":"$tag","assets":[${names.joinToString(",") { asset(tag, it) }}]}"""

    // GitHub lists newest first. The newest release may still be missing its desktop files.
    private val json = "[" + listOf(
        release("mine-9", "my-habits-9.apk"),
        release("mine-8", "my-habits-8.apk", "my-habits-desktop-8.msi", "my-habits-desktop-8-portable.zip"),
        release("mine-7", "my-habits-7.apk")
    ).joinToString(",") + "]"

    @Test
    fun `finds the newest installer, skipping releases without one`() {
        val info = Updater.newestIn(json)!!
        assertEquals(8, info.build)
        assertEquals("https://github.com/o/r/releases/download/mine-8/my-habits-desktop-8.msi", info.url)
    }

    @Test
    fun `only offers a build newer than the installed one`() {
        assertEquals(8, Updater.findUpdate(json, 0)!!.build)
        assertEquals(8, Updater.findUpdate(json, 7)!!.build)
        assertNull(Updater.findUpdate(json, 8))
        assertNull(Updater.findUpdate(json, 20))
    }

    @Test
    fun `ignores releases with no installer at all`() {
        assertNull(Updater.newestIn("[" + release("mine-7", "my-habits-7.apk") + "]"))
        assertNull(Updater.newestIn("[]"))
    }

    @Test
    fun `does not mistake the portable zip for an installer`() {
        val only = "[" + release("mine-8", "my-habits-desktop-8-portable.zip") + "]"
        assertNull(Updater.newestIn(only))
    }

    @Test
    fun `downloads the file whole`() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.msi") { ex ->
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val path = Updater.download(UpdateInfo(987654, "http://127.0.0.1:${server.address.port}/f.msi"))
            assertTrue(Files.readAllBytes(path).contentEquals(bytes))
            Files.deleteIfExists(path)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a failed download leaves no installer behind`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.msi") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
        try {
            val result = runCatching { Updater.download(UpdateInfo(987655, "http://127.0.0.1:${server.address.port}/f.msi")) }
            assertTrue(result.isFailure)
        } finally {
            server.stop(0)
        }
    }
}
