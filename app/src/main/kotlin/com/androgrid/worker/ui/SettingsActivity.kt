package com.androgrid.worker.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.androgrid.worker.enrollment.EnrollmentCoordinator
import com.androgrid.worker.enrollment.EnrollmentPayloadParser
import com.androgrid.worker.enrollment.EnrollmentResult
import com.androgrid.worker.network.ClusterSettings
import com.androgrid.worker.network.ClusterSettingsRepository
import com.androgrid.worker.network.DefaultMtlsSocketFactoryProvider
import com.androgrid.worker.network.MtlsSocketFactoryProvider
import com.androgrid.worker.network.Pkcs12ImportResult
import com.androgrid.worker.network.Pkcs12Importer
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import kotlinx.coroutines.launch
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Configuration screen for operator endpoint definitions and mTLS cryptographic enrollment.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001], [REQ-OP-003]
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    internal lateinit var repository: ClusterSettingsRepository
    internal var mtlsProvider: MtlsSocketFactoryProvider = DefaultMtlsSocketFactoryProvider

    private lateinit var wifiHostInput: EditText
    private lateinit var wifiPortInput: EditText
    private lateinit var cellularHostInput: EditText
    private lateinit var cellularPortInput: EditText

    private lateinit var caCertInput: EditText
    private lateinit var clientCertInput: EditText
    private lateinit var clientKeyInput: EditText

    private lateinit var validationBanner: TextView
    internal lateinit var p12StatusBanner: TextView
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button
    private lateinit var importP12Button: Button
    private lateinit var scanQrButton: Button

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val payload = result.data?.getStringExtra(QrScannerActivity.EXTRA_SCANNED_PAYLOAD)
            if (!payload.isNullOrBlank()) {
                handleScannedQrPayload(payload)
            }
        }
    }

    private val p12FileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleP12Import(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!::repository.isInitialized) {
            repository = SharedPreferencesClusterSettingsRepository.getInstance(applicationContext)
        }

        setupContentView()
        loadCurrentSettings()
    }

    private fun setupContentView() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val titleView = TextView(this).apply {
            text = "Cluster Network & mTLS Settings"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rootLayout.addView(titleView)

        validationBanner = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#263238"))
        }
        val bannerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 24, 0, 24)
        }
        rootLayout.addView(validationBanner, bannerParams)

        // Section 1: Wi-Fi LAN
        rootLayout.addView(createSectionHeader("Local Wi-Fi Orchestrator (mTLS 1.3)"))
        wifiHostInput = createTextInput("192.168.1.100", InputType.TYPE_CLASS_TEXT)
        wifiPortInput = createTextInput("50051", InputType.TYPE_CLASS_NUMBER)
        rootLayout.addView(createFieldLabel("Host / IP:"))
        rootLayout.addView(wifiHostInput)
        rootLayout.addView(createFieldLabel("Port:"))
        rootLayout.addView(wifiPortInput)

        // Section 2: Cellular WAN
        rootLayout.addView(createSectionHeader("Cellular WAN Anycast Ingress"))
        cellularHostInput = createTextInput("grpc.cluster.domain.com", InputType.TYPE_CLASS_TEXT)
        cellularPortInput = createTextInput("443", InputType.TYPE_CLASS_NUMBER)
        rootLayout.addView(createFieldLabel("Ingress Domain:"))
        rootLayout.addView(cellularHostInput)
        rootLayout.addView(createFieldLabel("Ingress Port:"))
        rootLayout.addView(cellularPortInput)

        // Section 3: Mutual TLS Cryptographic Material
        rootLayout.addView(createSectionHeader("Mutual TLS Cryptographic Identity"))

        scanQrButton = Button(this).apply {
            text = "Scan Enrollment QR Code"
            setBackgroundColor(Color.parseColor("#1B5E20"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@SettingsActivity, QrScannerActivity::class.java)
                qrScanLauncher.launch(intent)
            }
        }
        val scanParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 16)
        }
        rootLayout.addView(scanQrButton, scanParams)

        importP12Button = Button(this).apply {
            text = "Import PKCS#12 (.p12) KeyStore"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                p12FileLauncher.launch("*/*")
            }
        }
        rootLayout.addView(importP12Button)

        p12StatusBanner = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
            visibility = View.GONE
        }
        val p12BannerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 12, 0, 12)
        }
        rootLayout.addView(p12StatusBanner, p12BannerParams)

        rootLayout.addView(createFieldLabel("Root CA Certificate (PEM):"))
        caCertInput = createMultiLineInput()
        rootLayout.addView(caCertInput)

        rootLayout.addView(createFieldLabel("Client Certificate Chain (PEM):"))
        clientCertInput = createMultiLineInput()
        rootLayout.addView(clientCertInput)

        rootLayout.addView(createFieldLabel("Client Private Key (PKCS#8 PEM):"))
        clientKeyInput = createMultiLineInput()
        rootLayout.addView(clientKeyInput)

        // Action Buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 16)
        }

        saveButton = Button(this).apply {
            text = "Save Settings"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener { saveSettings() }
        }
        val saveParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
            setMargins(0, 0, 16, 0)
        }
        buttonRow.addView(saveButton, saveParams)

        resetButton = Button(this).apply {
            text = "Reset Defaults"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            setOnClickListener { resetSettings() }
        }
        val resetParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        buttonRow.addView(resetButton, resetParams)

        rootLayout.addView(buttonRow)

        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
        }
        setContentView(scrollView)
    }

    private fun loadCurrentSettings() {
        val settings = repository.getSettings()
        wifiHostInput.setText(settings.wifiHost)
        wifiPortInput.setText(settings.wifiPort.toString())
        cellularHostInput.setText(settings.cellularHost)
        cellularPortInput.setText(settings.cellularPort.toString())
        caCertInput.setText(settings.caCertPem)
        clientCertInput.setText(settings.clientCertPem)
        clientKeyInput.setText(settings.clientKeyPem)

        updateValidationStatus(settings)
    }

    private fun saveSettings() {
        val wifiHost = wifiHostInput.text.toString().trim()
        val wifiPort = wifiPortInput.text.toString().toIntOrNull() ?: ClusterSettings.DEFAULT_WIFI_PORT
        val cellularHost = cellularHostInput.text.toString().trim()
        val cellularPort = cellularPortInput.text.toString().toIntOrNull() ?: ClusterSettings.DEFAULT_CELLULAR_PORT

        val caCert = caCertInput.text.toString().trim()
        val clientCert = clientCertInput.text.toString().trim()
        val clientKey = clientKeyInput.text.toString().trim()

        if (wifiHost.isBlank()) {
            Toast.makeText(this, "Wi-Fi host cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val updated = ClusterSettings(
            wifiHost = wifiHost,
            wifiPort = wifiPort,
            cellularHost = cellularHost,
            cellularPort = cellularPort,
            caCertPem = caCert,
            clientCertPem = clientCert,
            clientKeyPem = clientKey
        )

        repository.updateSettings(updated)
        updateValidationStatus(updated)
        Toast.makeText(this, "Cluster settings persisted successfully", Toast.LENGTH_SHORT).show()
    }

    private fun resetSettings() {
        repository.resetDefaults()
        loadCurrentSettings()
        Toast.makeText(this, "Cluster settings restored to defaults", Toast.LENGTH_SHORT).show()
    }

    internal fun handleP12Import(uri: Uri, password: CharArray = charArrayOf()) {
        try {
            val stream = contentResolver.openInputStream(uri)
            if (stream == null) {
                val msg = "Unable to open PKCS#12 file stream for URI: $uri"
                Log.w(TAG, msg)
                showP12Status(msg, isError = true)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return
            }

            val bytes = stream.use { it.readBytes() }
            when (val result = Pkcs12Importer.import(bytes, password)) {
                is Pkcs12ImportResult.Success -> {
                    clientCertInput.setText(result.credentials.clientCertPem)
                    clientKeyInput.setText(result.credentials.clientKeyPem)
                    if (!result.credentials.caCertPem.isNullOrBlank() && caCertInput.text.isNullOrBlank()) {
                        caCertInput.setText(result.credentials.caCertPem)
                    }
                    val msg = "PKCS#12 credentials extracted successfully"
                    showP12Status(msg, isError = false)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                is Pkcs12ImportResult.PasswordRequired -> {
                    Log.w(TAG, "PKCS#12 bundle requires passphrase: ${result.reason}")
                    if (password.isEmpty()) {
                        promptForP12Password(uri)
                    } else {
                        val msg = "Incorrect PKCS#12 passphrase: ${result.reason}"
                        showP12Status(msg, isError = true)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
                is Pkcs12ImportResult.Failure -> {
                    Log.e(TAG, result.userMessage, result.cause)
                    showP12Status(result.userMessage, isError = true)
                    Toast.makeText(this, result.userMessage, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            val msg = "Failed to read PKCS#12 bundle: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, msg, e)
            showP12Status(msg, isError = true)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptForP12Password(uri: Uri) {
        val passwordInput = EditText(this).apply {
            hint = "PKCS#12 passphrase"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("PKCS#12 Passphrase Required")
            .setMessage("Enter the passphrase for this .p12 bundle (leave empty if none).")
            .setView(passwordInput)
            .setPositiveButton("Import") { _, _ ->
                handleP12Import(uri, passwordInput.text.toString().toCharArray())
            }
            .setNegativeButton("Cancel") { _, _ ->
                showP12Status("PKCS#12 import cancelled (passphrase required).", isError = true)
            }
            .show()
    }

    private fun showP12Status(message: String, isError: Boolean) {
        p12StatusBanner.text = message
        p12StatusBanner.setBackgroundColor(
            if (isError) Color.parseColor("#B71C1C") else Color.parseColor("#1B5E20")
        )
        p12StatusBanner.visibility = View.VISIBLE
    }

    internal fun handleScannedQrPayload(rawContent: String) {
        lifecycleScope.launch {
            try {
                val payload = EnrollmentPayloadParser.parse(rawContent)
                val coordinator = EnrollmentCoordinator(
                    settingsRepository = repository
                )
                val defaultNodeId = "worker_${android.os.Build.MODEL.replace(" ", "_")}"
                val result = coordinator.executeEnrollment(payload, defaultNodeId)

                when (result) {
                    is EnrollmentResult.Success -> {
                        loadCurrentSettings()
                        Toast.makeText(this@SettingsActivity, "Device enrolled successfully via QR code!", Toast.LENGTH_LONG).show()
                    }
                    is EnrollmentResult.Failure -> {
                        Toast.makeText(this@SettingsActivity, "Enrollment failed: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Invalid QR payload: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateValidationStatus(settings: ClusterSettings) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val statusBuilder = StringBuilder()

        if (settings.caCertPem.isNotBlank()) {
            try {
                val caInfo = mtlsProvider.inspectCertificate(settings.caCertPem)
                val validity = if (caInfo.isValidNow) "VALID" else "EXPIRED"
                statusBuilder.append("Root CA: [$validity] Issuer: ${caInfo.issuer}\n")
            } catch (_: Exception) {
                statusBuilder.append("Root CA: [CORRUPT / INVALID PEM]\n")
            }
        } else {
            statusBuilder.append("Root CA: [NOT CONFIGURED]\n")
        }

        if (settings.clientCertPem.isNotBlank()) {
            try {
                val clientInfo = mtlsProvider.inspectCertificate(settings.clientCertPem)
                val validity = if (clientInfo.isValidNow) "VALID" else "EXPIRED"
                statusBuilder.append("Client Cert: [$validity] Expires: ${dateFormat.format(clientInfo.notAfter)} (${clientInfo.subject})\n")
            } catch (_: Exception) {
                statusBuilder.append("Client Cert: [CORRUPT / INVALID PEM]\n")
            }
        } else {
            statusBuilder.append("Client Cert: [NOT CONFIGURED]\n")
        }

        if (settings.clientKeyPem.isNotBlank()) {
            statusBuilder.append("Private Key: [LOADED]")
        } else {
            statusBuilder.append("Private Key: [NOT CONFIGURED]")
        }

        validationBanner.text = statusBuilder.toString()
        if (settings.hasMtlsCredentials) {
            validationBanner.setBackgroundColor(Color.parseColor("#1B5E20")) // Green
        } else {
            validationBanner.setBackgroundColor(Color.parseColor("#37474F")) // Slate Gray
        }
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#81C784"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 24, 0, 8)
        }
    }

    private fun createFieldLabel(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 8, 0, 4)
        }
    }

    private fun createTextInput(hint: String, inputType: Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 20, 24, 20)
        }
    }

    private fun createMultiLineInput(): EditText {
        return EditText(this).apply {
            hint = "-----BEGIN ...-----"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(20, 16, 20, 16)
        }
    }
}
