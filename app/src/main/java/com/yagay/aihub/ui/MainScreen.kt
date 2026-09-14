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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteSweep
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.model.ChatMessage
import com.yagay.aihub.model.MessageRole
import com.yagay.aihub.web.WebRuntime
import kotlinx.coroutines.Dispatchers
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

    DisposableEffect(Unit) { onDispose { runtime.destroy() } }

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
                    onSend = { val value = prompt; prompt = ""; viewModel.send(value, runtime) },
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
}

@Composable
private fun ChatPane(messages: List<ChatMessage>, status: String?, prompt: String, onPromptChange: (String) -> Unit, isGenerating: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
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
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
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
                FilledIconButton(onClick = if (isGenerating) onStop else onSend, enabled = isGenerating || prompt.isNotBlank()) {
                    Icon(if (isGenerating) Icons.Default.Stop else Icons.Default.Send, if (isGenerating) "停止" else "发送")
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
        Text("先点击右上角网页图标登录，然后直接在这里聊天。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WebHost(runtime: WebRuntime, viewModel: AIHubViewModel, visible: Boolean) {
    Box(modifier = if (visible) Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background) else Modifier.size(1.dp).alpha(0f)) {
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
                        Text("在官方网页完成登录或特殊操作", style = MaterialTheme.typography.labelSmall)
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
