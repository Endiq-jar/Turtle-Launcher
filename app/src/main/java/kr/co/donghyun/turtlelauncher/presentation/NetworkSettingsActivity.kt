package kr.co.donghyun.turtlelauncher.presentation

import androidx.compose.ui.platform.LocalContext
import kr.co.donghyun.turtlelauncher.R
import android.content.Context
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kr.co.donghyun.turtlelauncher.presentation.base.BaseActivity
import kr.co.donghyun.turtlelauncher.domain.model.HostEntry
import kr.co.donghyun.turtlelauncher.domain.model.Instance
import kr.co.donghyun.turtlelauncher.domain.model.ServerRow
import kr.co.donghyun.turtlelauncher.presentation.network.NetworkSettingsViewModel
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.*
import kr.co.donghyun.turtlelauncher.presentation.util.window.isTablet
import kr.co.donghyun.turtlelauncher.presentation.util.window.isCompact
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class NetworkSettingsActivity : BaseActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NetworkSettingsActivity::class.java))
        }
    }

    private val viewModel: NetworkSettingsViewModel by viewModels()

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )
        setContent {
            TurtleLauncherTheme {
                val hostEntries by viewModel.hostEntries.collectAsState()
                val instances by viewModel.instances.collectAsState()
                val serverRows by viewModel.serverRows.collectAsState()

                NetworkSettingsScreen(
                    onBack = { finish() },
                    hostEntries = hostEntries,
                    instances = instances,
                    serverRows = serverRows,
                    onAddHost = { viewModel.addHostEntry(it) },
                    onUpdateHost = { old, updated -> viewModel.updateHostEntry(old, updated) },
                    onToggleHost = { viewModel.toggleHostEntry(it) },
                    onDeleteHost = { viewModel.deleteHostEntry(it) },
                    onAddServer = { id, mc, name, addr -> viewModel.addServerFavorite(id, mc, name, addr) },
                    onRemoveServer = { id, mc, addr -> viewModel.removeServerFavorite(id, mc, addr) },
                )
            }
        }
    }
}

