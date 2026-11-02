package desktop.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalTv = staticCompositionLocalOf { false }

/** UI mode, rather than screen width: a landscape tablet still uses touch controls. */
@Composable
fun isTelevision(): Boolean =
    LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

/** Activity launch can precede window focus; request only once the window can accept remote input. */
@Composable
fun InitialTvFocus(requester: FocusRequester) {
    val tv = LocalTv.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(tv, windowFocused) {
        if (tv && windowFocused && !placed) {
            withFrameNanos { }
            placed = requester.requestFocus()
        }
    }
}

/** Draw inside the bounds so lazy lists and clipped artwork cannot cut off the focus outline. */
fun Modifier.tvFocusIndicator(): Modifier = composed {
    if (!LocalTv.current) return@composed this
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .border(3.dp, if (focused) Palette.accent else Color.Transparent, MaterialTheme.shapes.small)
}

/** A focus-contained TV picker; phones retain their bottom sheet interaction. */
@Composable
fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    sheetGesturesEnabled: Boolean = true,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalTv.current) {
        Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val focus = remember { FocusRequester() }
            val availableHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                focus.requestFocus()
            }
            Surface(
                modifier = Modifier.padding(24.dp).widthIn(max = 640.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = containerColor,
            ) {
                Column(Modifier.heightIn(max = (availableHeight - 48.dp).coerceAtLeast(0.dp))
                    .focusRequester(focus).focusGroup(), content = content)
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetGesturesEnabled = sheetGesturesEnabled,
            dragHandle = dragHandle,
            sheetMaxWidth = 640.dp,
            containerColor = containerColor,
            content = content,
        )
    }
}
