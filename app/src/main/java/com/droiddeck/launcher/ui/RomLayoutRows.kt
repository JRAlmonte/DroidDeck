package com.droiddeck.launcher.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.RomControls
import com.droiddeck.launcher.frontend.ScreenRects
import com.droiddeck.launcher.frontend.ScreenRects.Screen
import com.droiddeck.launcher.input.InputLibraries
import com.droiddeck.launcher.input.InputRole
import com.droiddeck.launcher.input.InputSelection
import com.droiddeck.launcher.input.SavedInputLayout
import com.droiddeck.launcher.input.inputFailure
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The profile [key] had before [ownProfile] gave it [id]; [made] when [id] is a copy made just now. */
private class ScopedCopy(val key: String, val id: String, val previous: InputSelection?) {
    val made get() = previous?.profile != id

    fun discard(context: Context) {
        if (made) runCatching { InputLibraries.update(context) { it.discardScopedProfile(key, id, previous) } }
    }
}

/** Gives the first scope of [chain] a profile of its own to edit, copied from what it inherits unless it already has one alone. */
private fun ownProfile(context: Context, chain: List<String>, name: String): Result<ScopedCopy> = runCatching {
    val key = chain.first()
    val previous = InputLibraries.read(context).scoped[key]
    var id = ""
    InputLibraries.update(context) { latest -> latest.ownScopedProfile(key, chain.drop(1), name).also { id = it.second }.first }
    ScopedCopy(key, id, previous)
}

@Composable
private fun scopedProfileName(key: String) = stringResource(R.string.rom_buttons_profile, scopeName(key))

/** Opens the touch layout editor on the profile the first scope of [chain] starts with, made its own first. */
@Composable
internal fun RomButtonsRow(chain: List<String>) {
    val key = chain.firstOrNull() ?: return
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ScopedCopy?>(null) }
    var changed by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val name = scopedProfileName(key)
    ActionRow(stringResource(R.string.rom_buttons_title), stringResource(R.string.rom_buttons_hint), stringResource(R.string.ctrl_edit), onClick = {
        ownProfile(context, chain, name).onSuccess { copy ->
            if (InputLibraries.read(context).layout(copy.id, InputRole.PRIMARY)?.editable == true) {
                changed = false
                editing = copy
            } else {
                copy.discard(context)
                problem = context.getString(R.string.rom_buttons_none)
            }
        }.onFailure { problem = it.inputFailure(context) }
    }, modifier = Modifier.testTag("rom-buttons-$key"))
    editing?.let { copy ->
        InputSurfaceDialog(copy.id, role = InputRole.PRIMARY, onChanged = { changed = true }, onDismiss = {
            if (!changed) copy.discard(context)
            editing = null
        })
    }
    problem?.let { message -> RomProblem(R.string.rom_buttons_title, message) { problem = null } }
}

/** Opens the button mapping page on the profile the first scope of [chain] starts with, made its own first. */
@Composable
internal fun RomMappingRow(chain: List<String>, open: ((String, (Boolean) -> Unit) -> Unit)?) {
    val key = chain.firstOrNull() ?: return
    if (open == null) return
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    val name = scopedProfileName(key)
    ActionRow(stringResource(R.string.rom_mapping_title), stringResource(R.string.rom_mapping_hint), stringResource(R.string.ctrl_edit), onClick = {
        ownProfile(context, chain, name).onSuccess { copy -> open(copy.id) { changed -> if (!changed) copy.discard(context) } }
            .onFailure { problem = it.inputFailure(context) }
    }, modifier = Modifier.testTag("rom-mapping-$key"))
    problem?.let { message -> RomProblem(R.string.rom_mapping_title, message) { problem = null } }
}

@Composable
private fun RomProblem(title: Int, message: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) }, text = { Text(message) },
        confirmButton = { PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } })
}

private fun scopeName(key: String) =
    if (key.startsWith("system:")) key.removePrefix("system:").uppercase() else key.substringAfterLast('/').substringBeforeLast('.').take(60)