@Composable
private fun NetworkSettingsScreen(
    onBack: () -> Unit,
    hostEntries: List<HostEntry>,
    instances: List<Instance>,
    serverRows: List<ServerRow>,
    onAddHost: (HostEntry) -> Unit,
    onUpdateHost: (HostEntry, HostEntry) -> Unit,
    onToggleHost: (HostEntry) -> Unit,
    onDeleteHost: (HostEntry) -> Unit,
    onAddServer: (instanceId: String, mcVersion: String, name: String, address: String) -> Unit,
    onRemoveServer: (instanceId: String, mcVersion: String, address: String) -> Unit,
) {
    val context = LocalContext.current
    val tablet = isTablet()
    val compact = isCompact()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<HostEntry?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        // 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder)
                .padding(horizontal = if (tablet) 16.dp else if (compact) 6.dp else 10.dp, vertical = if (tablet) 10.dp else if (compact) 4.dp else 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(context.getString(R.string.back_button), color = TextSecondary, fontSize = if (tablet) 14.sp else if (compact) 10.sp else 11.sp)
            }
            Text(context.getString(R.string.network_settings_title), color = TextPrimary,
                fontSize = if (tablet) 18.sp else if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp))
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Turtle),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 12.dp, vertical = 4.dp)
            ) {
                Text(context.getString(R.string.add_button), color = Color.White,
                    fontSize = if (tablet) 13.sp else if (compact) 9.sp else 11.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (tablet) 20.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InfoCard(tablet)

            // ── 내 서버 (멀티플레이) 섹션 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(context.getString(R.string.my_servers_count, serverRows.size),
                    color = TextPrimary,
                    fontSize = if (tablet) 15.sp else if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp))
                Button(
                    onClick = { showServerDialog = true },
                    enabled = instances.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Turtle),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 12.dp, vertical = 4.dp)
                ) {
                    Text(context.getString(R.string.add_server_button), color = Color.White,
                        fontSize = if (tablet) 13.sp else if (compact) 9.sp else 11.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }

            if (instances.isEmpty()) {
                ServerEmptyBox(context.getString(R.string.no_instances_create_first_note), tablet)
            } else if (serverRows.isEmpty()) {
                ServerEmptyBox(
                    context.getString(R.string.no_registered_servers),
                    tablet
                )
            } else {
                serverRows.forEach { row ->
                    ServerRowItem(
                        row = row,
                        tablet = tablet,
                        onDelete = {
                            onRemoveServer(row.instanceId, row.mcVersion, row.address)
                        }
                    )
                }
            }

            Text(context.getString(R.string.host_mapping_count, hostEntries.size),
                color = TextPrimary,
                fontSize = if (tablet) 15.sp else 12.sp,
                fontWeight = FontWeight.Bold)

            if (hostEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgSurface, RoundedCornerShape(10.dp))
                        .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                        .padding(if (tablet) 24.dp else 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        context.getString(R.string.no_registered_hosts_note),
                        color = TextSecondary,
                        fontSize = if (tablet) 12.sp else 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                hostEntries.forEach { entry ->
                    HostEntryItem(
                        entry = entry,
                        tablet = tablet,
                        onToggle = { onToggleHost(entry) },
                        onEdit = { editingEntry = entry },
                        onDelete = { onDeleteHost(entry) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        HostEntryDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { newEntry ->
                onAddHost(newEntry)
                showAddDialog = false
            },
            tablet = tablet
        )
    }
    editingEntry?.let { e ->
        HostEntryDialog(
            initial = e,
            onDismiss = { editingEntry = null },
            onSave = { updated ->
                onUpdateHost(e, updated)
                editingEntry = null
            },
            tablet = tablet
        )
    }

    if (showServerDialog) {
        ServerAddDialog(
            instances = instances,
            tablet = tablet,
            onDismiss = { showServerDialog = false },
            onSave = { instanceId, mcVersion, name, address ->
                onAddServer(instanceId, mcVersion, name, address)
                showServerDialog = false
            }
        )
    }
}

@Composable
private fun InfoCard(tablet: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(if (tablet) 14.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(context.getString(R.string.how_it_works_label),
            color = Turtle, fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
        Text(
            context.getString(R.string.kr_domain_dns_hint) +
                    context.getString(R.string.hamachi_hostname_hint) +
                    context.getString(R.string.hamachi_app_required_hint),
            color = TextSecondary,
            fontSize = if (tablet) 12.sp else 10.sp,
            lineHeight = if (tablet) 17.sp else 14.sp
        )
    }
}

@Composable
private fun HostEntryItem(
    entry: HostEntry,
    tablet: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, if (entry.enabled) BgBorder else BgBorder.copy(alpha = 0.4f),
                RoundedCornerShape(10.dp))
            .clickable { onEdit() }
            .padding(horizontal = if (tablet) 14.dp else 10.dp, vertical = if (tablet) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.hostname,
                color = if (entry.enabled) TextPrimary else TextSecondary,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "→ ${entry.ip}",
                color = if (entry.enabled) Turtle else TextSecondary,
                fontSize = if (tablet) 12.sp else 10.sp,
            )
            if (entry.note.isNotBlank()) {
                Text(entry.note, color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = if (tablet) 10.sp else 9.sp)
            }
        }
        Switch(
            checked = entry.enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Turtle,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BgBorder
            )
        )
        Box(
            modifier = Modifier
                .size(if (tablet) 32.dp else 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BgDark)
                .border(1.dp, BgBorder, RoundedCornerShape(6.dp))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Text("🗑", fontSize = if (tablet) 14.sp else 12.sp)
        }
    }
}

