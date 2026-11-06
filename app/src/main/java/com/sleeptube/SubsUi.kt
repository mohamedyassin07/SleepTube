package com.sleeptube

import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private fun ymdToUtcMillis(ymd: String): Long? = try {
    SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(ymd)?.time
} catch (_: Exception) { null }

private fun utcMillisToYmd(ms: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

/** Starts the download service if a check/download was queued and the folder is usable. */
private fun kick(ctx: Context, queued: Boolean) {
    if (queued && VideoRepo.hasFolderAccess(ctx)) DownloadService.start(ctx)
}

/** Manual check: only with (Wi-Fi) internet, otherwise tell the user why. */
private fun manualCheck(ctx: Context, enqueue: () -> Boolean) {
    val reason = Net.subsBlockedReason(ctx)
    if (reason != null) {
        Toast.makeText(ctx, reason, Toast.LENGTH_SHORT).show()
        return
    }
    kick(ctx, enqueue())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubsScreen(
    bottomBar: @Composable () -> Unit,
    timerText: String?,
    prefillUrl: String?,
    onPrefillConsumed: () -> Unit,
) {
    val ctx = LocalContext.current
    val version by Subs.version.collectAsState()
    val subs = remember(version) { Subs.all() }
    var showAdd by remember { mutableStateOf(false) }
    var addUrl by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Sub?>(null) }
    var deleting by remember { mutableStateOf<Sub?>(null) }

    LaunchedEffect(prefillUrl) {
        if (prefillUrl != null) {
            addUrl = prefillUrl
            showAdd = true
            onPrefillConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text("المتابعة")
                        if (timerText != null) Text(
                            timerText, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (subs.isNotEmpty()) TextButton(onClick = { manualCheck(ctx) { Subs.enqueueAllChecks() } }) {
                        Text("تحقق الكل")
                    }
                }
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            FloatingActionButton(onClick = { addUrl = ""; showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "إضافة قناة")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 80.dp,
                start = 12.dp, end = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "__wifi") {
                var wifiOnly by remember { mutableStateOf(Prefs.subsWifiOnly) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("تحميل المتابعة على Wi-Fi بس", color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            "اللينكات اللي بتعملها Share بتتحمل على أي نت.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = {
                        wifiOnly = it
                        Prefs.subsWifiOnly = it
                        if (!it) DownloadService.resumeIfNeeded(ctx)
                    })
                }
                HorizontalDivider()
            }
            if (subs.isEmpty()) item {
                Text(
                    "مش متابع أي قناة. دوس + وحط لينك القناة، أو اعمل Share للينك قناة من يوتيوب.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            items(subs, key = { it.id }) { s ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.name, modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(onClick = { manualCheck(ctx) { Subs.enqueueCheck(s.id) } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "تحقق الآن")
                        }
                        IconButton(onClick = { editing = s }) {
                            Icon(Icons.Filled.Edit, contentDescription = "تعديل")
                        }
                        IconButton(onClick = { deleting = s }) {
                            Icon(Icons.Filled.Delete, contentDescription = "إلغاء المتابعة")
                        }
                    }
                    val info = buildString {
                        append("من ${Subs.prettyYmd(s.startDate)}")
                        if (s.keyword.isNotBlank()) append(" • كلمة: ${s.keyword}")
                        append("\nآخر تحقق: ${Subs.prettyTime(s.lastChecked)} • اتحمل ${s.downloaded}")
                    }
                    Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAdd) {
        SubDialog(
            title = "متابعة قناة",
            initialUrl = addUrl,
            initial = null,
            onDismiss = { showAdd = false },
            onSaved = { showAdd = false },
        )
    }
    editing?.let { s ->
        SubDialog(
            title = "تعديل: ${s.name}",
            initialUrl = s.url,
            initial = s,
            onDismiss = { editing = null },
            onSaved = { editing = null },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("إلغاء المتابعة") },
            text = { Text("تلغي متابعة ${s.name}؟ الفيديوهات اللي اتحملت هتفضل موجودة.") },
            confirmButton = {
                TextButton(onClick = { Subs.delete(s.id); deleting = null }) { Text("إلغاء المتابعة") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("لأ") } }
        )
    }
}

/** Add (initial == null: resolves the URL with yt-dlp) or edit a subscription. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubDialog(
    title: String,
    initialUrl: String,
    initial: Sub?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initialUrl) }
    var keyword by remember { mutableStateOf(initial?.keyword ?: "") }
    var start by remember { mutableStateOf(initial?.startDate ?: Subs.todayYmd()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                if (initial == null) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it; error = null },
                        label = { Text("لينك القناة") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("حمّل الفيديوهات اللي نزلت من يوم:", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { picking = true }) { Text(Subs.prettyYmd(start)) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyword, onValueChange = { keyword = it },
                    label = { Text("كلمة في العنوان (اختياري)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("بيدور على القناة…")
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (initial != null) {
                    Subs.update(initial.id) { it.copy(startDate = start, keyword = keyword.trim()) }
                    onSaved()
                    return@TextButton
                }
                val u = url.trim()
                if (!Subs.isChannelUrl(u)) {
                    error = "ده مش لينك قناة يوتيوب (لازم يكون فيه /@ أو /channel/)"
                    return@TextButton
                }
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        try { Subs.resolve(ctx, u) } catch (e: Throwable) { null }
                    }
                    busy = false
                    if (r == null) {
                        error = "مقدرتش أوصل للقناة. اتأكد من اللينك والنت."
                    } else {
                        Subs.upsert(Sub(r.id, r.name, Subs.canonicalUrl(r.id), start, keyword.trim()))
                        if (Net.subsAllowed(ctx)) kick(ctx, Subs.enqueueCheck(r.id))
                        else Toast.makeText(ctx, "اتضافت. هتتحقق لما يبقى فيه Wi-Fi", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                }
            }) { Text(if (initial == null) "متابعة" else "حفظ") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء") }
        }
    )

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = ymdToUtcMillis(start))
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { start = utcMillisToYmd(it) }
                    picking = false
                }) { Text("تمام") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("إلغاء") } }
        ) {
            Box { DatePicker(state = state) }
        }
    }
}
