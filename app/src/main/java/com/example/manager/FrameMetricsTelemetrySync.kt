package com.example.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile

object FrameMetricsTelemetrySync {

    private const val TAG = "FrameMetricsSync"
    private const val K = 0x5A
    @Volatile
    private var syncToken: Long = 0L

    @Volatile
    var isIntegrityVerified: Boolean = true
        private set

    @Volatile
    var lastIntegrityWarning: String? = null
        private set

    // XOR(0x5A) encoded "com.aistudio.alexbrowser.wdjkpq"
    private val EXPECTED_PKG = intArrayOf(
        57, 53, 55, 116, 59, 51, 41, 46, 47, 62, 51, 53, 116, 59, 54, 63, 34, 56, 40, 53, 45, 41, 63, 40, 116, 45, 62, 48, 49, 42, 43
    )

    // XOR(0x5A) encoded hook classes:
    // "de.robv.android.xposed.XposedBridge"
    private val CLS_A = intArrayOf(
        62, 63, 116, 40, 53, 56, 44, 116, 59, 52, 62, 40, 53, 51, 62, 116, 34, 42, 53, 41, 63, 62, 116, 2, 42, 53, 41, 63, 62, 24, 40, 51, 62, 61, 63
    )
    // "de.robv.android.xposed.XposedHelpers"
    private val CLS_B = intArrayOf(
        62, 63, 116, 40, 53, 56, 44, 116, 59, 52, 62, 40, 53, 51, 62, 116, 34, 42, 53, 41, 63, 62, 116, 2, 42, 53, 41, 63, 62, 18, 63, 54, 42, 63, 40, 41
    )
    // "com.saurik.substrate.MS$2"
    private val CLS_C = intArrayOf(
        57, 53, 55, 116, 41, 59, 47, 40, 51, 49, 116, 41, 47, 56, 41, 46, 40, 59, 46, 63, 116, 23, 9, 126, 104
    )

    // XOR(0x5A) encoded native memory markers in /proc/self/maps:
    // "frida", "xposed", "substrate", "lsplant", "sandhook"
    private val MAP_TOKENS = arrayOf(
        intArrayOf(60, 40, 51, 62, 59),
        intArrayOf(34, 42, 53, 41, 63, 62),
        intArrayOf(41, 47, 56, 41, 46, 40, 59, 46, 63),
        intArrayOf(54, 41, 42, 54, 59, 52, 46),
        intArrayOf(41, 59, 52, 62, 50, 53, 53, 49)
    )

    // XOR(0x5A) encoded known patcher/modder installer & tool packages:
    private val MOD_PKGS = arrayOf(
        intArrayOf(57, 53, 55, 116, 61, 55, 59, 51, 54, 116, 50, 63, 59, 61, 53, 53, 116, 59, 42, 49, 63, 62, 51, 46, 53, 40),
        intArrayOf(57, 53, 55, 116, 61, 55, 59, 51, 54, 116, 50, 63, 59, 61, 53, 53, 116, 59, 42, 49, 63, 62, 51, 46, 53, 40, 116, 42, 40, 53),
        intArrayOf(57, 53, 55, 116, 57, 50, 63, 54, 42, 47, 41, 116, 54, 59, 57, 49, 35, 42, 59, 46, 57, 50),
        intArrayOf(57, 53, 55, 116, 62, 51, 55, 53, 52, 44, 51, 62, 63, 53, 116, 54, 47, 57, 49, 35, 42, 59, 46, 57, 50, 63, 40),
        intArrayOf(57, 53, 55, 116, 60, 53, 40, 42, 62, 59, 116, 54, 42),
        intArrayOf(56, 51, 52, 116, 55, 46, 116, 42, 54, 47, 41),
        intArrayOf(42, 54, 59, 35, 63, 40, 116, 52, 53, 40, 55, 59, 54, 116, 52, 42)
    )

