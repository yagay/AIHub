package com.yagay.aihub.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.model.AttachmentMeta
import com.yagay.aihub.model.ChatMessage
import com.yagay.aihub.model.ChatWindow
import com.yagay.aihub.model.MessageRole
import com.yagay.aihub.model.WindowViewMode
import com.yagay.aihub.provider.ProviderCatalog
import com.yagay.aihub.web.WindowWebRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceRoot(runtime: WindowWebRuntime) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm: WorkspaceViewModel = viewModel(factory = WorkspaceViewModel.Factory(application))
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var nativePickerTarget by remember { mutableStateOf<String?>(null) }

    val nativeAttachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val windowId = nativePickerTarget
        nativePickerTarget = null

        if (windowId != null && uris.isNotEmpty()) {
            val window = vm.windows.firstOrNull { it.id == windowId }
            if (window != null) {
                val provider = ProviderCatalog.byId(window.providerId)
                scope.launch {
                    val result = runCatching {
                        runtime.attachFiles(windowId, provider, uris)
                    }.onFailure {
                        DiagnosticLogger.e(
                            "FILE",
                            "workspace_attachment_failed provider=${provider.id}",
                            it
                        )
                    }.getOrNull()

                    if (result == null || result.attachedCount <= 0) {
                        Toast.makeText(
                            context,
                            "${provider.name} 没有接收文件，请在 YBrowser 网页检查。",
                            Toast.LENGTH_LONG
                        ).show()
                        runtime.openWeb(
                            window,
                            provider,
                        )
                    }
                }
            }
        }
    }

    val exportDiagnostics = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    DiagnosticLogger.export(context.applicationContext, uri)
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) "诊断日志已导出" else "导出失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        vm.activeWindow.id,
        vm.activeWindow.url,
    ) {
        runtime.ensureSession(vm.activeWindow)
        vm.refreshConversationFromBridge(
            vm.activeWindow.id
        )
    }

    DisposableEffect(runtime) {
        runtime.setFileSelectionListener {
                windowId,
                _,
                attachments,
            ->
            vm.onAttachments(
                windowId,
                attachments,
            )
        }
        runtime.setPageChangeListener {
                windowId,
                provider,
                url,
            ->
            vm.onPageChanged(
                windowId,
                provider,
                url,
            )
        }
        runtime.setHistoryChangeListener { windowId ->
            val window =
                vm.windows.firstOrNull {
                    it.id == windowId
                }
            if (window != null) {
                val provider =
                    ProviderCatalog.byId(
                        window.providerId
                    )
                runtime.currentUrl(
                    windowId,
                    provider,
                )?.let { url ->
                    vm.onPageChanged(
                        windowId,
                        provider,
                        url,
                    )
                }
            }
            vm.refreshConversationFromBridge(
                windowId
            )
        }

        onDispose {
            runtime.setFileSelectionListener(null)
            runtime.setPageChangeListener(null)
            runtime.setHistoryChangeListener(null)
            runtime.destroy()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.50f)) {
                Spacer(Modifier.height(16.dp))

                Text(
                    "AIHub",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )

                Button(
                    onClick = {
                        vm.newWindow()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("新建聊天窗口", modifier = Modifier.padding(start = 8.dp))
                }

                vm.providers.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                vm.newWindow(provider.id)
                                scope.launch { drawerState.close() }
                            }
                        ) {
                            Text("+ 新窗口")
                        }
                    }

                    vm.windowsFor(provider.id).forEach { window ->
                        NavigationDrawerItem(
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        window.title,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    when {
                                        window.generating -> Text(" ⟳")
                                        window.unread -> Text(
                                            " ●",
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            selected = window.id == vm.activeWindowId,
                            onClick = {
                                vm.switchWindow(window.id)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                NavigationDrawerItem(
                    label = { Text("导出诊断日志") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        exportDiagnostics.launch(DiagnosticLogger.suggestedFileName())
                    },
                    icon = { Icon(Icons.Outlined.BugReport, null) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    vm.activeWindow.title,
                                    maxLines = 1,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    vm.activeProvider.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch { drawerState.open() }
                                }
                            ) {
                                Icon(Icons.Default.Menu, "窗口列表")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    runtime.openWeb(
                                        vm.activeWindow,
                                        vm.activeProvider,
                                    )
                                }
                            ) {
                                Text("网页")
                            }

                            IconButton(
                                onClick = {
                                    vm.newWindow(vm.activeWindow.providerId)
                                }
                            ) {
                                Icon(Icons.Default.Add, "新窗口")
                            }
                        }
                    )

                    WindowTabStrip(
                        windows = vm.windows,
                        activeWindowId = vm.activeWindowId,
                        onSelect = vm::switchWindow,
                        onClose = { id ->
                            vm.closeWindow(id, runtime)
                        }
                    )
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                NativeChatPane(
                    messages = vm.messages,
                    status = vm.activeStatus,
                    draft = vm.activeDraft,
                    onDraftChange = vm::updateDraft,
                    generating = vm.activeWindow.generating,
                    attachments = vm.activePendingAttachments,
                    onAttach = {
                        nativePickerTarget = vm.activeWindow.id
                        nativeAttachmentPicker.launch(arrayOf("*/*"))
                    },
                    onSend = {
                        vm.send(runtime)
                    },
                    onStop = {
                        vm.stop(runtime)
                    },
                    visible = true
                )
            }
        }
    }
}

