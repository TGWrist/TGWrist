package com.tgwrist.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥解密失败异常
 *
 * 当尝试解密现有数据库密钥失败时抛出此异常。
 * 可能的原因：
 * - 硬件密钥状态改变
 * - 设备恢复出厂设置
 * - 数据损坏
 *
 * 解决方案：调用 TeeKeyManager.regenerateKey() 重新生成密钥
 * 警告：重新生成密钥将导致旧数据无法解密
 */
class KeyDecryptionException : SecurityException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

/**
 * TEE 密钥管理器
 *
 * 使用 Android Keystore 系统（TEE/StrongBox）来保护数据库加密密钥。
 * 支持 StrongBox 独立安全芯片（Android 9+），并自动回退到 TEE。
 *
 * 安全特性：
 * - 密钥材料永不离开硬件安全模块
 * - 支持密钥版本管理
 * - 自动清理内存中的敏感数据
 * - 防止密钥被非授权应用访问
 */
class TeeKeyManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "TeeKeyManager"
        private const val PREFS_NAME = "secure_db_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AppNativeTeeKey"
        private const val KEY_ALIAS_STRONGBOX = "AppNativeTeeKey_StrongBox"

        // SharedPreferences 标签
        private const val ENCRYPTED_KEY_TAG = "db_key_blob"
        private const val IV_TAG = "db_key_iv"
        private const val KEY_VERSION_TAG = "db_key_version"
        private const val IS_STRONGBOX_TAG = "is_strongbox_backed"

        // 当前密钥版本（用于未来密钥迁移）
        private const val CURRENT_KEY_VERSION = 1

        // AES-GCM 参数
        private const val AES_KEY_SIZE = 256
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val DB_KEY_LENGTH = 32
    }

    /**
     * 密钥信息数据类
     */
    data class KeyInfo(
        val isStrongBoxBacked: Boolean,
        val isInsideSecureHardware: Boolean,
        val keyVersion: Int
    )

    /**
     * 核心方法：获取 32 字节的数据库原始密钥
     *
     * 逻辑：
     * 1. 检查 SharedPreferences 是否存有被加密的密钥
     * 2. 如果有 -> 调用 TEE/StrongBox 解密 -> 返回明文
     * 3. 如果无 -> 生成新随机密钥 -> 调用 TEE/StrongBox 加密 -> 存入 SP -> 返回明文
     *
     * @return 32 字节的数据库加密密钥
     * @throws SecurityException 如果无法创建或访问安全密钥
     * @throws KeyDecryptionException 如果解密现有密钥失败（需要手动调用 regenerateKey）
     */
    @Throws(SecurityException::class, KeyDecryptionException::class)
    fun getOrGenerateDatabaseKey(): ByteArray {
        val encryptedBase64 = prefs.getString(ENCRYPTED_KEY_TAG, null)
        val ivBase64 = prefs.getString(IV_TAG, null)

        if (encryptedBase64 != null && ivBase64 != null) {
            try {
                val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
                val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

                // 验证 IV 长度
                if (iv.size != GCM_IV_LENGTH) {
                    throw KeyDecryptionException("Invalid IV length: ${iv.size}, expected: $GCM_IV_LENGTH")
                }

                return decryptWithKeystore(encryptedBytes, iv)
            } catch (e: KeyDecryptionException) {
                // 重新抛出自定义异常
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt existing key: ${e.message}")
                // 抛出异常，让调用者决定是否重新生成密钥
                throw KeyDecryptionException("Failed to decrypt database key. Key may be corrupted or hardware state changed. Consider calling regenerateKey() to reset.", e)
            }
        }

        return generateAndSaveNewKey()
    }

    /**
     * 检查数据库密钥是否存在
     */
    fun isDatabaseKeyExists(): Boolean {
        return prefs.contains(ENCRYPTED_KEY_TAG) && prefs.contains(IV_TAG)
    }

    /**
     * 获取当前密钥的安全信息
     */
    fun getKeySecurityInfo(): KeyInfo? {
        return try {
            val keyAlias = getCurrentKeyAlias()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

            if (!keyStore.containsAlias(keyAlias)) {
                return null
            }

            val secretKey = (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: return null

            val factory = SecretKeyFactory.getInstance(secretKey.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(secretKey, android.security.keystore.KeyInfo::class.java)
                as android.security.keystore.KeyInfo

            KeyInfo(
                isStrongBoxBacked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
                } else {
                    prefs.getBoolean(IS_STRONGBOX_TAG, false)
                },
                isInsideSecureHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    keyInfo.securityLevel >= KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                } else {
                    @Suppress("DEPRECATION")
                    keyInfo.isInsideSecureHardware
                },
                keyVersion = prefs.getInt(KEY_VERSION_TAG, 1)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get key info: ${e.message}")
            null
        }
    }

    /**
     * 强制重新生成数据库密钥
     * 警告：这将使所有用旧密钥加密的数据无法解密
     */
    fun regenerateKey(): ByteArray {
        // 删除旧的 Keystore 密钥
        deleteKeystoreKey()
        // 清除 SharedPreferences 中的加密数据
        clearStoredKey()
        // 生成新密钥
        return generateAndSaveNewKey()
    }

    /**
     * 检查设备是否支持 StrongBox
     */
    fun isStrongBoxSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } else {
            false
        }
    }

    /**
     * 清除所有存储的密钥数据
     * 警告：这将删除所有加密密钥，加密数据将无法恢复
     */
    fun clearAllKeys() {
        deleteKeystoreKey()
        clearStoredKey()
    }

    // ========================================================================
    // 私有辅助方法
    // ========================================================================

    private fun generateAndSaveNewKey(): ByteArray {
        // 1. 生成真实的 32 字节随机密钥 (Payload)
        val rawKey = ByteArray(DB_KEY_LENGTH)
        SecureRandom().nextBytes(rawKey)

        try {
            // 2. 获取或创建 TEE/StrongBox 硬件密钥 (KEK)
            val (masterKey, isStrongBox) = getOrCreateKeystoreKey()

            // 3. 使用 AES-GCM 加密 Payload
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)

            val encryptedBytes = cipher.doFinal(rawKey)
            val iv = cipher.iv

            // 验证 IV 长度
            require(iv.size == GCM_IV_LENGTH) { "Unexpected IV length: ${iv.size}" }

            // 4. 持久化存储（使用 NO_WRAP 避免换行符问题）
            prefs.edit {
                putString(ENCRYPTED_KEY_TAG, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                putString(IV_TAG, Base64.encodeToString(iv, Base64.NO_WRAP))
                putInt(KEY_VERSION_TAG, CURRENT_KEY_VERSION)
                putBoolean(IS_STRONGBOX_TAG, isStrongBox)
            }

            Log.i(TAG, "New database key generated (StrongBox: $isStrongBox)")
            return rawKey.copyOf() // 返回副本，保护原始数据

        } finally {
            // 安全清理：覆盖原始密钥内存
            Arrays.fill(rawKey, 0.toByte())
        }
    }

    private fun decryptWithKeystore(encryptedBytes: ByteArray, iv: ByteArray): ByteArray {
        val keyAlias = getCurrentKeyAlias()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        val secretKey = (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw SecurityException("Keystore key not found: $keyAlias")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(encryptedBytes)
    }

    /**
     * 获取当前使用的密钥别名
     */
    private fun getCurrentKeyAlias(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // 优先检查 StrongBox 密钥
        if (keyStore.containsAlias(KEY_ALIAS_STRONGBOX)) {
            return KEY_ALIAS_STRONGBOX
        }
        return KEY_ALIAS
    }

    /**
     * 获取或创建 Android Keystore 中的硬件密钥
     * 优先尝试 StrongBox，失败后回退到 TEE
     *
     * @return Pair<密钥, 是否为StrongBox>
     */
    private fun getOrCreateKeystoreKey(): Pair<SecretKey, Boolean> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // 检查是否已存在 StrongBox 密钥
        if (keyStore.containsAlias(KEY_ALIAS_STRONGBOX)) {
            val entry = keyStore.getEntry(KEY_ALIAS_STRONGBOX, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return Pair(entry.secretKey, true)
            }
        }

        // 检查是否已存在普通 TEE 密钥
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return Pair(entry.secretKey, false)
            }
        }

        // 尝试创建 StrongBox 密钥（Android 9+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isStrongBoxSupported()) {
            try {
                val strongBoxKey = createKeystoreKey(KEY_ALIAS_STRONGBOX, useStrongBox = true)
                Log.i(TAG, "StrongBox key created successfully")
                return Pair(strongBoxKey, true)
            } catch (e: StrongBoxUnavailableException) {
                Log.w(TAG, "StrongBox unavailable, falling back to TEE: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create StrongBox key, falling back to TEE: ${e.message}")
            }
        }

        // 回退到普通 TEE 密钥
        val teeKey = createKeystoreKey(KEY_ALIAS, useStrongBox = false)
        Log.i(TAG, "TEE key created successfully")
        return Pair(teeKey, false)
    }

    /**
     * 在 Keystore 中创建新密钥
     */
    private fun createKeystoreKey(alias: String, useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            .setRandomizedEncryptionRequired(true) // 强制随机 IV，防止重放攻击

        // Android 7.0+ 支持用户认证绑定（可选，取消注释启用）
        // 启用后，需要用户在过去 N 秒内认证（指纹/PIN）才能使用密钥
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        //     builder.setUserAuthenticationRequired(true)
        //     builder.setUserAuthenticationValidityDurationSeconds(300) // 5分钟有效
        // }

        // Android 9.0+ 支持 StrongBox 独立安全芯片
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useStrongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        // Android 11+ 支持更细粒度的密钥访问限制
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 不允许在锁屏时使用密钥（更安全，但可能影响后台服务）
            // builder.setUnlockedDeviceRequired(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * 删除 Keystore 中的密钥
     */
    private fun deleteKeystoreKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

            if (keyStore.containsAlias(KEY_ALIAS_STRONGBOX)) {
                keyStore.deleteEntry(KEY_ALIAS_STRONGBOX)
                Log.i(TAG, "StrongBox key deleted")
            }

            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
                Log.i(TAG, "TEE key deleted")
            }
        } catch (e: KeyStoreException) {
            Log.e(TAG, "Failed to delete keystore key: ${e.message}")
        }
    }

    /**
     * 清除 SharedPreferences 中存储的加密密钥数据
     */
    private fun clearStoredKey() {
        prefs.edit {
            remove(ENCRYPTED_KEY_TAG)
            remove(IV_TAG)
            remove(KEY_VERSION_TAG)
            remove(IS_STRONGBOX_TAG)
        }
    }
}
