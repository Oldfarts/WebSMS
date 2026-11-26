package com.example.websms

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.Locale

data class ContactItem(val name: String, val phone: String)

data class SendTextRequest(
    val phone: String = "",
    val message: String = ""
)
data class MediaPartItem(
    val partId: Long,
    val contentType: String,
    val isVideo: Boolean
)

private data class MultipartPart(
    val name: String,
    val fileName: String?,
    val isFile: Boolean,
    val bytes: ByteArray
)

data class ChatMessageItem(
    val id: String,
    val address: String,
    val body: String,
    val date: Long,
    val isSent: Boolean,
    val isMms: Boolean,
    val mediaParts: List<MediaPartItem> = emptyList()
)

data class ApiResponse(
    val success: Boolean,
    val error: String? = null,
    val resolvedPhone: String? = null
)

class SmsServerService : Service() {

    companion object {
        const val CHANNEL_ID = "websms_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STATUS_UPDATE = "com.example.websms.STATUS_UPDATE"
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_LOG_TEXT = "extra_log_text"

        @Volatile
        var currentUrl: String = ""
            private set

        @Volatile
        var currentLog: String = "Palvelin ei ole käynnissä."
            private set
    }

    private var webServer: SmsWebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        startForegroundWithNotification("Käynnistetään Web SMS -palvelinta...")
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification(currentLog)
        if (webServer == null || webServer?.isAlive != true) {
            startServer()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Web SMS Taustapalvelu",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Pitää paikallisen Web SMS -palvelimen käynnissä taustalla"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val contentText = if (currentUrl.isNotBlank()) {
            "$currentUrl · $statusText"
        } else {
            statusText
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Web SMS -palvelin")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WebSMS::ServerWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }

            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "WebSMS::ServerWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun publishStatus(url: String = currentUrl, logText: String) {
        currentUrl = url
        currentLog = logText

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(logText))

        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_SERVER_URL, currentUrl)
            putExtra(EXTRA_LOG_TEXT, currentLog)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun resolveRecipientPhone(rawInput: String): Pair<String?, String?> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Pair(null, "Valitse vastaanottaja tai syötä nimi/puhelinnumero.")
        }

        val contacts = getPhoneContacts()
        val hasDigits = trimmed.any { it.isDigit() }

        if (hasDigits) {
            val normInput = normalizePhone(trimmed)
            val suffix = if (normInput.length >= 7) normInput.takeLast(7) else normInput

            val phoneMatches = contacts.filter { contact ->
                val normContact = normalizePhone(contact.phone)
                if (suffix.length >= 7 && normContact.length >= 7) {
                    normContact.endsWith(suffix)
                } else {
                    normContact == normInput || contact.phone.equals(trimmed, ignoreCase = true)
                }
            }

            val distinctPhones = phoneMatches.map { normalizePhone(it.phone) }.distinct()
            return when {
                phoneMatches.size == 1 -> Pair(phoneMatches.first().phone, null)
                distinctPhones.size == 1 -> Pair(phoneMatches.first().phone, null)
                phoneMatches.isEmpty() -> Pair(trimmed, null) // Suora puhelinnumero, jota ei ole osoitekirjassa
                else -> Pair(null, "Numero '$trimmed' täsmää useaan yhteystietoon. Tarkenna numero.")
            }
        }

        // Syöte on pelkkää nimeä -> vaatii yksiselitteisen osuman osoitekirjasta/keskusteluista
        val exactNameMatches = contacts.filter {
            it.name.equals(trimmed, ignoreCase = true)
        }
        val candidates = if (exactNameMatches.isNotEmpty()) {
            exactNameMatches
        } else {
            contacts.filter { it.name.contains(trimmed, ignoreCase = true) }
        }

        val uniquePhones = candidates.map { normalizePhone(it.phone) }.distinct()
        return when {
            candidates.isEmpty() -> Pair(null, "Yhteystietoa '$trimmed' ei löytynyt.")
            uniquePhones.size == 1 -> Pair(candidates.first().phone, null)
            else -> Pair(null, "Nimi '$trimmed' on moniselitteinen (${uniquePhones.size} eri numeroa). Tarkenna nimi tai valitse listasta.")
        }
    }

    private fun startServer() {
        try {
            val ip = getWifiIpAddress()
            val port = 8080

            webServer?.stop()
            webServer = SmsWebServer(port)
            webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

            publishStatus(
                url = "http://$ip:$port",
                logText = "Palvelin käynnissä taustalla!"
            )
        } catch (e: Exception) {
            publishStatus(logText = "Virhe: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        return String.format(
            Locale.US, "%d.%d.%d.%d",
            ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
        )
    }

    private fun normalizePhone(raw: String): String =
        raw.replace("[^+0-9]".toRegex(), "")

    private fun phonesMatch(filterPhone: String?, candidateAddress: String): Boolean {
        val rawFilter = filterPhone?.trim().orEmpty()
        if (rawFilter.isEmpty()) return true
        if (candidateAddress.isBlank()) return false

        val targetNorm = normalizePhone(rawFilter)
        val candNorm = normalizePhone(candidateAddress)
        return if (targetNorm.length >= 7 && candNorm.length >= 7) {
            candNorm.endsWith(targetNorm.takeLast(7)) || targetNorm.endsWith(candNorm.takeLast(7))
        } else {
            candidateAddress.equals(rawFilter, ignoreCase = true)
        }
    }

    private fun getPhoneContacts(): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        val seenKeys = mutableSetOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: "Tuntematon"
                    val number = cursor.getString(numIdx)?.replace("\\s+".toRegex(), "") ?: ""
                    val key = normalizePhone(number).takeLast(7)
                    if (number.isNotEmpty() && key !in seenKeys) {
                        seenKeys.add(key)
                        list.add(ContactItem(name, number))
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            val recentAddresses = mutableListOf<String>()

            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT 100"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(addrIdx)?.trim().orEmpty()
                    if (addr.isNotEmpty()) recentAddresses.add(addr)
                }
            }

            contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"),
                null,
                null,
                "date DESC LIMIT 50"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(idIdx)
                    val addr = getMmsCounterpartAddress(mmsId, isSent = false)
                    if (addr.isNotEmpty()) recentAddresses.add(addr)
                }
            }

            for (address in recentAddresses) {
                val norm = normalizePhone(address)
                val key = if (norm.length >= 7) norm.takeLast(7) else address
                if (key !in seenKeys) {
                    seenKeys.add(key)
                    list.add(0, ContactItem(address, address))
                }
            }
        }

        return list
    }

    private fun getMmsCounterpartAddress(mmsId: Long, isSent: Boolean): String {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        val targetType = if (isSent) 151 else 137
        var fallbackAddress = ""

        contentResolver.query(addrUri, arrayOf("address", "type"), null, null, null)?.use { cursor ->
            val addrIdx = cursor.getColumnIndex("address")
            val typeIdx = cursor.getColumnIndex("type")
            while (cursor.moveToNext()) {
                val rawAddr = cursor.getString(addrIdx)?.trim().orEmpty()
                if (rawAddr.isEmpty() || rawAddr == "insert-address-token") continue
                val type = cursor.getInt(typeIdx)
                if (type == targetType) {
                    return rawAddr
                }
                if (fallbackAddress.isEmpty()) {
                    fallbackAddress = rawAddr
                }
            }
        }
        return fallbackAddress
    }

    private fun getMmsParts(mmsId: Long): Pair<String, List<MediaPartItem>> {
        val partUri = Uri.parse("content://mms/part")
        val textBuilder = StringBuilder()
        val mediaList = mutableListOf<MediaPartItem>()

        contentResolver.query(
            partUri,
            arrayOf("_id", "ct", "text"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex("_id")
            val ctIdx = cursor.getColumnIndex("ct")
            val textIdx = cursor.getColumnIndex("text")

            while (cursor.moveToNext()) {
                val partId = cursor.getLong(idIdx)
                val contentType = cursor.getString(ctIdx)?.lowercase(Locale.ROOT).orEmpty()

                if (contentType == "text/plain") {
                    val bodyText = cursor.getString(textIdx)
                    if (!bodyText.isNullOrBlank()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(bodyText)
                    } else {
                        try {
                            contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { input ->
                                val streamText = input.bufferedReader().readText().trim()
                                if (streamText.isNotEmpty()) {
                                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                                    textBuilder.append(streamText)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                } else if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
                    mediaList.add(
                        MediaPartItem(
                            partId = partId,
                            contentType = contentType,
                            isVideo = contentType.startsWith("video/")
                        )
                    )
                }
            }
        }

        return Pair(textBuilder.toString(), mediaList)
    }

    private fun getPhoneMessages(filterPhone: String? = null): List<ChatMessageItem> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val combined = mutableListOf<ChatMessageItem>()

        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT 200"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
            val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
            val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)

            while (cursor.moveToNext()) {
                val address = cursor.getString(addrIdx).orEmpty()
                if (!phonesMatch(filterPhone, address)) continue

                val type = cursor.getInt(typeIdx)
                combined.add(
                    ChatMessageItem(
                        id = "sms_${cursor.getLong(idIdx)}",
                        address = address,
                        body = cursor.getString(bodyIdx).orEmpty(),
                        date = cursor.getLong(dateIdx),
                        isSent = type == Telephony.Sms.MESSAGE_TYPE_SENT,
                        isMms = false
                    )
                )
            }
        }

        contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "msg_box"),
            null,
            null,
            "date DESC LIMIT 100"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex("_id")
            val dateIdx = cursor.getColumnIndex("date")
            val boxIdx = cursor.getColumnIndex("msg_box")

            while (cursor.moveToNext()) {
                val mmsId = cursor.getLong(idIdx)
                val msgBox = cursor.getInt(boxIdx)
                val isSent = msgBox == Telephony.Mms.MESSAGE_BOX_SENT
                val address = getMmsCounterpartAddress(mmsId, isSent)
                if (!phonesMatch(filterPhone, address)) continue

                val (mmsText, mediaParts) = getMmsParts(mmsId)
                if (mmsText.isBlank() && mediaParts.isEmpty()) continue

                val dateMs = cursor.getLong(dateIdx) * 1000L
                combined.add(
                    ChatMessageItem(
                        id = "mms_$mmsId",
                        address = address,
                        body = mmsText,
                        date = dateMs,
                        isSent = isSent,
                        isMms = true,
                        mediaParts = mediaParts
                    )
                )
            }
        }

        return combined.sortedBy { it.date }
    }

    private fun sendSms(phone: String, message: String) {
        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
        publishStatus(logText = "SMS lähetetty automaattisesti: $phone")
    }

    private fun saveUploadedFile(tempFilePath: String, fileName: String): File {
        val uploadDir = File(filesDir, "attachments")
        if (!uploadDir.exists()) uploadDir.mkdirs()
        val destination = File(uploadDir, fileName)
        File(tempFilePath).copyTo(destination, overwrite = true)
        return destination
    }

    private fun saveImage(tempFilePath: String, fileName: String): File {
        val picturesDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: filesDir
        val webSmsDir = File(picturesDir, "WebSMS")
        if (!webSmsDir.exists()) webSmsDir.mkdirs()
        val destination = File(webSmsDir, fileName)
        File(tempFilePath).copyTo(destination, overwrite = true)
        return destination
    }

    private fun saveVideo(tempFilePath: String, fileName: String): File {
        val moviesDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
        val webSmsDir = File(moviesDir, "WebSMS")
        if (!webSmsDir.exists()) webSmsDir.mkdirs()
        val destination = File(webSmsDir, fileName)
        File(tempFilePath).copyTo(destination, overwrite = true)
        return destination
    }

    private fun detectMimeType(file: File): String {
        return when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "*/*"
        }
    }

    private fun openMmsComposer(phone: String, message: String, files: List<File>) {
        try {
            if (files.isEmpty()) return

            val authority = "$packageName.fileprovider"
            val uris = ArrayList<Uri>(files.size)
            val mimeTypes = mutableSetOf<String>()

            for (file in files) {
                uris.add(FileProvider.getUriForFile(this, authority, file))
                mimeTypes.add(detectMimeType(file))
            }

            val intentType = when {
                mimeTypes.size == 1 -> mimeTypes.first()
                mimeTypes.all { it.startsWith("image/") } -> "image/*"
                mimeTypes.all { it.startsWith("video/") } -> "video/*"
                else -> "*/*"
            }

            publishStatus(logText = "MMS-liitteitä (${files.size} kpl) avattu viestisovellukseen")

            val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(this)

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = intentType
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = intentType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (phone.isNotBlank()) {
                    putExtra("address", phone)
                }
                if (message.isNotBlank()) {
                    putExtra("sms_body", message)
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.isNotEmpty()) {
                    clipData = ClipData.newUri(contentResolver, "MMS-liite", uris.first()).apply {
                        for (i in 1 until uris.size) {
                            addItem(ClipData.Item(uris[i]))
                        }
                    }
                }
                if (!defaultSmsPackage.isNullOrBlank()) {
                    setPackage(defaultSmsPackage)
                }
            }

            startActivity(intent)
        } catch (e: Exception) {
            publishStatus(logText = "MMS VIRHE: ${e.message}")
        }
    }

    inner class SmsWebServer(port: Int) : NanoHTTPD(port) {
        private val gson = Gson()

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.uri == "/api/contacts" && session.method == Method.GET -> {
                    val json = gson.toJson(getPhoneContacts())
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
                }

                session.uri == "/api/messages" && session.method == Method.GET -> {
                    val rawPhoneParam = decodeUtf8Field(session.parameters["phone"]?.firstOrNull()?.trim())
                    val effectivePhone = if (rawPhoneParam.isNotEmpty() && !rawPhoneParam.any { it.isDigit() }) {
                        resolveRecipientPhone(rawPhoneParam).first ?: rawPhoneParam
                    } else {
                        rawPhoneParam
                    }
                    val json = gson.toJson(getPhoneMessages(effectivePhone.ifBlank { null }))
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
                }

                session.uri == "/api/mms-part" && session.method == Method.GET -> {
                    val partId = session.parameters["partId"]?.firstOrNull()?.toLongOrNull()
                    if (partId == null || ContextCompat.checkSelfPermission(this@SmsServerService, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", "Virheellinen partId tai lupa puuttuu")
                    }

                    val partUri = Uri.parse("content://mms/part/$partId")
                    var mimeType = "application/octet-stream"
                    contentResolver.query(
                        Uri.parse("content://mms/part"),
                        arrayOf("ct"),
                        "_id = ?",
                        arrayOf(partId.toString()),
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val ct = cursor.getString(cursor.getColumnIndexOrThrow("ct"))
                            if (!ct.isNullOrBlank()) mimeType = ct
                        }
                    }

                    val inputStream = contentResolver.openInputStream(partUri)
                        ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Liitettä ei löytynyt")

                    newChunkedResponse(Response.Status.OK, mimeType, inputStream)
                }

                session.uri == "/api/send" && session.method == Method.POST -> {
                    val bodyBytes = readRequestBodyBytes(session)
                    val parts = parseMultipartParts(session, bodyBytes)

                    val textFields = mutableMapOf<String, String>()
                    for (part in parts) {
                        if (!part.isFile) {
                            textFields[part.name] = String(part.bytes, Charsets.UTF_8)
                        }
                    }

                    val rawRecipient = (textFields["phone"] ?: session.parameters["phone"]?.firstOrNull()).orEmpty().trim()
                    val message = (textFields["message"] ?: session.parameters["message"]?.firstOrNull()).orEmpty().trim()

                    val (resolvedPhone, recipientError) = resolveRecipientPhone(rawRecipient)
                    if (recipientError != null || resolvedPhone.isNullOrBlank()) {
                        val errJson = gson.toJson(ApiResponse(success = false, error = recipientError ?: "Virheellinen vastaanottaja"))
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", errJson)
                    }

                    val batchTimestamp = System.currentTimeMillis()
                    val savedFiles = mutableListOf<File>()

                    val fileParts = parts
                        .filter { it.isFile && it.bytes.isNotEmpty() }
                        .sortedBy { it.name }

                    fileParts.forEachIndexed { index, filePart ->
                        val suffix = if (filePart.name == "attachment") "" else "_${filePart.name.removePrefix("attachment_")}"
                        val partFileName = filePart.fileName?.takeIf { it.isNotBlank() }
                            ?: textFields["fileName$suffix"]?.takeIf { it.isNotBlank() }
                        val saved = writeTempAttachmentFile(filePart.bytes, index, batchTimestamp, partFileName)
                        savedFiles.add(saved)
                    }

                    return try {
                        if (savedFiles.isNotEmpty()) {
                            openMmsComposer(resolvedPhone, message, savedFiles)
                        } else {
                            sendSms(resolvedPhone, message)
                        }

                        val okJson = gson.toJson(ApiResponse(success = true, resolvedPhone = resolvedPhone))
                        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", okJson)
                    } catch (e: Exception) {
                        val errJson = gson.toJson(ApiResponse(success = false, error = e.message ?: "Tuntematon virhe"))
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", errJson)
                    }
                }

                session.uri == "/api/send-text" && session.method == Method.POST -> {
                    val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                    val rawBody = if (contentLength > 0) {
                        val bytes = ByteArray(contentLength)
                        var offset = 0
                        while (offset < contentLength) {
                            val read = session.inputStream.read(bytes, offset, contentLength - offset)
                            if (read <= 0) break
                            offset += read
                        }
                        String(bytes, 0, offset, Charsets.UTF_8)
                    } else {
                        session.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    }

                    val req = try {
                        gson.fromJson(rawBody, SendTextRequest::class.java) ?: SendTextRequest()
                    } catch (_: Exception) {
                        SendTextRequest()
                    }

                    val rawRecipient = req.phone.trim()
                    val message = req.message.trim()
                    val (resolvedPhone, recipientError) = resolveRecipientPhone(rawRecipient)

                    if (recipientError != null || resolvedPhone.isNullOrBlank()) {
                        val errJson = gson.toJson(ApiResponse(success = false, error = recipientError ?: "Virheellinen vastaanottaja"))
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", errJson)
                    }

                    return try {
                        sendSms(resolvedPhone, message)
                        val okJson = gson.toJson(ApiResponse(success = true, resolvedPhone = resolvedPhone))
                        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", okJson)
                    } catch (e: Exception) {
                        val errJson = gson.toJson(ApiResponse(success = false, error = e.message ?: "Tuntematon virhe"))
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", errJson)
                    }
                }

                else -> {
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", getWebHtml())
                }
            }
        }

        private fun decodeUtf8Field(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            return when {
                raw.contains('Ã') || raw.contains('Å') -> {
                    try {
                        String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    } catch (_: Exception) {
                        raw
                    }
                }
                else -> raw
            }
        }

        private fun readRequestBodyBytes(session: NanoHTTPD.IHTTPSession): ByteArray {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength <= 0) {
                return try {
                    session.inputStream.readBytes()
                } catch (_: Exception) {
                    ByteArray(0)
                }
            }
            if (contentLength > 50 * 1024 * 1024) {
                return ByteArray(0)
            }

            val buf = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = session.inputStream.read(buf, offset, contentLength - offset)
                if (read <= 0) break
                offset += read
            }
            return buf.copyOf(offset)
        }

        private fun parseMultipartParts(session: NanoHTTPD.IHTTPSession, bodyBytes: ByteArray): List<MultipartPart> {
            if (bodyBytes.isEmpty()) return emptyList()

            val contentType = session.headers["content-type"].orEmpty()
            val boundary = Regex("boundary=([^;\\s]+)").find(contentType)?.groupValues?.get(1)?.trim('"')
                ?: return emptyList()

            val boundaryBytes = "--$boundary".toByteArray(Charsets.ISO_8859_1)
            val crlfCrlf = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

            val parts = mutableListOf<MultipartPart>()
            var pos = indexOfBytes(bodyBytes, boundaryBytes, 0)

            while (pos >= 0) {
                val partStart = pos + boundaryBytes.size
                if (partStart + 1 < bodyBytes.size &&
                    bodyBytes[partStart] == '-'.code.toByte() &&
                    bodyBytes[partStart + 1] == '-'.code.toByte()
                ) {
                    break
                }

                val headerStart = if (partStart + 1 < bodyBytes.size &&
                    bodyBytes[partStart] == '\r'.code.toByte() &&
                    bodyBytes[partStart + 1] == '\n'.code.toByte()
                ) {
                    partStart + 2
                } else {
                    partStart
                }

                val headerEnd = indexOfBytes(bodyBytes, crlfCrlf, headerStart)
                if (headerEnd < 0) break

                val headersText = String(bodyBytes, headerStart, headerEnd - headerStart, Charsets.ISO_8859_1)
                val fieldName = Regex("name=\"([^\"]+)\"").find(headersText)?.groupValues?.get(1)
                val rawFileName = Regex("filename=\"([^\"]*)\"").find(headersText)?.groupValues?.get(1)
                val isFile = rawFileName != null

                val valueStart = headerEnd + crlfCrlf.size
                val nextBoundary = indexOfBytes(bodyBytes, boundaryBytes, valueStart)
                if (nextBoundary < 0) break

                var valueEnd = nextBoundary
                if (valueEnd >= 2 &&
                    bodyBytes[valueEnd - 2] == '\r'.code.toByte() &&
                    bodyBytes[valueEnd - 1] == '\n'.code.toByte()
                ) {
                    valueEnd -= 2
                }

                if (fieldName != null && valueEnd >= valueStart) {
                    val partBytes = bodyBytes.copyOfRange(valueStart, valueEnd)
                    val cleanFileName = rawFileName?.let {
                        String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                            .substringAfterLast("/")
                            .substringAfterLast("\\")
                    }
                    parts.add(
                        MultipartPart(
                            name = fieldName,
                            fileName = cleanFileName,
                            isFile = isFile,
                            bytes = partBytes
                        )
                    )
                }

                pos = nextBoundary
            }

            return parts
        }

        private fun writeTempAttachmentFile(bytes: ByteArray, index: Int, batchTimestamp: Long, originalFileName: String?): File {
            val safeOriginal = originalFileName.orEmpty()
            val rawExt = safeOriginal.substringAfterLast(".", "").lowercase(Locale.ROOT)
            val extension = if (rawExt.isNotBlank()) rawExt else "jpg"
            val safeName = "upload_${batchTimestamp}_${index}.$extension"

            val tempFile = File(cacheDir, "mms_part_${batchTimestamp}_${index}.$extension")
            tempFile.writeBytes(bytes)

            return when (extension) {
                "jpg", "jpeg", "png", "gif", "webp" -> saveImage(tempFile.absolutePath, safeName).also { tempFile.delete() }
                "mp4", "3gp", "mkv", "avi", "webm" -> saveVideo(tempFile.absolutePath, safeName).also { tempFile.delete() }
                else -> saveUploadedFile(tempFile.absolutePath, safeName).also { tempFile.delete() }
            }
        }

        private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
            if (needle.isEmpty() || fromIndex >= haystack.size) return -1
            val limit = haystack.size - needle.size
            for (i in fromIndex..limit) {
                var matched = true
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) return i
            }
            return -1
        }

        private fun getWebHtml(): String = """
            <!DOCTYPE html>
            <html lang="fi">
            <head>
                <meta charset="UTF-8">
                <title>Web SMS</title>
                <style>
                    * { box-sizing: border-box; }
                    body { font-family: Segoe UI, sans-serif; display: flex; height: 100vh; margin: 0; background: #efeae2; }
                    #sidebar { width: 320px; background: #ffffff; border-right: 1px solid #ddd; display: flex; flex-direction: column; }
                    #search { padding: 14px; border: none; border-bottom: 1px solid #ddd; font-size: 15px; outline: none; }
                    #contacts { overflow-y: auto; flex: 1; }
                    .contact { padding: 12px 16px; border-bottom: 1px solid #f0f0f0; cursor: pointer; }
                    .contact:hover, .contact.active { background: #e9f5ff; }
                    .contact-name { font-weight: bold; }
                    .contact-phone { font-size: 13px; color: #666; }
                    #chat { flex: 1; display: flex; flex-direction: column; height: 100vh; }
                    #chatHeader { background: #008069; color: white; padding: 14px 20px; display: flex; align-items: center; gap: 12px; }
                    #chatHeader h2 { margin: 0; font-size: 18px; flex: 1; }
                    #manualPhone { padding: 8px 10px; font-size: 14px; border: none; border-radius: 4px; width: 220px; }
                    #refreshBtn { background: rgba(255,255,255,0.2); border: none; color: white; padding: 8px 12px; border-radius: 4px; cursor: pointer; }
                    #refreshBtn:hover { background: rgba(255,255,255,0.3); }
                    #messages { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 8px; }
                    .bubble { max-width: 70%; padding: 10px 14px; border-radius: 8px; font-size: 15px; line-height: 1.4; word-wrap: break-word; box-shadow: 0 1px 1px rgba(0,0,0,0.1); }
                    .received { align-self: flex-start; background: #ffffff; }
                    .sent { align-self: flex-end; background: #d9fdd3; }
                    .media-box { display: flex; flex-direction: column; gap: 8px; margin-bottom: 6px; }
                    .media-box img, .media-box video { max-width: 100%; max-height: 360px; border-radius: 6px; display: block; }
                    .meta { font-size: 11px; color: #667781; margin-top: 4px; text-align: right; }
                    .empty-hint { text-align: center; color: #667781; margin-top: 40px; }
                    #composer { background: #f0f2f5; padding: 12px 16px; border-top: 1px solid #ddd; display: flex; flex-direction: column; gap: 8px; }
                    .composer-row { display: flex; gap: 10px; align-items: center; }
                    textarea { flex: 1; height: 54px; padding: 10px; font-size: 15px; border: 1px solid #ccc; border-radius: 6px; resize: none; font-family: inherit; }
                    #sendBtn { padding: 14px 22px; background: #008069; color: white; border: none; font-size: 15px; cursor: pointer; border-radius: 6px; font-weight: bold; }
                    #sendBtn:hover { background: #006654; }
                    #status { margin: 0; font-size: 13px; color: #333; }
                </style>
            </head>
            <body>
                <div id="sidebar">
                    <input type="text" id="search" placeholder="Hae yhteystietoa tai numeroa..." onkeyup="filterContacts()">
                    <div id="contacts">Ladataan yhteystietoja...</div>
                </div>
                <div id="chat">
                    <div id="chatHeader">
                        <h2 id="recipientTitle">Valitse keskustelu, syötä numero tai anna nimi</h2>
                        <input type="text" id="manualPhone" placeholder="Nimi tai numero (+358...)" onchange="loadMessages(this.value)">
                        <button id="refreshBtn" onclick="loadMessages(document.getElementById('manualPhone').value)">Päivitä</button>
                    </div>
                    <div id="messages">
                        <div class="empty-hint">Valitse vastaanottaja vasemmalta nähdäksesi SMS- ja MMS-historian.</div>
                    </div>
                    <div id="composer">
                        <div class="composer-row">
                            <textarea id="msg" placeholder="Kirjoita vastaus tai uusi viesti..."></textarea>
                            <button id="sendBtn" onclick="send()">Lähetä</button>
                        </div>
                        <div class="composer-row">
                            <input type="file" id="attachment" accept="image/*,video/*" multiple>
                            <p id="status"></p>
                        </div>
                    </div>
                </div>
                <script>
                    let allContacts = [];
                    let activePhone = '';

                    async function loadContacts() {
                        const res = await fetch('/api/contacts');
                        allContacts = await res.json();
                        renderContacts(allContacts);
                    }

                    function renderContacts(list) {
                        const container = document.getElementById('contacts');
                        container.innerHTML = '';
                        list.forEach(c => {
                            const div = document.createElement('div');
                            div.className = 'contact' + (c.phone === activePhone ? ' active' : '');
                            div.innerHTML = '<div class="contact-name">' + escapeHtml(c.name) + '</div><div class="contact-phone">' + escapeHtml(c.phone) + '</div>';
                            div.onclick = () => {
                                activePhone = c.phone;
                                document.getElementById('recipientTitle').innerText = c.name;
                                document.getElementById('manualPhone').value = c.phone;
                                renderContacts(list);
                                loadMessages(c.phone);
                            };
                            container.appendChild(div);
                        });
                    }

                    function filterContacts() {
                        const q = document.getElementById('search').value.toLowerCase();
                        renderContacts(allContacts.filter(c => c.name.toLowerCase().includes(q) || c.phone.includes(q)));
                    }

                    async function loadMessages(phone) {
                        const cleaned = (phone || '').trim();
                        const box = document.getElementById('messages');
                        if (!cleaned) {
                            box.innerHTML = '<div class="empty-hint">Valitse vastaanottaja vasemmalta nähdäksesi viestihistorian.</div>';
                            return;
                        }
                        const res = await fetch('/api/messages?phone=' + encodeURIComponent(cleaned));
                        const msgs = await res.json();
                        box.innerHTML = '';
                        if (!msgs.length) {
                            box.innerHTML = '<div class="empty-hint">Ei viestejä numeron ' + escapeHtml(cleaned) + ' kanssa.</div>';
                            return;
                        }
                        msgs.forEach(m => {
                            const div = document.createElement('div');
                            div.className = 'bubble ' + (m.isSent ? 'sent' : 'received');

                            let mediaHtml = '';
                            if (Array.isArray(m.mediaParts) && m.mediaParts.length > 0) {
                                mediaHtml += '<div class="media-box">';
                                m.mediaParts.forEach(part => {
                                    const src = '/api/mms-part?partId=' + encodeURIComponent(part.partId);
                                    if (part.isVideo) {
                                        mediaHtml += '<video src="' + src + '" controls preload="metadata"></video>';
                                    } else {
                                        mediaHtml += '<img src="' + src + '" alt="MMS-liite" loading="lazy">';
                                    }
                                });
                                mediaHtml += '</div>';
                            }

                            const textHtml = m.body
                                ? '<div>' + escapeHtml(m.body).replace(/\n/g, '<br>') + '</div>'
                                : '';
                            const timeStr = new Date(m.date).toLocaleString('fi-FI');
                            const typeLabel = m.isMms ? 'MMS · ' : '';

                            div.innerHTML = mediaHtml + textHtml + '<div class="meta">' + typeLabel + timeStr + '</div>';
                            box.appendChild(div);
                        });
                        box.scrollTop = box.scrollHeight;
                    }

                    function escapeHtml(text) {
                        const div = document.createElement('div');
                        div.innerText = text || '';
                        return div.innerHTML;
                    }

                    async function send() {
                        const fileInput = document.getElementById('attachment');
                        const files = Array.from(fileInput.files || []);
                        const phone = document.getElementById('manualPhone').value.trim();
                        const message = document.getElementById('msg').value.trim();
                        const status = document.getElementById('status');

                        if (!phone) {
                            alert('Valitse vastaanottaja tai syötä puhelinnumero.');
                            return;
                        }
                        if (!message && files.length === 0) {
                            alert('Kirjoita viesti tai valitse liite.');
                            return;
                        }

                        status.innerText = 'Lähetetään...';

                        let res;
                        if (files.length === 0) {
                            res = await fetch('/api/send-text', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json; charset=utf-8' },
                                body: JSON.stringify({ phone: phone, message: message })
                            });
                        } else {
                            const formData = new FormData();
                            formData.append("phone", phone);
                            formData.append("message", message);
                            files.forEach((file, idx) => {
                                formData.append("attachment_" + idx, file);
                                formData.append("fileName_" + idx, file.name);
                            });
                            res = await fetch('/api/send', {
                                method: 'POST',
                                body: formData
                            });
                        }

                        const data = await res.json();

                        if (data.success) {
                            const targetPhone = data.resolvedPhone || phone;
                            if (data.resolvedPhone) {
                                document.getElementById('manualPhone').value = data.resolvedPhone;
                            }
                            status.innerText = files.length > 0
                                ? 'Viestisovellus avattu (' + files.length + ' liitettä)!'
                                : 'Tekstiviesti lähetetty (' + targetPhone + ')!';
                            document.getElementById('msg').value = '';
                            fileInput.value = '';
                            setTimeout(() => loadMessages(targetPhone), 1500);
                        } else {
                            status.innerText = 'Virhe: ' + (data.error || 'Tuntematon virhe');
                        }
                    }

                    setInterval(() => {
                        const current = document.getElementById('manualPhone').value.trim();
                        if (current) loadMessages(current);
                    }, 8000);

                    loadContacts();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stop()
        releaseLocks()
    }
}