@Composable
private fun WindowTabStrip(
    windows: List<ChatWindow>,
    activeWindowId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(windows, key = { it.id }) { window ->
            val selected = window.id == activeWindowId

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onSelect(window.id) }
                        .padding(start = 12.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append(window.title)
                            when {
                                window.generating -> append(" ⟳")
                                window.unread -> append(" ●")
                            }
                        },
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )

                    IconButton(
                        onClick = {
                            onClose(window.id)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            "关闭窗口",
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeChatPane(
    messages: List<ChatMessage>,
    status: String?,
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    attachments: List<AttachmentMeta>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    visible: Boolean,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followBottom by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }

    LaunchedEffect(
        atBottom,
        listState.isScrollInProgress,
    ) {
        when {
            atBottom -> followBottom = true
            listState.isScrollInProgress ->
                followBottom = false
        }
    }

    LaunchedEffect(
        messages.size,
        generating,
        visible,
    ) {
        if (
            visible &&
            followBottom &&
            messages.isNotEmpty()
        ) {
            listState.animateScrollToItem(
                messages.lastIndex
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .alpha(if (visible) 1f else 0f)
            .zIndex(if (visible) 1f else -1f)
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 20.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement =
                    Arrangement.spacedBy(20.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = 72.dp,
                                    start = 24.dp,
                                    end = 24.dp,
                                ),
                            horizontalAlignment =
                                Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color =
                                    MaterialTheme.colorScheme
                                        .primaryContainer,
                            ) {
                                Box(
                                    Modifier.size(64.dp),
                                    contentAlignment =
                                        Alignment.Center,
                                ) {
                                    Text(
                                        "AI",
                                        style =
                                            MaterialTheme.typography
                                                .headlineMedium,
                                        fontWeight =
                                            FontWeight.SemiBold,
                                    )
                                }
                            }

                            Spacer(Modifier.height(18.dp))

                            Text(
                                "开始聊天",
                                style =
                                    MaterialTheme.typography
                                        .headlineSmall,
                            )

                            Text(
                                "聊天内容由 YBrowser 同步，项目切换不会重新加载网页。",
                                style =
                                    MaterialTheme.typography
                                        .bodyMedium,
                                color =
                                    MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                modifier = Modifier
                                    .widthIn(max = 560.dp)
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                }

                items(
                    messages,
                    key = { it.id },
                ) { message ->
                    MessageBubble(message)
                }

                if (status != null) {
                    item {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth(),
                            contentAlignment =
                                Alignment.Center,
                        ) {
                            Text(
                                status,
                                style =
                                    MaterialTheme.typography
                                        .bodySmall,
                                color =
                                    MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                modifier = Modifier
                                    .widthIn(max = 760.dp)
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = 18.dp,
                                    ),
                            )
                        }
                    }
                }
            }

            if (
                !followBottom &&
                listState.canScrollForward
            ) {
                FilledIconButton(
                    onClick = {
                        followBottom = true
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(
                                    messages.lastIndex
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .size(42.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription =
                            "回到最新消息",
                    )
                }
            }
        }

        ChatComposer(
            draft = draft,
            onDraftChange = onDraftChange,
            generating = generating,
            attachments = attachments,
            onAttach = onAttach,
            onSend = {
                followBottom = true
                onSend()
            },
            onStop = onStop,
        )
    }
}

