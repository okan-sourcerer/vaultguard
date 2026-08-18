package com.vaultguard.desktop.service

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The local endpoint the native-messaging host connects to.
 *
 * ## Why a socket and not native messaging straight into the vault
 *
 * A browser spawns a **new** native-messaging host process per connection. That process has
 * no unlocked vault and could only ask for the master password again, every time — which
 * would defeat the tray service entirely. So the host is a thin relay, and this is what it
 * relays to: the one process that actually holds the key.
 *
 * ## Why it is safe to listen on localhost
 *
 * Any page in any browser can reach `127.0.0.1`. Two things stand in the way:
 *
 * - The socket is bound to the **loopback address explicitly**, never `0.0.0.0`, so nothing
 *   off this machine can reach it at all.
 * - Every connection must present a token from [handshakeFile], which is written
 *   owner-only. A web page cannot read that file; the native host, running as the user, can.
 *
 * The browser is what establishes *who* is calling: the native-messaging manifest names the
 * extension id, and the browser refuses to launch the host for anything else. This socket
 * only has to establish that the caller is a process running as this user.
 *
 * Tokens are compared in constant time. A timing oracle on a loopback socket is not much of
 * an attack, but neither is the check expensive.
 */
class BridgeServer(
    private val service: VaultService,
    private val handshakeFile: File = defaultHandshakeFile,
    private val onWarn: (String) -> Unit = {}
) {
    companion object {
        val defaultHandshakeFile: File
            get() = File(System.getProperty("user.home"), ".vaultguard/bridge.json")

        /** Refuses a request larger than any legitimate one, rather than buffering it. */
        private const val MAX_REQUEST_BYTES = 64 * 1024
    }

    private val random = SecureRandom()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private lateinit var token: String

    private val clients = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "vaultguard-bridge-client").apply { isDaemon = true }
    }

    val port: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        if (!running.compareAndSet(false, true)) return

        token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { random.nextBytes(it) })

        // Port 0: the OS picks a free one, so two users on a machine cannot collide and
        // nothing has to be reserved. The handshake file is how it is found.
        val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        serverSocket = socket
        writeHandshake(socket.localPort, token)

        Thread({ acceptLoop(socket) }, "vaultguard-bridge").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        clients.shutdownNow()
        // The handshake names a port nothing is listening on once this returns. Leaving it
        // would have the next host connect to whatever inherited the port.
        handshakeFile.delete()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get()) onWarn("Bridge stopped accepting: ${e.message}")
                return
            }
            clients.submit { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use {
            it.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8)

            var authenticated = false
            while (true) {
                val line = reader.readLine() ?: return
                if (line.length > MAX_REQUEST_BYTES) {
                    respond(writer, BridgeProtocol.error("Request too large."))
                    return
                }

                val request = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    respond(writer, BridgeProtocol.error("Malformed request."))
                    return
                }

                if (!authenticated) {
                    if (!constantTimeEquals(request.optString("token"), token)) {
                        // No detail, and the connection closes: a caller that cannot present
                        // the token has nothing to learn here, including whether it was
                        // close.
                        respond(writer, BridgeProtocol.error("Unauthorised."))
                        return
                    }
                    authenticated = true
                }

                respond(
                    writer,
                    BridgeProtocol.handle(
                        request = request,
                        state = service.state,
                        credentials = { service.credentials() },
                        lock = { service.lock() }
                    )
                )
            }
        }
    }

    private fun respond(writer: OutputStreamWriter, response: JSONObject) {
        writer.write(response.toString())
        writer.write("\n")
        writer.flush()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /**
     * Publishes where to connect and how to prove it.
     *
     * Owner-only where the filesystem understands that. On Windows the inherited ACL on a
     * file under the user's profile is already user-scoped, which is the same protection the
     * saved session relies on.
     */
    private fun writeHandshake(port: Int, token: String) {
        val document = JSONObject()
            .put("version", BridgeProtocol.VERSION)
            .put("port", port)
            .put("token", token)
            .toString()

        handshakeFile.parentFile?.mkdirs()
        handshakeFile.writeText(document, Charsets.UTF_8)
        runCatching {
            Files.setPosixFilePermissions(
                handshakeFile.toPath(), PosixFilePermissions.fromString("rw-------")
            )
        }
    }
}
