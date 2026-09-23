package com.yagay.aihub.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
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
fun WorkspaceRoot(
    runtime: WindowWebRuntime,
    launchIntent: Intent? = null,
    launchRevision: Int = 0,
    resumeRevision: Int = 0,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm: WorkspaceViewModel = viewModel(factory = WorkspaceViewModel.Factory(application))
    LaunchedEffect(launchRevision) {
        vm.handleLaunchIntent(launchIntent)
    }
    LaunchedEffect(resumeRevision) {
        if (resumeRevision > 1) {
            vm.refreshConversationFromBridge(vm.activeWindowId)
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var nativePickerTarget by remember { mutableStateOf<String?>(null) }
    var bindingActionWindowId by remember { mutableStateOf<String?>(null) }
    var deleteActionWindowId by remember { mutableStateOf<String?>(null) }

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
        vm.activeWindow.boundUrl,
    ) {
        runtime.ensureSession(vm.activeWindow)
        vm.refreshConversationFromBridge(
            vm.activeWindow.id
        )
        vm.syncConversation(
            runtime,
            vm.activeWindow.id,
        )
    }

    val bindingActionWindow = vm.windows.firstOrNull {
        it.id == bindingActionWindowId
    }
    if (bindingActionWindow != null) {
        val projectName = bindingActionWindow.boundProject.orEmpty()
            .ifBlank { bindingActionWindow.title }
        AlertDialog(
            onDismissRequest = { bindingActionWindowId = null },
            title = { Text(projectName) },
            text = {
                Text(
                    if (bindingActionWindow.boundUrl.isNullOrBlank()) {
                        "这个聊天还没有绑定项目。可以绑定项目，或直接删除这个聊天。"
                    } else {
                        "可以重新绑定、解除当前项目绑定，或删除这个聊天。"
                    }
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            val id = bindingActionWindow.id
                            bindingActionWindowId = null
                            vm.requestBinding(id)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (bindingActionWindow.boundUrl.isNullOrBlank()) {
                                "绑定项目"
                            } else {
                                "重新绑定"
                            }
                        )
                    }

                    if (!bindingActionWindow.boundUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                val id = bindingActionWindow.id
                                bindingActionWindowId = null
                                vm.unbindWindow(id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("解除绑定")
                        }
                    }

                    TextButton(
                        onClick = {
                            val id = bindingActionWindow.id
                            bindingActionWindowId = null
                            deleteActionWindowId = id
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除聊天")
                    }

                    TextButton(
                        onClick = {
                            bindingActionWindowId = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("取消")
                    }
                }
            }
        )
    }

    val deleteActionWindow = vm.windows.firstOrNull {
        it.id == deleteActionWindowId
    }
    if (deleteActionWindow != null) {
        val displayName =
            deleteActionWindow.boundProject.orEmpty()
                .ifBlank { deleteActionWindow.title }
                .ifBlank { "聊天" }

        AlertDialog(
            onDismissRequest = {
                deleteActionWindowId = null
            },
            title = {
                Text("删除聊天")
            },
            text = {
                Text(
                    if (deleteActionWindow.boundUrl.isNullOrBlank()) {
                        "确定删除“$displayName”吗？AIHub 中的这个聊天标签会被删除。"
                    } else {
                        "确定删除“$displayName”吗？项目绑定和 AIHub 中的这个聊天标签会一起删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleteActionWindow.id
                        deleteActionWindowId = null
                        vm.deleteChat(id, runtime)
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleteActionWindowId = null
                    }
                ) {
                    Text("取消")
                }
            }
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

    BackHandler(
        enabled = drawerState.isOpen
    ) {
        scope.launch {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxWidth(0.50f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp,
                            end = 8.dp,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "AIHub",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭菜单",
                        )
                    }
                }

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
                        val selected =
                            window.id == vm.activeWindowId
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme
                                        .secondaryContainer
                                } else {
                                    androidx.compose.ui.graphics.Color
                                        .Transparent
                                },
                            modifier = Modifier
                                .padding(
                                    horizontal = 8.dp,
                                    vertical = 2.dp,
                                )
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        vm.switchWindow(window.id)
                                        scope.launch {
                                            drawerState.close()
                                        }
                                    },
                                    onLongClick = {
                                        bindingActionWindowId =
                                            window.id
                                        scope.launch {
                                            drawerState.close()
                                        }
                                    },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 13.dp,
                                ),
                                verticalAlignment =
                                    Alignment.CenterVertically,
                            ) {
                                Text(
                                    window.boundProject.orEmpty()
                                        .ifBlank {
                                            window.title
                                        },
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                when {
                                    window.generating ->
                                        Text(" ⟳")
                                    window.unread ->
                                        Text(
                                            " ●",
                                            color =
                                                MaterialTheme
                                                    .colorScheme
                                                    .primary,
                                        )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                NavigationDrawerItem(
                    label = { Text("清空诊断日志") },
                    selected = false,
                    onClick = {
                        DiagnosticLogger.clear()
                        Toast.makeText(
                            context,
                            "诊断日志已清空，请复现一次问题后再导出",
                            Toast.LENGTH_LONG
                        ).show()
                        scope.launch {
                            drawerState.close()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

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
                                    vm.activeWindow.boundProject.orEmpty()
                                        .ifBlank { vm.activeWindow.title },
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
                            IconButton(
                                onClick = {
                                    vm.refreshChat(
                                        runtime,
                                        vm.activeWindowId,
                                    )
                                },
                                enabled = !vm.activeWindow.generating
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    "刷新聊天",
                                )
                            }

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
                        windows = vm.tabWindows,
                        activeWindowId = vm.activeWindowId,
                        focusRevision = launchRevision,
                        onSelect = vm::switchWindow,
                        onLongPress = { bindingActionWindowId = it },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowTabStrip(
    windows: List<ChatWindow>,
    activeWindowId: String,
    focusRevision: Int,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(
        activeWindowId,
        windows.map { it.id },
        focusRevision,
    ) {
        val index = windows.indexOfFirst { it.id == activeWindowId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(windows, key = { it.id }) { window ->
            val selected = window.id == activeWindowId
            val label = window.boundProject.orEmpty()
                .ifBlank { window.title }

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
                        .combinedClickable(
                            onClick = { onSelect(window.id) },
                            onLongClick = { onLongPress(window.id) },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append(label)
                            when {
                                window.generating -> append(" ⟳")
                                window.unread -> append(" ●")
                            }
                        },
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
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
                        ChatMarkdownContent(
                            text = message.text,
                            compact = true,
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
                    ChatMarkdownContent(
                        text = message.text,
                        compact = false,
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

private enum class ChatBlockType {
    PARAGRAPH,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BULLET,
    NUMBERED,
    QUOTE,
    CODE,
}

private data class ChatTextBlock(
    val type: ChatBlockType,
    val text: String,
    val marker: String = "",
)

private fun parseChatTextBlocks(
    raw: String,
): List<ChatTextBlock> {
    val codeMark = 96.toChar()
    val fence =
        codeMark.toString().repeat(3)
    val lines =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
    val blocks =
        mutableListOf<ChatTextBlock>()
    var index = 0

    fun isSpecial(
        line: String,
    ): Boolean {
        val value = line.trimStart()
        return value.startsWith(fence) ||
            value.startsWith("# ") ||
            value.startsWith("## ") ||
            value.startsWith("### ") ||
            value.startsWith("> ") ||
            value.startsWith("- ") ||
            value.startsWith("* ") ||
            Regex("""^\d+\.\s+.+""")
                .matches(value)
    }

    while (index < lines.size) {
        val trimmed =
            lines[index].trim()

        if (trimmed.isBlank()) {
            index += 1
            continue
        }

        if (trimmed.startsWith(fence)) {
            val language =
                trimmed.removePrefix(fence)
                    .trim()
            index += 1
            val code =
                mutableListOf<String>()
            while (
                index < lines.size &&
                !lines[index]
                    .trim()
                    .startsWith(fence)
            ) {
                code += lines[index]
                index += 1
            }
            if (index < lines.size) {
                index += 1
            }
            blocks += ChatTextBlock(
                type = ChatBlockType.CODE,
                text = code.joinToString("\n"),
                marker = language,
            )
            continue
        }

        when {
            trimmed.startsWith("### ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_3,
                    trimmed.removePrefix("### "),
                )
                index += 1
            }

            trimmed.startsWith("## ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_2,
                    trimmed.removePrefix("## "),
                )
                index += 1
            }

            trimmed.startsWith("# ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_1,
                    trimmed.removePrefix("# "),
                )
                index += 1
            }

            trimmed.startsWith("> ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.QUOTE,
                    trimmed.removePrefix("> "),
                )
                index += 1
            }

            trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.BULLET,
                    trimmed.drop(2),
                    marker = "•",
                )
                index += 1
            }

            Regex("""^\d+\.\s+.+""")
                .matches(trimmed) -> {
                blocks += ChatTextBlock(
                    ChatBlockType.NUMBERED,
                    trimmed
                        .substringAfter(".")
                        .trim(),
                    marker =
                        trimmed
                            .substringBefore(".") +
                            ".",
                )
                index += 1
            }

            else -> {
                val paragraph =
                    mutableListOf<String>()
                while (
                    index < lines.size &&
                    lines[index].isNotBlank() &&
                    !isSpecial(lines[index])
                ) {
                    paragraph +=
                        lines[index].trim()
                    index += 1
                }
                if (paragraph.isNotEmpty()) {
                    blocks += ChatTextBlock(
                        ChatBlockType.PARAGRAPH,
                        paragraph.joinToString(
                            "\n"
                        ),
                    )
                } else {
                    index += 1
                }
            }
        }
    }

    return blocks
}

@Composable
private fun ChatMarkdownContent(
    text: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) {
        parseChatTextBlocks(text)
    }

    Column(
        modifier = modifier,
        verticalArrangement =
            Arrangement.spacedBy(
                if (compact) 6.dp else 10.dp
            ),
    ) {
        blocks.forEach { block ->
            when (block.type) {
                ChatBlockType.HEADING_1,
                ChatBlockType.HEADING_2,
                ChatBlockType.HEADING_3 -> {
                    val style =
                        when (block.type) {
                            ChatBlockType.HEADING_1 ->
                                MaterialTheme
                                    .typography
                                    .headlineSmall
                            ChatBlockType.HEADING_2 ->
                                MaterialTheme
                                    .typography
                                    .titleLarge
                            else ->
                                MaterialTheme
                                    .typography
                                    .titleMedium
                        }

                    ChatInlineMarkdown(
                        text = block.text,
                        style = style,
                        fontWeight =
                            FontWeight.SemiBold,
                    )
                }

                ChatBlockType.CODE -> {
                    Surface(
                        shape =
                            RoundedCornerShape(
                                12.dp
                            ),
                        color =
                            MaterialTheme.colorScheme
                                .surfaceContainerHighest,
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier =
                                Modifier.padding(
                                    horizontal = 13.dp,
                                    vertical = 11.dp,
                                ),
                        ) {
                            if (
                                block.marker
                                    .isNotBlank()
                            ) {
                                Text(
                                    block.marker,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant,
                                    modifier =
                                        Modifier.padding(
                                            bottom = 7.dp,
                                        ),
                                )
                            }

                            Text(
                                block.text,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyMedium
                                        .copy(
                                            fontFamily =
                                                FontFamily
                                                    .Monospace,
                                            lineHeight =
                                                20.sp,
                                        ),
                                modifier =
                                    Modifier
                                        .horizontalScroll(
                                            rememberScrollState()
                                        ),
                            )
                        }
                    }
                }

                ChatBlockType.BULLET,
                ChatBlockType.NUMBERED -> {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.Top,
                    ) {
                        Text(
                            block.marker,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(
                                        lineHeight =
                                            26.sp
                                    ),
                            modifier =
                                Modifier.width(28.dp),
                        )
                        ChatInlineMarkdown(
                            text = block.text,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(
                                        lineHeight =
                                            26.sp
                                    ),
                            modifier =
                                Modifier.weight(1f),
                        )
                    }
                }

                ChatBlockType.QUOTE -> {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            color =
                                MaterialTheme.colorScheme
                                    .outlineVariant,
                            modifier = Modifier
                                .width(3.dp)
                                .height(26.dp),
                        ) {}
                        ChatInlineMarkdown(
                            text = block.text,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(
                                        lineHeight =
                                            26.sp
                                    ),
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        )
                    }
                }

                ChatBlockType.PARAGRAPH -> {
                    ChatInlineMarkdown(
                        text = block.text,
                        style =
                            MaterialTheme.typography
                                .bodyLarge
                                .copy(
                                    lineHeight =
                                        if (compact) {
                                            25.sp
                                        } else {
                                            26.sp
                                        },
                                ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInlineMarkdown(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color:
        androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight? = null,
) {
    val background =
        MaterialTheme.colorScheme
            .surfaceContainerHighest

    Text(
        text = remember(
            text,
            background,
        ) {
            buildInlineMarkdown(
                text,
                background,
            )
        },
        style = style,
        color = color,
        fontWeight = fontWeight,
        modifier = modifier,
    )
}

private fun buildInlineMarkdown(
    text: String,
    codeBackground:
        androidx.compose.ui.graphics.Color,
): AnnotatedString =
    buildAnnotatedString {
        val codeMark = 96.toChar()
        var cursor = 0

        while (cursor < text.length) {
            when {
                text.startsWith(
                    "**",
                    cursor,
                ) -> {
                    val end =
                        text.indexOf(
                            "**",
                            cursor + 2,
                        )
                    if (end > cursor + 2) {
                        withStyle(
                            SpanStyle(
                                fontWeight =
                                    FontWeight
                                        .SemiBold,
                            )
                        ) {
                            append(
                                text.substring(
                                    cursor + 2,
                                    end,
                                )
                            )
                        }
                        cursor = end + 2
                    } else {
                        append(text[cursor])
                        cursor += 1
                    }
                }

                text[cursor] == codeMark -> {
                    val end =
                        text.indexOf(
                            codeMark,
                            cursor + 1,
                        )
                    if (end > cursor + 1) {
                        withStyle(
                            SpanStyle(
                                fontFamily =
                                    FontFamily
                                        .Monospace,
                                background =
                                    codeBackground,
                            )
                        ) {
                            append(
                                text.substring(
                                    cursor + 1,
                                    end,
                                )
                            )
                        }
                        cursor = end + 1
                    } else {
                        append(text[cursor])
                        cursor += 1
                    }
                }

                else -> {
                    val nextBold =
                        text.indexOf(
                            "**",
                            cursor,
                        ).takeIf {
                            it >= 0
                        } ?: text.length
                    val nextCode =
                        text.indexOf(
                            codeMark,
                            cursor,
                        ).takeIf {
                            it >= 0
                        } ?: text.length
                    val next =
                        minOf(
                            nextBold,
                            nextCode,
                        )
                    append(
                        text.substring(
                            cursor,
                            next,
                        )
                    )
                    cursor = next
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
