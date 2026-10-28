package io.github.coderang.laps.coros

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles COROS Mobile API authentication to acquire the device's RSA-2048 session private key,
 * bind profile, and cryptographic challenge responses for authenticating direct BLE connections
 * on COROS sports watches (PACE Pro, PACE 3, APEX 2, VERTIX 2).
 */
object CorosMobileAuth {

    private const val TAG = "CorosMobileAuth"
    private const val MOBILE_APP_KEY = "3475792298363620"
    private const val MOBILE_LOGIN_IV = "weloop3_2015_03#"
    private const val MOBILE_VERSION_CODE = "407081000"
    private const val MOBILE_APP_VERSION = 1125929972137984L
    private const val MOBILE_USER_SETTING_SCOPE = "CAEQARgBIAEoATABOAFAAQ=="

    private val httpClient = OkHttpClient.Builder()
        .dns(CorosOAuthManager.resilientDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class WatchAuthCredentials(
        val accountUserId: Long,
        val privateKeyDerBase64: String,
        val bindProfileBase64: String,
        val unit: Int = 0,
        val dataCollectSwitch: Long = 0L,
        val keyIdHex: String = ""
    )

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun encryptMobileField(value: String): String {
        val key = MOBILE_APP_KEY.toByteArray(Charsets.UTF_8)
        val iv = MOBILE_LOGIN_IV.toByteArray(Charsets.UTF_8)
        val clear = value.toByteArray(Charsets.UTF_8)
        val obfuscated = ByteArray(clear.size)
        for (i in clear.indices) {
            obfuscated[i] = (clear[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(obfuscated)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decryptUserKey(appEncKeyStr: String, userKeyB64: String): JSONObject {
        val encKey = appEncKeyStr.toByteArray(Charsets.UTF_8)
        val iv = MOBILE_LOGIN_IV.toByteArray(Charsets.UTF_8)
        val encrypted = Base64.decode(userKeyB64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        val clear = cipher.doFinal(encrypted)
        for (i in clear.indices) {
            clear[i] = (clear[i].toInt() xor encKey[i % encKey.size].toInt()).toByte()
        }
        val rawStr = String(clear, Charsets.UTF_8)
        // Fix large numeric user IDs if unquoted in JSON
        val fixedJson = rawStr.replace(Regex("(\"userId\"\\s*:\\s*)(\\d+)(?=\\s*[,}])"), "$1\"$2\"")
        return JSONObject(fixedJson)
    }

    fun crc32Mpeg2(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in data) {
            crc = crc xor ((b.toLong() and 0xFFL) shl 24)
            for (i in 0 until 8) {
                crc = if ((crc and 0x80000000L) != 0L) {
                    ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
        }
        return crc and 0xFFFFFFFFL
    }

    fun crc16CcittFalse(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (i in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun buildMobileLoginPayload(account: String, pwdHash: String, checkStatus: Int): JSONObject {
        val payload = JSONObject()
        payload.put("account", encryptMobileField(account))
        payload.put("accountType", 2)
        payload.put("appKey", MOBILE_APP_KEY)
        payload.put("checkStatus", checkStatus)
        payload.put("clientType", 1)
        payload.put("hasHrCalibrated", 0)
        payload.put("kbValidity", 0)
        payload.put("pwd", encryptMobileField(pwdHash.lowercase()))
        payload.put("region", "DomainRegion(simMcc=310,countryCode=US,timeZoneId=UTC,language=en,countryIso=us)")
        payload.put("skipValidation", false)
        return payload
    }

    private fun buildYfHeader(installId: String, tzQuarterHours: Int, userIdStr: String): String {
        val safeUId = if (userIdStr.matches(Regex("^\\d+$"))) userIdStr else "0"
        return """{"appVersion":$MOBILE_APP_VERSION,"clientType":1,"language":"en-US","mobileName":"$installId","releaseType":1,"systemDisplayId":"c$installId","systemVersion":"16","timezone":$tzQuarterHours,"userId":$safeUId,"userSettingScope":"$MOBILE_USER_SETTING_SCOPE","versionCode":"$MOBILE_VERSION_CODE"}"""
    }

    /**
     * Executes the mobile client authentication against COROS Cloud to retrieve the RSA watch credentials.
     */
    suspend fun login(
        email: String,
        rawPassword: String,
        region: String = "us"
    ): Result<WatchAuthCredentials> = withContext(Dispatchers.IO) {
        val account = email.trim()
        val pwdHash = md5(rawPassword)
        val lowerReg = region.lowercase()
        val primaryBaseUrl = when {
            lowerReg.contains("eu") -> "https://apieu.coros.com/coros"
            lowerReg.contains("cn") -> "https://apicn.coros.com/coros"
            else -> "https://api.coros.com/coros"
        }

        val tzOffsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val tzQuarterHours = Math.round(tzOffsetMs / (15.0 * 60.0 * 1000.0)).toInt()
        val installId = md5(account + "LapsCorosSync").take(32)

        suspend fun executeLoginRequest(targetBaseUrl: String, checkStatus: Int): Result<JSONObject> {
            val endpoint = "$targetBaseUrl/user/login"
            val payload = buildMobileLoginPayload(account, pwdHash, checkStatus)
            val mediaType = "application/json;charset=UTF-8".toMediaType()
            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody(mediaType))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("YFHeader", buildYfHeader(installId, tzQuarterHours, "0"))
                .header("app-state", "foreground")
                .header("request-time", System.currentTimeMillis().toString())
                .build()

            return try {
                httpClient.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: ""
                    Log.i(TAG, "executeLoginRequest ($targetBaseUrl) HTTP ${resp.code}: ${bodyStr.take(160)}")
                    val root = try { JSONObject(bodyStr) } catch (e: Exception) { JSONObject() }
                    val code = root.optString("result", root.optString("code", ""))
                    if (code == "0000" || code == "1115") {
                        Result.success(root)
                    } else {
                        val msg = root.optString("message", if (code.isNotBlank()) "Login failed (code $code)" else "Login failed (HTTP ${resp.code})")
                        Result.failure(IllegalStateException(msg))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "executeLoginRequest network error", e)
                Result.failure(e)
            }
        }

        suspend fun tryRegionLogin(targetBaseUrl: String): Result<JSONObject> {
            var res = executeLoginRequest(targetBaseUrl, 1)
            if (res.isSuccess) {
                val root = res.getOrNull()!!
                val code = root.optString("result", root.optString("code", ""))
                if (code == "1115") {
                    res = executeLoginRequest(targetBaseUrl, 0)
                }
            }
            return res
        }

        var loginResult = tryRegionLogin(primaryBaseUrl)
        if (loginResult.isFailure) {
            val fallbackUrls = listOf(
                "https://api.coros.com/coros",
                "https://apieu.coros.com/coros"
            ).filter { it != primaryBaseUrl }
            for (fallback in fallbackUrls) {
                val fbRes = tryRegionLogin(fallback)
                if (fbRes.isSuccess) {
                    loginResult = fbRes
                    break
                }
            }
        }

        if (loginResult.isFailure) {
            return@withContext Result.failure(loginResult.exceptionOrNull() ?: IllegalStateException("COROS mobile authentication failed"))
        }

        val root = loginResult.getOrNull()!!
        val data = root.optJSONObject("data") ?: return@withContext Result.failure(IllegalStateException("No data in COROS login response"))

        val uIdStr = data.optString("userId")
        val accountUserId = uIdStr.toLongOrNull() ?: 0L
        val appEncKey = data.optString("appEncKey")
        val userKeyB64 = data.optString("userKey")
        val unit = data.optInt("unit", 0)
        val userProfile = data.optJSONObject("userProfile")
        val dataCollectSwitch = userProfile?.optLong("dataCollectSwitch", 0L) ?: 0L

        if (appEncKey.isBlank() || userKeyB64.isBlank() || accountUserId == 0L) {
            return@withContext Result.failure(IllegalStateException("COROS account did not provide watch encryption keys. Please verify your login credentials."))
        }

        try {
            val keyJson = decryptUserKey(appEncKey, userKeyB64)
            val privateKeyDerB64 = keyJson.optString("privateKey").replace("\r", "").replace("\n", "").trim()
            val publicKeyDerB64 = keyJson.optString("publicKey").replace("\r", "").replace("\n", "").trim()

            // Build 17-byte initial bind profile matching Android / CorosLink:
            // [0] = 1 (protocol v2)
            // [1..4] = crc32_mpeg2(accountUserId_8B_LE)
            // [5..8] = placeholder for timestamp (0)
            // [9] = timezoneQuarterHours
            // [10] = 0 (English)
            // [11] = 0x20 or (if (is12Hour) 0 else 1) or (unit shl 1)
            // [12..15] = dataCollectSwitch (UInt32 LE)
            // [16] = 0x80 (check command flag)
            val userBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(accountUserId).array()
            val userCrc = crc32Mpeg2(userBuf)

            val profile = ByteArray(17)
            profile[0] = 1
            profile[1] = (userCrc and 0xFF).toByte()
            profile[2] = ((userCrc shr 8) and 0xFF).toByte()
            profile[3] = ((userCrc shr 16) and 0xFF).toByte()
            profile[4] = ((userCrc shr 24) and 0xFF).toByte()
            profile[9] = (tzQuarterHours and 0xFF).toByte()
            profile[10] = 0
            profile[11] = (0x20 or 1 or (unit shl 1)).toByte()
            profile[12] = (dataCollectSwitch and 0xFF).toByte()
            profile[13] = ((dataCollectSwitch shr 8) and 0xFF).toByte()
            profile[14] = ((dataCollectSwitch shr 16) and 0xFF).toByte()
            profile[15] = ((dataCollectSwitch shr 24) and 0xFF).toByte()
            profile[16] = 0x80.toByte()

            val creds = WatchAuthCredentials(
                accountUserId = accountUserId,
                privateKeyDerBase64 = privateKeyDerB64,
                bindProfileBase64 = Base64.encodeToString(profile, Base64.NO_WRAP),
                unit = unit,
                dataCollectSwitch = dataCollectSwitch
            )
            Log.i(TAG, "Successfully extracted watch auth credentials for userId=$accountUserId")
            Result.success(creds)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt watch private key", e)
            Result.failure(IllegalStateException("Failed to decrypt watch private key: ${e.message}"))
        }
    }

    /**
     * Converts PKCS#1 RSA private key DER bytes into standard PKCS#8 DER format for Android's KeyFactory.
     */
    fun pkcs1ToPkcs8(pkcs1Bytes: ByteArray): ByteArray {
        val innerLen = pkcs1Bytes.size
        val totalLen = innerLen + 22

        val lenBytes = if (totalLen <= 127) {
            byteArrayOf(totalLen.toByte())
        } else if (totalLen <= 255) {
            byteArrayOf(0x81.toByte(), totalLen.toByte())
        } else {
            byteArrayOf(0x82.toByte(), (totalLen shr 8).toByte(), (totalLen and 0xFF).toByte())
        }

        val octetLenBytes = if (innerLen <= 127) {
            byteArrayOf(innerLen.toByte())
        } else if (innerLen <= 255) {
            byteArrayOf(0x81.toByte(), innerLen.toByte())
        } else {
            byteArrayOf(0x82.toByte(), (innerLen shr 8).toByte(), (innerLen and 0xFF).toByte())
        }

        val header = ByteArray(1 + lenBytes.size + 3 + 15 + 1 + octetLenBytes.size)
        var pos = 0
        header[pos++] = 0x30
        System.arraycopy(lenBytes, 0, header, pos, lenBytes.size); pos += lenBytes.size
        header[pos++] = 0x02; header[pos++] = 0x01; header[pos++] = 0x00 // INTEGER 0
        // SEQUENCE { OID rsaEncryption, NULL }
        val rsaOid = byteArrayOf(0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00)
        System.arraycopy(rsaOid, 0, header, pos, rsaOid.size); pos += rsaOid.size
        header[pos++] = 0x04 // OCTET STRING
        System.arraycopy(octetLenBytes, 0, header, pos, octetLenBytes.size); pos += octetLenBytes.size

        val pkcs8 = ByteArray(pos + pkcs1Bytes.size)
        System.arraycopy(header, 0, pkcs8, 0, pos)
        System.arraycopy(pkcs1Bytes, 0, pkcs8, pos, pkcs1Bytes.size)
        return pkcs8
    }

    /**
     * Signs the 4-byte watch challenge nonce + 8-byte LE user ID using RSA-2048 PKCS#1 v1.5 with SHA-256.
     */
    fun signChallenge(privateKeyPkcs1DerBase64: String, nonce: ByteArray, userId: Long): ByteArray {
        val pkcs1Bytes = Base64.decode(privateKeyPkcs1DerBase64, Base64.DEFAULT)
        val pkcs8Bytes = pkcs1ToPkcs8(pkcs1Bytes)

        val keySpec = PKCS8EncodedKeySpec(pkcs8Bytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey: PrivateKey = keyFactory.generatePrivate(keySpec)

        val userBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(userId).array()
        val dataToSign = ByteArray(nonce.size + userBytes.size)
        System.arraycopy(nonce, 0, dataToSign, 0, nonce.size)
        System.arraycopy(userBytes, 0, dataToSign, nonce.size, userBytes.size)

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(dataToSign)
        return signer.sign() // 256 bytes signature
    }
}
