package com.novelseek.ultra.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelseek.ultra.data.model.WorldSetting
import com.novelseek.ultra.ui.theme.Accent
import com.novelseek.ultra.ui.theme.CardSurface
import com.novelseek.ultra.ui.theme.OnBackground
import com.novelseek.ultra.ui.viewmodel.WorldListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldListScreen(
    workId: Long,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: WorldListViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorldListViewModel(
                    context.applicationContext as Application,
                    workId
                ) as T
            }
        }
    )

    val worldSettings by viewModel.worldSettings.collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var settingToDelete by remember { mutableStateOf<WorldSetting?>(null) }
    var editingSetting by remember { mutableStateOf<WorldSetting?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("世界观", color = OnBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Accent,
                contentColor = OnBackground
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加设定")
            }
        }
    ) { padding ->
        if (worldSettings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    "暂无世界观设定，点击右下角添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(worldSettings, key = { it.id }) { setting ->
                    WorldSettingCard(
                        setting = setting,
                        onEdit = { editingSetting = setting },
                        onDelete = { settingToDelete = setting }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showAddDialog) {
        WorldSettingDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, content ->
                viewModel.addSetting(name, content)
                showAddDialog = false
            }
        )
    }

    editingSetting?.let { setting ->
        WorldSettingDialog(
            setting = setting,
            onDismiss = { editingSetting = null },
            onConfirm = { name, content ->
                viewModel.updateSetting(setting.copy(name = name, content = content))
                editingSetting = null
            }
        )
    }

    settingToDelete?.let { setting ->
        AlertDialog(
            onDismissRequest = { settingToDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除设定「${setting.name}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSetting(setting)
                        settingToDelete = null
                    }
                ) {
                    Text("删除", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { settingToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun WorldSettingCard(
    setting: WorldSetting,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = setting.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground
                )
                Row {
                    TextButton(onClick = onEdit) {
                        Text("编辑", color = Accent)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = Accent.copy(alpha = 0.7f))
                    }
                }
            }
            if (setting.content.isNotBlank()) {
                Text(
                    text = setting.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun WorldSettingDialog(
    setting: WorldSetting? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(setting?.name ?: "") }
    var content by remember { mutableStateOf(setting?.content ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (setting == null) "添加设定" else "编辑设定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("设定名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("设定内容") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name, content)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("保存", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
