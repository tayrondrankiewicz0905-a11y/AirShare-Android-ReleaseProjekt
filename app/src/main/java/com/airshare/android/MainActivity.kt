package com.airshare.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var urlText: TextView
    private lateinit var server: TransferServer
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val pickedFiles = mutableListOf<Pair<String, ByteArray>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        server = TransferServer()
        handleShareIntent(intent)
        startServer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
            gravity = Gravity.TOP
        }
        outer.addView(TextView(this).apply { text = "AirShare"; textSize = 32f })
        outer.addView(TextView(this).apply {
            text = "Dateien zwischen Android, iPhone und Computer übertragen"
            textSize = 16f; setPadding(0, 8, 0, 20)
        })
        status = TextView(this).apply { text = "Starte lokalen Transfer…"; textSize = 16f; setPadding(0, 12, 0, 12) }
        outer.addView(status)
        urlText = TextView(this).apply { textSize = 19f; setTextIsSelectable(true); setPadding(0, 12, 0, 20) }
        outer.addView(urlText)
        outer.addView(Button(this).apply {
            text = "Dateien auswählen und teilen"
            setOnClickListener { pickFiles() }
        }, match())
        outer.addView(Button(this).apply {
            text = "Transfer-Link aktualisieren"
            setOnClickListener { startServer() }
        }, match())
        outer.addView(TextView(this).apply {
            text = "\nSo funktioniert es\n1. Beide Geräte müssen im selben WLAN sein.\n2. Öffne die angezeigte Adresse im Browser des iPhones.\n3. Lade Dateien hoch oder lade bereitgestellte Dateien herunter.\n\nWichtig: Dies ist eine lokale Transfer-Alternative, keine native AirDrop-Implementierung. Teile die Adresse nur mit Personen, denen du vertraust."
            textSize = 14f; setPadding(0, 18, 0, 0)
        })
        ScrollView(this).apply { addView(outer); setContentView(this) }
    }

    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }

    private fun startServer() {
        try {
            try { if (::server.isInitialized) server.stop() } catch (_: Exception) {}
            server = TransferServer()
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val ip = localIpv4()
            if (ip == null) {
                status.text = "Server läuft, aber keine WLAN-IP gefunden. WLAN aktivieren."
                urlText.text = ""
            } else {
                status.text = "Bereit – beide Geräte im selben WLAN"
                urlText.text = "http://$ip:8765/$token/"
            }
        } catch (e: Exception) {
            status.text = "Serverstart fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}"
        }
    }

    private fun localIpv4(): String? {
        return try {
            val all = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
            (all.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.hostAddress?.startsWith("192.168.") == true }
                ?: all.firstOrNull { it is Inet4Address && !it.isLoopbackAddress })?.hostAddress
        } catch (_: Exception) { null }
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 42)
    }

    @Deprecated("Deprecated callback retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }
        Thread {
            val added = uris.mapNotNull { uri ->
                try { (displayName(uri) ?: "Datei") to (contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null) }
                catch (_: Exception) { null }
            }
            runOnUiThread {
                pickedFiles.addAll(added)
                refreshFiles()
                status.text = "${pickedFiles.size} Datei(en) zum Download bereitgestellt."
            }
        }.start()
    }

    private fun handleShareIntent(i: Intent?) {
        if (i == null) return
        val uris = mutableListOf<Uri>()
        if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            i.clipData?.let { c -> if (uris.isEmpty()) for (x in 0 until c.itemCount) uris.add(c.getItemAt(x).uri) }
        } else if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) return
        Thread {
            val added = uris.mapNotNull { uri ->
                try { (displayName(uri) ?: "Datei") to (contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null) }
                catch (_: Exception) { null }
            }
            runOnUiThread {
                pickedFiles.addAll(added); refreshFiles()
                status.text = "${pickedFiles.size} Datei(en) zum Download bereitgestellt."
            }
        }.start()
    }

    private fun refreshFiles() {
        server.files.clear()
        pickedFiles.forEachIndexed { idx, item -> server.files["$idx-${safeName(item.first)}"] = item }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)

    inner class TransferServer : NanoHTTPD(8765) {
        val files = ConcurrentHashMap<String, Pair<String, ByteArray>>()

        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.trim('/')
            if (path != token && !path.startsWith("$token/")) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            if (session.method == Method.POST && path == "$token/upload") {
                val tmp = HashMap<String, String>()
                return try {
                    session.parseBody(tmp)
                    val name = safeName(session.parameters["name"]?.firstOrNull() ?: "upload.bin")
                    val bodyPath = tmp["postData"] ?: tmp.values.firstOrNull()
                    val bytes = bodyPath?.let { java.io.File(it).readBytes() } ?: ByteArray(0)
                    val id = UUID.randomUUID().toString().take(8)
                    files["$id-$name"] = name to bytes
                    html("<h1>Upload erfolgreich</h1><p>${escape(name)} (${bytes.size} Bytes)</p><a href='/$token/'>Zurück</a>")
                } catch (_: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Upload fehlgeschlagen")
                }
            }
            if (session.method == Method.GET && path.startsWith("$token/download/")) {
                val key = path.substringAfter("$token/download/")
                val item = files[key] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Datei nicht gefunden")
                val response = newFixedLengthResponse(Response.Status.OK, mime(item.first), item.second.inputStream(), item.second.size.toLong())
                response.addHeader("Content-Disposition", "attachment; filename=\"${item.first.replace("\"", "")}\"")
                return response
            }
            val listing = files.entries.sortedBy { it.key }.joinToString("") {
                "<li>${escape(it.value.first)} (${it.value.second.size} Bytes) — <a href='/$token/download/${it.key}'>Herunterladen</a></li>"
            }
            val page = "<h1>AirShare</h1><p>Lokaler Transfer; Dateien verlassen das WLAN nicht über einen Cloud-Dienst.</p>" +
                "<h2>Datei hochladen</h2><form method='post' enctype='multipart/form-data' action='/$token/upload'>" +
                "<input type='file' name='file' multiple required><button type='submit'>Hochladen</button></form>" +
                "<h2>Verfügbare Dateien</h2><ul>$listing</ul><p>Halte die Adresse privat und stoppe AirShare nach dem Transfer.</p>"
            return html(page)
        }

        private fun html(s: String): Response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", s)
            .also { it.addHeader("Cache-Control", "no-store") }
        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
            "pdf" -> "application/pdf"; "mp4" -> "video/mp4"; "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    override fun onDestroy() {
        try { if (::server.isInitialized) server.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
