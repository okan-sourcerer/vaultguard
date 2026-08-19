package com.vaultguard.desktop.service

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

/**
 * Exercises the bridge the way the native host does, without a browser in the way.
 *
 * There are four things between clicking the extension and seeing a password — the
 * extension, native messaging, this socket, and the vault — and a silent failure looks the
 * same from the popup whichever one broke. This settles the bottom two, so what is left is
 * genuinely the browser's half.
 *
 * It prints counts and never credential contents: a diagnostic that dumps a vault into a
 * terminal is worse than no diagnostic.
 */
object BridgeCheck {

    fun run(say: (String) -> Unit) {
        val handshakeFile = BridgeServer.defaultHandshakeFile

        if (!handshakeFile.exists()) {
            say("No bridge handshake at ${handshakeFile.path}")
            say("")
            say("The service is not running, or it is an older build without the bridge.")
            say("Start it with `vaultguard --service`.")
            return
        }

        val handshake = try {
            JSONObject(handshakeFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            say("The handshake file is unreadable: ${e.message}")
            return
        }

        val port = handshake.optInt("port", -1)
        val token = handshake.optString("token")
        say("Handshake:   ${handshakeFile.path}")
        say("Port:        $port")

        val socket = try {
            Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 10_000 }
        } catch (e: Exception) {
            say("Connect:     FAILED - ${e.message}")
            say("")
            // The commonest cause by far: the service was killed rather than quit, so the
            // file names a port nothing is listening on any more.
            say("A handshake file with no listener usually means the service was stopped")
            say("without using Quit. Start `vaultguard --service` again.")
            return
        }

        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8)

            fun ask(request: JSONObject): JSONObject? {
                writer.write(request.put("token", token).toString())
                writer.write("\n")
                writer.flush()
                return reader.readLine()?.let { line -> JSONObject(line) }
            }

            val status = ask(JSONObject().put("action", "status"))
            if (status == null) {
                say("Status:      FAILED - the service closed the connection.")
                return
            }
            say("Connect:     ok")

            if (!status.optBoolean("ok")) {
                say("Status:      refused - ${status.optString("error")}")
                return
            }

            val state = status.optString("state")
            say("State:       $state")

            if (state != ServiceState.UNLOCKED.name) {
                say("")
                say("The bridge works. The vault is not open, so there is nothing to serve -")
                say("unlock it from the tray icon and run this again.")
                return
            }

            // A host that has no credentials for a site is indistinguishable from a broken
            // matcher, from the popup. Two hosts, one certain to match nothing, separate them.
            val hosts = listOf("github.com", "no-such-site.invalid")
            for (host in hosts) {
                val response = ask(JSONObject().put("action", "match").put("url", "https://$host/login"))
                val count = response?.optJSONArray("credentials")?.length() ?: -1
                say("Match $host: ${if (count < 0) "FAILED" else "$count entr${if (count == 1) "y" else "ies"}"}")
            }

            say("")
            say("The service side is working. If the extension still cannot reach it, the")
            say("problem is native messaging - the host registration or the launcher shim.")
        }
    }
}
