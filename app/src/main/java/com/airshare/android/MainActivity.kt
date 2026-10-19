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

        outer.addView(TextView(this).apply {
            text = "AirShare"
            textSize = 32f
        })

        outer.addView(TextView(this).apply {
            text = "Dateien zwischen Android, iPhone und Computer übertragen"
            textSize = 16f
            setPadding(0, 8, 0, 20)
        })

        status = TextView(this).apply {
            text = "Starte lokalen Transfer…"
            textSize = 16f
            setPadding(0, 12, 0, 12)
        }
        outer.addView(status)

        urlText = TextView(this).apply {
            textSize = 19f
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 20)
        }
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
            text = """
                
                So funktioniert es
                1. Beide Geräte müssen im selben WLAN sein.
                2. Öffne die angezeigte Adresse im Browser des iPhones.
                3. Lade Dateien hoch oder lade bereitgestellte Dateien herunter.
                
                Wichtig: Dies ist eine lokale Transfer-Alternative, keine native AirDrop-Implementierung. Teile die Adresse nur mit Personen, denen du vertraust.
            """.trimIndent()
            textSize = 14f
            setPadding(0, 18, 0, 0)
        })

        ScrollView(this).apply {
            addView(outer)
            setContentView(this)
        }
    }

    private fun match() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 12
        }

    private fun startServer() {
        try {
            if (::server.isInitialized) {
                server.stopServer()
            }

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
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: return null

            val addresses = Collections.list(interfaces)
                .flatMap { Collections.list(it.inetAddresses) }

            (
                addresses.firstOrNull {
                    it is Inet4Address &&
                        !it.isLoopbackAddress &&
                        it.hostAddress?.startsWith("192.168.") == true
                } ?: addresses.firstOrNull {
                    it is Inet4Address && !it.isLoopbackAddress
                }
            )?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, 42)
    }

    @Deprecated("Deprecated callback retained for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != 42 || resultCode != RESULT_OK || data == null) {
            return
        }

        val uris = mutableListOf<Uri>()

        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(index).uri)
            }
        } ?: data.data?.let { uris.add(it) }

        loadFiles(uris)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return

        val uris = mutableListOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    ?.let { uris.add(it) }

                intent.clipData?.let { clip ->
                    if (uris.isEmpty()) {
                        for (index in 0 until clip.itemCount) {
                            uris.add(clip.getItemAt(index).uri)
                        }
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.let { uris.addAll(it) }
            }
        }

        if (uris.isNotEmpty()) {
            loadFiles(uris)
        }
    }

    private fun loadFiles(uris: List<Uri>) {
        Thread {
            val added = uris.mapNotNull { uri ->
                try {
                    val name = displayName(uri) ?: "Datei"
                    val bytes = contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: return@mapNotNull null

                    name to bytes
                } catch (_: Exception) {
                    null
                }
            }

            runOnUiThread {
                pickedFiles.addAll(added)
                refreshFiles()
                status.text =
                    "${pickedFiles.size} Datei(en) zum Download bereitgestellt."
            }
        }.start()
    }

    private fun refreshFiles() {
        if (!::server.isInitialized) return

        server.files.clear()

        pickedFiles.forEachIndexed { index, item ->
            server.files["$index-${safeName(item.first)}"] = item
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }

        return uri.lastPathSegment
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)

    inner class TransferServer : NanoHTTPD(8765) {
        val files = ConcurrentHashMap<String, Pair<String, ByteArray>>()

        fun stopServer() {
            this@TransferServer.stop()
        }

        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.trim('/')

            if (path != token && !path.startsWith("$token/")) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND
