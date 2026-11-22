package io.github.ccch1mneyyy.dsh_app.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object RemoteControlConfig {
    private const val ALIAS = "dsh.remote-control.v1"
    private const val FLAGS = Base64.NO_WRAP or Base64.NO_PADDING
    private val random = SecureRandom()

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "remote-control.v1"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
    }

    @Synchronized fun read(context: Context): JSONObject {
        val bytes = try { file(context).readFully() } catch (_: FileNotFoundException) { return JSONObject() }
        check(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        val clear = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try { JSONObject(clear.toString(Charsets.UTF_8)) } finally { clear.fill(0) }
    }

    @Synchronized fun update(context: Context, change: JSONObject.() -> Unit): JSONObject {
        val config = read(context).apply(change)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(cipher.iv.size == 12)
        val clear = config.toString().toByteArray(Charsets.UTF_8)
        val bytes = try { cipher.iv + cipher.doFinal(clear) } finally { clear.fill(0) }
        val file = file(context)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        return config
    }

    fun newToken(): String = Base64.encodeToString(ByteArray(32).also(random::nextBytes), FLAGS or Base64.URL_SAFE)

    fun publicConfig(c: JSONObject): Map<String, Any> = mapOf(
        "host" to c.optString("host"), "sshPort" to c.optInt("sshPort", 22),
        "username" to c.optString("username"), "remotePort" to c.optInt("remotePort", 18765),
        "fingerprint" to c.optString("fingerprint"), "hasPrivateKey" to c.optString("privateKey").isNotEmpty(),
        "hasToken" to c.optString("token").isNotEmpty(), "autoStart" to c.optBoolean("autoStart"),
        "allowExec" to c.optBoolean("allowExec"),
    )
}
