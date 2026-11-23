package com.droiddeck.launcher.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.romm.RommAuth
import com.droiddeck.launcher.stores.romm.RommPrefs
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog

private const val SERVER_EXAMPLE = "http://romm.example:8080"

private fun copiedToken(ctx: Context): String? {
    val clipboard = ctx.getSystemService(ClipboardManager::class.java)
    val text = runCatching { clipboard?.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull() ?: return null
    return RommAuth.tokenIn(text)
}

@Composable
internal fun RommConnectDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var server by remember { mutableStateOf(RommAuth.server(ctx)) }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf(copiedToken(ctx).orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val serverKnown = remember { server.isNotEmpty() }

    val keyboard = LocalSoftwareKeyboardController.current
    val cancelled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var pairing by remember { mutableStateOf<RommAuth.Pairing?>(null) }
    DisposableEffect(Unit) { onDispose { cancelled.set(true) } }

    fun finish(app: Context, result: RommAuth.Result?) {
        val failure = when (result?.problem) {
            RommAuth.Problem.UNREACHABLE -> R.string.stores_romm_unreachable
            RommAuth.Problem.NOT_ROMM -> R.string.stores_romm_not_romm
            RommAuth.Problem.TOO_OLD -> R.string.stores_romm_too_old
            RommAuth.Problem.BAD_TOKEN -> R.string.stores_romm_token_rejected
            RommAuth.Problem.BAD_LOGIN -> R.string.stores_romm_login_rejected
            RommAuth.Problem.NO_TOKEN -> R.string.stores_romm_no_token
            RommAuth.Problem.PAIR_UNSUPPORTED -> R.string.stores_romm_pair_unsupported
            RommAuth.Problem.PAIR_DENIED -> R.string.stores_romm_pair_denied
            RommAuth.Problem.PAIR_EXPIRED -> R.string.stores_romm_pair_expired
            null -> if (result == null || runCatching { RommAuth.save(app, result.server, result.token, result.user!!, result.scopes, result.version) }.isSuccess) null else R.string.stores_romm_not_saved
        }
        StoresState.post {
            busy = false
            pairing = null
            problem = failure
            if (failure == null && result != null) onDismiss()
        }
        if (failure == null && result != null) {
            StoresState.expired.remove(Store.ROMM)
            StoresState.refresh(app)
        }
    }

    fun submit() {
        val address = server.trim()
        val name = username.trim()
        val key = if (name.isEmpty()) RommAuth.tokenIn(secret) ?: secret.trim() else secret
        if (address.isEmpty() || key.isEmpty() || busy) return
        busy = true
        problem = null
        val app = ctx.applicationContext
        Thread({ finish(app, RommAuth.verify(address, name, key, "DroidDeck (${android.os.Build.MODEL})")) }, "romm-connect").start()
    }

    fun pair() {
        val address = server.trim()
        if (address.isEmpty() || busy) return
        busy = true
        problem = null
        cancelled.set(false)
        keyboard?.hide()
        val app = ctx.applicationContext
        Thread({
            val (started, failure) = RommAuth.pair(address, RommPrefs.pairingId(app), "DroidDeck (${android.os.Build.MODEL})")
            if (started == null) { finish(app, RommAuth.Result(address, "", null, failure)); return@Thread }
            StoresState.post { pairing = started }
            finish(app, RommAuth.awaitPairing(started, { cancelled.get() }))
        }, "romm-pair").start()
    }

    AlertDialog(
        onDismissRequest = { if (!busy || pairing != null) { cancelled.set(true); onDismiss() } },
        title = { Text(Store.ROMM.label) },
        text = {
            val waiting = pairing
            if (waiting != null) Column {
                Text(stringResource(R.string.stores_romm_pair_prompt, waiting.server + "/pair/device"), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(waiting.userCode.chunked(4).joinToString(" "), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.stores_romm_pair_waiting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waiting.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.stores_romm_pair_open)) }
            } else Column {
                AdbTextField(
                    value = server,
                    onValueChange = { server = it; problem = null },
                    label = stringResource(R.string.stores_romm_server),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    placeholder = SERVER_EXAMPLE,
                    focusOnShow = !serverKnown,
                )
                Spacer(Modifier.height(8.dp))
                AdbTextField(
                    value = username,
                    onValueChange = { username = it; problem = null },
                    label = stringResource(R.string.stores_romm_username),
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                    focusOnShow = serverKnown,
                )
                Spacer(Modifier.height(8.dp))
                AdbTextField(
                    value = secret,
                    onValueChange = { secret = it; problem = null },
                    label = stringResource(R.string.stores_romm_password),
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onDone = { submit() },
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                TextButton(enabled = server.isNotBlank() && !busy, onClick = { pair() }, modifier = Modifier.focusGlide(CircleShape)) {
                    Text(stringResource(R.string.stores_romm_pair))
                }
            }
        },
        confirmButton = {
            if (pairing == null) TextButton(enabled = server.isNotBlank() && secret.isNotBlank() && !busy, onClick = { submit() }, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.game_env_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy || pairing != null, onClick = { cancelled.set(true); onDismiss() }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
