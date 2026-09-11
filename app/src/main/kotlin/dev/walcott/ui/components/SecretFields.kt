package dev.walcott.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.ui.theme.Tokens

/**
 * "Choose a PIN, then type it again", as a value rather than as four booleans on a screen.
 *
 * Pure, because this is the rule that decides what becomes a family's PIN — the one that opens
 * parent mode and frees a child's phone — and a rule that important should be settled in a test
 * rather than by tapping through a dialog. The two halves are deliberately never both on screen:
 * a person who mistypes the same thing twice into two boxes they can see has confirmed nothing,
 * which is why every lock screen on earth asks for it a second time from memory.
 */
data class PinSetup(
    /** What the first step settled on, once it is behind us. */
    val chosen: String = "",
    /** What is being typed right now, in whichever step this is. */
    val entry: String = "",
    /** False while choosing, true while typing it again. */
    val confirming: Boolean = false,
    /** The last attempt disagreed with itself, so we are back at the first step. */
    val mismatch: Boolean = false,
    /** Non-null once both halves agreed: the PIN to save. */
    val completed: String? = null,
) {

    /** Digits only, capped — everything else a keyboard can produce is dropped on the way in. */
    fun typed(text: String, maxLength: Int): PinSetup =
        copy(entry = text.filter(Char::isDigit).take(maxLength), mismatch = false)

    /** Whether the current step holds enough to move on. */
    fun canAdvance(minLength: Int): Boolean = entry.length >= minLength

    /**
     * Moves on: from choosing to confirming, or from confirming to done.
     *
     * A disagreement throws BOTH halves away and starts again, rather than keeping the first and
     * asking for the second once more. Whichever of the two was the typo, nobody knows which —
     * and a PIN the family is unsure of is not one to guard a phone with.
     */
    fun advance(minLength: Int): PinSetup = when {
        !canAdvance(minLength) -> this
        !confirming -> copy(chosen = entry, entry = "", confirming = true, mismatch = false)
        entry == chosen -> copy(completed = entry)
        else -> PinSetup(mismatch = true)
    }
}

/**
 * The digits of a PIN or a code, one box each.
 *
 * Boxes rather than a text field with dots in it, and that is the whole point: the person typing
 * can see how many digits are expected and how many they have got, which a row of identical dots
 * behind a label cannot tell them. It is also what every phone's own lock screen looks like, so
 * nobody has to be taught what it is.
 *
 * The field itself is real — a [BasicTextField] with its decoration replaced — so the keyboard,
 * pasting a code somebody sent, and accessibility all keep working. [slots] is a MINIMUM: a
 * seventh and eighth box appear if the family's PIN is longer, because a fixed six would quietly
 * refuse a PIN this app accepts.
 */