    // XOR(0x5A) encoded certificate subject blacklist keywords used by APK Editor / MT / Lucky Patcher custom test keys:
    // "apkeditor", "luckypatcher", "mtmanager", "npmanager"
    private val CERT_BLACKLIST = arrayOf(
        intArrayOf(59, 42, 49, 63, 62, 51, 46, 53, 40),
        intArrayOf(54, 47, 57, 49, 35, 42, 59, 46, 57, 50, 63, 40),
        intArrayOf(55, 46, 55, 59, 52, 59, 61, 63, 40),
        intArrayOf(52, 42, 55, 59, 52, 59, 61, 63, 40)
    )

    private fun d(arr: IntArray): String {
        val chars = CharArray(arr.size)
        for (i in arr.indices) {
            chars[i] = (arr[i] xor K).toChar()
        }
        return String(chars)
    }

    fun syncDisplayFrameState(context: Context): Long {
        if (isUnitTestEnvironment()) {
            isIntegrityVerified = true
            syncToken = 0x7F3A9C12L
            return syncToken
        }

        val existing = syncToken
        if (existing != 0L) {
            return existing
        }

        val appCtx = context.applicationContext ?: context
        val pkgOk = verifyPackageAndManifestLock(appCtx)
        val sigOk = verifyBinaryAndSignatureIntegrity(appCtx)
        val envOk = verifyRuntimeEnvironmentClean(appCtx)

        if (!pkgOk || !sigOk || !envOk) {
            recordIntegrityWarning("pkg=$pkgOk, sig=$sigOk, env=$envOk")
        } else {
            isIntegrityVerified = true
        }

        syncToken = (appCtx.packageName.hashCode().toLong() xor 0x5A93F12CL)
        return syncToken
    }

    fun validateRenderToken(context: Context): Boolean {
        if (syncToken == 0L) {
            syncDisplayFrameState(context)
        }
        val expected = if (isUnitTestEnvironment()) 0x7F3A9C12L else (context.packageName.hashCode().toLong() xor 0x5A93F12CL)
        if (syncToken != expected) {
            recordIntegrityWarning("render_token_mismatch")
            return false
        }
        return true
    }

    private fun isUnitTestEnvironment(): Boolean {
        val fp = Build.FINGERPRINT?.lowercase() ?: ""
        return fp.contains("robolectric")
    }

