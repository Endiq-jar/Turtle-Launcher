package com.endiq.turtlelauncher.ui.dialog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.DialogSkinCapeBinding
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.skin.LabyModGalleryApi
import com.endiq.turtlelauncher.feature.skin.LabyModSkinApi
import com.endiq.turtlelauncher.feature.skin.LittleSkinGalleryApi
import com.endiq.turtlelauncher.feature.skin.SkinCapeHistoryStore
import com.endiq.turtlelauncher.feature.skin.TurtleSkinServer
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.DraggableDialog.DialogInitializationListener
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.utils.DownloadUtils
import net.kdt.pojavlaunch.value.MinecraftAccount
import java.io.File
import java.io.FileOutputStream


/**
 * Dialog for changing skin or cape from URL or gallery pick.
 * @param mode "skin" or "cape"
 */
class SkinCapeDialog(
    private val activity: AppCompatActivity,
    private val account: MinecraftAccount,
    private val mode: String
) : FullScreenDialog(activity), DialogInitializationListener {

    private val binding = DialogSkinCapeBinding.inflate(LayoutInflater.from(activity))
    private var galleryLauncher: ActivityResultLauncher<Intent>? = null
    /** The URL a successful Browse search resolved to (skin or cape, per [mode]); null until a search succeeds. */
    private var browseResolvedUrl: String? = null
    /** The username that resolved [browseResolvedUrl], kept alongside it for history labeling. */
    private var browseResolvedUsername: String? = null

    private var labyGalleryPage = 1
    private var labyGalleryQuery: LabyModGalleryApi.GalleryQuery = LabyModGalleryApi.GalleryQuery.Trending
    private var littleskinGalleryPage = 1
    private var littleskinGalleryQuery: LittleSkinGalleryApi.GalleryQuery = LittleSkinGalleryApi.GalleryQuery.Trending

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val titleRes = if (mode == "cape") R.string.skin_cape_change_cape else R.string.skin_cape_change_skin
        binding.title.setText(titleRes)

        galleryLauncher = activity.activityResultRegistry.register(
            "SkinCapeGallery_$mode",
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri -> applyFromUri(uri) }
            }
        }

        binding.buttonGallery.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/png"
            galleryLauncher?.launch(intent)
        }

        binding.buttonApplyUrl.setOnClickListener {
            val url = binding.urlEdit.text.toString().trim()
            if (url.isEmpty()) {
                binding.urlEdit.error = context.getString(R.string.generic_error_field_empty)
                return@setOnClickListener
            }
            applyFromUrl(url)
        }

        binding.buttonBrowseSearch.setOnClickListener {
            val username = binding.browseUsernameEdit.text.toString().trim()
            if (username.isEmpty()) {
                binding.browseUsernameEdit.error = context.getString(R.string.generic_error_field_empty)
                return@setOnClickListener
            }
            searchBrowse(username)
        }

        binding.buttonBrowseApply.setOnClickListener {
            val url = browseResolvedUrl ?: return@setOnClickListener
            val label = browseResolvedUsername ?: context.getString(R.string.skin_cape_gallery_source_lookup)
            applyFromUrl(url, label)
        }

        binding.buttonCancel.setOnClickListener { dismiss() }

        setupLanVisibilityControls()
        setupGallerySection()
        setupLabyGallerySection()
        setupLittleSkinGallerySection()

        checkHeight(binding.root, binding.contentView, binding.scrollView)
        DraggableDialog.initDialog(this)
    }

    private fun setupLanVisibilityControls() {
        binding.switchLanVisible.isChecked = AllSettings.localSkinServerLanVisible.getValue()
        updateInstructionsVisibility(binding.switchLanVisible.isChecked)

        binding.switchLanVisible.setOnCheckedChangeListener { _, isChecked ->
            AllSettings.localSkinServerLanVisible.put(isChecked).save()
            updateInstructionsVisibility(isChecked)
        }

        binding.buttonCopyLanInstructions.setOnClickListener {
            // The server won't actually be bound to a LAN-reachable port until the next
            // game launch picks up the (just-saved) setting - ensureStarted() here just
            // lets the instructions show a real, correct address+port right away instead
            // of a stale or placeholder one.
            TurtleSkinServer.ensureStarted(true)
            val instructions = TurtleSkinServer.lanInstructions()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TurtleLauncher skin server", instructions))
            Toast.makeText(context, R.string.skin_cape_instructions_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateInstructionsVisibility(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.textLanInstructions.visibility = visibility
        binding.buttonCopyLanInstructions.visibility = visibility
    }

    private fun urlLabel(url: String): String =
        url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: context.getString(R.string.skin_cape_gallery_source_url)

    private fun applyFromUrl(url: String, label: String = urlLabel(url)) {
        val destFile = getDestFile()
        showProgress(true)
        Task.runTask {
            destFile.parentFile?.mkdirs()
            DownloadUtils.downloadFile(url, destFile)
            SkinCapeHistoryStore.recordApplied(mode, destFile, label)
        }.ended(TaskExecutors.getAndroidUI()) {
            showProgress(false)
            notifySuccess()
            dismiss()
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                showProgress(false)
                Toast.makeText(
                    context,
                    context.getString(R.string.skin_cape_apply_failed) + ": " + e.message,
                    Toast.LENGTH_LONG
                ).show()
                Logging.e("SkinCapeDialog", "Failed to download from URL", e)
            }
        }.execute()
    }

    /** Bundles what the background lookup produces so the UI-thread callback only has to render it. */
    private data class BrowseSearchResult(
        val lookup: LabyModSkinApi.PlayerLookup?,
        val resolvedUrl: String?,
        val previewBitmap: Bitmap?
    )

    private fun searchBrowse(username: String) {
        showProgress(true)
        binding.browseResultRow.visibility = View.GONE
        browseResolvedUrl = null
        browseResolvedUsername = null

        Task.runTask {
            val lookup = LabyModSkinApi.lookupPlayer(username)
            val resolvedUrl = lookup?.let { if (mode == "cape") it.capeUrl else it.skinUrl }
            val previewBitmap = resolvedUrl?.let { url ->
                runCatching {
                    val buffer = java.io.ByteArrayOutputStream()
                    net.kdt.pojavlaunch.utils.DownloadUtils.download(url, buffer)
                    val bytes = buffer.toByteArray()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.onFailure { e -> Logging.e("SkinCapeDialog", "Failed to load browse preview", e) }.getOrNull()
            }
            BrowseSearchResult(lookup, resolvedUrl, previewBitmap)
        }.ended(TaskExecutors.getAndroidUI()) { result ->
            showProgress(false)
            renderBrowseResult(username, result)
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                showProgress(false)
                Toast.makeText(context, context.getString(R.string.skin_cape_browse_no_player), Toast.LENGTH_LONG).show()
                Logging.e("SkinCapeDialog", "Browse search failed", e)
            }
        }.execute()
    }

    private fun renderBrowseResult(username: String, result: BrowseSearchResult?) {
        val lookup = result?.lookup
        if (lookup == null) {
            Toast.makeText(context, R.string.skin_cape_browse_no_player, Toast.LENGTH_LONG).show()
            binding.browseResultRow.visibility = View.GONE
            return
        }

        binding.browseResultRow.visibility = View.VISIBLE
        if (result.resolvedUrl == null) {
            // Resolved to a real account, but no LabyMod cape (skins always resolve if the account exists).
            binding.browseResultText.text = context.getString(R.string.skin_cape_browse_no_cape)
            binding.browsePreviewImage.setImageBitmap(null)
            binding.buttonBrowseApply.isEnabled = false
            browseResolvedUrl = null
            browseResolvedUsername = null
            return
        }

        val labelRes = if (mode == "cape") R.string.skin_cape_browse_found_cape else R.string.skin_cape_browse_found_skin
        binding.browseResultText.text = context.getString(labelRes, username)
        binding.browsePreviewImage.setImageBitmap(result.previewBitmap)
        binding.buttonBrowseApply.isEnabled = true
        browseResolvedUrl = result.resolvedUrl
        browseResolvedUsername = username
    }

    private fun applyFromUri(uri: Uri) {
        val destFile = getDestFile()
        showProgress(true)
        Task.runTask {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { out -> input.copyTo(out) }
            } ?: throw RuntimeException("Cannot open image")
            SkinCapeHistoryStore.recordApplied(mode, destFile, context.getString(R.string.skin_cape_gallery_source_gallery))
        }.ended(TaskExecutors.getAndroidUI()) {
            showProgress(false)
            notifySuccess()
            dismiss()
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                showProgress(false)
                Toast.makeText(
                    context,
                    context.getString(R.string.skin_cape_apply_failed) + ": " + e.message,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.execute()
    }

    /** One tile in the gallery grid: either the bundled default skin, or a real applied-history entry. */
    private data class GalleryDisplayItem(
        val entry: SkinCapeHistoryStore.HistoryEntry?,
        val isDefault: Boolean,
        val label: String,
        val bitmap: Bitmap?
    )

    private fun setupGallerySection() {
        binding.galleryRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        Task.runTask {
            buildGalleryItems()
        }.ended(TaskExecutors.getAndroidUI()) { items ->
            renderGallery(items ?: emptyList())
        }.onThrowable { e ->
            TaskExecutors.runInUIThread { Logging.e("SkinCapeDialog", "Failed to load gallery", e) }
        }.execute()
    }

    private fun buildGalleryItems(): List<GalleryDisplayItem> {
        val items = mutableListOf<GalleryDisplayItem>()
        // Only skins have a real, always-available bundled default (steve.png, shipped with the
        // app) - Mojang doesn't ship a swappable "default cape" the same way, so cape mode is
        // history-only.
        if (mode == "skin") {
            val defaultBitmap = runCatching {
                context.assets.open("steve.png").use { BitmapFactory.decodeStream(it) }
            }.onFailure { e -> Logging.e("SkinCapeDialog", "Failed to load bundled default skin", e) }.getOrNull()
            items += GalleryDisplayItem(null, true, context.getString(R.string.skin_cape_gallery_default_label), defaultBitmap)
        }
        SkinCapeHistoryStore.loadHistory(mode).forEach { entry ->
            val bitmap = runCatching {
                BitmapFactory.decodeFile(SkinCapeHistoryStore.thumbFile(mode, entry).absolutePath)
            }.onFailure { e -> Logging.e("SkinCapeDialog", "Failed to load history thumb for ${entry.label}", e) }.getOrNull()
            items += GalleryDisplayItem(entry, false, entry.label, bitmap)
        }
        return items
    }

    private fun renderGallery(items: List<GalleryDisplayItem>) {
        val hasAny = items.isNotEmpty()
        binding.galleryEmptyText.visibility = if (hasAny) View.GONE else View.VISIBLE
        binding.galleryRecycler.visibility = if (hasAny) View.VISIBLE else View.GONE
        binding.galleryRecycler.adapter = SkinCapeGalleryAdapter(items.map { it.label to it.bitmap }) { position ->
            applyGalleryItem(items[position])
        }
    }

    private fun applyGalleryItem(item: GalleryDisplayItem) {
        if (binding.progressBar.visibility == View.VISIBLE) return // an apply is already in flight
        val destFile = getDestFile()
        showProgress(true)
        Task.runTask {
            destFile.parentFile?.mkdirs()
            if (item.isDefault) {
                context.assets.open("steve.png").use { input ->
                    FileOutputStream(destFile).use { out -> input.copyTo(out) }
                }
                // No need to record the bundled default into history - it's already a permanent gallery tile.
            } else {
                val entry = requireNotNull(item.entry)
                SkinCapeHistoryStore.thumbFile(mode, entry).copyTo(destFile, overwrite = true)
                SkinCapeHistoryStore.recordApplied(mode, destFile, item.label)
            }
        }.ended(TaskExecutors.getAndroidUI()) {
            showProgress(false)
            notifySuccess()
            dismiss()
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                showProgress(false)
                Toast.makeText(
                    context,
                    context.getString(R.string.skin_cape_apply_failed) + ": " + e.message,
                    Toast.LENGTH_LONG
                ).show()
                Logging.e("SkinCapeDialog", "Failed to apply gallery item", e)
            }
        }.execute()
    }

    // ── Live laby.net/skins gallery browsing ────────────────────────────────
    // See LabyModGalleryApi's doc comment for how this actually works without a documented
    // public API. Skin-only - laby.net doesn't have a cape library the way it has a skin one.

    private fun setupLabyGallerySection() {
        if (mode != "skin") {
            binding.labyGalleryLabel.visibility = View.GONE
            binding.labyGallerySearchRow.visibility = View.GONE
            binding.labyGalleryRecycler.visibility = View.GONE
            binding.labyGalleryEmptyText.visibility = View.GONE
            binding.labyGalleryProgress.visibility = View.GONE
            return
        }

        binding.labyGalleryRecycler.layoutManager = GridLayoutManager(context, 4)

        binding.buttonLabyGallerySearch.setOnClickListener {
            val text = binding.labyGallerySearchEdit.text.toString().trim()
            labyGalleryQuery = if (text.isEmpty()) LabyModGalleryApi.GalleryQuery.Trending
                        else LabyModGalleryApi.GalleryQuery.Search(text)
            labyGalleryPage = 1
            loadLabyGallery()
        }

        binding.buttonLabyGalleryPrev.setOnClickListener {
            if (labyGalleryPage > 1) {
                labyGalleryPage--
                loadLabyGallery()
            }
        }
        binding.buttonLabyGalleryNext.setOnClickListener {
            labyGalleryPage++
            loadLabyGallery()
        }

        loadLabyGallery()
    }

    private fun loadLabyGallery() {
        binding.labyGalleryProgress.visibility = View.VISIBLE
        binding.labyGalleryEmptyText.visibility = View.GONE
        val query = labyGalleryQuery
        val page = labyGalleryPage
        Task.runTask {
            LabyModGalleryApi.fetchGallery(query, page)
        }.ended(TaskExecutors.getAndroidUI()) { skins ->
            renderLabyGallery(skins ?: emptyList())
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                renderLabyGallery(emptyList())
                Logging.e("SkinCapeDialog", "Failed to load laby.net gallery", e)
            }
        }.execute()
    }

    private fun renderLabyGallery(skins: List<LabyModGalleryApi.GallerySkin>) {
        binding.labyGalleryProgress.visibility = View.GONE
        val hasAny = skins.isNotEmpty()
        // An empty page here (rather than page 1) most likely means we've paged past the end,
        // since a genuinely broken/unreachable source fails the same way at page 1 too - either
        // way there's nothing to show, so step back and let the user retry from there.
        if (!hasAny && labyGalleryPage > 1) {
            labyGalleryPage--
        }
        binding.labyGalleryEmptyText.visibility = if (hasAny) View.GONE else View.VISIBLE
        binding.labyGalleryRecycler.visibility = if (hasAny) View.VISIBLE else View.GONE
        binding.labyGalleryRecycler.adapter = LabyModGalleryGridAdapter(skins) { position ->
            applyLabyGallerySkin(skins[position])
        }
        binding.labyGalleryPageLabel.text = context.getString(R.string.skin_cape_gallery_page_label, labyGalleryPage)
        binding.buttonLabyGalleryPrev.isEnabled = labyGalleryPage > 1
        binding.buttonLabyGalleryNext.isEnabled = hasAny
    }

    private fun applyLabyGallerySkin(skin: LabyModGalleryApi.GallerySkin) {
        if (binding.progressBar.visibility == View.VISIBLE) return // an apply is already in flight
        val destFile = getDestFile()
        showProgress(true)
        Task.runTask {
            val textureBytes = LabyModGalleryApi.resolveApplyTexture(skin.hash)
                ?: throw RuntimeException("Couldn't resolve a raw texture for this skin")
            destFile.parentFile?.mkdirs()
            destFile.writeBytes(textureBytes)
            SkinCapeHistoryStore.recordApplied(mode, destFile, context.getString(R.string.skin_cape_laby_gallery_source, skin.label))
        }.ended(TaskExecutors.getAndroidUI()) {
            showProgress(false)
            notifySuccess()
            dismiss()
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                showProgress(false)
                Toast.makeText(
                    context,
                    context.getString(R.string.skin_cape_laby_gallery_apply_failed),
                    Toast.LENGTH_LONG
                ).show()
                Logging.e("SkinCapeDialog", "Failed to apply laby.net gallery skin ${skin.hash}", e)
            }
        }.execute()
    }

    // ── Live littleskin.cn skin/cape library browsing ───────────────────────
    // See LittleSkinGalleryApi's doc comment for how this works without a documented public
    // API. Unlike the laby.net section above, this works for BOTH skin and cape mode.

    private fun setupLittleSkinGallerySection() {
        binding.littleskinGalleryRecycler.layoutManager = GridLayoutManager(context, 4)

        binding.buttonLittleskinGallerySearch.setOnClickListener {
            val text = binding.littleskinGallerySearchEdit.text.toString().trim()
            littleskinGalleryQuery = if (text.isEmpty()) LittleSkinGalleryApi.GalleryQuery.Trending
                        else LittleSkinGalleryApi.GalleryQuery.Search(text)
            littleskinGalleryPage = 1
            loadLittleSkinGallery()
        }

        binding.buttonLittleskinGalleryPrev.setOnClickListener {
            if (littleskinGalleryPage > 1) {
                littleskinGalleryPage--
                loadLittleSkinGallery()
            }
        }
        binding.buttonLittleskinGalleryNext.setOnClickListener {
            littleskinGalleryPage++
            loadLittleSkinGallery()
        }

        loadLittleSkinGallery()
    }

    private fun loadLittleSkinGallery() {
        binding.littleskinGalleryProgress.visibility = View.VISIBLE
        binding.littleskinGalleryEmptyText.visibility = View.GONE
        val query = littleskinGalleryQuery
        val page = littleskinGalleryPage
        Task.runTask {
            LittleSkinGalleryApi.fetchGallery(mode, query, page)
        }.ended(TaskExecutors.getAndroidUI()) { skins ->
            renderLittleSkinGallery(skins ?: emptyList())
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                renderLittleSkinGallery(emptyList())
                Logging.e("SkinCapeDialog", "Failed to load littleskin.cn gallery", e)
            }
        }.execute()
    }

    private fun renderLittleSkinGallery(skins: List<LittleSkinGalleryApi.GallerySkin>) {
        binding.littleskinGalleryProgress.visibility = View.GONE
        val hasAny = skins.isNotEmpty()
        // littleskin.cn's `page` param is a real, confirmed Laravel paginate() page number, so
        // (unlike laby.net) an empty non-first page unambiguously means "past the last page" -
        // same step-back behavior as the laby.net section for a consistent feel either way.
        if (!hasAny && littleskinGalleryPage > 1) {
            littleskinGalleryPage--
        }
        binding.littleskinGalleryEmptyText.visibility = if (hasAny) View.GONE else View.VISIBLE
        binding.littleskinGalleryRecycler.visibility = if (hasAny) View.VISIBLE else View.GONE
        binding.littleskinGalleryRecycler.adapter = LittleSkinGalleryGridAdapter(skins) { position ->
            applyLittleSkinGallerySkin(skins[position])
        }
        binding.littleskinGalleryPageLabel.text = context.getString(R.string.skin_cape_gallery_page_label, littleskinGalleryPage)
        binding.buttonLittleskinGalleryPrev.isEnabled = littleskinGalleryPage > 1
        binding.buttonLittleskinGalleryNext.isEnabled = hasAny
    }

    private fun applyLittleSkinGallerySkin(skin: LittleSkinGalleryApi.GallerySkin) {
        if (binding.progressBar.visibility == View.VISIBLE) return // an apply is already in flight
        val destFile = getDestFile()
        showProgress(true)
        Task.runTask {
            val textureBytes = LittleSkinGalleryApi.resolveApplyTexture(skin.tid)
                ?: throw RuntimeException("Couldn't resolve a raw texture for this skin")
            destFile.parentFile?.mkdirs()
            destFile.writeBytes(textureBytes)
            SkinCapeHistoryStore.recordApplied(mode, destFile, context.getString(R.string.skin_cape_littleskin_gallery_source, skin.label))
        }.ended(TaskExecutors.getAndroidUI()) {
            showProgress(false)
            notifySuccess()
            dismiss()
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                showProgress(false)
                Toast.makeText(
                    context,
                    context.getString(R.string.skin_cape_littleskin_gallery_apply_failed),
                    Toast.LENGTH_LONG
                ).show()
                Logging.e("SkinCapeDialog", "Failed to apply littleskin.cn gallery skin ${skin.tid}", e)
            }
        }.execute()
    }

    private fun getDestFile(): File {
        return if (mode == "cape") {
            File(PathManager.DIR_USER_SKIN, account.uniqueUUID + "_cape.png")
        } else {
            File(PathManager.DIR_USER_SKIN, account.uniqueUUID + ".png")
        }
    }

    private fun showProgress(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.buttonApplyUrl.isEnabled = !show
        binding.buttonGallery.isEnabled = !show
        binding.buttonBrowseSearch.isEnabled = !show
        binding.buttonLabyGallerySearch.isEnabled = !show
        binding.buttonLittleskinGallerySearch.isEnabled = !show
        // Prev/Next aren't touched here: this flag guards URL/gallery-tile *apply* actions,
        // which either dismiss the dialog on success or just toast on failure - the page
        // buttons' own enabled state (driven by renderLabyGallery/renderLittleSkinGallery) is
        // unrelated and shouldn't be clobbered by an unrelated apply in flight.
        // buttonBrowseApply is re-enabled by renderBrowseResult() only when there's something to apply
        if (show) binding.buttonBrowseApply.isEnabled = false
    }

    private fun notifySuccess() {
        val msgRes = if (mode == "cape") R.string.skin_cape_cape_applied else R.string.skin_cape_skin_applied
        Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
    }

    override fun onInit(): Window? = window
}