@Composable
fun PinEntryField(
    value: String,
    onValueChange: (String) -> Unit,
    /** What a screen reader announces this as. */
    label: String,
    modifier: Modifier = Modifier,
    slots: Int = 6,
    maxLength: Int = 8,
    enabled: Boolean = true,
    isError: Boolean = false,
    /** Ask for focus (and the keyboard) as soon as this appears. */
    autoFocus: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    /**
     * Whether the digits are hidden behind dots, with an eye to reveal them.
     *
     * False for the codes that are not secrets from the person typing them — a rescue code read
     * out over the phone, a lock PIN the parent is about to dictate. Hiding those helps nobody
     * and makes six digits down a bad line harder than they already are.
     */
    masked: Boolean = true,
) {
    val spacing = Tokens.spacing
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var unmasked by remember { mutableStateOf(false) }
    val revealed = !masked || unmasked
    val shake = remember { Animatable(0f) }
    val amplitude = remember(density) { with(density) { 7.dp.toPx() } }

    // A wrong PIN is felt as well as read: the row refuses, the way a lock refuses, and the
    // error text underneath explains it. Short enough to stay out of the way of typing again.
    LaunchedEffect(isError) {
        if (!isError) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        // The keyboard closes when the entry is submitted, and a refusal means typing again —
        // so take it back rather than leaving somebody to tap the boxes to reopen it.
        if (enabled) runCatching { focusRequester.requestFocus() }
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 240
                0f at 0
                -amplitude at 40
                amplitude at 90
                -amplitude * 0.6f at 140
                amplitude * 0.3f at 190
                0f at 240
            },
        )
    }

    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }

    val shown = maxOf(slots, value.length).coerceAtMost(maxLength)

    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxLength)) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction() },
                onNext = { onImeAction() },
                onGo = { onImeAction() },
            ),
            // The text itself is never drawn: the boxes below are the rendering of it, and a
            // caret blinking behind them would be a second, disagreeing cursor.
            textStyle = LocalTextStyle.current.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .graphicsLayer { translationX = shake.value }
                .semantics { contentDescription = label },
            decorationBox = {
                // Sized against the room there actually is, rather than by weight: eight boxes
                // and an eye inside a dialog would otherwise come out as tall thin slivers, and
                // six across a whole screen as billboards. Proportion is the thing being kept.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val eye = if (masked) EYE_WIDTH else 0.dp
                    val gaps = spacing.sm * (shown - 1).coerceAtLeast(0)
                    val slotWidth = ((maxWidth - eye - gaps) / shown).coerceIn(MIN_SLOT, MAX_SLOT)
                    val slotHeight = (slotWidth * SLOT_RATIO).coerceIn(MIN_SLOT_HEIGHT, MAX_SLOT_HEIGHT)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(shown) { index ->
                            PinSlot(
                                digit = value.getOrNull(index),
                                revealed = revealed,
                                // Where the next digit lands, so there is somewhere to look.
                                active = enabled && index == value.length,
                                isError = isError,
                                modifier = Modifier.size(slotWidth, slotHeight),
                            )
                        }
                        if (masked) {
                            IconButton(onClick = { unmasked = !unmasked }, enabled = enabled) {
                                Icon(
                                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (revealed) R.string.secret_hide else R.string.secret_show,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * A PIN this phone already knows, in the same boxes it was typed into — for the one screen that
 * shows a PIN rather than asking for one (see the parent's "show my PIN").
 */
@Composable
fun PinReadout(pin: String, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val slots = pin.length.coerceAtLeast(1)
        val gaps = spacing.sm * (slots - 1).coerceAtLeast(0)
        val slotWidth = ((maxWidth - gaps) / slots).coerceIn(MIN_SLOT, MAX_SLOT)
        val slotHeight = (slotWidth * SLOT_RATIO).coerceIn(MIN_SLOT_HEIGHT, MAX_SLOT_HEIGHT)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pin.forEach { digit ->
                PinSlot(
                    digit = digit,
                    revealed = true,
                    active = false,
                    isError = false,
                    modifier = Modifier.size(slotWidth, slotHeight),
                )
            }
        }
    }
}

/** One digit's worth of box: empty, filled, or showing the digit itself. */
@Composable
private fun PinSlot(
    digit: Char?,
    revealed: Boolean,
    active: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = Tokens.motion
    val border by animateColorAsState(
        when {
            isError -> scheme.error
            active -> scheme.primary
            digit != null -> scheme.outline
            else -> scheme.outlineVariant
        },
        tween(motion.fast),
        label = "slot border",
    )
    val width by animateFloatAsState(
        if (active || isError) 2f else 1f,
        tween(motion.fast),
        label = "slot border width",
    )
    val fill by animateColorAsState(
        // A filled box is tinted, not just dotted: how far along you are should be readable
        // without counting dots, and at a glance from arm's length.
        if (digit != null) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surfaceVariant.copy(alpha = 0.5f),
        tween(motion.fast),
        label = "slot fill",
    )
    val shape = RoundedCornerShape(SLOT_CORNER)
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(width.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = digit != null,
            // Lands rather than appears: the spring is what makes typing feel answered.
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.5f) + fadeIn(),
            exit = scaleOut(targetScale = 0.5f) + fadeOut(),
        ) {
            if (revealed) {
                Text(
                    digit?.toString().orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            } else {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurface),
                )
            }
        }
    }
}

/**
 * A passphrase, with the two things a passphrase needs and a password box never gives it: an eye,
 * and an honest answer to "is that long enough yet".
 *
 * The eye matters more here than anywhere else in the app. A backup passphrase has no reset — a
 * typo in it is a family's rules gone — and nobody can proof-read twelve characters of dots.
 */
@Composable
fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Shows how far along a minimum length is, and holds the field short of it. */
    minLength: Int? = null,
    /** For a "type it again" field: true ticks, false is an error, null is "not yet". */
    matches: Boolean? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    autoFocus: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    val spacing = Tokens.spacing
    var revealed by remember { mutableStateOf(false) }
    // How many characters are still missing, or null when nothing is short of anything.
    val missing = minLength
        ?.takeIf { value.isNotEmpty() && value.length < it }
        ?.minus(value.length)
    val error = isError || matches == false

    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            isError = error,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction() },
                onNext = { onImeAction() },
                onGo = { onImeAction() },
            ),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The tick is the whole feedback a repeat field needs: it says "these agree"
                    // while you type, instead of waiting for a button to say they do not.
                    AnimatedVisibility(
                        visible = matches == true,
                        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.secret_matches),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { revealed = !revealed }, enabled = enabled) {
                        Icon(
                            if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (revealed) R.string.secret_hide else R.string.secret_show,
                            ),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        // Progress rather than a scolding: the bar fills as they type and the line counts down,
        // so "at least twelve characters" stops being a rule they find out they broke.
        if (missing != null && minLength != null) {
            val progress by animateFloatAsState(
                value.length.toFloat() / minLength,
                tween(Tokens.motion.fast),
                label = "passphrase length",
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
            )
        }
        val helper = supportingText
            ?: missing?.let { pluralStringResource(R.plurals.secret_chars_left, it, it) }
        helper?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs, start = spacing.md),
            )
        }
    }
}

/** How a box is shaped, wherever it is asked to fit (a dialog is far narrower than a screen). */
private val MIN_SLOT = 28.dp
private val MAX_SLOT = 52.dp
private const val SLOT_RATIO = 1.3f
private val MIN_SLOT_HEIGHT = 40.dp
private val MAX_SLOT_HEIGHT = 58.dp
private val SLOT_CORNER = 10.dp

/** What the reveal button takes out of the row it sits in. */
private val EYE_WIDTH = 48.dp