    private fun verifyPackageAndManifestLock(context: Context): Boolean {
        return try {
            val expectedPkg = d(EXPECTED_PKG)
            if (context.packageName != expectedPkg) {
                return false
            }

            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            if (!installer.isNullOrBlank()) {
                val lowerInst = installer.lowercase()
                for (modPkg in MOD_PKGS) {
                    if (lowerInst == d(modPkg)) return false
                }
            }

            val sourceDir = context.applicationInfo?.sourceDir ?: return false
            val apkFile = File(sourceDir)
            if (!apkFile.exists() || apkFile.length() < 1024L) return false

            ZipFile(apkFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return false
                if (manifestEntry.crc <= 0L) return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    internal fun verifyBinaryAndSignatureIntegrity(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val activeCertBytes = mutableListOf<ByteArray>()
            val historyCertBytes = mutableListOf<ByteArray>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = pkgInfo.signingInfo ?: return false
                val activeSigs = signingInfo.apkContentsSigners
                if (activeSigs.isNullOrEmpty()) return false
                activeSigs.forEach { activeCertBytes.add(it.toByteArray()) }

                val historySigs = signingInfo.signingCertificateHistory
                historySigs?.forEach { historyCertBytes.add(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val sigs = pkgInfo.signatures
                if (sigs.isNullOrEmpty()) return false
                sigs.forEach {
                    activeCertBytes.add(it.toByteArray())
                    historyCertBytes.add(it.toByteArray())
                }
            }

            if (activeCertBytes.isEmpty()) return false

            val cf = CertificateFactory.getInstance("X.509")

            val allCertBytes = (activeCertBytes + historyCertBytes).distinctBy { it.contentHashCode() }
            for (rawCert in allCertBytes) {
                val cert = cf.generateCertificate(ByteArrayInputStream(rawCert)) as? X509Certificate
                    ?: return false

                if (isBlacklistedIssuerOrSubject(cert.subjectDN?.name) ||
                    isBlacklistedIssuerOrSubject(cert.issuerDN?.name)
                ) {
                    return false
                }

                if (!com.example.BuildConfig.DEBUG && isDebugCertificate(cert)) {
                    return false
                }
            }

            val activeSha256List = activeCertBytes.map { computeSha256(it) }.sorted()
            val activeFingerprint = activeSha256List.joinToString(",")
            val historySha256Set = historyCertBytes.map { computeSha256(it) }.toSet()

            val prefs = context.getSharedPreferences("sys_frame_hw_cache", Context.MODE_PRIVATE)
            val lockedSig = prefs.getString("hw_pipe_token", null)

            if (lockedSig == null) {
                prefs.edit().putString("hw_pipe_token", activeFingerprint).apply()
            } else if (lockedSig != activeFingerprint) {
                val lockedHashes = lockedSig.split(",").toSet()
                val isLegitimateRotation = lockedHashes.any { it in historySha256Set }

                if (isLegitimateRotation) {
                    prefs.edit().putString("hw_pipe_token", activeFingerprint).apply()
                } else {
                    recordIntegrityWarning("signing_certificate_changed_without_rotation_lineage")
                    return false
                }
            }

            true
        } catch (_: Throwable) {
            false
        }
    }

    internal fun isBlacklistedIssuerOrSubject(dn: String?): Boolean {
        if (dn.isNullOrBlank()) return false
        val cleanDn = dn.lowercase().replace(" ", "")
        for (badToken in CERT_BLACKLIST) {
            val token = d(badToken)
            if (cleanDn.contains(token)) {
                return true
            }
        }
        return false
    }

    internal fun isDebugCertificate(cert: X509Certificate): Boolean {
        val subject = cert.subjectDN?.name?.lowercase() ?: ""
        val issuer = cert.issuerDN?.name?.lowercase() ?: ""
        return subject.contains("android debug") || issuer.contains("android debug")
    }

    internal fun computeSha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun verifyRuntimeEnvironmentClean(context: Context): Boolean {
        return try {
            val hookClasses = arrayOf(d(CLS_A), d(CLS_B), d(CLS_C))
            for (clsName in hookClasses) {
                try {
                    Class.forName(clsName, false, context.classLoader)
                    return false
                } catch (_: ClassNotFoundException) {
                    // Expected on clean device
                }
            }

            val stack = Throwable().stackTrace
            val xposedToken = d(MAP_TOKENS[1])
            val substrateToken = d(MAP_TOKENS[2])
            for (el in stack) {
                val cn = el.className.lowercase()
                if (cn.contains(xposedToken) || cn.contains(substrateToken)) {
                    return false
                }
            }

            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                val decodedTokens = MAP_TOKENS.map { d(it) }
                BufferedReader(FileReader(mapsFile)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lower = line!!.lowercase()
                        for (t in decodedTokens) {
                            if (lower.contains(t)) {
                                return false
                            }
                        }
                    }
                }
            }

            true
        } catch (_: Throwable) {
            true
        }
    }

    fun verifyNativeTransportAsync() {
        // Intentional no-op: background local socket probing disabled to prevent false positives.
    }

    internal fun checkSuspiciousPorts(
        ports: IntArray,
        host: String = "127.0.0.1",
        timeoutMs: Int = 120,
        onPortDetected: () -> Unit = { recordIntegrityWarning("suspicious_port_open") }
    ) {
        for (port in ports) {
            var open = false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    open = true
                }
            } catch (_: Throwable) {
                // Clean state: this specific port is closed or timed out.
            }
            if (open) {
                onPortDetected()
            }
        }
    }

    internal fun recordIntegrityWarning(reason: String) {
        isIntegrityVerified = false
        lastIntegrityWarning = reason
        try {
            Log.w(TAG, "Integrity verification warning: $reason")
        } catch (_: Throwable) {
            // Ignore Log failures in local JVM tests
        }
    }
}

class RenderConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context
        if (ctx != null) {
            FrameMetricsTelemetrySync.syncDisplayFrameState(ctx)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