/** Opens [ScreenArrangeDialog] for the first scope of [chain], starting from what it inherits; saving puts it on Azahar's custom layout. */
@Composable
internal fun RomScreenArrangeRow(chain: List<String>, onSaved: () -> Unit) {
    val key = chain.firstOrNull() ?: return
    val context = LocalContext.current
    var arranging by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val start = remember(revision, chain) { chain.firstNotNullOfOrNull { ScreenRects.parse(RomControls.get(context, it, RomControls.Setting.N3DS_SCREENS)) } }
    val inUse = start != null && RomControls.resolve(context, chain, RomControls.Setting.N3DS_LAYOUT) == ScreenRects.LAYOUT
    ActionRow(stringResource(R.string.rom_screen_arrange), stringResource(if (inUse) R.string.rom_screen_arrange_in_use else R.string.rom_screen_arrange_hint),
        stringResource(R.string.rom_screen_arrange_button), onClick = { arranging = true }, modifier = Modifier.testTag("rom-arrange-$key"))
    if (arranging) {
        val buttons = remember(chain) { runCatching { InputLibraries.read(context).forScope(chain).activeLayout(false, InputRole.PRIMARY) }.getOrNull() }
        val (w, h) = remember { InputLibraries.primaryDisplay(context).second.let { max(it.widthPixels, it.heightPixels) to minOf(it.widthPixels, it.heightPixels) } }
        ScreenArrangeDialog(start ?: ScreenRects.default(w, h), buttons, onDismiss = { arranging = false }) { rects ->
            RomControls.set(context, key, RomControls.Setting.N3DS_SCREENS, rects.format())
            RomControls.set(context, key, RomControls.Setting.N3DS_LAYOUT, ScreenRects.LAYOUT)
            arranging = false
            revision++
            onSaved()
        }
    }
}

@Composable
internal fun ScreenArrangeDialog(initial: ScreenRects, buttons: SavedInputLayout?, onDismiss: () -> Unit, onSave: (ScreenRects) -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(10.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(20.dp), color = p.background, contentColor = p.onBackground,
                border = BorderStroke(1.dp, p.line2)) {
                ScreenArrangeContent(initial, buttons, onDismiss, onSave)
            }
        }
    }
}