@Composable
private fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    attachments: List<AttachmentMeta>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 10.dp,
                        vertical = 8.dp,
                    )
            ) {
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(
                                rememberScrollState()
                            ),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                    ) {
                        attachments.forEach { attachment ->
                            Surface(
                                shape =
                                    RoundedCornerShape(
                                        12.dp
                                    ),
                                color =
                                    MaterialTheme.colorScheme
                                        .surfaceContainerHigh,
                            ) {
                                Column(
                                    modifier =
                                        Modifier.padding(
                                            horizontal = 10.dp,
                                            vertical = 7.dp,
                                        ),
                                ) {
                                    Text(
                                        attachment.name,
                                        style =
                                            MaterialTheme.typography
                                                .labelMedium,
                                        maxLines = 1,
                                    )
                                    if (
                                        attachment.sizeBytes > 0
                                    ) {
                                        Text(
                                            formatFileSize(
                                                attachment
                                                    .sizeBytes
                                            ),
                                            style =
                                                MaterialTheme.typography
                                                    .labelSmall,
                                            color =
                                                MaterialTheme.colorScheme
                                                    .onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }

                Row(
                    verticalAlignment =
                        Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = onAttach,
                        enabled = !generating,
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            "添加附件",
                        )
                    }

                    TextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("询问任何问题")
                        },
                        minLines = 1,
                        maxLines = 7,
                        shape = RoundedCornerShape(24.dp),
                        colors =
                            TextFieldDefaults.colors(
                                focusedIndicatorColor =
                                    androidx.compose.ui
                                        .graphics.Color
                                        .Transparent,
                                unfocusedIndicatorColor =
                                    androidx.compose.ui
                                        .graphics.Color
                                        .Transparent,
                                disabledIndicatorColor =
                                    androidx.compose.ui
                                        .graphics.Color
                                        .Transparent,
                            ),
                    )

                    Spacer(Modifier.size(6.dp))

                    FilledIconButton(
                        onClick =
                            if (generating) {
                                onStop
                            } else {
                                onSend
                            },
                        enabled =
                            generating ||
                                draft.isNotBlank() ||
                                attachments.isNotEmpty(),
                    ) {
                        Icon(
                            if (generating) {
                                Icons.Default.Stop
                            } else {
                                Icons.Default.Send
                            },
                            if (generating) {
                                "停止"
                            } else {
                                "发送"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
) {
    val mine =
        message.role == MessageRole.USER
    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalAlignment =
                if (mine) {
                    Alignment.End
                } else {
                    Alignment.Start
                },
        ) {
            if (mine) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color =
                        MaterialTheme.colorScheme
                            .surfaceContainerHigh,
                    modifier =
                        Modifier.fillMaxWidth(0.86f),
                ) {
                    SelectionContainer {
                        Text(
                            message.text,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(
                                        lineHeight = 25.sp
                                    ),
                            modifier =
                                Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 11.dp,
                                ),
                        )
                    }
                }
            } else {
                SelectionContainer {
                    Text(
                        message.text,
                        style =
                            MaterialTheme.typography
                                .bodyLarge
                                .copy(
                                    lineHeight = 26.sp
                                ),
                        modifier =
                            Modifier.fillMaxWidth(),
                    )
                }
            }

            if (message.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState()
                        ),
                    horizontalArrangement =
                        if (mine) {
                            Arrangement.End
                        } else {
                            Arrangement.Start
                        },
                ) {
                    message.attachments.forEach {
                        attachment ->
                        Surface(
                            shape =
                                RoundedCornerShape(10.dp),
                            color =
                                MaterialTheme.colorScheme
                                    .surfaceContainer,
                            modifier =
                                Modifier.padding(
                                    end = 8.dp
                                ),
                        ) {
                            Text(
                                "📎 " + attachment.name,
                                style =
                                    MaterialTheme.typography
                                        .labelMedium,
                                modifier =
                                    Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 7.dp,
                                    ),
                            )
                        }
                    }
                }
            }

            if (!mine) {
                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "AI reply",
                                message.text,
                            )
                        )
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(17.dp),
                        tint =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatFileSize(
    bytes: Long,
): String = when {
    bytes >= 1024L * 1024L ->
        String.format(
            "%.1f MB",
            bytes.toDouble() /
                (1024.0 * 1024.0),
        )
    bytes >= 1024L ->
        String.format(
            "%.1f KB",
            bytes.toDouble() / 1024.0,
        )
    else -> bytes.toString() + " B"
}
