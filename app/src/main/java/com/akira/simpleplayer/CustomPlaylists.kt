package com.akira.simpleplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.simpleplayer.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Custom share playlists.
 *
 * The user picks songs, the server stores them for 30 days and returns an id
 * that is shared as <web>/c/<id>. The lists created on this phone are kept in
 * SharedPreferences (with the delete token) and dropped once they expire.
 */
data class CustomPlaylist(
    val id: String,
    val name: String,
    val count: Int,
    val expiresAt: Long,
    val deleteToken: String
) {
    val daysLeft: Int
        get() = ((expiresAt - System.currentTimeMillis() + 86_399_999L) / 86_400_000L)
            .toInt()
            .coerceAtLeast(1)
}

object CustomPlaylistStore {
    private const val PREFS = "custom_playlists"
    private const val KEY = "lists"
    const val MAX_TRACKS = 500

    private fun prefs() =
        AppPrefs.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Lists created on this device that have not expired yet. */
    fun load(): List<CustomPlaylist> {
        val raw = prefs().getString(KEY, null) ?: return emptyList()
        val now = System.currentTimeMillis()
        val all = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                CustomPlaylist(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    count = o.optInt("count"),
                    expiresAt = o.optLong("expiresAt"),
                    deleteToken = o.optString("deleteToken")
                ).takeIf { it.id.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        val live = all.filter { it.expiresAt > now }
        if (live.size != all.size) save(live)
        return live
    }

    private fun save(lists: List<CustomPlaylist>) {
        val a = JSONArray()
        lists.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("count", it.count)
                    .put("expiresAt", it.expiresAt)
                    .put("deleteToken", it.deleteToken)
            )
        }
        prefs().edit().putString(KEY, a.toString()).apply()
    }

    private fun url(apiBase: String, path: String): URL {
        val base = apiBase.trim().trimEnd('/')
        return URL(
            if (base.startsWith("http://") || base.startsWith("https://")) base + path
            else "http://$base$path"
        )
    }

    private fun enc(v: String) =
        java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    suspend fun create(apiBase: String, name: String, trackIds: List<String>): CustomPlaylist =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("name", name)
                .put("tracks", JSONArray(trackIds))
                .toString()
                .toByteArray(Charsets.UTF_8)
            val c = (url(apiBase, "/api/custom-playlists").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
            try {
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val o = runCatching { JSONObject(text) }.getOrNull()
                if (code !in 200..299 || o == null) {
                    error(o?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP $code")
                }
                val entry = CustomPlaylist(
                    id = o.getString("id"),
                    name = o.optString("name", name),
                    count = o.optJSONArray("tracks")?.length() ?: trackIds.size,
                    expiresAt = o.optLong("expiresAt"),
                    deleteToken = o.optString("deleteToken")
                )
                save(listOf(entry) + load().filter { it.id != entry.id })
                entry
            } finally {
                c.disconnect()
            }
        }

    /** Deletes the list on the server (best effort) and forgets it locally. */
    suspend fun delete(apiBase: String, list: CustomPlaylist) {
        withContext(Dispatchers.IO) {
            runCatching {
                val c = (url(apiBase, "/api/custom-playlists/${enc(list.id)}").openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = 8000
                    readTimeout = 10000
                    setRequestProperty("X-Delete-Token", list.deleteToken)
                }
                try { c.responseCode } finally { c.disconnect() }
            }
            save(load().filter { it.id != list.id })
        }
    }
}

private enum class CustomMode { List, Edit, Done }

private fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_TITLE, title)
    context.startActivity(
        Intent.createChooser(send, "分享播放清單").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Full-screen page: "my share lists" + the song picker that creates a new one. */
@Composable
fun CustomPlaylistScreen(
    vm: PlayerViewModel,
    onClose: () -> Unit
) {
    val context = AppPrefs.context
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var mode by remember {
        mutableStateOf(if (vm.customStartEditor) CustomMode.Edit else CustomMode.List)
    }
    LaunchedEffect(Unit) { vm.customStartEditor = false }
    var lists by remember { mutableStateOf(CustomPlaylistStore.load()) }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val picked = vm.customPicked
    var busy by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<CustomPlaylist?>(null) }
    var confirmDelete by remember { mutableStateOf<CustomPlaylist?>(null) }

    fun linkOf(id: String) = vm.customPlaylistUrl(id)

    fun back() {
        when (mode) {
            CustomMode.List -> onClose()
            else -> mode = CustomMode.List
        }
    }

    // Drawn inside PlayerApp's root Box (which already pads for system bars and the keyboard)
    // instead of a Dialog window, so the bottom bar never ends up under the navigation bar.
    BackHandler(onBack = ::back)

    Box(Modifier.fillMaxSize()) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        when (mode) {
                            CustomMode.List -> "自訂播放清單"
                            CustomMode.Edit -> "建立自訂播放清單"
                            CustomMode.Done -> "已建立"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                when (mode) {
                    CustomMode.List -> CustomListPage(
                        lists = lists,
                        onCreate = {
                            query = ""
                            mode = CustomMode.Edit
                        },
                        onPickFromList = {
                            vm.selectMode = true
                            onClose()
                        },
                        onOpen = { openUrl(context, linkOf(it.id)) },
                        onShare = { shareText(context, linkOf(it.id), it.name) },
                        onDelete = { confirmDelete = it }
                    )

                    CustomMode.Edit -> CustomEditPage(
                        vm = vm,
                        name = name,
                        onName = { name = it.take(80) },
                        query = query,
                        onQuery = { query = it },
                        picked = picked,
                        busy = busy,
                        onToggle = { id -> vm.toggleCustomPick(id) },
                        onClear = { picked.clear() },
                        onPickFromList = {
                            vm.selectMode = true
                            onClose()
                        },
                        onSubmit = {
                            busy = true
                            scope.launch {
                                runCatching {
                                    CustomPlaylistStore.create(vm.apiBase, name.trim(), picked.toList())
                                }.onSuccess {
                                    created = it
                                    picked.clear()
                                    name = ""
                                    lists = CustomPlaylistStore.load()
                                    mode = CustomMode.Done
                                }.onFailure {
                                    Toast.makeText(context, "建立失敗：${it.message}", Toast.LENGTH_LONG).show()
                                }
                                busy = false
                            }
                        }
                    )

                    CustomMode.Done -> created?.let { c ->
                        CustomDonePage(
                            list = c,
                            link = linkOf(c.id),
                            onShare = { shareText(context, linkOf(c.id), c.name) },
                            onCopy = {
                                clipboard.setText(AnnotatedString(linkOf(c.id)))
                                Toast.makeText(context, "已複製連結", Toast.LENGTH_SHORT).show()
                            },
                            onFinish = { mode = CustomMode.List }
                        )
                    }
                }
            }
        }

        confirmDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("刪除「${target.name}」？") },
                text = { Text("分享出去的連結也會一起失效。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = null
                        scope.launch {
                            CustomPlaylistStore.delete(vm.apiBase, target)
                            lists = CustomPlaylistStore.load()
                        }
                    }) { Text("刪除") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun CustomListPage(
    lists: List<CustomPlaylist>,
    onCreate: () -> Unit,
    onPickFromList: () -> Unit,
    onOpen: (CustomPlaylist) -> Unit,
    onShare: (CustomPlaylist) -> Unit,
    onDelete: (CustomPlaylist) -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
    ) {
        item {
            CustomStartCard(
                icon = Icons.Filled.TouchApp,
                title = "直接選擇",
                subtitle = "回到歌單，點一下反白選取歌曲，可以切換不同歌單",
                onClick = onPickFromList
            )
            Spacer(Modifier.height(10.dp))
            CustomStartCard(
                icon = Icons.Filled.Search,
                title = "搜尋加入",
                subtitle = "搜尋曲名、歌手、專輯，點一下加入（連結保存 30 天）",
                onClick = onCreate
            )
        }

        item {
            Text(
                "我的分享清單",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = muted,
                modifier = Modifier.padding(top = 22.dp, bottom = 6.dp, start = 4.dp)
            )
        }

        if (lists.isEmpty()) {
            item {
                Text(
                    "還沒有建立過清單",
                    color = muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
        }

        items(lists, key = { it.id }) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen(item) }
                    .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${item.count} 首 · 剩 ${item.daysLeft} 天",
                        fontSize = 12.sp,
                        color = muted
                    )
                }
                IconButton(onClick = { onShare(item) }) {
                    Icon(Icons.Filled.Share, contentDescription = "分享", tint = muted)
                }
                IconButton(onClick = { onDelete(item) }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "刪除", tint = muted)
                }
            }
        }
    }
}

