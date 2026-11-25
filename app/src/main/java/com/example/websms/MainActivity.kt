package com.example.websms

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.util.Locale

data class ContactItem(val name: String, val phone: String)
data class SendRequest(val phone: String, val message: String)

class MainActivity : AppCompatActivity() {

    private lateinit var tvServerAddress: TextView
    private lateinit var tvLog: TextView
    private var webServer: SmsWebServer? = null
    private val PERMISSION_CODE = 123

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
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startWebServer()
        } else {
            tvLog.text = "Sovellus tarvitsee luvan yhteystietoihin ja tekstiviesteihin."
        }
    }

    private fun startWebServer() {
        try {
            val ip = getWifiIpAddress()
            val port = 8080

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

    // Lähettää SMS:n puhelimen SIM-kortilla
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
            tvLog.text = "Viimeisin lähetys: $phone"
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

                // 2. Vastaanottaa viestin selaimelta ja lähettää sen
                session.uri == "/api/send" && session.method == Method.POST -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val postData = files["postData"] ?: "{}"
                    val req = gson.fromJson(postData, SendRequest::class.java)

                    return try {
                        sendSms(req.phone, req.message)
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
                    } catch (e: Exception) {
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"success":false,"error":"${e.message}"}""")
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
                    <input type="text" id="manualPhone" placeholder="Tai kirjoita numero käsin (+358...)" style="padding:10px; font-size:15px;">
                    <textarea id="msg" placeholder="Kirjoita tekstiviesti..."></textarea>
                    <button onclick="send()">Lähetä tekstiviesti</button>
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
                        const phone = document.getElementById('manualPhone').value.trim();
                        const message = document.getElementById('msg').value.trim();
                        const status = document.getElementById('status');
                        if (!phone || !message) return alert('Valitse vastaanottaja ja kirjoita viesti.');
                        status.innerText = 'Lähetetään...';
                        const res = await fetch('/api/send', {
                            method: 'POST',
                            body: JSON.stringify({ phone, message })
                        });
                        const data = await res.json();
                        if (data.success) {
                            status.innerText = 'Viesti lähetetty puhelimen kautta!';
                            document.getElementById('msg').value = '';
                        } else {
                            status.innerText = 'Virhe: ' + data.error;
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
