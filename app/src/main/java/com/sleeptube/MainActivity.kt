package com.sleeptube

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val refreshTick = mutableIntStateOf(0)
    private val folderOk = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        SleepTimer.ensureStarted()

        lifecycleScope.launch { SleepTimer.expired.collect { finishAndRemoveTask() } }
        lifecycleScope.launch { SleepTimer.closed.collect { finishAndRemoveTask() } }
        lifecycleScope.launch { DownloadService.changed.collect { refreshTick.intValue++ } }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Root()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        folderOk.value = VideoRepo.hasFolderAccess(this)
        refreshTick.intValue++
        if (folderOk.value) {
            consumePendingUrl()
            DownloadService.resumeIfNeeded(this)
        }
    }

    private fun consumePendingUrl() {
        val p = Prefs.pendingUrl ?: return
        Prefs.pendingUrl = null
        Prefs.enqueue(p)
        DownloadService.start(this)
        Toast.makeText(this, "اتضاف للتحميل", Toast.LENGTH_SHORT).show()
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        try {
            VideoRepo.takeFolder(this, uri)
        } catch (e: Exception) {
            Toast.makeText(this, "مقدرتش آخد صلاحية الفولدر", Toast.LENGTH_LONG).show()
            return
        }
        folderOk.value = VideoRepo.hasFolderAccess(this)
        refreshTick.intValue++
        if (folderOk.value) {
            consumePendingUrl()
            DownloadService.resumeIfNeeded(this)
        }
    }

    @Composable
    private fun Root() {
        val ctx = LocalContext.current
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            onFolderPicked(it)
        }
        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        var showSettings by remember { mutableStateOf(false) }
        val ok by folderOk

        when {
            !ok -> Onboarding { pickFolder.launch(Prefs.folderUri) }
            showSettings -> {
                BackHandler { showSettings = false }
                SettingsScreen(onBack = { showSettings = false }, onChangeFolder = { pickFolder.launch(Prefs.folderUri) })
            }
            else -> ListScreen(onSettings = { showSettings = true })
        }
    }

    @Composable
    private fun Onboarding(onPick: () -> Unit) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SleepTube", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(16.dp))
                Text(
                    "اختار الفولدر اللي هتتحمل فيه الفيديوهات. نفس الفولدر ده هو اللي هتتفرج منه.",
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onPick) { Text("اختار فولدر التحميل") }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun ListScreen(onSettings: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val tick by refreshTick
        val items = remember { mutableStateListOf<VideoItem>() }
        LaunchedEffect(tick) {
            val list = withContext(Dispatchers.IO) { VideoRepo.list(ctx) }
            items.clear()
            items.addAll(list)
        }
        val selected = remember { mutableStateListOf<String>() }
        val selecting = selected.isNotEmpty()
        var confirmDelete by remember { mutableStateOf(false) }
        val remaining by SleepTimer.remaining.collectAsState()
        val dl by DownloadService.state.collectAsState()

        BackHandler(enabled = selecting) { selected.clear() }

        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    navigationIcon = {
                        if (selecting) IconButton(onClick = { selected.clear() }) {
                            Icon(Icons.Filled.Close, contentDescription = "إلغاء التحديد")
                        }
                    },
                    title = {
                        if (selecting) {
                            Text("${selected.size} متحدد")
                        } else {
                            Column {
                                Text("SleepTube")
                                if (remaining >= 0) {
                                    Text(
                                        "هيقفل بعد ${formatTime(remaining)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (selecting) {
                            TextButton(onClick = {
                                selected.clear()
                                selected.addAll(items.map { it.key })
                            }) { Text("الكل") }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "امسح")
                            }
                        } else {
                            IconButton(onClick = onSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = "الإعدادات")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                    start = 12.dp, end = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (dl.active) {
                    item(key = "__download") { DownloadCard(dl) }
                }
                if (items.isEmpty()) {
                    item(key = "__empty") {
                        Text(
                            "مفيش فيديوهات لسه. اعمل Share لأي فيديو من يوتيوب واختار SleepTube.",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                items(items, key = { it.key }) { v ->
                    val isSel = v.key in selected
                    VideoRow(
                        v, tick, isSel,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selecting) {
                                    if (isSel) selected.remove(v.key) else selected.add(v.key)
                                } else {
                                    PlayerActivity.start(ctx, items.toList(), items.indexOf(v))
                                }
                            },
                            onLongClick = {
                                if (!isSel) selected.add(v.key)
                            }
                        )
                    )
                }
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("مسح") },
                text = { Text("تمسح ${selected.size} ملف من الفولدر نهائيًا؟") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        val toDelete = items.filter { it.key in selected }
                        selected.clear()
                        scope.launch {
                            withContext(Dispatchers.IO) { toDelete.forEach { VideoRepo.delete(ctx, it) } }
                            refreshTick.intValue++
                        }
                    }) { Text("امسح") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("لأ") }
                }
            )
        }
    }

    @Composable
    private fun DownloadCard(dl: DlState) {
        val ctx = LocalContext.current
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "بيحمل: " + (dl.title ?: ""),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    if (dl.progress >= 0) {
                        LinearProgressIndicator(
                            progress = { (dl.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(4.dp))
                    val parts = ArrayList<String>()
                    if (dl.progress >= 0) parts.add("${dl.progress.toInt()}%")
                    if (dl.etaSec > 0) parts.add("باقي " + formatTime(dl.etaSec * 1000))
                    if (dl.queued > 1) parts.add("في الطابور ${dl.queued - 1}")
                    Text(parts.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { DownloadService.cancel(ctx) }) {
                    Icon(Icons.Filled.Close, contentDescription = "إلغاء")
                }
            }
        }
    }

    @Composable
    private fun VideoRow(v: VideoItem, tick: Int, isSel: Boolean, modifier: Modifier) {
        val ctx = LocalContext.current
        val pos = remember(v.key, tick) { Prefs.position(v.key) }
        val dur = remember(v.key, tick) { Prefs.duration(v.key) }
        val fin = remember(v.key, tick) { Prefs.finished(v.key) }
        val thumb by produceState(VideoRepo.cachedThumb(v), v.key) {
            if (value == null) value = withContext(Dispatchers.IO) { VideoRepo.thumbnail(ctx, v) }
        }
        val bg = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        Card(
            colors = CardDefaults.cardColors(containerColor = bg),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(modifier)
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF263040)),
                    contentAlignment = Alignment.Center
                ) {
                    val b = thumb
                    if (b != null) {
                        Image(
                            b.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Filled.PlayArrow, contentDescription = null,
                            tint = Color(0xFFB0BEC5), modifier = Modifier.size(36.dp)
                        )
                    }
                    if (isSel) {
                        Icon(
                            Icons.Filled.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    val frac = when {
                        fin -> 1f
                        dur > 0 -> (pos.toFloat() / dur).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(3.dp))
                    Spacer(Modifier.height(4.dp))
                    val status = when {
                        fin -> "خلص"
                        pos > 0 && dur > 0 -> "وقفت عند ${formatTime(pos)} من ${formatTime(dur)}"
                        pos > 0 -> "وقفت عند ${formatTime(pos)}"
                        else -> "لسه متشافش"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen(onBack: () -> Unit, onChangeFolder: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val tick by refreshTick
        val folderName = remember(tick) { VideoRepo.folderName(ctx) ?: "-" }
        var minutes by remember { mutableIntStateOf(Prefs.timerMinutes) }
        var version by remember { mutableStateOf<String?>(null) }
        var updating by remember { mutableStateOf(false) }
        var updateMsg by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            version = withContext(Dispatchers.IO) { Ytdl.version(ctx) } ?: "مش معروف"
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("الإعدادات") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Text("فولدر التحميل", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(folderName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onChangeFolder) { Text("غير الفولدر") }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("مؤقت النوم", style = MaterialTheme.typography.titleMedium)
                Text(
                    "التطبيق بيقفل لوحده بعد المدة دي من وقت ما تفتحه.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Prefs.TIMER_CHOICES.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = m == minutes, role = Role.RadioButton) {
                                minutes = m
                                Prefs.timerMinutes = m
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = m == minutes, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text("$m دقيقة")
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("yt-dlp", style = MaterialTheme.typography.titleMedium)
                Text("النسخة: ${version ?: "…"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(enabled = !updating, onClick = {
                    updating = true
                    updateMsg = "بيحدث…"
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            try { Ytdl.update(ctx) } catch (e: Throwable) { "فشل: ${e.message?.take(150)}" }
                        }
                        version = withContext(Dispatchers.IO) { Ytdl.version(ctx) } ?: version
                        updateMsg = msg
                        updating = false
                    }
                }) { Text("تحديث yt-dlp") }
                updateMsg?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "نسخة التطبيق: " + (try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                    } catch (_: Exception) { "" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
