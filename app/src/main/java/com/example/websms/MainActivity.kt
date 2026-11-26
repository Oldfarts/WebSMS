package com.example.websms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.Locale

data class ContactItem(val name: String, val phone: String)
data class ApiResponse(val success: Boolean, val error: String? = null)

class MainActivity : AppCompatActivity() {

    private lateinit var tvServerAddress: TextView
    private lateinit var tvLog: TextView
    private var webServer: SmsWebServer? = null
    private val permissionCode = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvServerAddress = findViewById(R.id.tvServerAddress)
        tvLog = findViewById(R.id.tvLog)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startWebServer()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionCode && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startWebServer()
        } else {
            tvLog.text = "Sovellus tarvitsee luvan yhteystietoihin ja tekstiviesteihin."
        }
    }

    private fun startWebServer() {
        try {
            val ip = getWifiIpAddress()
            val port = 8080

            webServer?.stop()
            webServer = SmsWebServer(port)
            webServer?.start()

            tvServerAddress.text = "http://$ip:$port"
            tvLog.text = "Palvelin käynnissä!"
        } catch (e: Exception) {
            tvLog.text = "Virhe: ${e.message}"
            e.printStackTrace()
        }
    }

    private fun getWifiIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ipInt = wifiManager.connectionInfo.ipAddress
        return String.format(
            Locale.US, "%d.%d.%d.%d",
            ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
        )
    }

    // Hakee nimet ja puhelinnumerot puhelimen osoitekirjasta
    private fun getPhoneContacts(): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: "Tuntematon"
                val number = it.getString(numIdx)?.replace("\\s+".toRegex(), "") ?: ""
                if (number.isNotEmpty()) {
                    list.add(ContactItem(name, number))
                }
            }
        }
        return list.distinctBy { it.phone }
    }

    // Lähettää SMS:n automaattisesti puhelimen SIM-kortilla (ilman lisäpainalluksia)
    private fun sendSms(phone: String, message: String) {
        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(phone, null, parts, null, null)

        runOnUiThread {
            tvLog.text = "SMS lähetetty automaattisesti: $phone"
        }
    }

    private fun saveUploadedFile(tempFilePath: String, fileName: String): File {
        val uploadDir = File(filesDir, "attachments")
        if (!uploadDir.exists()) {
            uploadDir.mkdirs()
        }
        val destination = File(uploadDir, fileName)
        File(tempFilePath).copyTo(destination, overwrite = true)
        return destination
    }

    private fun saveImage(tempFilePath: String, fileName: String): File {
        val picturesDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: filesDir
        val webSmsDir = File(picturesDir, "WebSMS")
        if (!webSmsDir.exists()) {
            webSmsDir.mkdirs()
        }
        val destination = File(webSmsDir, fileName)
        File(tempFilePath).copyTo(destination, overwrite = true)
        return destination
    }

    private fun saveVideo(tempFilePath: String, fileName: String): File {
        val moviesDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
        val webSmsDir = File(moviesDir, "WebSMS")
        if (!webSmsDir.exists()) {
            webSmsDir.mkdirs()
        }
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

    // Avaa oletusviestisovelluksen yhdelle tai useammalle liitteelle
    // Avaa oletusviestisovelluksen yhdelle tai useammalle liitteelle
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

            runOnUiThread {
                tvLog.text = "MMS-liitteitä (${files.size} kpl):\n" +
                        "TYPE=$intentType\n" +
                        uris.joinToString("\n") { it.toString() }
            }

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
                if (phone.isNotBlank()) {
                    putExtra("address", phone)
                }
                if (message.isNotBlank()) {
                    putExtra("sms_body", message)
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && uris.isNotEmpty()) {
                    clipData = android.content.ClipData.newUri(contentResolver, "MMS-liite", uris.first()).apply {
                        for (i in 1 until uris.size) {
                            addItem(android.content.ClipData.Item(uris[i]))
                        }
                    }
                }
                if (!defaultSmsPackage.isNullOrBlank()) {
                    setPackage(defaultSmsPackage)
                }
            }

            startActivity(intent)
        } catch (e: Exception) {
            runOnUiThread {
                tvLog.text = "MMS VIRHE\n\n${e.stackTraceToString()}"
            }
        }
    }

    // Sisäinen Web-palvelin, joka palvelee selainta
    inner class SmsWebServer(port: Int) : NanoHTTPD(port) {
        private val gson = Gson()

        override fun serve(session: IHTTPSession): Response {
            return when {
                // 1. Palauttaa yhteystiedot JSON-muodossa
                session.uri == "/api/contacts" && session.method == Method.GET -> {
                    val json = gson.toJson(getPhoneContacts())
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
                }

                // 2. Vastaanottaa viestin (ja 0..N liitettä) selaimelta
                session.uri == "/api/send" && session.method == Method.POST -> {
                    val filesMap = HashMap<String, String>()
                    session.parseBody(filesMap)

                    val phone = session.parameters["phone"]?.firstOrNull()?.trim().orEmpty()
                    val message = session.parameters["message"]?.firstOrNull()?.trim().orEmpty()
                    val batchTimestamp = System.currentTimeMillis()

                    val savedFiles = mutableListOf<File>()

                    // Kerää kaikki kentät, joiden avain alkaa "attachment_" (sekä mahdollinen yksittäinen "attachment")
                    val attachmentKeys = filesMap.keys
                        .filter { it == "attachment" || it.startsWith("attachment_") }
                        .sorted()

                    attachmentKeys.forEachIndexed { index, key ->
                        val tempPath = filesMap[key] ?: return@forEachIndexed
                        val tempFile = File(tempPath)
                        if (tempFile.exists() && tempFile.length() > 0) {
                            val nameKey = if (key == "attachment") "fileName" else "fileName_${key.removePrefix("attachment_")}"
                            val originalFileName = session.parameters[nameKey]
                                ?.firstOrNull()
                                ?.substringAfterLast("/")
                                ?.substringAfterLast("\\")
                                .orEmpty()

                            val rawExt = originalFileName.substringAfterLast(".", "")
                                .lowercase(Locale.ROOT)
                            val extension = if (rawExt.isNotBlank()) rawExt else "jpg"
                            val safeName = "upload_${batchTimestamp}_${index}.$extension"

                            val saved = when (extension) {
                                "jpg", "jpeg", "png", "gif", "webp" -> saveImage(tempPath, safeName)
                                "mp4", "3gp", "mkv", "avi", "webm" -> saveVideo(tempPath, safeName)
                                else -> saveUploadedFile(tempPath, safeName)
                            }
                            savedFiles.add(saved)
                        }
                    }

                    return try {
                        if (savedFiles.isNotEmpty()) {
                            runOnUiThread {
                                openMmsComposer(phone, message, savedFiles)
                            }
                        } else {
                            sendSms(phone, message)
                        }

                        val okJson = gson.toJson(ApiResponse(success = true))
                        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", okJson)
                    } catch (e: Exception) {
                        val errJson = gson.toJson(ApiResponse(success = false, error = e.message ?: "Tuntematon virhe"))
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", errJson)
                    }
                }

                // 3. Tarjoaa selaimelle WhatsApp Web -tyylisen käyttöliittymän
                else -> {
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", getWebHtml())
                }
            }
        }

        private fun getWebHtml(): String = """
            <!DOCTYPE html>
            <html lang="fi">
            <head>
                <meta charset="UTF-8">
                <title>Web SMS</title>
                <style>
                    body { font-family: Segoe UI, sans-serif; display: flex; height: 100vh; margin: 0; background: #f0f2f5; }
                    #sidebar { width: 320px; background: white; border-right: 1px solid #ddd; display: flex; flex-direction: column; }
                    #search { padding: 12px; border: none; border-bottom: 1px solid #ddd; font-size: 15px; outline: none; }
                    #contacts { overflow-y: auto; flex: 1; }
                    .contact { padding: 12px 16px; border-bottom: 1px solid #f0f0f0; cursor: pointer; }
                    .contact:hover, .contact.active { background: #e9f5ff; }
                    .contact-name { font-weight: bold; }
                    .contact-phone { font-size: 13px; color: #666; }
                    #chat { flex: 1; display: flex; flex-direction: column; padding: 24px; justify-content: center; max-width: 600px; margin: auto; }
                    textarea { width: 100%; height: 140px; padding: 12px; font-size: 16px; margin: 12px 0; box-sizing: border-box; }
                    button { padding: 12px 24px; background: #008069; color: white; border: none; font-size: 16px; cursor: pointer; border-radius: 4px; }
                    button:hover { background: #006654; }
                </style>
            </head>
            <body>
                <div id="sidebar">
                    <input type="text" id="search" placeholder="Hae yhteystietoa..." onkeyup="filterContacts()">
                    <div id="contacts">Ladataan yhteystietoja...</div>
                </div>
                <div id="chat">
                    <h2 id="recipientTitle">Valitse vastaanottaja vasemmalta</h2>
                    <input type="text" id="manualPhone"
                           placeholder="Tai kirjoita numero käsin (+358...)"
                           style="padding:10px; font-size:15px;">

                    <textarea id="msg"
                              placeholder="Kirjoita tekstiviesti..."></textarea>

                    <h3>Lisää kuvia tai videoita viestiin?</h3>
                    <input type="file"
                           id="attachment"
                           accept="image/*,video/*"
                           multiple
                           style="margin:12px 0;">

                    <button onclick="send()">Lähetä viesti</button>

                    <p id="status"></p>
                </div>
                <script>
                    let allContacts = [];
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
                            div.className = 'contact';
                            div.innerHTML = '<div class="contact-name">' + c.name + '</div><div class="contact-phone">' + c.phone + '</div>';
                            div.onclick = () => {
                                document.getElementById('recipientTitle').innerText = 'Vastaanottaja: ' + c.name;
                                document.getElementById('manualPhone').value = c.phone;
                            };
                            container.appendChild(div);
                        });
                    }
                    function filterContacts() {
                        const q = document.getElementById('search').value.toLowerCase();
                        renderContacts(allContacts.filter(c => c.name.toLowerCase().includes(q) || c.phone.includes(q)));
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

                        const formData = new FormData();
                        formData.append("phone", phone);
                        formData.append("message", message);
                        files.forEach((file, idx) => {
                            formData.append("attachment_" + idx, file);
                            formData.append("fileName_" + idx, file.name);
                        });

                        status.innerText = 'Lähetetään...';
                        const res = await fetch('/api/send', {
                            method: 'POST',
                            body: formData
                        });
                        const data = await res.json();

                        if (data.success) {
                            status.innerText = files.length > 0
                                ? 'Viestisovellus avattu (' + files.length + ' liitettä)!'
                                : 'Tekstiviesti lähetetty automaattisesti!';
                            document.getElementById('msg').value = '';
                            fileInput.value = '';
                        } else {
                            status.innerText = 'Virhe: ' + (data.error || 'Tuntematon virhe');
                        }
                    }
                    loadContacts();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stop()
    }
}