@Composable
private fun HostEntryDialog(
    initial: HostEntry?,
    onDismiss: () -> Unit,
    onSave: (HostEntry) -> Unit,
    tablet: Boolean,
) {
    val context = LocalContext.current
    var hostname by remember { mutableStateOf(initial?.hostname ?: "") }
    var ip       by remember { mutableStateOf(initial?.ip ?: "") }
    var note     by remember { mutableStateOf(initial?.note ?: "") }
    var error    by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (tablet) 0.65f else 0.95f)
                .clip(RoundedCornerShape(14.dp))
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                .padding(if (tablet) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (initial == null) context.getString(R.string.add_host) else context.getString(R.string.edit_host),
                color = TextPrimary,
                fontSize = if (tablet) 17.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )

            LabeledField(context.getString(R.string.hostname_label), hostname, context.getString(R.string.hamachi_example_placeholder), tablet) { hostname = it; error = null }
            LabeledField(context.getString(R.string.ip_address_label),  ip,       context.getString(R.string.ip_example_placeholder),     tablet) { ip = it; error = null }
            LabeledField(context.getString(R.string.memo_optional), note,    context.getString(R.string.server_name_example_placeholder),          tablet) { note = it }

            error?.let {
                Text("❌ $it", color = Color(0xFFFF6B6B),
                    fontSize = if (tablet) 12.sp else 10.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(context.getString(R.string.cancel_button), color = TextSecondary,
                        fontSize = if (tablet) 13.sp else 11.sp)
                }
                Button(
                    onClick = {
                        val h = hostname.trim()
                        val i = ip.trim()
                        when {
                            h.isBlank() -> error = context.getString(R.string.hostname_hint)
                            i.isBlank() -> error = context.getString(R.string.ip_address_hint)
                            !validateIp(i) -> error = context.getString(R.string.ipv4_format_invalid)
                            !validateHost(h) -> error = context.getString(R.string.hostname_invalid_chars)
                            else -> onSave(HostEntry(h, i, note.trim(), enabled = initial?.enabled ?: true))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Turtle),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(context.getString(R.string.save_button), color = Color.White,
                        fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String, value: String, hint: String,
    tablet: Boolean,
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextSecondary, fontSize = if (tablet) 12.sp else 10.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = TextPrimary,
                fontSize = if (tablet) 13.sp else 11.sp),
            cursorBrush = SolidColor(Turtle),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(hint, color = TextSecondary.copy(alpha = 0.4f),
                            fontSize = if (tablet) 13.sp else 11.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(BgDark, RoundedCornerShape(8.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

private fun validateIp(s: String): Boolean {
    val parts = s.split('.')
    if (parts.size != 4) return false
    return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}

private fun validateHost(s: String): Boolean =
    s.isNotBlank() && s.length <= 253 &&
            s.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' }
// ───────────────────────── 내 서버(멀티플레이) ─────────────────────────

@Composable
private fun ServerEmptyBox(text: String, tablet: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(if (tablet) 24.dp else 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = TextSecondary,
            fontSize = if (tablet) 12.sp else 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = if (tablet) 17.sp else 15.sp
        )
    }
}

@Composable
private fun ServerRowItem(
    row: ServerRow,
    tablet: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = if (tablet) 14.dp else 10.dp, vertical = if (tablet) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.name,
                color = TextPrimary,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                "→ ${row.address}",
                color = Turtle,
                fontSize = if (tablet) 12.sp else 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                "📦 ${row.instanceName} · MC ${row.mcVersion}",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = if (tablet) 10.sp else 9.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(if (tablet) 32.dp else 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BgDark)
                .border(1.dp, BgBorder, RoundedCornerShape(6.dp))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Text("🗑", fontSize = if (tablet) 14.sp else 12.sp)
        }
    }
}

@Composable
private fun ServerAddDialog(
    instances: List<Instance>,
    tablet: Boolean,
    onDismiss: () -> Unit,
    onSave: (instanceId: String, mcVersion: String, name: String, address: String) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(instances.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (tablet) 0.65f else 0.95f)
                .clip(RoundedCornerShape(14.dp))
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                .padding(if (tablet) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                context.getString(R.string.add_server),
                color = TextPrimary,
                fontSize = if (tablet) 17.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )

            LabeledField(context.getString(R.string.server_name_label), name, context.getString(R.string.world_name_example_placeholder), tablet) { name = it; error = null }
            LabeledField(context.getString(R.string.address_label), address, context.getString(R.string.playit_example_placeholder), tablet) { address = it; error = null }

            // ── 인스턴스 선택 ──
            Text(context.getString(R.string.instance_to_install_label), color = TextSecondary, fontSize = if (tablet) 12.sp else 10.sp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (tablet) 200.dp else 160.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                instances.forEach { inst ->
                    val sel = selected?.id == inst.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Turtle.copy(alpha = 0.18f) else BgDark)
                            .border(1.dp, if (sel) Turtle else BgBorder, RoundedCornerShape(8.dp))
                            .clickable { selected = inst; error = null }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(inst.name, color = TextPrimary,
                                fontSize = if (tablet) 13.sp else 11.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("MC ${inst.mcVersion}", color = TextSecondary,
                                fontSize = if (tablet) 10.sp else 9.sp)
                        }
                        if (sel) Text("✓", color = Turtle,
                            fontSize = if (tablet) 14.sp else 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            error?.let {
                Text("❌ $it", color = Color(0xFFFF6B6B),
                    fontSize = if (tablet) 12.sp else 10.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(context.getString(R.string.cancel_button), color = TextSecondary, fontSize = if (tablet) 13.sp else 11.sp)
                }
                Button(
                    onClick = {
                        val n = name.trim()
                        val a = address.trim()
                        val inst = selected
                        when {
                            a.isBlank() -> error = context.getString(R.string.address_hint)
                            inst == null -> error = context.getString(R.string.select_instance_prompt)
                            else -> onSave(inst.id, inst.mcVersion, n.ifBlank { a }, a)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Turtle),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(context.getString(R.string.save_button), color = Color.White,
                        fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}