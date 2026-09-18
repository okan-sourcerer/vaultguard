package com.vaultguard.desktop.service

import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The native-messaging host: a relay between the browser and the running tray service.
 *
 * It holds no key, decrypts nothing, and keeps no state. It exists because a browser starts
 * a **new** host process for each connection, and a fresh process has no unlocked vault —
 * it could only ask for the master password again, every time, which would make the tray
 * service pointless. So the vault lives in one long-running process and this forwards to it.
 *
 * ## The wire format
 *
 * Native messaging frames each message with a **4-byte length in the platform's native byte
 * order**, not network order. Everything this runs on is little-endian; writing big-endian
 * produces a length in the billions and the browser closes the port with no useful error.
 * `ByteOrder.nativeOrder()` says what is meant.
 *
 * Anything written to stdout that is not a frame corrupts the stream, so this must never
 * print. Diagnostics go to stderr, which the browser captures separately.
 */
object NativeHost {

    /** The browser will not send more than this, and neither will anything legitimate. */
    private const val MAX_MESSAGE_BYTES = 1024 * 1024

    fun run(handshakeFile: File = BridgeServer.defaultHandshakeFile) {
        val input = DataInputStream(System.`in`.buffered())
        val output = System.out

        var bridge: Bridge? = null
        try {
            while (true) {
                val length = readLength(input) ?: return
                if (length <= 0 || length > MAX_MESSAGE_BYTES) return

                val body = ByteArray(length)
                input.readFully(body)

                val text = String(body, Charsets.UTF_8)
                val response = try {
                    if (!isRelayable(text)) {
                        BridgeProtocol.error("That action is not available to the browser.").toString()
                    } else {
                        if (bridge == null) bridge = Bridge.connect(handshakeFile)
                        bridge.ask(text)
                    }
                } catch (e: Exception) {
                    // A dead bridge is the normal case, not a crash: the service may simply
                    // not be running. The extension turns this into "start VaultGuard".
                    bridge?.close()
                    bridge = null
                    BridgeProtocol.error(
                        e.message ?: "VaultGuard is not running. Start it from the tray."
                    ).put("serviceUnavailable", true).toString()
                }

                write(output, response)
            }
        } finally {
            bridge?.close()
        }
    }

    /**
     * The browser gets the actions the extension needs and no others. The service would
     * also refuse an unknown action, but "open" is a known one, meant for a second launch
     * of VaultGuard, and a page's extension has no business popping the vault's window.
     */
    fun isRelayable(message: String): Boolean =
        runCatching { JSONObject(message).optString("action") in BridgeProtocol.RELAYABLE }.getOrDefault(false)

    /**
     * Asks a running service to open its window. Used by a second launch, which then
     * exits; nothing else on this side of the socket should need it.
     *
     * @return true if a service answered.
     */
    fun askRunningServiceToOpen(handshakeFile: File = BridgeServer.defaultHandshakeFile): Boolean = runCatching {
        val bridge = Bridge.connect(handshakeFile)
        try {
            JSONObject(bridge.ask(JSONObject().put("action", BridgeProtocol.Action.OPEN).toString())).optBoolean("ok")
        } finally {
            bridge.close()
        }
    }.getOrDefault(false)

    private fun readLength(input: DataInputStream): Int? {
        val header = ByteArray(4)
        var read = 0
        while (read < 4) {
            val count = input.read(header, read, 4 - read)
            if (count < 0) return null
            read += count
        }
        return ByteBuffer.wrap(header).order(ByteOrder.nativeOrder()).int
    }

    private fun write(output: java.io.OutputStream, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(bytes.size).array()
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    /** One authenticated connection to the tray service. */
    private class Bridge(private val socket: Socket, private val token: String) {

        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

        fun ask(request: String): String {
            // The token rides on every request rather than only the first, so the relay
            // holds no session state and a reconnect is invisible to the caller.
            val withToken = JSONObject(request).put("token", token)
            writer.write(withToken.toString())
            writer.write("\n")
            writer.flush()
            return reader.readLine() ?: throw IllegalStateException("VaultGuard closed the connection.")
        }

        fun close() {
            runCatching { socket.close() }
        }

        companion object {
            fun connect(handshakeFile: File): Bridge {
                if (!handshakeFile.exists()) {
                    throw IllegalStateException("VaultGuard is not running. Start it from the tray.")
                }
                val handshake = JSONObject(handshakeFile.readText(Charsets.UTF_8))
                val socket = Socket(InetAddress.getLoopbackAddress(), handshake.getInt("port"))
                socket.soTimeout = 30_000
                return Bridge(socket, handshake.getString("token"))
            }
        }
    }
}
