package com.cooper.wheellog.settings

import android.app.Activity
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Patterns
import androidx.annotation.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.cooper.wheellog.AppConfig
import com.cooper.wheellog.R
import com.cooper.wheellog.WheelLog
import com.cooper.wheellog.cj.Cj
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import com.cooper.wheellog.utils.ThemeEnum
import com.cooper.wheellog.utils.ThemeIconEnum
import com.cooper.wheellog.utils.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun clickablePref(
    name: String,
    modifier: Modifier = Modifier,
    themeIcon: ThemeIconEnum? = null,
    desc: String = "",
    showArrowIcon: Boolean = true,
    showDiv: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        baseSettings(
            name = name,
            themeIcon = themeIcon,
            desc = desc,
            showDiv = showDiv,
            rightContent = {
                if (showArrowIcon) Text("›", fontSize = 20.sp, color = Cj.dim)
            }
        )
    }
}

@Composable
fun switchPref(
    name: String,
    themeIcon: ThemeIconEnum? = null,
    desc: String = "",
    default: Boolean = false,
    defaultState: MutableState<Boolean>? = null,
    showDiv: Boolean = true,
    onClick: (checked: Boolean) -> Unit
) {
    val mutableState = defaultState ?: remember { mutableStateOf(default) }
    baseSettings(
        name = name,
        desc = desc,
        descAsHint = true,
        themeIcon = themeIcon,
        showDiv = showDiv,
        rightContent = {
            Switch(
                colors = cjSwitchColors(),
                checked = mutableState.value,
                onCheckedChange = {
                    mutableState.value = it
                    onClick(it)
                },
            )
        }
    )
}

@Composable
fun sliderPref(
    name: String,
    themeIcon: ThemeIconEnum? = null,
    desc: String = "",
    position: Float = 0f,
    min: Float = 0f,
    max: Float = 100f,
    @StringRes unit: Int = 0,
    visualMultiple: Float = 1f,
    format: String = "%.0f",
    showSwitch: Boolean = false,
    valueWhenSwitchOff: Float = min,
    disableSwitchAtMin: Boolean = false,
    showDiv: Boolean = true,
    onChanged: (newPosition: Float) -> Unit
) {
    // if the dialog is visible
    var isDialogShown by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(position * visualMultiple) }
    var prevPosition by remember { mutableFloatStateOf(position * visualMultiple) }
    val minV = min * visualMultiple
    val maxV = max * visualMultiple

    LaunchedEffect(position) {
        sliderPosition = position * visualMultiple
    }

    val showSlider = !(disableSwitchAtMin && sliderPosition == minV)
    val unitStr = if (unit != 0) {
        " " + stringResource(unit)
    } else {
        ""
    }

    if (isDialogShown) {
        val tv = String.format(Locale.US, format, sliderPosition)
        var textValue by remember { mutableStateOf(TextFieldValue(tv, TextRange(0, tv.length))) }
        val onDone = {
            isDialogShown = false
            sliderPosition = textValue.text.toFloat()
            onChanged(sliderPosition / visualMultiple)
        }
        var isError by remember { mutableStateOf(sliderPosition !in minV..maxV) }
        AlertDialog(
            onDismissRequest = {
                isDialogShown = false
            },
            title = {
                Text(name)
            },
            text = {
                var errorText by remember { mutableStateOf("") }
                val invalidNumberText = stringResource(R.string.invalid_number)
                val focusRequester = remember { FocusRequester() }
                TextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = textValue,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!isError) {
                                onDone()
                            }
                        }
                    ),
                    onValueChange = {
                        val f = it.text.toFloatOrNull()
                        if (f != null && f in minV..maxV) {
                            isError = false
                            errorText = ""
                        } else {
                            isError = true
                            errorText = when {
                                f == null -> invalidNumberText
                                f > maxV -> "Max = ${String.format(format, maxV)}"
                                f < minV -> "Min = ${String.format(format, minV)}"
                                else -> ""
                            }
                        }
                        textValue = it
                    },
                    singleLine = true,
                    suffix = {
                        Text(
                            text = unitStr,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    isError = isError,
                    supportingText = {
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    textStyle = TextStyle.Default.copy(fontSize = 32.sp),
                )

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            },
            dismissButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary
                    ),
                    onClick = {
                    isDialogShown = false
                }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            },
            confirmButton = {
                Button(
                    enabled = !isError,
                    onClick = onDone
                ) {
                    Text(stringResource(id = android.R.string.ok))
                }
            },
            properties = DialogProperties(
                dismissOnClickOutside = false,
                dismissOnBackPress = false,
            )
        )
    }

    baseSettings(
        name = name,
        desc = desc,
        descAsHint = true,
        themeIcon = themeIcon,
        showDiv = showDiv,
        rightContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSlider) Text(
                    String.format(Locale.US, format, sliderPosition) + unitStr.replace(" %", "%"),
                    fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Cj.ink,
                    modifier = Modifier.clickable { isDialogShown = true }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                )
                if (showSwitch) {
                    val offValue = valueWhenSwitchOff * visualMultiple
                    Switch(
                        colors = cjSwitchColors(),
                        modifier = Modifier.padding(start = 12.dp),
                        checked = disableSwitchAtMin && sliderPosition != offValue,
                        onCheckedChange = {
                            if (!it) {
                                prevPosition = sliderPosition
                                sliderPosition = offValue
                            } else {
                                if (prevPosition == offValue) {
                                    prevPosition = offValue + 1f
                                }
                                sliderPosition = prevPosition
                            }
                            onChanged(sliderPosition / visualMultiple)
                        },
                    )
                }
            }
        },
        bottomContent = {
            AnimatedVisibility(showSlider) {
                cjSlider(
                    value = sliderPosition,
                    onValueChange = { sliderPosition = it },
                    range = min * visualMultiple..max * visualMultiple,
                    onFinished = { onChanged(sliderPosition / visualMultiple) },
                )
            }
        }
    )
}

