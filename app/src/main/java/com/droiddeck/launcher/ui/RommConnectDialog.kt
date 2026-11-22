package com.droiddeck.launcher.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.romm.RommAuth
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

    fun submit() {
        val address = server.trim()
        val name = username.trim()
        val key = if (name.isEmpty()) RommAuth.tokenIn(secret) ?: secret.trim() else secret
        if (address.isEmpty() || key.isEmpty() || busy) return
        busy = true
        problem = null
        val app = ctx.applicationContext
        Thread({
            val result = RommAuth.verify(address, name, key, "DroidDeck (${android.os.Build.MODEL})")
            val failure = when (result.problem) {
                RommAuth.Problem.UNREACHABLE -> R.string.stores_romm_unreachable
                RommAuth.Problem.NOT_ROMM -> R.string.stores_romm_not_romm
                RommAuth.Problem.TOO_OLD -> R.string.stores_romm_too_old
                RommAuth.Problem.BAD_TOKEN -> R.string.stores_romm_token_rejected
                RommAuth.Problem.BAD_LOGIN -> R.string.stores_romm_login_rejected
                RommAuth.Problem.NO_TOKEN -> R.string.stores_romm_no_token
                null -> if (runCatching { RommAuth.save(app, result.server, result.token, result.user!!) }.isSuccess) null else R.string.stores_romm_not_saved
            }
            StoresState.post {
                busy = false
                problem = failure
                if (failure == null) onDismiss()
            }
            if (failure == null) {
                StoresState.expired.remove(Store.ROMM)
                StoresState.refresh(app)
            }
        }, "romm-connect").start()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(Store.ROMM.label) },
        text = {
            Column {
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
            }
        },
        confirmButton = {
            TextButton(enabled = server.isNotBlank() && secret.isNotBlank() && !busy, onClick = { submit() }, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.game_env_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
