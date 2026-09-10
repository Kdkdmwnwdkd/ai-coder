package com.novelseek.ultra.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelseek.ultra.data.model.Chapter
import com.novelseek.ultra.ui.theme.Accent
import com.novelseek.ultra.ui.theme.CardSurface
import com.novelseek.ultra.ui.theme.OnBackground
import com.novelseek.ultra.ui.viewmodel.WorkDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDetailScreen(
    workId: Long,
    onBack: () -> Unit,
    onChapterClick: (Long) -> Unit,
    onCharactersClick: () -> Unit,
    onWorldClick: () -> Unit,
    onOutlineClick: () -> Unit,
    onAgentConfigClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: WorkDetailViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkDetailViewModel(
                    context.applicationContext as android.app.Application,
                    workId
                ) as T
            }
        }
    )

    val work by viewModel.work.collectAsState(initial = null)
    val chapters by viewModel.chapters.collectAsState(initial = emptyList())
    var showAddChapterDialog by remember { mutableStateOf(false) }
    var chapterToDelete by remember { mutableStateOf<Chapter?>(null) }
    var isEditingTitle by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var editDesc by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingTitle) {
                        Column {
                            OutlinedTextField(
                                value = editTitle,
                                onValueChange = { editTitle = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Text(work?.title ?: "作品详情", color = OnBackground)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    if (isEditingTitle) {
                        TextButton(onClick = {
                            viewModel.updateWorkTitle(editTitle, editDesc)
                            isEditingTitle = false
                        }) {
                            Text("保存", color = Accent)
                        }
                    } else {
                        IconButton(onClick = {
                            work?.let {
                                editTitle = it.title
                                editDesc = it.description
                                isEditingTitle = true
                            }
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑", tint = OnBackground)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddChapterDialog = true },
                containerColor = Accent,
                contentColor = OnBackground
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加章节")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                if (work?.description?.isNotBlank() == true) {
                    Text(
                        text = work!!.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            item {
                QuickActionsRow(
                    onCharactersClick = onCharactersClick,
                    onWorldClick = onWorldClick,
                    onOutlineClick = onOutlineClick,
                    onAgentConfigClick = onAgentConfigClick
                )
            }

            item {
                Text(
                    "章节列表",
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            if (chapters.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无章节，点击右下角添加",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(chapters, key = { it.id }) { chapter ->
                    ChapterCard(
                        chapter = chapter,
                        onClick = { onChapterClick(chapter.id) },
                        onDelete = { chapterToDelete = chapter }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showAddChapterDialog) {
        AddChapterDialog(
            onDismiss = { showAddChapterDialog = false },
            onConfirm = { title ->
                viewModel.addChapter(title)
                showAddChapterDialog = false
            }
        )
    }

    chapterToDelete?.let { chapter ->
        AlertDialog(
            onDismissRequest = { chapterToDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${chapter.title}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChapter(chapter)
                        chapterToDelete = null
                    }
                ) {
                    Text("删除", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { chapterToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun QuickActionsRow(
    onCharactersClick: () -> Unit,
    onWorldClick: () -> Unit,
    onOutlineClick: () -> Unit,
    onAgentConfigClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        QuickActionButton(Icons.Default.Person, "角色", onCharactersClick)
        QuickActionButton(Icons.Default.Public, "世界观", onWorldClick)
        QuickActionButton(Icons.Default.List, "大纲", onOutlineClick)
        QuickActionButton(Icons.Default.Build, "Agent", onAgentConfigClick)
    }
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = CardSurface,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Accent)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun ChapterCard(
    chapter: Chapter,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground
                )
                if (chapter.content.isNotBlank()) {
                    Text(
                        text = chapter.content.take(60) + if (chapter.content.length > 60) "..." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = Accent.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun AddChapterDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加章节") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("章节标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("添加", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