@Composable
private fun CustomStartCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Floating bar shown over the normal UI while the user picks songs in select mode. */
@Composable
fun CustomSelectBar(vm: PlayerViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "已選 ${vm.customPicked.size} 首",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "點歌曲反白選取",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                vm.customPicked.clear()
                vm.selectMode = false
            }) { Text("取消") }
            Button(
                onClick = {
                    vm.selectMode = false
                    vm.customStartEditor = true
                    vm.showCustomPlaylists = true
                },
                enabled = vm.customPicked.isNotEmpty()
            ) { Text("下一步") }
        }
    }
}

@Composable
private fun CustomEditPage(
    vm: PlayerViewModel,
    name: String,
    onName: (String) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    picked: List<String>,
    busy: Boolean,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onPickFromList: () -> Unit,
    onSubmit: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pickedSet = picked.toSet()
    val q = query.trim().lowercase()

    val rows: List<Track> =
        remember(vm.library, q, picked.size) {
            if (q.isEmpty()) {
                val byId = vm.library.associateBy { it.id }
                picked.mapNotNull(byId::get)
            } else {
                vm.library.asSequence()
                    .filter {
                        "${it.title} ${it.artist} ${it.album} ${it.name} ${it.folder}"
                            .lowercase()
                            .contains(q)
                    }
                    .take(200)
                    .toList()
            }
        }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                singleLine = true,
                label = { Text("清單名稱（選填）") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("搜尋曲名、歌手、專輯…") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                when {
                    q.isNotEmpty() && rows.isEmpty() -> "沒有符合的歌曲"
                    q.isNotEmpty() -> "搜尋結果" + if (rows.size >= 200) "（前 200 首）" else ""
                    rows.isEmpty() -> "搜尋歌曲點一下加入，或按「到列表選取」"
                    else -> "已選的歌曲（點一下移除）"
                },
                fontSize = 12.sp,
                color = muted,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 2.dp)
            )
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
            items(rows, key = { it.id }) { t ->
                val on = t.id in pickedSet
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (on) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.background
                        )
                        .clickable { onToggle(t.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(vm, t, Modifier.size(44.dp), RoundedCornerShape(6.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title.ifBlank { t.name },
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            t.artist.ifBlank { t.album.ifBlank { t.folder } },
                            fontSize = 12.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        if (on) Icons.Filled.Check else Icons.Filled.Add,
                        contentDescription = if (on) "已加入" else "加入",
                        tint = if (on) MaterialTheme.colorScheme.onBackground else muted,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已選 ${picked.size} 首",
                color = muted,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPickFromList, enabled = !busy) {
                Text("到列表選取")
            }
            TextButton(onClick = onClear, enabled = picked.isNotEmpty() && !busy) {
                Text("清空")
            }
            Spacer(Modifier.width(6.dp))
            Button(onClick = onSubmit, enabled = picked.isNotEmpty() && !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("建立連結")
                }
            }
        }
    }
}

@Composable
private fun CustomDonePage(
    list: CustomPlaylist,
    link: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onFinish: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "${list.name} · ${list.count} 首",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            link,
            color = muted,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text("連結會保存 30 天，之後自動刪除。", fontSize = 12.sp, color = muted)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("分享")
            }
            OutlinedButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("複製")
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onFinish) { Text("完成") }
    }
}
