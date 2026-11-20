package com.droidbubble.app.tasks

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidbubble.app.ui.main.ProgressiveTopBlurBox
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val repository = remember { TasksRepository.getInstance(context) }
    val shortcuts by repository.shortcuts.collectAsState()

    var editingShortcut by remember { mutableStateOf<TaskShortcut?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var shortcutToDelete by remember { mutableStateOf<TaskShortcut?>(null) }

    BackHandler(onBack = {
        if (isCreatingNew || editingShortcut != null) {
            isCreatingNew = false
            editingShortcut = null
        } else {
            onBack()
        }
    })

    if (isCreatingNew || editingShortcut != null) {
        TaskEditorScreen(
            initialShortcut = editingShortcut,
            onSave = { saved ->
                repository.saveShortcut(saved)
                editingShortcut = null
                isCreatingNew = false
            },
            onBack = {
                editingShortcut = null
                isCreatingNew = false
            },
            modifier = modifier
        )
    } else {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text("Tasks", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        isCreatingNew = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Shortcut",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        ) { innerPadding ->
            if (shortcuts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(64.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Text(
                            text = "No shortcuts yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                val tasksListState = rememberLazyListState()
                val tasksIsScrolled by remember {
                    derivedStateOf {
                        tasksListState.firstVisibleItemIndex > 0 || tasksListState.firstVisibleItemScrollOffset > 6
                    }
                }
                ProgressiveTopBlurBox(
                    isScrolled = tasksIsScrolled,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    LazyColumn(
                        state = tasksListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shortcuts, key = { it.id }) { shortcut ->
                            TaskShortcutCard(
                                shortcut = shortcut,
                                onRun = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    runCatching {
                                        TaskExecutor.execute(context, shortcut)
                                    }.onFailure { e ->
                                        android.util.Log.e("TasksPage", "Task error", e)
                                    }
                                },
                                onToggleDroidbar = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    repository.saveShortcut(shortcut.copy(showInDroidbar = !shortcut.showInDroidbar))
                                },
                                onToggleAutomation = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    repository.saveShortcut(shortcut.copy(automationEnabled = !shortcut.automationEnabled))
                                },
                                onEdit = { editingShortcut = shortcut },
                                onDelete = { shortcutToDelete = shortcut }
                            )
                        }
                    }
                }
            }
        }
    }

    if (shortcutToDelete != null) {
        val s = shortcutToDelete!!
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { shortcutToDelete = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Delete Shortcut",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Are you sure you want to delete \"${s.name}\"? This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { shortcutToDelete = null },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            repository.deleteShortcut(s.id)
                            shortcutToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskShortcutCard(
    shortcut: TaskShortcut,
    onRun: () -> Unit,
    onToggleDroidbar: () -> Unit,
    onToggleAutomation: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()
    var showOptionsSheet by remember { mutableStateOf(false) }

    fun triggerBounce() {
        coroutineScope.launch {
            scale.animateTo(0.96f, tween(60))
            scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f))
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(20.dp))
            .clickable {
                triggerBounce()
                onRun()
            }
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = shortcut.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            },
            supportingContent = if (shortcut.triggers.isNotEmpty()) {
                {
                    val summary = shortcut.triggers.joinToString(" • ") { it.getDisplayName() }
                    val statusPrefix = if (shortcut.automationEnabled) "Auto: " else "Paused: "
                    Text(
                        text = statusPrefix + summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (shortcut.automationEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else null,
            leadingContent = {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = TaskIcons.getIcon(shortcut.iconName),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            },
            trailingContent = {
                IconButton(onClick = { showOptionsSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showOptionsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = shortcut.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                FilledTonalButton(
                    onClick = {
                        showOptionsSheet = false
                        triggerBounce()
                        onRun()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Shortcut Now", fontWeight = FontWeight.Bold)
                }

                if (shortcut.triggers.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = {
                            showOptionsSheet = false
                            onToggleAutomation()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            if (shortcut.automationEnabled) Icons.Default.PauseCircleOutline else Icons.Default.Schedule,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (shortcut.automationEnabled) "Pause Automations" else "Enable Automations",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                FilledTonalButton(
                    onClick = {
                        showOptionsSheet = false
                        onToggleDroidbar()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.VerticalSplit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (shortcut.showInDroidbar) "Remove from Droidbar" else "Add to Droidbar",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                FilledTonalButton(
                    onClick = {
                        showOptionsSheet = false
                        onEdit()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit Shortcut", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        showOptionsSheet = false
                        onDelete()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Shortcut", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
