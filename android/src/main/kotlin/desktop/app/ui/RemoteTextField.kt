package desktop.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation

/** Remote focus browses; OK edits. A dismissed IME stays dismissed until the next OK. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RemoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val tv = LocalTv.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    var sawIme by remember { mutableStateOf(false) }

    fun finishEditing() {
        editing = false
        sawIme = false
        keyboard?.hide()
    }
    LaunchedEffect(editing) {
        if (tv && editing) {
            withFrameNanos { }
            keyboard?.show()
        }
    }
    LaunchedEffect(imeVisible, editing) {
        if (tv && editing) {
            if (imeVisible) sawIme = true
            else if (sawIme) finishEditing()
        }
    }
    BackHandler(enabled = tv && editing) { finishEditing() }
    val remoteModifier = if (!tv) Modifier else Modifier
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) { editing = false; sawIme = false }
        }
        .onPreviewKeyEvent { event ->
            if (!enabled || !focused || editing) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (event.type == KeyEventType.KeyUp) editing = true
                    true
                }
                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                    if (event.type == KeyEventType.KeyDown) focus.moveFocus(when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionLeft -> FocusDirection.Left
                        else -> FocusDirection.Right
                    })
                    true
                }
                else -> false
            }
        }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.then(remoteModifier), enabled = enabled,
        readOnly = tv && !editing,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = trailingIcon, supportingText = supportingText,
        singleLine = singleLine, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = if (!tv) keyboardActions else KeyboardActions(
            onDone = { finishEditing(); keyboardActions.onDone?.invoke(this) },
            onSearch = { finishEditing(); keyboardActions.onSearch?.invoke(this) },
            onGo = { finishEditing(); keyboardActions.onGo?.invoke(this) },
            onSend = { finishEditing(); keyboardActions.onSend?.invoke(this) },
            onNext = {
                finishEditing()
                keyboardActions.onNext?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Next)
            },
            onPrevious = {
                finishEditing()
                keyboardActions.onPrevious?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Previous)
            },
        ),
        shape = shape, colors = colors,
    )
}
