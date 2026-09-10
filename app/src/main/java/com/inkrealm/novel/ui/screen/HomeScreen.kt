package com.inkrealm.novel.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkrealm.novel.data.model.Work
import com.inkrealm.novel.data.model.WorkType
import com.inkrealm.novel.ui.theme.OnBackgroundLight
import com.inkrealm.novel.ui.theme.OnSurfaceVariant
import com.inkrealm.novel.ui.theme.PrimaryLight
import com.inkrealm.novel.ui.theme.SurfaceVariant
import com.inkrealm.novel.ui.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    selectedTab: String,
    onTabChange: (String) -> Unit,
    onWorkClick: (Long) -> Unit,
    onAgentClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val works by viewModel.allWorks.collectAsState(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    var workToDelete by remember { mutableStateOf<Work?>(null) }
    var tabType by remember { mutableStateOf(WorkType.LONG) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("墨境", style = MaterialTheme.typography.headlineMedium, color = OnBackgroundLight) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = PrimaryLight,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建长篇")
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.ShortText, contentDescription = "短篇") },
                    label = { Text("短篇") },
                    selected = selectedTab == "short",
                    onClick = { onTabChange("short"); tabType = WorkType.SHORT }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = "长篇") },
                    label = { Text("长篇") },
                    selected = selectedTab == "long",
                    onClick = { onTabChange("long"); tabType = WorkType.LONG }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Psychology, contentDescription = "AI") },
                    label = { Text("AI") },
                    selected = selectedTab == "agent",
                    onClick = { onTabChange("agent"); onAgentClick() }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Mic, contentDescription = "听书") },
                    label = { Text("听书") },
                    selected = selectedTab == "listen",
                    onClick = { onTabChange("listen") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                    label = { Text("设置") },
                    selected = selectedTab == "settings",
                    onClick = { onTabChange("settings"); onSettingsClick() }
                )
            }
        }
    ) { padding ->
        val filtered = works.filter { it.type == tabType }
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有作品", style = MaterialTheme.typography.bodyLarge, color = OnSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filtered, key = { it.id }) { work ->
                    WorkCard(work = work, onClick = { onWorkClick(work.id) }, onDelete = { workToDelete = work })
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        CreateWorkDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, desc ->
                viewModel.createWork(title, desc, tabType) { showCreateDialog = false; onWorkClick(it) }
            }
        )
    }

    workToDelete?.let { work ->
        AlertDialog(
            onDismissRequest = { workToDelete = null },
            title = { Text("确认删除") },
            text = { Text("删除「${work.title}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteWork(work); workToDelete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { workToDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
fun WorkCard(work: Work, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = work.title, style = MaterialTheme.typography.titleMedium, color = OnBackgroundLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (work.description.isNotBlank()) {
                    Text(text = work.description, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                Text(text = formatDate(work.updatedAt), style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = OnSurfaceVariant)
        }
    }
}

@Composable
fun CreateWorkDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建作品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("作品标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("简介（可选）") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onConfirm(title, desc) }, enabled = title.isNotBlank()) {
                Text("创建", color = PrimaryLight)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
