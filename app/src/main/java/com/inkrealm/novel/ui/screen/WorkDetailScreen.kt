package com.inkrealm.novel.ui.screen

import android.app.Application
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
import com.inkrealm.novel.data.model.Arc
import com.inkrealm.novel.data.model.Chapter
import com.inkrealm.novel.ui.theme.OnBackgroundLight
import com.inkrealm.novel.ui.theme.OnSurfaceVariant
import com.inkrealm.novel.ui.theme.PrimaryLight
import com.inkrealm.novel.ui.theme.SurfaceVariant
import com.inkrealm.novel.ui.viewmodel.WorkDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDetailScreen(
    workId: Long,
    onBack: () -> Unit,
    onChapterClick: (Long) -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: WorkDetailViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkDetailViewModel(context.applicationContext as Application, workId) as T
            }
        }
    )

    val work by viewModel.work.collectAsState(initial = null)
    val chapters by viewModel.chapters.collectAsState(initial = emptyList())
    val arcs by viewModel.arcs.collectAsState(initial = emptyList())
    var showAddChapter by remember { mutableStateOf(false) }
    var showAddArc by remember { mutableStateOf(false) }
    var selectedArc by remember { mutableStateOf<Arc?>(null) }
    var chapterToDelete by remember { mutableStateOf<Chapter?>(null) }

    val chapterCount = chapters.size
    val wordCount = chapters.sumOf { it.content.length }
    val arcCount = arcs.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(work?.title ?: "作品详情", color = OnBackgroundLight, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnBackgroundLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddChapter = true },
                containerColor = PrimaryLight,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建章节")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatCard("字", wordCount.toString())
                    StatCard("章", chapterCount.toString())
                    StatCard("弧线", arcCount.toString())
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillButton("大纲", Icons.Default.List, Modifier.weight(1f)) {}
                    PillButton("角色", Icons.Default.Person, Modifier.weight(1f)) {}
                    PillButton("境界", Icons.Default.Public, Modifier.weight(1f)) {}
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillButton("导出", Icons.Default.FileDownload, Modifier.weight(1f)) {}
                    PillButton("版本", Icons.Default.History, Modifier.weight(1f)) {}
                    PillButton("问答", Icons.Default.ChatBubble, Modifier.weight(1f)) {}
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillButton("容器", Icons.Default.Folder, Modifier.weight(1f)) {}
                    PillButton("全书梗概", Icons.Default.MenuBook, Modifier.weight(1f)) {}
                    PillButton("封面", Icons.Default.Image, Modifier.weight(1f)) {}
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("副本（弧线）", style = MaterialTheme.typography.titleMedium, color = OnBackgroundLight)
                    TextButton(onClick = { showAddArc = true }) { Text("+ 添加", color = PrimaryLight) }
                }
                if (arcs.isEmpty()) {
                    Text("暂无弧线", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
                } else {
                    ScrollableTabRow(selectedTabIndex = arcs.indexOf(selectedArc).coerceAtLeast(0), edgePadding = 0.dp) {
                        arcs.forEach { arc ->
                            Tab(
                                selected = selectedArc?.id == arc.id,
                                onClick = { selectedArc = arc },
                                text = { Text(arc.name, maxLines = 1) }
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("章节列表", style = MaterialTheme.typography.titleMedium, color = OnBackgroundLight)
                    TextButton(onClick = { showAddChapter = true }) { Text("+ 添加", color = PrimaryLight) }
                }
            }

            if (chapters.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Text("还没有章节", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
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

    if (showAddChapter) {
        AddChapterDialog(
            onDismiss = { showAddChapter = false },
            onConfirm = { title ->
                viewModel.addChapter(title, selectedArc?.id ?: 0)
                showAddChapter = false
            }
        )
    }

    if (showAddArc) {
        AddArcDialog(
            onDismiss = { showAddArc = false },
            onConfirm = { name ->
                viewModel.addArc(name)
                showAddArc = false
            }
        )
    }

    chapterToDelete?.let { chapter ->
        AlertDialog(
            onDismissRequest = { chapterToDelete = null },
            title = { Text("确认删除") },
            text = { Text("删除「${chapter.title}」？") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteChapter(chapter); chapterToDelete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { chapterToDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
fun StatCard(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(100.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = PrimaryLight)
            Text(label, style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
        }
    }
}

@Composable
fun PillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SurfaceVariant,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, tint = PrimaryLight, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = OnBackgroundLight)
        }
    }
}

@Composable
fun ChapterCard(chapter: Chapter, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(chapter.title, style = MaterialTheme.typography.titleMedium, color = OnBackgroundLight)
                if (chapter.content.isNotBlank()) {
                    Text(
                        chapter.content.take(40) + if (chapter.content.length > 40) "..." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text("${chapter.content.length} 字", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun AddChapterDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建章节") },
        text = {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("章节标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onConfirm(title) }, enabled = title.isNotBlank()) {
                Text("添加", color = PrimaryLight)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun AddArcDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建弧线") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("弧线名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("添加", color = PrimaryLight)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
