package com.sleeptube

import android.Manifest
import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
    private val tab = mutableIntStateOf(0) // 0 = التحميلات, 1 = المتابعة
    private val showSettings = mutableStateOf(false)
    private val pendingSubUrl = mutableStateOf<String?>(null)

    // in-app updater state
    private val update = mutableStateOf<Updater.Result.Available?>(null)
    private val updateProgress = mutableStateOf<Float?>(null) // null = not downloading
    private val updateMsg = mutableStateOf<String?>(null)
    private var installAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        SleepTimer.ensureStarted()

        lifecycleScope.launch { SleepTimer.expired.collect { finishAndRemoveTask() } }
        lifecycleScope.launch { SleepTimer.closed.collect { finishAndRemoveTask() } }
        lifecycleScope.launch { DownloadService.changed.collect { refreshTick.intValue++ } }
        handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val u = i?.getStringExtra(EXTRA_ADD_SUB) ?: return
        i.removeExtra(EXTRA_ADD_SUB)
        pendingSubUrl.value = u
        showSettings.value = false
        tab.intValue = 1
    }

    override fun onResume() {
        super.onResume()
        folderOk.value = VideoRepo.hasFolderAccess(this)
        refreshTick.intValue++
        if (folderOk.value) {
            consumePendingUrl()
            if (Subs.enqueueDueChecks()) DownloadService.start(this)
            DownloadService.resumeIfNeeded(this)
        }
        if (installAfterPermission && Updater.canInstall(this)) {
            installAfterPermission = false
            startUpdate()
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Updater.checkIfDue() }
            if (r is Updater.Result.Available) update.value = r
        }
    }

    /** Settings button: explicit check with feedback. */
    private fun checkForUpdateNow() {
        updateMsg.value = "بيدور على تحديث…"
        lifecycleScope.launch {
            Prefs.lastAppUpdateCheck = System.currentTimeMillis()
            when (val r = withContext(Dispatchers.IO) { Updater.check() }) {
                is Updater.Result.Available -> { update.value = r; updateMsg.value = "فيه تحديث جديد (نسخة ${r.versionCode})" }
                Updater.Result.UpToDate -> updateMsg.value = "عندك آخر نسخة"
                Updater.Result.Unreachable -> updateMsg.value = "مش قادر أوصل للتحديثات"
            }
        }
    }

    private fun startUpdate() {
        val u = update.value ?: return
        if (updateProgress.value != null) return
        if (!Updater.canInstall(this)) {
            installAfterPermission = true
            Toast.makeText(this, "اسمح لـ SleepTube بتثبيت التحديثات وارجع", Toast.LENGTH_LONG).show()
            Updater.openInstallPermissionSettings(this)
            return
        }
        updateProgress.value = 0f
        lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) {
                try {
                    Updater.download(this@MainActivity, u.apkUrl) { p -> updateProgress.value = p }
                } catch (e: Exception) { null }
            }
            updateProgress.value = null
            if (apk == null) {
                Toast.makeText(this@MainActivity, "مش قادر أوصل للتحديثات", Toast.LENGTH_LONG).show()
            } else {
                try { Updater.install(this@MainActivity, apk) } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "مقدرتش أفتح التثبيت", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Composable
    private fun BottomNav() {
        val t by tab
        NavigationBar {
            NavigationBarItem(
                selected = t == 0, onClick = { tab.intValue = 0 },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                label = { Text("التحميلات") }
            )
            NavigationBarItem(
                selected = t == 1, onClick = { tab.intValue = 1 },
                icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                label = { Text("المتابعة") }
            )
        }
    }

    @Composable
    private fun UpdateBanner() {
        val u by update
        val prog by updateProgress
        val info = u ?: return
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("فيه تحديث جديد (نسخة ${info.versionCode})", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    if (prog == null) {
                        TextButton(onClick = { update.value = null }) { Text("بعدين") }
                        Button(onClick = { startUpdate() }) { Text("تحديث") }
                    }
                }
                prog?.let {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    private fun consumePendingUrl() {
        val p = Prefs.pendingUrl ?: return
        Prefs.pendingUrl = null
        Prefs.enqueue(QueueItem(p))
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

        val settings by showSettings
        val ok by folderOk
        val t by tab
        val remaining by SleepTimer.remaining.collectAsState()
        val prefill by pendingSubUrl

        when {
            !ok -> Onboarding { pickFolder.launch(Prefs.folderUri) }
            settings -> {
                BackHandler { showSettings.value = false }
                SettingsScreen(onBack = { showSettings.value = false }, onChangeFolder = { pickFolder.launch(Prefs.folderUri) })
            }
            t == 1 -> {
                BackHandler { tab.intValue = 0 }
                SubsScreen(
                    bottomBar = { BottomNav() },
                    timerText = if (remaining >= 0) "هيقفل بعد ${formatTime(remaining)}" else null,
                    prefillUrl = prefill,
                    onPrefillConsumed = { pendingSubUrl.value = null },
                )
            }
            else -> ListScreen(onSettings = { showSettings.value = true })
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
        // Root view: playlist folders first, then root videos. Folder view: that folder's videos.
        var openDir by remember { mutableStateOf<String?>(null) }
        var openName by remember { mutableStateOf("") }
        val folders = remember { mutableStateListOf<FolderItem>() }
        val items = remember { mutableStateListOf<VideoItem>() }
        LaunchedEffect(tick, openDir) {
            val dir = openDir
            if (dir == null) {
                val (f, v) = withContext(Dispatchers.IO) { VideoRepo.listAll(ctx) }
                folders.clear(); folders.addAll(f)
                items.clear(); items.addAll(v)
            } else {
                val v = withContext(Dispatchers.IO) { VideoRepo.list(ctx, dir) }
                folders.clear()
                items.clear(); items.addAll(v)
            }
        }
        val selected = remember { mutableStateListOf<String>() }
        val selecting = selected.isNotEmpty()
        var confirmDelete by remember { mutableStateOf(false) }
        val remaining by SleepTimer.remaining.collectAsState()
        val dl by DownloadService.state.collectAsState()

        fun toggle(key: String) {
            if (key in selected) selected.remove(key) else selected.add(key)
        }

        BackHandler(enabled = selecting) { selected.clear() }
        BackHandler(enabled = !selecting && openDir != null) { openDir = null }

        Scaffold(
            bottomBar = { if (!selecting) BottomNav() },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        if (selecting) {
                            IconButton(onClick = { selected.clear() }) {
                                Icon(Icons.Filled.Close, contentDescription = "إلغاء التحديد")
                            }
                        } else if (openDir != null) {
                            IconButton(onClick = { openDir = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                            }
                        }
                    },
                    title = {
                        if (selecting) {
                            Text("${selected.size} متحدد")
                        } else {
                            Column {
                                Text(
                                    if (openDir != null) openName else "SleepTube",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
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
                                selected.addAll(folders.map { it.key })
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
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                    start = 8.dp, end = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item(key = "__update") { UpdateBanner() }
                if (dl.active) {
                    item(key = "__download") { DownloadCard(dl) }
                }
                if (items.isEmpty() && folders.isEmpty()) {
                    item(key = "__empty") {
                        Text(
                            "مفيش فيديوهات لسه. اعمل Share لأي فيديو من يوتيوب واختار SleepTube.",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                items(folders, key = { it.key }) { f ->
                    val isSel = f.key in selected
                    FolderRow(
                        f, tick, isSel,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selecting) toggle(f.key) else {
                                    openName = f.name
                                    openDir = f.docId
                                }
                            },
                            onLongClick = { if (!isSel) selected.add(f.key) }
                        )
                    )
                }
                items(items, key = { it.key }) { v ->
                    val isSel = v.key in selected
                    VideoRow(
                        v, tick, isSel,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selecting) toggle(v.key)
                                else PlayerActivity.start(ctx, items.toList(), items.indexOf(v))
                            },
                            onLongClick = { if (!isSel) selected.add(v.key) }
                        )
                    )
                }
            }
        }

        if (confirmDelete) {
            val delFolders = folders.filter { it.key in selected }
            val delVideos = items.filter { it.key in selected }
            val total = delVideos.size + delFolders.sumOf { it.count }
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("مسح") },
                text = {
                    Text(
                        if (delFolders.isEmpty()) "تمسح $total فيديو من الفولدر نهائيًا؟"
                        else "تمسح $total فيديو (منهم ${delFolders.size} فولدر بلاي ليست) نهائيًا؟"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        selected.clear()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                delFolders.forEach { VideoRepo.deleteFolder(ctx, it) }
                                delVideos.forEach { VideoRepo.delete(ctx, it) }
                            }
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
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
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

    private fun watchedFraction(key: String): Float = when {
        Prefs.finished(key) -> 1f
        else -> {
            val dur = Prefs.duration(key)
            if (dur > 0) (Prefs.position(key).toFloat() / dur).coerceIn(0f, 1f) else 0f
        }
    }

    /** No background of its own; only a subtle highlight while selected. */
    @Composable
    private fun rowModifier(isSel: Boolean, modifier: Modifier): Modifier {
        val bg = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent
        return Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).then(modifier)
    }

    @Composable
    private fun Thumb(item: VideoItem?, isSel: Boolean, badge: (@Composable () -> Unit)? = null) {
        val ctx = LocalContext.current
        val thumb by produceState(item?.let { VideoRepo.cachedThumb(it) }, item?.key) {
            if (value == null && item != null) value = withContext(Dispatchers.IO) { VideoRepo.thumbnail(ctx, item) }
        }
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
            badge?.invoke()
            if (isSel) {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                )
            }
        }
    }

    @Composable
    private fun VideoRow(v: VideoItem, tick: Int, isSel: Boolean, modifier: Modifier) {
        val frac = remember(v.key, tick) { watchedFraction(v.key) }
        Row(rowModifier(isSel, modifier).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(v, isSel)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    v.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(3.dp))
            }
        }
    }

    @Composable
    private fun FolderRow(f: FolderItem, tick: Int, isSel: Boolean, modifier: Modifier) {
        val frac = remember(f.key, tick, f.count) {
            if (f.count == 0) 0f else f.videos.sumOf { watchedFraction(it.key).toDouble() }.toFloat() / f.count
        }
        Row(rowModifier(isSel, modifier).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(f.videos.firstOrNull(), isSel) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("${f.count}", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    f.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "${f.count} فيديو", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(3.dp))
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
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("تحديث التطبيق", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val um by this@MainActivity.updateMsg
                val av by update
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { checkForUpdateNow() }) { Text("تحقق من تحديث") }
                    if (av != null) {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { startUpdate() }) { Text("تحديث") }
                    }
                }
                um?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                updateProgress.value?.let {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
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

    companion object {
        const val EXTRA_ADD_SUB = "add_sub_url"
    }
}
