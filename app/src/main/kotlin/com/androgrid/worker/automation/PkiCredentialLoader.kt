package com.androgrid.worker.automation

import android.content.Context
import com.androgrid.worker.network.ClusterSettingsRepository
import java.io.File

/**
 * Utility discovering and loading Mutual TLS X.509 certificate materials from local filesystem.
 * Manages the canonical app-private credentials directory (/data/user/0/com.androgrid.worker/files/pki/).
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
object PkiCredentialLoader {

    const val CANONICAL_PKI_SUBDIR: String = "pki"

    private val CA_CERT_FILENAMES = listOf("ca.crt", "ca.pem")
    private val CLIENT_CERT_FILENAMES = listOf("client.crt", "client.pem")
    private val CLIENT_KEY_FILENAMES = listOf("client.key", "client.key.pem", "client.pk8")

    /**
     * Resolves the canonical app-private PKI directory.
     *
     * @param context Application context.
     * @return File handle pointing to files/pki directory.
     */
    fun getCanonicalPkiDirectory(context: Context): File {
        return File(context.filesDir, CANONICAL_PKI_SUBDIR)
    }

    /**
     * Discovers certificate files inside target directory and updates repository.
     *
     * @param directory Target directory to scan.
     * @param repository Target settings repository to update.
     * @return true if CA, client certificate, and private key were all loaded.
     */
    fun loadFromDirectory(directory: File, repository: ClusterSettingsRepository): Boolean {
        if (!directory.exists() || !directory.isDirectory) {
            return false
        }

        val caFile = CA_CERT_FILENAMES.map { File(directory, it) }.firstOrNull { it.exists() && it.isFile }
        val certFile = CLIENT_CERT_FILENAMES.map { File(directory, it) }.firstOrNull { it.exists() && it.isFile }
        val keyFile = CLIENT_KEY_FILENAMES.map { File(directory, it) }.firstOrNull { it.exists() && it.isFile }

        if (caFile == null || certFile == null || keyFile == null) {
            return false
        }

        return try {
            val caPem = caFile.readText(Charsets.UTF_8).trim()
            val certPem = certFile.readText(Charsets.UTF_8).trim()
            val keyPem = keyFile.readText(Charsets.UTF_8).trim()

            if (caPem.isNotBlank() && certPem.isNotBlank() && keyPem.isNotBlank()) {
                repository.updateCertificates(caPem, certPem, keyPem)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ingests certificate files from explicit file paths.
     *
     * @param caPath Path to root CA PEM file.
     * @param certPath Path to client certificate PEM file.
     * @param keyPath Path to client private key PEM file.
     * @param repository Target settings repository to update.
     * @return true if all three files were loaded.
     */
    fun loadFromPaths(
        caPath: String?,
        certPath: String?,
        keyPath: String?,
        repository: ClusterSettingsRepository
    ): Boolean {
        if (caPath.isNullOrBlank() || certPath.isNullOrBlank() || keyPath.isNullOrBlank()) {
            return false
        }

        val caFile = File(caPath)
        val certFile = File(certPath)
        val keyFile = File(keyPath)

        if (!caFile.exists() || !certFile.exists() || !keyFile.exists()) {
            return false
        }

        return try {
            val caPem = caFile.readText(Charsets.UTF_8).trim()
            val certPem = certFile.readText(Charsets.UTF_8).trim()
            val keyPem = keyFile.readText(Charsets.UTF_8).trim()

            if (caPem.isNotBlank() && certPem.isNotBlank() && keyPem.isNotBlank()) {
                repository.updateCertificates(caPem, certPem, keyPem)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Inspects the canonical app-private PKI directory and reloads certificates if present.
     *
     * @param context Application context.
     * @param repository Target settings repository.
     * @return true if valid credentials were found and loaded.
     */
    fun syncCanonicalDirectoryIfPresent(context: Context, repository: ClusterSettingsRepository): Boolean {
        val canonicalDir = getCanonicalPkiDirectory(context)
        return loadFromDirectory(canonicalDir, repository)
    }
}
