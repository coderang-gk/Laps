package io.github.coderang.laps.coros

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Handles official password-free COROS OAuth via the Model Context Protocol (MCP) standard.
 *
 * Implements RFC 7591 Dynamic Client Registration and RFC 7636 PKCE (S256) on an ephemeral
 * loopback server (127.0.0.1), keeping all credentials and tokens 100% on-device.
 */
object CorosOAuthManager {

    internal val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            for (attempt in 1..3) {
                try {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    if (addresses.isNotEmpty()) return addresses
                } catch (e: UnknownHostException) {
                    if (attempt == 3) break
                    Thread.sleep(300L * attempt)
                }
            }
            return when (hostname.lowercase()) {
                "mcpus.coros.com", "mcp.coros.com" -> listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(50.toByte(), 18.toByte(), 183.toByte(), 11.toByte())),
                    InetAddress.getByAddress(hostname, byteArrayOf(13.toByte(), 52.toByte(), 95.toByte(), 79.toByte()))
                )
                "mcpeu.coros.com" -> listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(63.toByte(), 181.toByte(), 159.toByte(), 175.toByte())),
                    InetAddress.getByAddress(hostname, byteArrayOf(63.toByte(), 182.toByte(), 189.toByte(), 61.toByte())),
                    InetAddress.getByAddress(hostname, byteArrayOf(3.toByte(), 126.toByte(), 38.toByte(), 48.toByte()))
                )
                "mcpcn.coros.com" -> listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(8.toByte(), 141.toByte(), 27.toByte(), 17.toByte()))
                )
                else -> Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getMcpHost(region: String): String {
        val lower = region.lowercase()
        return when {
            lower.contains("eu") -> "https://mcpeu.coros.com"
            lower.contains("cn") -> "https://mcpcn.coros.com"
            else -> "https://mcpus.coros.com"
        }
    }

    /**
     * Initiates the official COROS OAuth web login in a secure Custom Tab.
     * Starts a temporary local loopback receiver, opens official COROS web auth,
     * and exchanges the resulting authorization code for an official Bearer token.
     */
    suspend fun authenticate(
        context: Context,
        region: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val mcpHost = getMcpHost(region)

        // 1. Bind ephemeral loopback server on 127.0.0.1
        val serverSocket = try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 180_000 // 3 minutes timeout for user to complete sign in
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Could not start local OAuth receiver: ${e.message}"))
        }

        val port = serverSocket.localPort
        val redirectUri = "http://127.0.0.1:$port/callback"
        Log.i("LapsAuth", "Ephemeral loopback receiver running on port $port, target region host: $mcpHost")

        try {
            // 2. Dynamic Client Registration (RFC 7591)
            Log.i("LapsAuth", "Registering client dynamically at $mcpHost/connect/register...")
            val regJson = JSONObject().apply {
                put("client_name", "Laps for Android")
                put("redirect_uris", org.json.JSONArray().apply { put(redirectUri) })
                put("grant_types", org.json.JSONArray().apply {
                    put("authorization_code")
                    put("refresh_token")
                })
                put("response_types", org.json.JSONArray().apply { put("code") })
                put("token_endpoint_auth_method", "none")
                put("scope", "mcp.tools offline_access")
            }

            val regReq = Request.Builder()
                .url("$mcpHost/connect/register")
                .post(regJson.toString().toRequestBody("application/json;charset=UTF-8".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; Laps)")
                .build()

            val clientId = httpClient.newCall(regReq).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("MCP registration failed (${resp.code}): $body"))
                }
                val obj = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val cId = obj.optString("client_id")
                if (cId.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("No client_id returned by COROS MCP"))
                }
                Log.i("LapsAuth", "Successfully registered OAuth client_id: $cId")
                cId
            }

            // 3. Generate PKCE code verifier & S256 challenge
            val secureRandom = SecureRandom()
            val verifierBytes = ByteArray(32)
            secureRandom.nextBytes(verifierBytes)
            val codeVerifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

            val digest = MessageDigest.getInstance("SHA-256")
            val challengeBytes = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
            val codeChallenge = Base64.encodeToString(challengeBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

            val stateBytes = ByteArray(16)
            secureRandom.nextBytes(stateBytes)
            val oauthState = Base64.encodeToString(stateBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

            // 4. Construct Authorization URL
            val authUrl = Uri.parse("$mcpHost/oauth2/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", "mcp.tools offline_access")
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", oauthState)
                .build()

            // 5. Open Chrome Custom Tab / System Browser on Main Thread
            Log.i("LapsAuth", "Opening COROS authorization URL: $authUrl")
            withContext(Dispatchers.Main) {
                try {
                    val customTabsIntent = CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                    customTabsIntent.launchUrl(context, authUrl)
                } catch (_: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, authUrl).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }

            // 6. Wait for HTTP Callback on 127.0.0.1
            var authCode: String? = null
            var errorReason: String? = null

            Log.i("LapsAuth", "Waiting for browser redirect callback on 127.0.0.1:$port...")
            val clientSocket = serverSocket.accept()
            clientSocket.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = OutputStreamWriter(socket.getOutputStream())

                val requestLine = reader.readLine() ?: ""
                Log.i("LapsAuth", "Received loopback request: $requestLine")
                val parts = requestLine.split(" ")
                if (parts.size >= 2) {
                    val pathWithQuery = parts[1]
                    val uri = Uri.parse("http://127.0.0.1$pathWithQuery")
                    val returnedCode = uri.getQueryParameter("code")
                    val returnedError = uri.getQueryParameter("error")

                    if (!returnedCode.isNullOrBlank()) {
                        authCode = returnedCode
                        val html = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1">
                                <style>
                                    body { font-family: -apple-system, sans-serif; text-align: center; padding: 40px 20px; background: #0f141c; color: #e1e2e8; }
                                    .card { background: #1b202a; border-radius: 16px; padding: 24px; max-width: 360px; margin: 0 auto; box-shadow: 0 4px 20px rgba(0,0,0,0.4); }
                                    h1 { color: #82d3e5; font-size: 20px; margin-bottom: 8px; }
                                    p { font-size: 14px; color: #c1c7ce; }
                                </style>
                            </head>
                            <body>
                                <div class="card">
                                    <h1>Connected to COROS!</h1>
                                    <p>Authentication was successful. You can now close this tab and return to Laps.</p>
                                </div>
                            </body>
                            </html>
                        """.trimIndent()

                        writer.write("HTTP/1.1 200 OK\r\n")
                        writer.write("Content-Type: text/html; charset=UTF-8\r\n")
                        writer.write("Content-Length: ${html.toByteArray().size}\r\n")
                        writer.write("Connection: close\r\n\r\n")
                        writer.write(html)
                        writer.flush()
                    } else {
                        errorReason = returnedError ?: "No authorization code returned"
                        writer.write("HTTP/1.1 400 Bad Request\r\n")
                        writer.write("Content-Type: text/plain\r\n\r\n")
                        writer.write("Authentication failed: $errorReason")
                        writer.flush()
                    }
                }
            }

            if (authCode.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException(errorReason ?: "Authorization timed out or cancelled"))
            }

            // Bring Laps back to foreground so Android unfreezes network
            withContext(Dispatchers.Main) {
                try {
                    val bringToFront = Intent(context, Class.forName("io.github.coderang.laps.MainActivity")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(bringToFront)
                } catch (_: Throwable) {}
            }

            // 7. Exchange Code for Access Token
            Log.i("LapsAuth", "Exchanging authorization code for Bearer token at $mcpHost/oauth2/token...")
            val tokenBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("code", authCode)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", codeVerifier)
                .build()

            val tokenReq = Request.Builder()
                .url("$mcpHost/oauth2/token")
                .post(tokenBody)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; Laps)")
                .build()

            var body = ""
            var code = 0
            var exchangeSuccess = false
            for (attempt in 1..4) {
                try {
                    httpClient.newCall(tokenReq).execute().use { resp ->
                        code = resp.code
                        body = resp.body?.string() ?: ""
                        Log.i("LapsAuth", "Token exchange returned HTTP $code: $body")
                        if (resp.isSuccessful) {
                            exchangeSuccess = true
                        }
                    }
                    if (exchangeSuccess) break
                    if (code in 400..499) break // Client error (e.g. invalid code), don't retry
                } catch (e: Exception) {
                    Log.w("LapsAuth", "Token exchange attempt $attempt failed with exception: ${e.message}")
                    if (attempt == 4) throw e
                    delay(500L * attempt)
                }
            }

            if (!exchangeSuccess) {
                return@withContext Result.failure(IllegalStateException("Token exchange failed ($code): $body"))
            }

            val tokenObj = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val accessToken = tokenObj.optString("access_token")
            if (accessToken.isNotBlank()) {
                Log.i("LapsAuth", "OAuth token acquired successfully (length: ${accessToken.length})")
                Result.success(accessToken)
            } else {
                Result.failure(IllegalStateException("No access_token returned by token endpoint: $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { serverSocket.close() } catch (_: Exception) {}
        }
    }
}
