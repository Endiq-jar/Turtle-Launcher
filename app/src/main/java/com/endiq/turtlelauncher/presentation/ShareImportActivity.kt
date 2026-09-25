package com.endiq.turtlelauncher.presentation

import com.endiq.turtlelauncher.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.endiq.turtlelauncher.data.instance.InstanceManager
import com.endiq.turtlelauncher.presentation.base.BaseActivity
import com.endiq.turtlelauncher.presentation.ui.theme.*
import com.endiq.turtlelauncher.presentation.util.maps.MapImporter
import com.endiq.turtlelauncher.presentation.util.mods.ModImporter
import java.io.File

/**
 * 파일관리자 등에서 .jar / .zip 을 "공유"하면 이 액티비티가 받아서
 * 종류(모드/월드)를 자동 감지하고, 사용자가 고른 인스턴스의 mods/ 또는 saves/ 로 넣는다.
 *
 *  - 모드(.jar) : 로더가 설치된 인스턴스의 mods/ 로 복사
 *  - 월드(.zip) : 인스턴스의 saves/ 로 압축 해제
 *
 * AndroidManifest 에 ACTION_SEND / ACTION_SEND_MULTIPLE intent-filter 등록 필요.
 *
 * ⚠️ 공유로 들어온 content:// 는 이 task 에 묶인 일시 권한이라
 *    백그라운드 코루틴에서 열기 전에 grantUriPermission 으로 권한을 보강하고,
 *    읽을 때도 Activity 컨텍스트(this)를 써야 한다(applicationContext 금지).
 *    이게 누락되면 IO 스레드에서 SecurityException/FileNotFound 로 조용히 실패한다.
 */
class ShareImportActivity : BaseActivity() {

    private enum class ImportType { MOD, WORLD }

    private data class SharedFileItem(val uri: Uri, val name: String, val detected: ImportType)
    private data class InstancePick(val id: String, val label: String, val loaderType: String?)

    private val importing = mutableStateOf(false)
    private val statusMsg = mutableStateOf("")

