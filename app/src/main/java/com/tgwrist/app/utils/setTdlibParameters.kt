package com.tgwrist.app.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.tgwrist.app.BuildConfig
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TgClient
import org.drinkless.tdlib.TdApi
import java.io.File

fun Context.setTdlibParameters(userId: String?, callBack: (TdApi.Object) -> Unit = {}) {
    // 获取内部存储
    val internalDir: File = filesDir
    // 初始化 TdClient
    val externalDir: File = getExternalFilesDir(null)
        ?: throw IllegalStateException("Failed to get external directory")
    // 获取 TDLib API ID 和 Hash（在 gradle.properties 中定义，并通过 BuildConfig 传递）
    val tdapiId = BuildConfig.TG_API_ID
    val tdapiHash = BuildConfig.TG_API_HASH

    // 获取加密密钥
    val teeKeyManager = TeeKeyManager(this)
    val dbKeyBytes =
        try {
            // 获取或生成数据库密钥
            teeKeyManager.getOrGenerateDatabaseKey()
        } catch (e: KeyDecryptionException) {
            // 解密失败，需要用户决定是否重新生成密钥
            Log.e("Security", "Failed to decrypt key", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, getString(R.string.Error_decrypt_keystore_failed), Toast.LENGTH_LONG).show()
            }
            return
        } catch (e: SecurityException) {
            // 其他安全相关错误
            Log.e("Security", "Failed to access keystore", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, getString(R.string.Error_access_keystore_failed), Toast.LENGTH_LONG).show()
            }
            return
        }

    // 时限控制
    // 获取当前 UTC 时间戳
    /*val currentUtcMillis = Instant.now().toEpochMilli()
    // 设定过期 UTC 时间戳
    val expiryUtcMillis = LocalDateTime.of(2026, 2, 18, 0, 0, 0)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli() // 结果是 1771286400000L
    if (currentUtcMillis > expiryUtcMillis) {
        // 已过期
        sharedPref.edit {
            putString("encryption_key", "")
        }
        throw IllegalStateException("Your encryption key has expired")
    }*/

    TgClient.send(TdApi.SetTdlibParameters().apply {
        databaseDirectory = if (userId != null)"${internalDir.absolutePath}/${userId}/tdlib" else internalDir.absolutePath + "/tdlib"
        apiId = tdapiId
        apiHash = tdapiHash
        systemLanguageCode = resources.configuration.locales[0].language
        deviceModel = Build.MODEL
        systemVersion = "Android ${Build.VERSION.RELEASE} WearOS"
        applicationVersion = getAppVersion()
        useSecretChats = false
        useMessageDatabase = true
        useChatInfoDatabase = true
        useFileDatabase = true
        filesDirectory = externalDir.absolutePath
        databaseEncryptionKey = dbKeyBytes
    }) { result ->
        Log.d("Tdlib", "SetTdlibParameters result: $result")
        if (result is TdApi.Error) {
            if (result.code == 401) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, getString(R.string.Error_auth_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
        callBack(result)
    }
}