/** Ползунок как в макете: тонкая линия, оранжевая до ручки, круглая ручка. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun cjSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, onFinished: () -> Unit = {}) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        onValueChangeFinished = onFinished,
        interactionSource = src,
        modifier = Modifier.fillMaxWidth().height(32.dp),
        thumb = {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Cj.acc).border(3.dp, Color(0xFF0B0D10), CircleShape))
        },
        track = { st ->
            val f = ((st.value - st.valueRange.start) / (st.valueRange.endInclusive - st.valueRange.start)).coerceIn(0f, 1f)
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val y = size.height / 2
                drawLine(Color(0x22FFFFFF), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(Cj.acc, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * f, y), size.height, StrokeCap.Round)
            }
        },
    )
}

/** Выключатель в цветах приложения: включён - оранжевый. */
@Composable
fun cjSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFF1A1206),
    checkedTrackColor = Cj.acc,
    checkedBorderColor = Cj.acc,
    uncheckedThumbColor = Cj.mute,
    uncheckedTrackColor = Color(0x14FFFFFF),
    uncheckedBorderColor = Color(0x33FFFFFF),
)

/** Подпись раздела настроек: мелко, разрядкой, без рамки. */
@Composable
fun cjSection(name: String) {
    Text(
        name.uppercase(), fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = Cj.mute,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 2.dp),
    )
}

@Composable
fun group(
    name: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        cjSection(name)
        content()
    }
}

