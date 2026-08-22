package com.smartphonekey.photoframe.gphotos.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.concurrent.ExecutorService

/**
 * OAuth for devices without Play services: the system browser + a loopback
 * redirect (RFC 8252 §7.3) + PKCE, against a Desktop-app OAuth client.
 *
 * App-scoped on purpose: consent happens in an external browser, and on a
 * frame the user may wander back minutes later — the flow must survive the
 * Settings screen dying. UI callbacks arrive on the main thread; callers
 * gate their own view work on lifecycle state.
 *
 * One flow at a time. [beginAuthorization] while a flow is pending simply
 * re-opens the browser for the same pending request instead of stacking
 * listeners on ports.
 */
class LoopbackAuth(
    context: Context,
    private val store: TokenStore,
    private val ioExecutor: ExecutorService,
    private val clientId: String,
    private val clientSecret: String,
) {

    interface Listener {
        /** A usable access token. Always on the main thread. */
        fun onToken(accessToken: String)

        /** [userCancelled] true when the user denied consent. Main thread. */
        fun onError(detail: String?, userCancelled: Boolean)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /**
     * App-scoped consumer of every fresh access token — set once at startup
     * (PhotoFrameApp wires it to start the sync). Consent happens in an
     * external browser and can outlive any Activity, so the action taken on
     * a token must not live in one.
     */
    @Volatile
    var onAccessToken: ((String) -> Unit)? = null

    /**
     * UI half of the callbacks: toasts and the sync dialog. One replaceable
     * slot instead of a captured reference — a recreated Settings screen
     * re-attaches and REPLACES the old one, so a 10-minute pending flow
     * never pins a destroyed Activity (PR #11 review).
     */
    @Volatile
    private var uiListener: Listener? = null

    fun attachUi(listener: Listener) {
        uiListener = listener
    }

    /** Detach only if [listener] is still the attached one (mirror of attachUi). */
    fun detachUi(listener: Listener) {
        if (uiListener === listener) uiListener = null
    }

    // Both callbacks resolve the CURRENT consumers at delivery time — never
    // the ones captured when the flow began.
    private fun deliverToken(accessToken: String) {
        main.post {
            onAccessToken?.invoke(accessToken)
            uiListener?.onToken(accessToken)
        }
    }

    private fun deliverError(detail: String?, userCancelled: Boolean) {
        main.post { uiListener?.onError(detail, userCancelled) }
    }

    /** True when a Desktop OAuth client is configured into the build. */
    val isConfigured: Boolean
        get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()

    /** True when the frame has signed in before (refresh token stored). */
    val isSignedIn: Boolean
        get() = store.refreshToken != null

    // The pending interactive flow; guarded by `this`.
    private var pending: PendingFlow? = null

    // Sign-out must also kill token work that is already past its network
    // call — an in-flight exchange/refresh finishing after signOut() would
    // write fresh tokens into the cleared store and restore access.
    private val generation = AuthGeneration()

    private class PendingFlow(
        val server: ServerSocket,
        val verifier: String,
        val state: String,
        val authUrl: String,
        val generation: Long,
    )

    /**
     * Produces an access token without UI when possible: cached token first,
     * then a silent refresh. Falls back to [beginAuthorization] (browser
     * consent) only when [interactive] allows it. Results arrive through
     * [onAccessToken] and the attached UI listener.
     */
    fun requestAccessToken(interactive: Boolean) {
        if (!isConfigured) {
            deliverError("OAuth client not configured", false)
            return
        }
        store.validAccessToken(System.currentTimeMillis())?.let { cached ->
            deliverToken(cached)
            return
        }
        val refresh = store.refreshToken
        if (refresh != null) {
            val gen = generation.current()
            ioExecutor.execute { refreshBlocking(refresh, interactive, gen) }
            return
        }
        if (interactive) {
            beginAuthorization()
        } else {
            deliverError(null, false)
        }
    }

    private fun refreshBlocking(refreshToken: String, interactive: Boolean, gen: Long) {
        try {
            val tokens = postTokenEndpoint(
                AuthProtocol.refreshBody(clientId, clientSecret, refreshToken)
            )
            if (!generation.isCurrent(gen)) return // signed out mid-refresh
            storeTokens(tokens)
            deliverToken(tokens.accessToken)
        } catch (e: Exception) {
            if (!generation.isCurrent(gen)) return
            if (TokenJson.isInvalidGrant(e)) {
                // The grant is dead (revoked, or expired for a Testing-mode
                // consent screen) — only a fresh consent can help.
                store.clear()
                if (interactive) {
                    main.post { beginAuthorization() }
                    return
                }
            }
            Log.w(TAG, "Token refresh failed: ${e.message}")
            deliverError(e.message, false)
        }
    }

    /** Starts (or re-shows) browser consent. Call on the main thread. */
    private fun beginAuthorization() {
        val flow: PendingFlow
        synchronized(this) {
            val existing = pending
            if (existing != null) {
                // Same pending flow: just reopen the browser at the same URL.
                flow = existing
            } else {
                val server = try {
                    // Port 0: the OS picks a free ephemeral port; Desktop
                    // OAuth clients accept any loopback port by design.
                    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                } catch (e: IOException) {
                    deliverError(e.message, false)
                    return
                }
                val verifier = Pkce.newVerifier()
                val state = Pkce.newState()
                flow = PendingFlow(
                    server = server,
                    verifier = verifier,
                    state = state,
                    authUrl = AuthProtocol.authorizationUrl(
                        clientId = clientId,
                        port = server.localPort,
                        codeChallenge = Pkce.challengeS256(verifier),
                        state = state,
                        // No stored refresh token ⇒ force the consent prompt,
                        // otherwise Google may skip issuing one.
                        forceConsent = store.refreshToken == null,
                    ),
                    generation = generation.current(),
                )
                pending = flow
                ioExecutor.execute { listenBlocking(flow) }
            }
        }
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(flow.authUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            cancel()
            deliverError("no browser", false)
        }
    }

    /** Aborts a pending interactive flow (listener gets no callback). */
    fun cancel() {
        val flow = synchronized(this) { pending.also { pending = null } }
        try {
            flow?.server?.close()
        } catch (e: IOException) {
            // Nothing useful to do — the accept loop unblocks either way.
        }
    }

    private fun listenBlocking(flow: PendingFlow) {
        val deadline = System.currentTimeMillis() + FLOW_TIMEOUT_MS
        var outcome: AuthProtocol.Redirect? = null
        try {
            // Browsers probe with favicon/preconnect requests; serve and
            // ignore anything that is not our redirect until the deadline.
            // Every accept() is bounded by the REMAINING deadline — a probe
            // landing at minute 9 must not arm another full window, or any
            // local app could keep the socket occupied indefinitely.
            while (true) {
                val remainingMs = deadline - System.currentTimeMillis()
                if (remainingMs <= 0) break
                flow.server.soTimeout =
                    remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val socket = try {
                    flow.server.accept()
                } catch (e: java.net.SocketTimeoutException) {
                    break // deadline reached with no redirect → timeout
                }
                val redirect = socket.use { handleConnection(it, flow.state) }
                if (redirect !is AuthProtocol.Redirect.Ignore) {
                    outcome = redirect
                    break
                }
            }
        } catch (e: SocketException) {
            // Socket closed under us — cancel() or a competing flow. Silent.
            return
        } catch (e: IOException) {
            finishFlow(flow)
            deliverError(e.message, false)
            return
        } finally {
            try {
                flow.server.close()
            } catch (e: IOException) {
                // Already closed is fine; the port is ephemeral anyway.
            }
        }

        finishFlow(flow)
        when (outcome) {
            is AuthProtocol.Redirect.Code -> exchangeBlocking(flow, outcome.code)
            is AuthProtocol.Redirect.Error -> {
                val cancelled = outcome.error == "access_denied"
                deliverError(outcome.error, cancelled)
            }
            else -> deliverError("timeout", false)
        }
    }

    /** Clears [pending] if it is still this flow. */
    private fun finishFlow(flow: PendingFlow) {
        synchronized(this) {
            if (pending === flow) pending = null
        }
    }

    /** Reads one HTTP request, answers it, classifies it. */
    private fun handleConnection(socket: Socket, expectedState: String): AuthProtocol.Redirect {
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)
        )
        // Bounded read: BufferedReader.readLine() would buffer an arbitrarily
        // long line in RAM, and any local app can connect to this port while
        // the flow is open — an easy OOM on a 1 GB frame. Oversized lines
        // are treated like any other non-redirect request.
        val requestLine = readLineBounded(reader) ?: return AuthProtocol.Redirect.Ignore
        // Drain headers so the browser sees a clean HTTP exchange. Bounded
        // twice over: the 10s socket read timeout caps a SLOW peer, the
        // line/length caps a chatty one.
        var headerLines = 0
        while (headerLines++ < MAX_HEADER_LINES) {
            val line = readLineBounded(reader) ?: break
            if (line.isEmpty()) break
        }
        val redirect = AuthProtocol.parseRequestLine(requestLine, expectedState)
        val body = when (redirect) {
            is AuthProtocol.Redirect.Code -> RESPONSE_OK
            is AuthProtocol.Redirect.Error -> RESPONSE_DENIED
            is AuthProtocol.Redirect.Ignore -> RESPONSE_IGNORED
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.write(bytes)
        out.flush()
        return redirect
    }

    /**
     * readLine() with a hard byte budget. Returns null at EOF or when the
     * line exceeds [MAX_LINE_BYTES] — callers treat both as "not our
     * redirect". A real OAuth redirect line is well under 1 KB.
     */
    private fun readLineBounded(reader: BufferedReader): String? {
        val sb = StringBuilder(96)
        while (true) {
            val c = reader.read()
            when {
                c == -1 -> return if (sb.isEmpty()) null else sb.toString()
                c == '\n'.code -> return sb.toString()
                c == '\r'.code -> { /* swallowed; \n terminates */ }
                sb.length >= MAX_LINE_BYTES -> return null
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun exchangeBlocking(flow: PendingFlow, code: String) {
        try {
            val tokens = postTokenEndpoint(
                AuthProtocol.codeExchangeBody(
                    clientId = clientId,
                    clientSecret = clientSecret,
                    code = code,
                    verifier = flow.verifier,
                    port = flow.server.localPort,
                )
            )
            // The redirect already cleared `pending`, so cancel() cannot
            // reach this exchange — the generation check is what makes
            // signOut() win against an in-flight exchange.
            if (!generation.isCurrent(flow.generation)) return
            storeTokens(tokens)
            deliverToken(tokens.accessToken)
        } catch (e: Exception) {
            if (!generation.isCurrent(flow.generation)) return
            Log.w(TAG, "Code exchange failed: ${e.message}")
            deliverError(e.message, false)
        }
    }

    private fun storeTokens(tokens: TokenJson.Tokens) {
        tokens.refreshToken?.let { store.refreshToken = it }
        store.saveAccessToken(
            tokens.accessToken,
            System.currentTimeMillis() + tokens.expiresInSeconds * 1000L,
        )
    }

    /** Blocking POST to the token endpoint. Call from the IO executor. */
    private fun postTokenEndpoint(body: String): TokenJson.Tokens {
        val conn = URL(AuthProtocol.TOKEN_ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded"
            )
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                // OAuth errors arrive as JSON on the error stream; surface
                // them as TokenError via the parser instead of "HTTP 400".
                conn.errorStream ?: throw IOException("HTTP ${conn.responseCode}")
            }
            val text = stream.bufferedReader().use { it.readText() }
            return TokenJson.parse(text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Sign out: forget tokens AND kill any in-flight token operation — an
     * exchange or refresh finishing after this call must not write tokens
     * back into the cleared store.
     */
    fun signOut() {
        generation.invalidate()
        cancel()
        store.clear()
    }

    private companion object {
        const val TAG = "LoopbackAuth"
        // 10 minutes, measured against reality: the first sign-in on a frame
        // means typing a password on a touch panel, maybe adding a Test user
        // in another room — 5 minutes was observed to expire mid-consent.
        // Per-accept() timeouts are derived from the REMAINING window.
        const val FLOW_TIMEOUT_MS = 10 * 60 * 1000L // whole consent window
        const val SOCKET_READ_TIMEOUT_MS = 10 * 1000
        const val MAX_HEADER_LINES = 100
        const val MAX_LINE_BYTES = 8 * 1024
        const val HTTP_TIMEOUT_MS = 30 * 1000

        // Plain-ASCII pages: the browser on the frame may predate emoji and
        // fancy CSS. Bilingual on purpose — the consent UI language is the
        // Google account's, not the frame's.
        const val RESPONSE_OK =
            "<html><body style=\"font-family:sans-serif;text-align:center;" +
                "margin-top:20%\"><h2>PhotoFrame connected</h2>" +
                "<p>You can close this page and return to the app.</p>" +
                "<p>Готово — поверніться до застосунку.</p></body></html>"
        const val RESPONSE_DENIED =
            "<html><body style=\"font-family:sans-serif;text-align:center;" +
                "margin-top:20%\"><h2>Sign-in cancelled</h2>" +
                "<p>You can close this page.</p></body></html>"
        const val RESPONSE_IGNORED = "<html><body></body></html>"
    }
}
