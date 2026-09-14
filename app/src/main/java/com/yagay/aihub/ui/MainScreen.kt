package com.yagay.aihub.ui

import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.model.ChatMessage
import com.yagay.aihub.model.MessageRole
import com.yagay.aihub.web.ProviderCapabilities
import com.yagay.aihub.web.WebRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIHubRoot(viewModel: AIHubViewModel, runtimeFactory: () -> WebRuntime) {
    val runtime = remember { runtimeFactory() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var prompt by remember { mutableStateOf("") }
    var showAddAccount by remember { mutableStateOf(false) }
    var pendingAttachmentCount by remember { mutableIntStateOf(0) }
    var pendingAttachmentNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCapabilities by remember { mutableStateOf(false) }
    var capabilityLoading by remember { mutableStateOf(false) }
    var capabilitySnapshot by remember { mutableStateOf<ProviderCapabilities?>(null) }
    var optionKind by remember { mutableStateOf<String?>(null) }
    var optionValues by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val webFileChooser = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        runtime.handleFileChooserResult(result.resultCode, result.data)
    }

    val nativeAttachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            DiagnosticLogger.i("FILE", "native_attachment_selection_cancelled")
        } else {
            val provider = viewModel.selectedProvider
            val targetSession = viewModel.session
            DiagnosticLogger.i("FILE", "native_attachment_selected provider=${provider.id} selected=${uris.size}")
            scope.launch {
                val result = runCatching {
                    runtime.attachFiles(targetSession, provider, uris)
                }.onFailure {
                    DiagnosticLogger.e("FILE", "native_attachment_injection_exception provider=${provider.id}", it)
                }.getOrNull()

                if (result != null && result.attachedCount > 0) {
                    pendingAttachmentCount = result.attachedCount
                    pendingAttachmentNames = result.names
                    Toast.makeText(
                        context,
                        "已添加 ${result.attachedCount} 个附件到 ${provider.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pendingAttachmentCount = 0
                    pendingAttachmentNames = emptyList()
                    Toast.makeText(
                        context,
                        "${provider.name} 当前页面没有接受附件，已打开官网供你检查。",
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.openWeb()
                }
            }
        }
    }

    val exportDiagnostics = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) {
            DiagnosticLogger.i("EXPORT", "diagnostic_export_cancelled")
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    DiagnosticLogger.export(context.applicationContext, uri)
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) "诊断日志已导出" else "导出失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun refreshCapabilities(openDialog: Boolean = true) {
        val provider = viewModel.selectedProvider
        val targetSession = viewModel.session
        capabilityLoading = true
        if (openDialog) showCapabilities = true
        scope.launch {
            capabilitySnapshot = runCatching { runtime.capabilities(targetSession, provider) }
                .onFailure { DiagnosticLogger.e("CAP", "capability_snapshot_exception provider=${provider.id}", it) }
                .getOrDefault(ProviderCapabilities())
            capabilityLoading = false
        }
    }

    fun loadOptions(kind: String) {
        val provider = viewModel.selectedProvider
        val targetSession = viewModel.session
        scope.launch {
            val opened = runCatching { runtime.openOptionPicker(targetSession, provider, kind) }.getOrDefault("")
            if (opened != "ok") {
                Toast.makeText(context, "${provider.name} 当前没有找到${if (kind == "model") "模型" else "工具"}入口。", Toast.LENGTH_SHORT).show()
                return@launch
            }
            delay(260)
            var values = runCatching { runtime.optionList(targetSession, provider, kind) }.getOrDefault(emptyList())
            if (values.isEmpty()) {
                delay(350)
                values = runCatching { runtime.optionList(targetSession, provider, kind) }.getOrDefault(emptyList())
            }
            if (values.isEmpty()) {
                Toast.makeText(context, "没有读取到选项，已打开官网。", Toast.LENGTH_SHORT).show()
                viewModel.openWeb()
            } else {
                optionKind = kind
                optionValues = values
            }
        }
    }

    fun performSimpleAction(action: String, openWebAfter: Boolean = false) {
        val provider = viewModel.selectedProvider
        val targetSession = viewModel.session
        scope.launch {
            val result = runCatching { runtime.performAction(targetSession, provider, action) }
                .onFailure { DiagnosticLogger.e("CAP", "ui_action_exception provider=${provider.id} action=$action", it) }
                .getOrDefault("")
            Toast.makeText(
                context,
                if (result == "ok" || result == "scheduled") "${provider.name}：操作已执行" else "${provider.name}：当前页面不支持此操作",
                Toast.LENGTH_SHORT
            ).show()
            if ((result == "ok" || result == "scheduled") && openWebAfter) viewModel.openWeb()
            delay(220)
            refreshCapabilities(openDialog = false)
        }
    }

    DisposableEffect(runtime) {
        runtime.setFileChooserLauncher { intent -> webFileChooser.launch(intent) }
        onDispose {
            runtime.setFileChooserLauncher(null)
            runtime.destroy()
        }
    }

    LaunchedEffect(viewModel.selectedProviderId, viewModel.selectedAccountId) {
        pendingAttachmentCount = 0
        pendingAttachmentNames = emptyList()
        capabilitySnapshot = null
        optionKind = null
        optionValues = emptyList()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.86f)) {
                Spacer(Modifier.height(18.dp))
                Text("AIHub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                OutlinedButton(
                    onClick = { viewModel.newChat(runtime); scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("新对话", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                viewModel.providers.forEach { provider ->
                    Text(provider.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                    viewModel.accountsFor(provider.id).forEach { account ->
                        NavigationDrawerItem(
                            label = { Text(if (account.label == "Default") "默认账号" else account.label) },
                            selected = viewModel.selectedAccountId == account.id,
                            onClick = { viewModel.selectAccount(account.id); scope.launch { drawerState.close() } },
                            icon = {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(provider.shortName, fontWeight = FontWeight.Bold) }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(
                    label = { Text("为 ${viewModel.selectedProvider.name} 添加账号") },
                    selected = false,
                    onClick = { showAddAccount = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                NavigationDrawerItem(
                    label = { Text("导出诊断日志") },
                    selected = false,
                    onClick = {
                        DiagnosticLogger.i("EXPORT", "diagnostic_export_ui_requested")
                        scope.launch {
                            drawerState.close()
                            exportDiagnostics.launch(DiagnosticLogger.suggestedFileName())
                        }
                    },
                    icon = { Icon(Icons.Outlined.BugReport, null) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(viewModel.selectedProvider.name, fontWeight = FontWeight.SemiBold)
                            Text(viewModel.selectedAccount.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "菜单") } },
                    actions = {
                        IconButton(onClick = { refreshCapabilities() }) { Icon(Icons.Outlined.Tune, "功能") }
                        IconButton(onClick = { viewModel.openWeb() }) { Icon(Icons.Default.Language, "打开官网") }
                        IconButton(onClick = { viewModel.newChat(runtime) }) { Icon(Icons.Outlined.DeleteSweep, "新对话") }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                ChatPane(
                    messages = viewModel.messages,
                    status = viewModel.status,
                    prompt = prompt,
                    onPromptChange = { prompt = it },
                    isGenerating = viewModel.isGenerating,
                    attachmentCount = pendingAttachmentCount,
                    attachmentNames = pendingAttachmentNames,
                    onAttach = {
                        val provider = viewModel.selectedProvider
                        DiagnosticLogger.i(
                            "FILE",
                            "attachment_button_tapped provider=${provider.id} generating=${viewModel.isGenerating}"
                        )
                        if (viewModel.isGenerating) {
                            Toast.makeText(context, "${provider.name} 正在生成，请等待或停止后再添加附件。", Toast.LENGTH_SHORT).show()
                        } else {
                            nativeAttachmentPicker.launch(arrayOf("*/*"))
                        }
                    },
                    onSend = {
                        val value = prompt
                        val attachments = pendingAttachmentCount
                        prompt = ""
                        viewModel.send(value, runtime, attachments)
                        pendingAttachmentCount = 0
                        pendingAttachmentNames = emptyList()
                    },
                    onStop = { viewModel.stop(runtime) }
                )
                WebHost(runtime, viewModel, viewModel.showWeb)
            }
        }
    }

    if (showAddAccount) {
        AddAccountDialog(
            providerName = viewModel.selectedProvider.name,
            onDismiss = { showAddAccount = false },
            onAdd = { showAddAccount = false; viewModel.addAccount(it, runtime) }
        )
    }

    if (showCapabilities) {
        CapabilityDialog(
            providerName = viewModel.selectedProvider.name,
            capabilities = capabilitySnapshot,
            loading = capabilityLoading,
            onDismiss = { showCapabilities = false },
            onRefresh = { refreshCapabilities(openDialog = false) },
            onModel = { showCapabilities = false; loadOptions("model") },
            onTools = { showCapabilities = false; loadOptions("tool") },
            onAction = { action ->
                showCapabilities = false
                when (action) {
                    "retry", "continue" -> viewModel.runProviderAction(action, runtime)
                    "deleteConversation" -> confirmDelete = true
                    "edit", "history", "rename", "voice" -> performSimpleAction(action, openWebAfter = true)
                    else -> performSimpleAction(action)
                }
            }
        )
    }

    if (optionKind != null) {
        OptionPickerDialog(
            title = if (optionKind == "model") "选择模型" else "选择工具",
            values = optionValues,
            onDismiss = { optionKind = null; optionValues = emptyList() },
            onSelect = { value ->
                val kind = optionKind ?: return@OptionPickerDialog
                optionKind = null
                optionValues = emptyList()
                val provider = viewModel.selectedProvider
                val targetSession = viewModel.session
                scope.launch {
                    val result = runtime.selectOption(targetSession, provider, kind, value)
                    Toast.makeText(
                        context,
                        if (result == "ok" || result == "scheduled") "已选择：$value" else "选择失败：$value",
                        Toast.LENGTH_SHORT
                    ).show()
                    delay(260)
                    refreshCapabilities(openDialog = false)
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除当前官网会话？") },
            text = { Text("AIHub 会调用当前 AI 网站的删除操作。网站可能还会显示自己的确认界面。") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    performSimpleAction("deleteConversation", openWebAfter = true)
                }) { Text("继续") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CapabilityDialog(
    providerName: String,
    capabilities: ProviderCapabilities?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onModel: () -> Unit,
    onTools: () -> Unit,
    onAction: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$providerName 功能") },
        text = {
            if (loading || capabilities == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (capabilities.currentModel.isNotBlank()) {
                        item {
                            Text(
                                "当前模型：${capabilities.currentModel}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (capabilities.error.isNotBlank()) {
                        item { Text("页面提示：${capabilities.error}", color = MaterialTheme.colorScheme.error) }
                    }
                    if (!capabilities.hasAdvancedFeatures) {
                        item { Text("当前页面暂未检测到可统一控制的高级功能。可以打开官网检查登录状态或页面结构。") }
                    }
                    if (capabilities.model) item { FeatureButton("模型选择", onModel) }
                    if (capabilities.search) item { FeatureButton("联网搜索", { onAction("search") }) }
                    if (capabilities.reasoning) item { FeatureButton("思考 / 推理", { onAction("reasoning") }) }
                    if (capabilities.deepResearch) item { FeatureButton("深度研究", { onAction("deepResearch") }) }
                    if (capabilities.imageGeneration) item { FeatureButton("图像生成", { onAction("imageGeneration") }) }
                    if (capabilities.tools) item { FeatureButton("工具", onTools) }
                    if (capabilities.retry) item { FeatureButton("重新生成", { onAction("retry") }) }
                    if (capabilities.continueGeneration) item { FeatureButton("继续生成", { onAction("continue") }) }
                    if (capabilities.copy) item { FeatureButton("复制最后回答", { onAction("copy") }) }
                    if (capabilities.edit) item { FeatureButton("编辑上一条", { onAction("edit") }) }
                    if (capabilities.history) item { FeatureButton("会话历史", { onAction("history") }) }
                    if (capabilities.rename || capabilities.conversationMenu) item { FeatureButton("重命名会话", { onAction("rename") }) }
                    if (capabilities.deleteConversation || capabilities.conversationMenu) item { FeatureButton("删除会话", { onAction("deleteConversation") }) }
                    if (capabilities.voice) item { FeatureButton("语音", { onAction("voice") }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onRefresh, enabled = !loading) { Text("刷新") } }
    )
}

@Composable
private fun FeatureButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OptionPickerDialog(
    title: String,
    values: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(values, key = { it }) { value ->
                    OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) {
                        Text(value, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ChatPane(
    messages: List<ChatMessage>,
    status: String?,
    prompt: String,
    onPromptChange: (String) -> Unit,
    isGenerating: Boolean,
    attachmentCount: Int,
    attachmentNames: List<String>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (messages.isEmpty()) item { EmptyState() }
            items(messages, key = { it.id }) { MessageBubble(it) }
            if (status != null) item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                if (attachmentCount > 0) {
                    Text(
                        text = buildString {
                            append("📎 已添加 $attachmentCount 个附件")
                            if (attachmentNames.isNotEmpty()) {
                                append(" · ")
                                append(attachmentNames.take(2).joinToString(", "))
                                if (attachmentNames.size > 2) append("…")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = onAttach) {
                        Icon(Icons.Outlined.AttachFile, "添加文件或图片")
                    }
                    TextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("发送消息…") },
                        minLines = 1,
                        maxLines = 6,
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                    Spacer(Modifier.size(8.dp))
                    FilledIconButton(
                        onClick = if (isGenerating) onStop else onSend,
                        enabled = isGenerating || prompt.isNotBlank() || attachmentCount > 0
                    ) {
                        Icon(if (isGenerating) Icons.Default.Stop else Icons.Default.Send, if (isGenerating) "停止" else "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (isUser) 20.dp else 6.dp, bottomEnd = if (isUser) 6.dp else 20.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f)
        ) { Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { Text("AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(18.dp))
        Text("一个入口，使用你的 AI 官网账号", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("支持文字、附件、模型和网站能力统一控制；右上角调节按钮显示当前 AI 可用功能。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WebHost(runtime: WebRuntime, viewModel: AIHubViewModel, visible: Boolean) {
    val hostModifier = Modifier
        .fillMaxSize()
        .zIndex(if (visible) 2f else -1f)
        .alpha(if (visible) 1f else 0f)
        .then(if (visible) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)

    Box(modifier = hostModifier) {
        AndroidView(
            factory = { context -> FrameLayout(context).also { runtime.attach(it, viewModel.session, viewModel.selectedProvider) } },
            update = { host -> runtime.attach(host, viewModel.session, viewModel.selectedProvider) },
            modifier = Modifier.fillMaxSize()
        )
        if (visible) {
            Surface(tonalElevation = 5.dp, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.closeWeb() }) { Icon(Icons.Default.ArrowBack, "返回聊天") }
                    Column(Modifier.weight(1f)) {
                        Text("${viewModel.selectedProvider.name} · ${viewModel.selectedAccount.label}", fontWeight = FontWeight.SemiBold)
                        Text("在官方网页完成登录、附件或特殊操作", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { viewModel.closeWeb() }) { Text("返回聊天") }
                }
            }
        }
    }
}

@Composable
private fun AddAccountDialog(providerName: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 $providerName 账号") },
        text = { TextField(value = label, onValueChange = { label = it }, label = { Text("账号名称") }, placeholder = { Text("例如：工作 / 个人") }, singleLine = true) },
        confirmButton = { Button(onClick = { onAdd(label) }) { Text("添加并登录") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
}