@Composable
fun list(
    name: String,
    modifier: Modifier = Modifier,
    desc: String = "",
    themeIcon: ThemeIconEnum? = null,
    entries: Map<String, String> = mapOf(),
    defaultKey: String = "",
    showDiv: Boolean = true,
    onSelect: (selected: Pair<String, String>) -> Unit = {},
) {
    val keys = entries.keys.toTypedArray()

    var selectedIndex by remember { mutableStateOf(defaultKey) }
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog && keys.isNotEmpty()) {
        AlertDialog(
            shape = RoundedCornerShape(8.dp),
            onDismissRequest = { showDialog = false },
            title = {
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    if (themeIcon != null) {
                        Icon(
                            painter = painterResource(id = ThemeManager.getId(themeIcon)),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp).padding(end = 8.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            },
            text = {
                LazyColumn {
                    items(items = keys) { key ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedIndex == key,
                                    onClick = {
                                        selectedIndex = key
                                        onSelect(Pair(key, entries[key] ?: ""))
                                        showDialog = false
                                    },
                                    role = Role.RadioButton
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedIndex == key,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(),
                                modifier = Modifier.padding(8.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entries[key] ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showDialog = false }) {
                    Text(stringResource(id = android.R.string.ok))
                }
            },
            properties = DialogProperties(
                dismissOnClickOutside = false,
            )
        )
    }

    if (keys.size in 2..3) {
        baseSettings(
            name = name,
            desc = desc,
            descAsHint = true,
            themeIcon = themeIcon,
            showDiv = showDiv,
            bottomContent = {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, Cj.hair, RoundedCornerShape(12.dp)).padding(3.dp)
                ) {
                    keys.forEach { key ->
                        val sel = selectedIndex == key
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                .background(if (sel) Cj.acc.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable { selectedIndex = key; onSelect(Pair(key, entries[key] ?: "")) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(entries[key] ?: "", fontSize = 13.sp, color = if (sel) Cj.acc else Cj.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
        )
        return
    }
    val onClick: () -> Unit = {
        showDialog = true
    }
    Surface(
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        baseSettings(
            name = name,
            desc = desc,
            themeIcon = themeIcon,
            showDiv = showDiv,
            rightContent = {
                if (selectedIndex != "") {
                    Text(
                        maxLines = 1,
                        text = (entries[selectedIndex] ?: "") + "  ›",
                        fontSize = 14.sp,
                        color = Cj.mute,
                        textAlign = TextAlign.End,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun multiList(
    name: String,
    modifier: Modifier = Modifier,
    desc: String = "",
    themeIcon: ThemeIconEnum? = null,
    entries: Map<String, String> = mapOf(),
    keyIcons: Map<String, Int> = mapOf(),
    defaultKeys: List<String> = listOf(),
    useSort: Boolean = false,
    showDiv: Boolean = true,
    onChecked: (selectedKeys: List<String>) -> Unit,
) {
    val keys = entries.keys.toList()
    var selectedIndex by remember { mutableStateOf(defaultKeys.distinct()) }
    // if selected keys are not in the list, then find them in the values
    if (!keys.containsAll(selectedIndex)) {
        selectedIndex = entries.filter { selectedIndex.contains(it.value) }.keys.toList()
    }
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            shape = RoundedCornerShape(8.dp),
            onDismissRequest = { showDialog = false },
            title = {
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    if (themeIcon != null) {
                        Icon(
                            painter = painterResource(id = ThemeManager.getId(themeIcon)),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp).padding(end = 8.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            },
            text = {
                val getItems = {
                    if (useSort) {
                        selectedIndex + keys.filter { i -> !selectedIndex.contains(i) }
                    } else {
                        keys
                    }
                }
                var items by remember {
                    mutableStateOf(getItems())
                }
                LazyColumn {
                    items(items = items, key = { it }) { key ->
                        val checked = selectedIndex.contains(key)
                        val onCheckedChange: (Boolean) -> Unit = {
                            selectedIndex = if (it) {
                                selectedIndex + key
                            } else {
                                selectedIndex - key
                            }
                            if (useSort) {
                                items = getItems()
                            }
                            onChecked(selectedIndex)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp)
                                .selectable(
                                    selected = checked,
                                    onClick = {
                                        onCheckedChange(!checked)
                                    },
                                    role = Role.Checkbox
                                )
                                .animateItem(
                                    placementSpec = tween(300)
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = onCheckedChange,
                                colors = CheckboxDefaults.colors(),
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (keyIcons.containsKey(key)) {
                                Icon(
                                    painter = painterResource(id = keyIcons[key]!!),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = entries[key] ?: "",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showDialog = false }) {
                    Text(stringResource(id = android.R.string.ok))
                }
            },
            properties = DialogProperties(
                dismissOnClickOutside = false,
            )
        )
    }

    Surface(
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
        onClick = { showDialog = true },
    ) {
        baseSettings(
            name = name,
            desc = desc,
            themeIcon = themeIcon,
            showDiv = showDiv,
            rightContent = {
                if (selectedIndex.isNotEmpty()) {
                    Text(
                        maxLines = 1,
                        text = selectedIndex.size.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        )
    }
}

@Composable
fun alarmsList(
    name: String,
    desc: String = "",
    themeIcon: ThemeIconEnum? = null,
    default: Uri = Uri.EMPTY,
    showDiv: Boolean = true,
    onSelect: (selected: Pair<String, Uri>) -> Unit = {},
) {
    val context = LocalContext.current
    val manager by lazy {
        RingtoneManager(context as Activity).apply {
            setType(RingtoneManager.TYPE_ALARM)
        }
    }

    val nameTitle = if (default != Uri.EMPTY) {
        manager.getRingtonePosition(default).let { position ->
            manager.getRingtone(position)?.getTitle(context)
        } ?: name
    } else {
        name
    }

    var perfName by remember { mutableStateOf(nameTitle) }
    var showDialog by remember { mutableStateOf(false) }

    clickablePref(
        name = perfName,
        desc = desc,
        themeIcon = themeIcon,
        showDiv = showDiv,
    ) {
        showDialog = true
    }

    if (showDialog) {
        val cursor = manager.cursor
        val ringtones = mutableListOf<Pair<String, Uri>>()
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
            val ringtoneURI = manager.getRingtoneUri(cursor.position)
            ringtones.add(title to ringtoneURI)
        }

        var currentRingtone: Ringtone? by remember { mutableStateOf(null) }
        var selectedIndex by remember { mutableStateOf(default) }
        val listState = rememberLazyListState()

        AlertDialog(
            onDismissRequest = { },
            title = {
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = stringResource(R.string.use_custom_beep_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            },
            text = {
                LazyColumn(state = listState) {
                    items(items = ringtones) { key ->
                        Row (
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedIndex == key.second,
                                    onClick = {
                                        val needPlay = selectedIndex != key.second ||
                                                currentRingtone == null ||
                                                currentRingtone?.isPlaying == false
                                        selectedIndex = key.second
                                        currentRingtone?.stop()
                                        if (needPlay) {
                                            currentRingtone = manager.getRingtone(manager.getRingtonePosition(key.second))
                                            currentRingtone?.play()
                                        }
                                        perfName = key.first
                                        onSelect(key)
                                    },
                                    role = Role.RadioButton
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedIndex == key.second,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(),
                                modifier = Modifier.padding(8.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = key.first,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                LaunchedEffect(key1 = Unit) {
                    val defaultIndex = ringtones.indexOfFirst { it.second == default }
                    if (defaultIndex != -1) {
                        listState.animateScrollToItem(defaultIndex)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDialog = false
                    currentRingtone?.stop()
                }) {
                    Text(stringResource(id = android.R.string.ok))
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            )
        )
    }
}

@Composable
fun clickableAndAlert(
    name: String,
    desc: String = "",
    alertDesc: String = "",
    themeIcon: ThemeIconEnum? = null,
    showDiv: Boolean = true,
    condition: () -> Boolean = { true },
    confirmButtonText: String = name,
    onConfirm: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(name) },
            text = {
                Text(alertDesc)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirm()
                        showDialog = false
                    },
                ) {
                    Text(confirmButtonText)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    clickablePref(
        name = name,
        desc = desc,
        themeIcon = themeIcon,
        showDiv = showDiv,
    ) {
        if (condition()) {
            showDialog = true
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun baseSettings(
    name: String,
    desc: String = "",
    themeIcon: ThemeIconEnum? = null,
    showDiv: Boolean = true,
    descAsHint: Boolean = false,
    rightContent: @Composable BoxScope.() -> Unit = { },
    bottomContent: @Composable (BoxScope.() -> Unit)? = null,
) {
    // Значок темы WheelLog не рисуем: в новом виде строки без значков.
    // Длинное пояснение у выключателей и ползунков - по долгому нажатию на название.
    val context = LocalContext.current
    val hint = descAsHint && desc.length > 40
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp)
                    .then(if (hint) Modifier.combinedClickable(onClick = {}, onLongClick = {
                        android.widget.Toast.makeText(context, desc, android.widget.Toast.LENGTH_LONG).show()
                    }) else Modifier),
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = name, fontSize = 15.sp, color = Cj.ink, overflow = TextOverflow.Ellipsis)
                if (desc.isNotEmpty() && !hint) {
                    Text(
                        modifier = Modifier.padding(top = 2.dp),
                        text = desc, fontSize = 12.sp, color = Cj.mute, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box { rightContent() }
        }
        if (bottomContent != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { bottomContent() }
        }
        if (showDiv) {
            HorizontalDivider(modifier = Modifier.padding(top = 14.dp), color = Cj.hair)
        } else {
            Spacer(modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
fun loginAlertDialog(
    title: String,
    loginPlaceholder: String = stringResource(R.string.email),
    passwordPlaceholder: String = stringResource(R.string.password),
    onDismiss: () -> Unit,
    /* return: success, error message */
    onConfirm: suspend (login: String, password: String) -> Pair<Boolean, String>,
){
    var login by rememberSaveable { mutableStateOf("") }
    var isLoginError by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val body = @Composable {
        Column {
            TextField(
                value = login,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                onValueChange = {
                    isLoginError = !Patterns.EMAIL_ADDRESS.matcher(it).matches()
                    login = it
                },
                placeholder = { Text(loginPlaceholder) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Login,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Color.Gray,
                    )
                },
                isError = isLoginError,
                supportingText = {
                    if (isLoginError && errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                trailingIcon = {
                    if (isLoginError) {
                        Icon(Icons.Filled.Error, "error", tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
            Spacer(modifier = Modifier.height(32.dp))
            TextField(
                value = password,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                onValueChange = { password = it },
                placeholder = { Text(passwordPlaceholder) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Password,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Color.Gray,
                    )
                },
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    val image = if (passwordVisible)
                        Icons.Filled.Visibility
                    else Icons.Filled.VisibilityOff
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(imageVector = image, contentDescription = null)
                    }
                },
            )
        }
    }
    var showDialog by rememberSaveable { mutableStateOf(true) }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = body,
            confirmButton = {
                Button(
                    onClick = {
                        isLoginError = false
                        errorMessage = ""
                        val scope = CoroutineScope(Dispatchers.IO + Job())
                        scope.launch {
                            val result = onConfirm(login, password)
                            if (result.first) {
                                showDialog = false
                            } else {
                                isLoginError = true
                                errorMessage = result.second
                            }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Preview
@Composable
private fun baseSettingsPreview(appConfig: AppConfig = koinInject()) {
    baseSettings(
        name = stringResource(R.string.auto_log_title),
        desc = stringResource(R.string.auto_log_description),
        themeIcon = ThemeIconEnum.SettingsAutoLog,
        rightContent = {
            Switch(
                checked = true,
                onCheckedChange = {},
            )
        },
        bottomContent = {
            Text("bottom")
        }
    )
}

@Preview
@Composable
private fun clickablePreview(appConfig: AppConfig = koinInject()) {
    clickablePref(
        name = stringResource(R.string.speed_settings_title),
        themeIcon = ThemeIconEnum.SettingsSpeedometer
    ) { }
}

@Preview
@Composable
private fun clickablePreview2(appConfig: AppConfig = koinInject()) {
    clickablePref(
        name = stringResource(R.string.donate_title),
        themeIcon = ThemeIconEnum.SettingsDonate,
        showArrowIcon = false
    ) { }
}

@Preview
@Composable
private fun clickablePreview3(appConfig: AppConfig = koinInject()) {
    clickablePref(
        name = stringResource(R.string.beep_on_volume_up_title),
        desc = stringResource(R.string.beep_on_volume_up_description),
        themeIcon = ThemeIconEnum.SettingsDonate,
        showArrowIcon = true
    ) { }
}

@Preview
@Composable
private fun clickablePreview4(appConfig: AppConfig = koinInject()) {
    clickablePref(
        name = stringResource(R.string.beep_on_volume_up_title),
        showArrowIcon = false
    ) { }
}

@Preview
@Composable
private fun switchPreview(appConfig: AppConfig = koinInject()) {
    switchPref(
        name = stringResource(R.string.use_eng_title),
        desc = stringResource(R.string.use_eng_description),
        themeIcon = ThemeIconEnum.SettingsLanguage,
        default = true
    ) { }
}

@Preview
@Composable
private fun switchPreview2(appConfig: AppConfig = koinInject()) {
    switchPref(
        name = stringResource(R.string.use_eng_title),
        themeIcon = ThemeIconEnum.SettingsLanguage,
        default = false
    ) { }
}

@Preview
@Composable
private fun sliderPreview(appConfig: AppConfig = koinInject()) {
    sliderPref(
        name = stringResource(R.string.alarm_1_battery_title),
        themeIcon = ThemeIconEnum.MenuMiBandAlarm,
        desc = stringResource(R.string.alarm_1_battery_description),
    ) { }
}

@Preview
@Composable
private fun sliderPreview2(appConfig: AppConfig = koinInject()) {
    sliderPref(
        name = stringResource(R.string.alarm_factor2_title),
        desc = stringResource(R.string.alarm_factor2_description),
        position = 50f,
        min = 10f,
        max = 60f,
        format = "%.2f"
    ) { }
}

@Preview
@Composable
private fun sliderPreview3(appConfig: AppConfig = koinInject()) {
    sliderPref(
        name = stringResource(R.string.alarm_factor2_title),
        position = 66.66f,
        min = 60f,
        max = 70f,
        visualMultiple = 2f,
        format = "%.2f"
    ) { }
}

@Preview
@Composable
private fun sliderPreview4(appConfig: AppConfig = koinInject()) {
    sliderPref(
        name = stringResource(R.string.warning_speed_period_title),
        desc = stringResource(R.string.warning_speed_period_description),
        position = 10f,
        min = 0f,
        max = 50f,
        showSwitch = true,
        disableSwitchAtMin = true,
        unit = R.string.sec,
        format = "%.0f"
    ) { }
}

@Preview
@Composable
private fun listPreview(appConfig: AppConfig = koinInject()) {
    list(
        name = stringResource(R.string.app_theme_title),
        desc = stringResource(R.string.app_theme_description),
        entries = ThemeEnum.values().associate { it.value.toString() to it.name },
        defaultKey = ThemeEnum.Original.value.toString(),
    )
}

@Preview
@Composable
private fun multiListPreview(appConfig: AppConfig = koinInject()) {
    multiList(
        name = stringResource(R.string.view_blocks_title),
        desc = stringResource(R.string.view_blocks_description),
        themeIcon = ThemeIconEnum.SettingsBlocks,
        entries = mapOf(
            "1" to "Just one",
            "2" to "Just two",
            "3" to "Just three",
            "4" to "Just four",
            "5" to "Just five",
            "6" to "Just six",
            "7" to "Just seven",
            "8" to "Just eight",
            "9" to "Just nine",
            "10" to "Just ten",
            "11" to "Just eleven",
            "12" to "Just twelve",
            "13" to "Just thirteen",
            "14" to "Just fourteen",
        ),
        defaultKeys = listOf("12", "2"),
    ) { }
}

@Preview
@Composable
fun loginAlertDialogPreview(appConfig: AppConfig = koinInject()) {
    loginAlertDialog(
        title = "Login Preview 3",
        onDismiss = { }
    ) { login, password ->
        suspendCoroutine {
            it.resume(Pair(login == "test" || password == "test", "Type 'test' as login"))
        }
    }
}