@Composable
internal fun ScreenArrangeContent(initial: ScreenRects, buttons: SavedInputLayout?, onDismiss: () -> Unit, onSave: (ScreenRects) -> Unit) {
    val p = LocalPalette.current
    var rects by remember { mutableStateOf(initial) }
    var picked by remember { mutableStateOf(Screen.BOTTOM) }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.rom_screen_arrange_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = p.onBackground)
        Text(stringResource(R.string.rom_screen_arrange_help) + if (buttons != null) " " + stringResource(R.string.rom_screen_arrange_buttons) else "",
            fontSize = 12.sp, color = p.onSurfaceVariant)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = rects.width / rects.height.toFloat()
            val wide = maxWidth / maxHeight > ratio
            Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = wide)
                .clip(RoundedCornerShape(6.dp)).background(Color.Black)
                .border(if (focused) 2.dp else 1.dp, if (focused) p.signal else p.line2, RoundedCornerShape(6.dp))
                .testTag("screen-arrange-canvas")) {
                ScreenArrangeCanvas(rects, picked, buttons, ratio, onFocus = { focused = it }, onPick = { picked = it }) { rects = it }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            for (screen in Screen.entries) {
                val label = stringResource(if (screen == Screen.TOP) R.string.rom_screen_top else R.string.rom_screen_bottom)
                SecondaryButton((if (picked == screen) "● " else "") + label, compact = true, modifier = Modifier.testTag("screen-pick-${screen.name.lowercase()}")) { picked = screen }
            }
            SecondaryButton(stringResource(R.string.rom_screen_smaller), compact = true) { rects = rects.scaled(picked, .9f) }
            SecondaryButton(stringResource(R.string.rom_screen_bigger), compact = true) { rects = rects.scaled(picked, 1.1f) }
            Text(stringResource(R.string.rom_screen_start_from), fontSize = 12.sp, color = p.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            for ((preset, label) in listOf(ScreenRects.Preset.SIDE_BY_SIDE to R.string.rom_screen_side_by_side,
                ScreenRects.Preset.STACKED to R.string.rom_screen_stacked, ScreenRects.Preset.LARGE_TOP to R.string.rom_screen_large)) {
                SecondaryButton(stringResource(label), compact = true, modifier = Modifier.testTag("screen-preset-${preset.name.lowercase()}")) {
                    rects = ScreenRects.preset(rects.width, rects.height, preset)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            PrimaryButton(stringResource(R.string.rom_screen_arrange_save), main = true, modifier = Modifier.testTag("screen-arrange-save")) { onSave(rects) }
        }
    }
}

@Composable
private fun ScreenArrangeCanvas(rects: ScreenRects, picked: Screen, buttons: SavedInputLayout?, ratio: Float,
    onFocus: (Boolean) -> Unit, onPick: (Screen) -> Unit, onChange: (ScreenRects) -> Unit) {
    val density = LocalDensity.current
    val signal = LocalPalette.current.signal
    var size by remember { mutableStateOf(IntSize.Zero) }
    val current by rememberUpdatedState(rects)
    val currentPick by rememberUpdatedState(picked)
    val handle = with(density) { 28.dp.toPx() }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }
        .onFocusChanged { onFocus(it.isFocused) }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (key.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            val step = max(4, current.width / 100)
            val box = current.box(currentPick)
            when (key.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { onChange(current.moved(currentPick, -step, 0)); box.x > 0 }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { onChange(current.moved(currentPick, step, 0)); box.x + box.w < current.width }
                KeyEvent.KEYCODE_DPAD_UP -> { onChange(current.moved(currentPick, 0, -step)); box.y > 0 }
                KeyEvent.KEYCODE_DPAD_DOWN -> { onChange(current.moved(currentPick, 0, step)); box.y + box.h < current.height }
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_MINUS -> { onChange(current.scaled(currentPick, .95f)); true }
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> { onChange(current.scaled(currentPick, 1.05f)); true }
                KeyEvent.KEYCODE_BUTTON_Y -> { onPick(if (currentPick == Screen.TOP) Screen.BOTTOM else Screen.TOP); true }
                else -> false
            }
        }
        .focusable()
        .pointerInput(Unit) {
            detectTapGestures { at -> hit(current, currentPick, at, size, 0f)?.let { onPick(it.first) } }
        }
        .pointerInput(Unit) {
            var start = current
            var mode: Pair<Screen, Boolean>? = null
            var moved = Offset.Zero
            detectDragGestures(
                onDragStart = { at ->
                    start = current
                    moved = Offset.Zero
                    mode = hit(current, currentPick, at, size, handle)?.also { onPick(it.first) }
                },
                onDragEnd = { mode = null },
                onDragCancel = { mode = null },
            ) { change, amount ->
                val (screen, resize) = mode ?: return@detectDragGestures
                change.consume()
                moved += amount
                val scale = size.width / start.width.toFloat()
                val dx = (moved.x / scale).roundToInt()
                val dy = (moved.y / scale).roundToInt()
                onChange(if (resize) {
                    val across = (dy * screen.aspect).roundToInt()
                    start.resized(screen, start.box(screen).w + if (abs(dx) >= abs(across)) dx else across)
                } else start.moved(screen, dx, dy))
            }
        }) {
        if (buttons != null) AndroidView(factory = { InputLayoutPreviewView(it) }, modifier = Modifier.matchParentSize().alpha(.5f),
            update = { it.update(buttons, InputRole.PRIMARY, ratio) })
        if (size.width > 0) {
            val scale = size.width / rects.width.toFloat()
            for (screen in listOf(Screen.TOP, Screen.BOTTOM).sortedBy { it == picked }) {
                val box = rects.box(screen)
                val chosen = screen == picked
                val shape = RoundedCornerShape(3.dp)
                Box(Modifier.offset { IntOffset((box.x * scale).roundToInt(), (box.y * scale).roundToInt()) }
                    .size(with(density) { (box.w * scale).toDp() }, with(density) { (box.h * scale).toDp() })
                    .background((if (screen == Screen.TOP) Color(0xFF3B6EA8) else Color(0xFF5A8F5A)).copy(alpha = if (chosen) .8f else .55f), shape)
                    .border(if (chosen) 2.dp else 1.dp, if (chosen) signal else Color.White.copy(alpha = .5f), shape)
                    .testTag("screen-box-${screen.name.lowercase()}"),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(if (screen == Screen.TOP) R.string.rom_screen_top else R.string.rom_screen_bottom),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (chosen) Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(14.dp).background(signal, CircleShape))
                }
            }
        }
    }
}

/** The screen under [at] and whether it is its resize corner; the picked screen wins where they overlap. */
private fun hit(rects: ScreenRects, picked: Screen, at: Offset, size: IntSize, corner: Float): Pair<Screen, Boolean>? {
    if (size.width <= 0) return null
    val scale = size.width / rects.width.toFloat()
    val order = listOf(picked, if (picked == Screen.TOP) Screen.BOTTOM else Screen.TOP)
    for (screen in order) {
        val b = rects.box(screen)
        val right = (b.x + b.w) * scale
        val bottom = (b.y + b.h) * scale
        if (corner > 0f && abs(at.x - right) <= corner && abs(at.y - bottom) <= corner) return screen to true
    }
    for (screen in order) {
        val b = rects.box(screen)
        if (at.x >= b.x * scale && at.x <= (b.x + b.w) * scale && at.y >= b.y * scale && at.y <= (b.y + b.h) * scale) return screen to false
    }
    return null
}