    override fun onCreated() {
        val uris = extractSharedUris(intent).also { grantReadOnShared(intent, it) }
        if (uris.isEmpty()) {
            Toast.makeText(this, getString(R.string.file_to_import_not_found), Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val files = uris.map { uri ->
            val name = queryDisplayName(uri) ?: (uri.lastPathSegment ?: "file")
            SharedFileItem(uri, name, detectType(name))
        }
        val instances = loadInstances(this)

        setContent {
            TurtleLauncherTheme {
                ShareImportContent(
                    files = files,
                    instances = instances,
                    importing = importing.value,
                    statusMsg = statusMsg.value,
                    onCancel = { finish() },
                    onConfirm = { instanceId, type -> doImport(files, instanceId, type) },
                )
            }
        }
    }

    // ── 공유 인텐트에서 Uri 추출 ──

    private fun extractSharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
        Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33)
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    /**
     * 공유(ACTION_SEND)로 들어온 content:// 는 이 task 에 묶인 일시 권한이라
     * 백그라운드 코루틴/다른 컨텍스트에서 열면 SecurityException/FileNotFound 로 죽는다.
     * intent 에 read flag 를 보강하고, 각 Uri 에 명시적으로 read 권한을 부여해 둔다.
     */
    private fun grantReadOnShared(intent: Intent, uris: List<Uri>) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (uri in uris) {
            runCatching {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun detectType(name: String): ImportType =
        if (name.endsWith(".jar", ignoreCase = true)) ImportType.MOD else ImportType.WORLD

    /**
     * 1.12.2 이하는 legacy — 게임 디렉터리가 <instance>/.minecraft 이고
     * 모드/월드도 그 아래(.minecraft/mods, .minecraft/saves)에 둬야 인식된다.
     * (ContentPackBrowserActivity 의 동명 로직과 동일 기준)
     */
    private fun isLegacyVersion(versionId: String): Boolean {
        // 베타/알파/클래식/인데브 등 "1."로 시작하지 않는 구버전 표기도 legacy 로 판정
        // (예: b1.7.3 — 예전엔 modern 오판으로 즉시 크래시).
        val id = versionId.trim().lowercase()
        if (id.startsWith("b1.") || id.startsWith("a1.") || id.startsWith("a0.") ||
            id.startsWith("c0.") || id.startsWith("inf-") || id.startsWith("rd-")) {
            return true
        }
        val major = Regex("""^1\.(\d+)""").find(id)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return major <= 12
    }

    // ── 인스턴스 목록 ──

    private fun loadInstances(context: Context): List<InstancePick> {
        return InstanceManager.listInstances(context).map { meta ->
            InstancePick(id = meta.id, label = meta.name, loaderType = meta.loaderType)
        }
    }

    // ── 실제 가져오기 ──

    private fun doImport(files: List<SharedFileItem>, instanceId: String, type: ImportType) {
        importing.value = true
        statusMsg.value = if (type == ImportType.MOD) getString(R.string.adding_mod) else getString(R.string.adding_world)

        lifecycleScope.launch(Dispatchers.IO) {
            // ⚠️ 공유 Uri 는 이 Activity task 에 묶인 일시 권한이므로
            //    applicationContext 가 아니라 Activity 컨텍스트로 읽어야 한다.
            val ctx = this@ShareImportActivity
            val instanceDir = InstanceManager.instanceDir(ctx, instanceId)

            // 1.12.2 이하(legacy)는 게임 디렉터리가 <instance>/.minecraft 라
            //   모드/월드도 .minecraft/mods, .minecraft/saves 에 들어가야 인식된다.
            val mcVersion = runCatching {
                InstanceManager.loadMeta(instanceDir)?.mcVersion
            }.getOrNull() ?: ""
            val gameDir = if (isLegacyVersion(mcVersion))
                File(instanceDir, ".minecraft") else instanceDir

            val text = when (type) {
                ImportType.MOD -> {
                    val r = ModImporter.importJars(
                        ctx, files.map { it.uri }, File(gameDir, "mods")
                    )
                    when (r) {
                        is ModImporter.Result.Success -> buildString {
                            append(getString(R.string.mods_added_count, r.added.size))
                            if (r.skipped.isNotEmpty()) append(getString(R.string.skipped_count_suffix, r.skipped.size))
                        }
                        is ModImporter.Result.Failure -> r.reason
                    }
                }
                ImportType.WORLD -> {
                    val savesDir = File(gameDir, "saves")
                    var ok = 0
                    val fails = ArrayList<String>()
                    files.forEach { f ->
                        when (val res = MapImporter.importZip(ctx, f.uri, savesDir)) {
                            is MapImporter.Result.Success -> ok++
                            is MapImporter.Result.Failure -> fails.add("${f.name}: ${res.reason}")
                        }
                    }
                    buildString {
                        append(getString(R.string.worlds_added_count, ok))
                        if (fails.isNotEmpty()) append(getString(R.string.failure_prefix_newline) + fails.joinToString("\n"))
                    }
                }
            }
            withContext(Dispatchers.Main) {
                importing.value = false
                Toast.makeText(this@ShareImportActivity, text, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
    } catch (_: Exception) {
        null
    }

    companion object {
        // 외부 호출 필요 없음 — 매니페스트 intent-filter 로만 진입.
    }

    // ──────────────────────────────────────────────────────────────
    // Compose UI
    // ──────────────────────────────────────────────────────────────

    @Composable
    private fun ShareImportContent(
        files: List<SharedFileItem>,
        instances: List<InstancePick>,
        importing: Boolean,
        statusMsg: String,
        onCancel: () -> Unit,
        onConfirm: (instanceId: String, type: ImportType) -> Unit,
    ) {
        var type by remember { mutableStateOf(files.firstOrNull()?.detected ?: ImportType.WORLD) }
        var selectedInstance by remember { mutableStateOf<String?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(getString(R.string.add_to_instance), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                // 파일 목록
                Text(getString(R.string.file_count_suffix, files.size), color = TextSecondary, fontSize = 12.sp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    files.take(5).forEach {
                        Text("• ${it.name}", color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                    }
                    if (files.size > 5)
                        Text(getString(R.string.more_files_suffix, files.size - 5), color = TextSecondary, fontSize = 11.sp)
                }

                // 종류
                Text(getString(R.string.type_label), color = TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip(getString(R.string.mod_jar_label), type == ImportType.MOD, Modifier.weight(1f)) {
                        type = ImportType.MOD
                        // 모드로 바꿨는데 선택된 인스턴스에 로더가 없으면 선택 해제
                        val ld = instances.find { it.id == selectedInstance }?.loaderType
                        if (ld == null) selectedInstance = null
                    }
                    TypeChip(getString(R.string.world_zip_label), type == ImportType.WORLD, Modifier.weight(1f)) {
                        type = ImportType.WORLD
                    }
                }

                // 인스턴스
                Text(getString(R.string.instance_label), color = TextSecondary, fontSize = 12.sp)
                if (instances.isEmpty()) {
                    Text(getString(R.string.no_instances), color = Color(0xFFFF6B6B), fontSize = 12.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(instances, key = { it.id }) { inst ->
                            // 모드는 로더가 있는 인스턴스에만 넣을 수 있음
                            val needsLoader = type == ImportType.MOD && inst.loaderType == null
                            val enabled = !needsLoader
                            val sel = selectedInstance == inst.id
                            InstanceRow(
                                label = inst.label,
                                loaderType = inst.loaderType,
                                selected = sel,
                                enabled = enabled,
                            ) { if (enabled) selectedInstance = inst.id }
                        }
                    }
                }

                // 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onCancel) { Text(getString(R.string.cancel_button), color = TextSecondary) }
                    Button(
                        onClick = { selectedInstance?.let { onConfirm(it, type) } },
                        enabled = selectedInstance != null && !importing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TurtlePrimary,
                            disabledContainerColor = BgBorder
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(getString(R.string.add_button_plain), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 진행 오버레이
            if (importing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = TurtlePrimary, strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(statusMsg, color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun TypeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Color(0xFF2D0A20) else BgDark)
                .border(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) TurtlePrimary else BgBorder,
                    RoundedCornerShape(10.dp)
                )
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) Color.White else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    @Composable
    private fun InstanceRow(
        label: String,
        loaderType: String?,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.4f)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Color(0xFF2D0A20) else BgDark)
                .border(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) TurtlePrimary else BgBorder,
                    RoundedCornerShape(8.dp)
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label, color = TextPrimary, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                loaderType?.replaceFirstChar { it.uppercase() } ?: "vanilla",
                color = TextSecondary, fontSize = 10.sp
            )
        }
    }
}