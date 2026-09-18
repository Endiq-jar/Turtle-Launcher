package com.endiq.turtlelauncher.ui.fragment

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.angcyo.tablayout.DslTabLayout
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentAccountBinding
import com.endiq.turtlelauncher.databinding.ItemOtherServerBinding
import com.endiq.turtlelauncher.databinding.ViewAddOtherServerBinding
import com.endiq.turtlelauncher.databinding.ViewSingleActionPopupBinding
import com.endiq.turtlelauncher.event.single.AccountUpdateEvent
import com.endiq.turtlelauncher.event.value.LocalLoginEvent
import com.endiq.turtlelauncher.event.value.OtherLoginEvent
import com.endiq.turtlelauncher.feature.accounts.AccountUtils
import com.endiq.turtlelauncher.feature.accounts.AccountsManager
import com.endiq.turtlelauncher.feature.accounts.LocalAccountUtils
import com.endiq.turtlelauncher.feature.accounts.LocalAccountUtils.CheckResultListener
import com.endiq.turtlelauncher.feature.accounts.LocalAccountUtils.Companion.checkUsageAllowed
import com.endiq.turtlelauncher.feature.accounts.LocalAccountUtils.Companion.openDialog
import com.endiq.turtlelauncher.feature.accounts.OtherLoginHelper
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.login.OtherLoginApi
import com.endiq.turtlelauncher.feature.login.Servers
import com.endiq.turtlelauncher.feature.login.Servers.Server
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.EditTextDialog
import com.endiq.turtlelauncher.ui.dialog.OtherLoginDialog
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.layout.AnimRelativeLayout
import com.endiq.turtlelauncher.ui.subassembly.account.AccountAdapter
import com.endiq.turtlelauncher.ui.subassembly.account.AccountAdapter.AccountUpdateListener
import com.endiq.turtlelauncher.ui.subassembly.account.SelectAccountListener
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.http.NetworkUtils
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.endiq.launcher.Tools
import net.endiq.launcher.fragments.MicrosoftLoginFragment
import net.endiq.launcher.value.MinecraftAccount
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern



class AccountFragment : FragmentWithAnim(R.layout.fragment_account), View.OnClickListener {
    companion object {
        const val TAG = "AccountFragment"
    }

    private lateinit var binding: FragmentAccountBinding
    private val mAccountsData: MutableList<MinecraftAccount> = AccountsManager.allAccounts.toMutableList()
    private val mAccountAdapter = AccountAdapter(mAccountsData)

    // The currently-live 3D skin preview instance. GLSurfaceView.setRenderer() (which
    // SkinView3DSurfaceView.render() calls internally) can only be called ONCE per instance -
    // calling it a second time throws IllegalStateException. Since updateAccountDetail() runs
    // every time the selected account changes, each render swaps in a fresh
    // SkinView3DSurfaceView (same id/layoutParams, so ConstraintLayout's existing constraints
    // against @id/detail_skin_preview keep resolving correctly) rather than reusing one.
    private var skinPreviewView: dev.storeforminecraft.skinviewandroid.library.threedimension.ui.SkinView3DSurfaceView? = null

    private val selectAccountListener = object : SelectAccountListener {
        override fun onSelect(account: MinecraftAccount) {
            if (!isTaskRunning()) {
                AccountsManager.currentAccount = account
            } else {
                TaskExecutors.runInUIThread {
                    activity?.let {
                        Toast.makeText(
                            it,
                            R.string.tasks_ongoing,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private val mServerActionPopupWindow: PopupWindow = PopupWindow().apply {
        isFocusable = true
        isOutsideTouchable = true
    }

    private val mLocalNamePattern = Pattern.compile("[^a-zA-Z0-9_]")
    private var mOtherServerConfig: Servers? = null
    private val mOtherServerConfigFile = File(PathManager.DIR_GAME_HOME, "servers.json")
    private val mOtherServerList: MutableList<Server> = ArrayList()
    private val mOtherServerViewList: MutableList<View> = ArrayList()

    private lateinit var mProgressDialog: AlertDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAccountBinding.inflate(layoutInflater)
        mProgressDialog = ZHTools.createTaskRunningDialog(binding.root.context)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireActivity()

        mAccountAdapter.setAccountUpdateListener(object : AccountUpdateListener {
            override fun onViewClick(account: MinecraftAccount) {
                selectAccountListener.onSelect(account)
            }

            override fun onRefresh(account: MinecraftAccount) {
                if (!isTaskRunning()) {
                    if (!NetworkUtils.isNetworkAvailable(context)) {
                        Toast.makeText(context, R.string.account_login_no_network, Toast.LENGTH_SHORT).show()
                        return
                    }
                    AccountsManager.performLogin(context, account)
                } else {
                    Toast.makeText(context, R.string.tasks_ongoing, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDelete(account: MinecraftAccount) {
                TipDialog.Builder(context)
                    .setTitle(R.string.generic_warning)
                    .setMessage(R.string.account_remove)
                    .setConfirm(R.string.generic_delete)
                    .setWarning()
                    .setConfirmClickListener {
                        val accountFile =
                            File(PathManager.DIR_ACCOUNT_NEW, account.uniqueUUID)
                        val userSkinFile =
                            File(PathManager.DIR_USER_SKIN, account.uniqueUUID + ".png")
                        if (accountFile.exists()) FileUtils.deleteQuietly(accountFile)
                        if (userSkinFile.exists()) FileUtils.deleteQuietly(userSkinFile)
                        reloadAccounts()
                    }.showDialog()
            }

            override fun onSkinChange(account: MinecraftAccount) {
                com.endiq.turtlelauncher.ui.dialog.SkinCapeDialog(
                    requireActivity() as androidx.appcompat.app.AppCompatActivity,
                    account,
                    "skin"
                ).show()
            }

            override fun onCapeChange(account: MinecraftAccount) {
                com.endiq.turtlelauncher.ui.dialog.SkinCapeDialog(
                    requireActivity() as androidx.appcompat.app.AppCompatActivity,
                    account,
                    "cape"
                ).show()
            }
        })

        binding.apply {
            accountsRecycler.layoutManager = LinearLayoutManager(context)
            accountsRecycler.setLayoutAnimation(
                TurtleTransitions.listLayoutAnimationController(context)
            )
            accountsRecycler.adapter = mAccountAdapter

            viewAccountDetail.detailPlay.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
            viewAccountDetail.detailRefresh.setOnClickListener {
                AccountsManager.currentAccount?.let { account ->
                    if (!isTaskRunning()) {
                        if (!NetworkUtils.isNetworkAvailable(context)) {
                            Toast.makeText(context, R.string.account_login_no_network, Toast.LENGTH_SHORT).show()
                        } else {
                            AccountsManager.performLogin(context, account)
                        }
                    } else {
                        Toast.makeText(context, R.string.tasks_ongoing, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            viewAccountDetail.detailEdit.setOnClickListener {
                AccountsManager.currentAccount?.let { account ->
                    com.endiq.turtlelauncher.ui.dialog.SkinCapeDialog(
                        requireActivity() as androidx.appcompat.app.AppCompatActivity,
                        account,
                        "skin"
                    ).show()
                }
            }
            viewAccountDetail.detailRemove.setOnClickListener {
                AccountsManager.currentAccount?.let { account ->
                    TipDialog.Builder(context)
                        .setTitle(R.string.generic_warning)
                        .setMessage(R.string.account_remove)
                        .setConfirm(R.string.generic_delete)
                        .setWarning()
                        .setConfirmClickListener {
                            val accountFile = File(PathManager.DIR_ACCOUNT_NEW, account.uniqueUUID)
                            val userSkinFile = File(PathManager.DIR_USER_SKIN, account.uniqueUUID + ".png")
                            if (accountFile.exists()) FileUtils.deleteQuietly(accountFile)
                            if (userSkinFile.exists()) FileUtils.deleteQuietly(userSkinFile)
                            reloadAccounts()
                        }.showDialog()
                }
            }
            viewAccountDetail.copyUuid.setOnClickListener {
                AccountsManager.currentAccount?.let { account ->
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("uuid", account.uniqueUUID))
                    Toast.makeText(context, R.string.generic_copied, Toast.LENGTH_SHORT).show()
                }
            }

            accountTypeTab.observeIndexChange { _, toIndex, _, fromUser ->
                fun nonMicrosoftLogin(message: Int, login: () -> Unit) {
                    checkUsageAllowed(object : CheckResultListener {
                        override fun onUsageAllowed() {
                            login()
                        }

                        override fun onUsageDenied() {
                            if (!AllSettings.localAccountReminders.getValue()) {
                                login()
                            } else {
                                openDialog(
                                    context,
                                    TipDialog.OnConfirmClickListener { checked ->
                                        LocalAccountUtils.saveReminders(checked)
                                        login()
                                    },
                                    getString(message) + getString(
                                        R.string.account_purchase_minecraft_account_tip
                                    ),
                                    R.string.account_no_microsoft_account_continue
                                )
                            }
                        }
                    })
                }

                if (fromUser) { // only react to real taps, otherwise we would loop into Microsoft login
                    when (toIndex) {
                        // Microsoft account.
                        0 -> ZHTools.swapFragmentWithAnim(
                            this@AccountFragment,
                            MicrosoftLoginFragment::class.java,
                            MicrosoftLoginFragment.TAG,
                            null
                        )
                        // Offline account.
                        1 -> {
                            nonMicrosoftLogin(
                                R.string.account_no_microsoft_account_local
                            ) { localLogin() }
                        }
                        // Third-party account.
                        else -> {
                            nonMicrosoftLogin(
                                R.string.account_no_microsoft_account_other
                            ) { otherLogin(toIndex - 2) /* Server indices are zero-based */ }
                        }
                    }
                }
            }

            addServer.setOnClickListener(this@AccountFragment)
            returnButton.setOnClickListener(this@AccountFragment)
        }

        reloadAccounts()
        refreshOtherServer()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun reloadRecyclerView() {
        this.mAccountsData.clear()
        mAccountsData.addAll(AccountsManager.allAccounts)

        this.mAccountAdapter.notifyDataSetChanged()
        binding.accountsRecycler.scheduleLayoutAnimation()
    }

    private fun reloadAccounts() {
        Task.runTask {
            AccountsManager.reload()
        }.ended(TaskExecutors.getAndroidUI()) {
            // reloadAccounts() is called from async login callbacks - the user may
            // have navigated away by now (updateAccountDetail uses requireContext).
            if (!isAdded) return@ended
            reloadRecyclerView()
            updateAccountDetail()
        }.execute()
    }

    /** Populates the right-pane detail panel (view_account_detail.xml) for whatever
     *  AccountsManager.currentAccount currently is - null clears it to a placeholder. */
    private fun updateAccountDetail() {
        val account = AccountsManager.currentAccount
        binding.viewAccountDetail.apply {
            if (account == null) {
                detailIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_help))
                detailName.text = null
                detailType.text = null
                detailUuid.text = null
                skinPreviewView?.onPause()
                return
            }

            runCatching {
                detailIcon.setImageDrawable(
                    com.endiq.turtlelauncher.utils.skin.SkinLoader.getAvatarDrawable(
                        requireContext(),
                        account,
                        Tools.dpToPx(resources.getDimensionPixelSize(R.dimen._72sdp).toFloat()).toInt()
                    )
                )
            }.onFailure { e -> Logging.e(TAG, "Failed to load avatar.", e) }

            runCatching {
                val bitmap = com.endiq.turtlelauncher.utils.skin.SkinLoader.getSkinBitmap(requireContext(), account)
                // See the skinPreviewView field comment: swap in a fresh instance every time
                // rather than reusing detailSkinPreview.render() directly, since GLSurfaceView
                // only allows setRenderer() to be called once per instance ever.
                val current = skinPreviewView ?: detailSkinPreview
                val container = current.parent as android.view.ViewGroup
                val index = container.indexOfChild(current)
                val fresh = dev.storeforminecraft.skinviewandroid.library.threedimension.ui.SkinView3DSurfaceView(requireContext()).apply {
                    id = current.id
                    layoutParams = current.layoutParams
                }
                container.removeView(current)
                container.addView(fresh, index)
                fresh.render(bitmap)
                fresh.onResume()
                skinPreviewView = fresh
            }.onFailure { e -> Logging.e(TAG, "Failed to load 3D skin preview.", e) }

            detailName.text = account.username
            detailType.text = AccountUtils.getAccountTypeName(requireContext(), account)
            detailUuid.text = account.uniqueUUID
        }
    }

    private fun SpannableString.spanText(start: Int, end: Int, what: Any) {
        this.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun localLogin() {
        fun startLogin(name: String) {
            EventBus.getDefault().post(LocalLoginEvent(name.trim()))
        }

        EditTextDialog.Builder(requireActivity())
            .setTitle(R.string.account_login_local_name)
            .setConfirmText(R.string.generic_login)
            .setEmptyErrorText(R.string.account_local_account_empty)
            .setAsRequired()
            .setConfirmListener { editText, _ ->
                val string = editText.text.toString()
                if (string.length <= 2 || string.length > 16 || mLocalNamePattern.matcher(string).find()) {
                    TipDialog.Builder(requireContext())
                        .setTitle(R.string.generic_warning)
                        .setMessage(R.string.account_local_account_invalid)
                        .setWarning()
                        .setTextBeautifier { _, messageText ->
                            val text = messageText.text.toString()
                            val startTag = "[RED;BOLD]"
                            val endTag = "[/RED;BOLD]"

                            val startIndex = text.indexOf(startTag)
                            val endIndex = text.indexOf(endTag)

                            if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                                val styledText = text.substring(startIndex + startTag.length, endIndex)
                                val plainText = text.replace(startTag, "").replace(endTag, "")
                                val adjustedEndIndex = startIndex + styledText.length

                                val spannableString = SpannableString(plainText)
                                spannableString.spanText(startIndex, adjustedEndIndex, ForegroundColorSpan(Color.RED))
                                spannableString.spanText(startIndex, adjustedEndIndex, StyleSpan(Typeface.BOLD))

                                messageText.text = spannableString
                            }
                        }
                        .setCenterMessage(false)
                        .setConfirmClickListener { startLogin(string) }
                        .setCancelable(false)
                        .setConfirmButtonCountdown(3000L)
                        .showDialog()
                } else startLogin(string)

                true
            }.showDialog()
    }

    private fun otherLogin(index: Int) {
        val server = mOtherServerList[index]
        OtherLoginDialog(requireActivity(), server,
            object : OtherLoginHelper.OnLoginListener {
                override fun onLoading() {
                    mProgressDialog.show()
                }

                override fun unLoading() {
                    mProgressDialog.dismiss()
                }

                override fun onSuccess(account: MinecraftAccount) {
                    EventBus.getDefault().post(OtherLoginEvent(account))
                }

                override fun onFailed(error: String) {
                    mProgressDialog.dismiss()

                    TipDialog.Builder(requireActivity())
                        .setTitle(R.string.generic_warning)
                        .setMessage(getString(R.string.other_login_error) + error)
                        .setWarning()
                        .setCancel(android.R.string.copy)
                        .setCancelClickListener {
                            StringUtils.copyText(
                                "error",
                                error,
                                requireActivity()
                            )
                        }
                        .showDialog()
                }
            }).show()
    }

    private fun refreshOtherServer() {
        Task.runTask {
            mOtherServerList.clear()
            if (mOtherServerConfigFile.exists()) {
                runCatching {
                    val serverConfig = Tools.GLOBAL_GSON.fromJson(
                        Tools.read(mOtherServerConfigFile.absolutePath),
                        Servers::class.java
                    )
                    mOtherServerConfig = serverConfig
                    serverConfig.server.forEach { server ->
                        mOtherServerList.add(server)
                    }
                }
            }
        }.ended(TaskExecutors.getAndroidUI()) {
            // The server scan runs in the background - bail out if the user
            // navigated away while it was running (requireActivity()/binding
            // access would crash with "not attached" otherwise).
            if (!isAdded) return@ended
            // Add the external server to the account category bar.
            mOtherServerViewList.forEach { view ->
                binding.accountTypeTab.removeView(view)
            }
            mOtherServerViewList.clear()

            val activity = requireActivity()
            val layoutInflater = activity.layoutInflater

            fun createView(server: Server): AnimRelativeLayout {
                val p8 = Tools.dpToPx(8f).toInt()
                val view = ItemOtherServerBinding.inflate(layoutInflater)
                view.text.text = server.serverName
                view.root.setOnLongClickListener { v ->
                    refreshActionPopupWindow(v, ViewSingleActionPopupBinding.inflate(LayoutInflater.from(activity)).apply {
                        icon.setImageDrawable(
                            ContextCompat.getDrawable(requireActivity(), R.drawable.ic_menu_delete_forever)
                        )
                        text.setText(R.string.generic_delete)
                        text.setOnClickListener {
                            deleteOtherServer(server)
                            mServerActionPopupWindow.dismiss()
                        }
                    })
                    true
                }
                view.root.layoutParams = DslTabLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                return view.root.apply {
                    setPadding(p8, 0, p8, 0)
                }
            }

            mOtherServerList.forEach { server ->
                val view = createView(server)
                mOtherServerViewList.add(view)
                binding.accountTypeTab.addView(view)
            }
        }.execute()
    }

    private fun showServerTypeSelectDialog(stringId: Int, type: Int, hintTextId: Int? = null) {
        EditTextDialog.Builder(requireActivity())
            .setTitle(stringId)
            .setAsRequired()
            .apply { hintTextId?.let { setHintText(it) } }
            .setConfirmListener { editText, _ ->
                addOtherServer(editText, type)
                true
            }.showDialog()
    }

    /**
     * TurtleLauncher: one-tap server registration (skips the manual URL text dialog).
     * Used for the ely.by quick-add button — reuses the same authlib-injector
     * discovery + registration flow as [addOtherServer], just with a fixed URL.
     */
    private fun addOtherServerDirect(rawUrl: String, type: Int) {
        Task.runTask {
            val serverUrl =
                if (type == 0) AccountUtils.tryGetFullServerUrl(rawUrl) else rawUrl
            OtherLoginApi.getServeInfo(
                requireActivity(),
                if (type == 0) serverUrl else "https://auth.mc-user.com:233/$serverUrl"
            )?.let { data ->
                val server = Server()
                JSONObject(data).optJSONObject("meta")?.let { meta ->
                    server.serverName = meta.optString("serverName")
                    server.baseUrl = serverUrl
                    server.serverType = type
                    if (type == 0) {
                        server.register =
                            meta.optJSONObject("links")?.optString("register") ?: ""
                    } else {
                        server.baseUrl = "https://auth.mc-user.com:233/$serverUrl"
                        server.register = "https://login.mc-user.com:233/$serverUrl"
                    }
                    checkServerConfig()
                    mOtherServerConfig?.server?.apply addServer@{
                        forEach {
                            // Make sure servers are not duplicated.
                            if (it.baseUrl == server.baseUrl) return@addServer
                        }
                        add(server)
                    }
                    Tools.write(
                        mOtherServerConfigFile.absolutePath,
                        Tools.GLOBAL_GSON.toJson(mOtherServerConfig, Servers::class.java)
                    )
                }
            }
        }.beforeStart(TaskExecutors.getAndroidUI()) {
            // Showing a dialog after detach throws BadTokenException - and if we
            // are detached there is nothing to show progress to anyway.
            if (isAdded) mProgressDialog.show()
        }.ended(TaskExecutors.getAndroidUI()) {
            refreshOtherServer()
            runCatching { mProgressDialog.dismiss() }
        }.onThrowable { e ->
            Logging.e("Add Other Server (direct)", Tools.printToString(e))
        }.execute()
    }

    private fun checkServerConfig() {
        mOtherServerConfig ?: run {
            val servers = Servers()
            servers.server = ArrayList()
            mOtherServerConfig = servers
        }
    }

    private fun addOtherServer(editText: EditText, type: Int) {
        Task.runTask {
            val editString = editText.text.toString()
            val serverUrl =
                if (type == 0) AccountUtils.tryGetFullServerUrl(editString) else editString
            OtherLoginApi.getServeInfo(
                requireActivity(),
                if (type == 0) serverUrl else "https://auth.mc-user.com:233/$serverUrl"
            )?.let { data ->
                val server = Server()
                JSONObject(data).optJSONObject("meta")?.let { meta ->
                    server.serverName = meta.optString("serverName")
                    server.baseUrl = serverUrl
                    server.serverType = type  // TurtleLauncher: 0=authlib-injector, 1=nide8auth
                    if (type == 0) {
                        server.register =
                            meta.optJSONObject("links")?.optString("register") ?: ""
                    } else {
                        server.baseUrl = "https://auth.mc-user.com:233/$serverUrl"
                        server.register = "https://login.mc-user.com:233/$serverUrl"
                    }
                    checkServerConfig()
                    mOtherServerConfig?.server?.apply addServer@{
                        forEach {
                            // Make sure servers are not duplicated.
                            if (it.baseUrl == server.baseUrl) return@addServer
                        }
                        add(server)
                    }
                    Tools.write(
                        mOtherServerConfigFile.absolutePath,
                        Tools.GLOBAL_GSON.toJson(mOtherServerConfig, Servers::class.java)
                    )
                }
            }
        }.beforeStart(TaskExecutors.getAndroidUI()) {
            // Showing a dialog after detach throws BadTokenException - and if we
            // are detached there is nothing to show progress to anyway.
            if (isAdded) mProgressDialog.show()
        }.ended(TaskExecutors.getAndroidUI()) {
            refreshOtherServer()
            runCatching { mProgressDialog.dismiss() }
        }.onThrowable { e ->
            Logging.e("Add Other Server", Tools.printToString(e))
        }.execute()
    }

    private fun deleteOtherServer(server: Server) {
        TipDialog.Builder(requireActivity())
            .setTitle(getString(R.string.account_remove_login_type_title, server.serverName))
            .setMessage(R.string.account_remove_login_type_message)
            .setWarning()
            .setConfirmClickListener {
                checkServerConfig()
                mOtherServerConfig?.server?.remove(server)
                Tools.write(
                    mOtherServerConfigFile.absolutePath,
                    Tools.GLOBAL_GSON.toJson(mOtherServerConfig, Servers::class.java)
                )
                refreshOtherServer()
            }.showDialog()
    }

    private fun refreshActionPopupWindow(anchorView: View, binding: ViewBinding) {
        mServerActionPopupWindow.apply {
            binding.root.measure(0, 0)
            this.contentView = binding.root
            this.width = binding.root.measuredWidth
            this.height = binding.root.measuredHeight
            showAsDropDown(anchorView)
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
        skinPreviewView?.onResume()
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
        skinPreviewView?.onPause()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun event(event: AccountUpdateEvent) {
        updateAccountDetail()
        reloadRecyclerView()
    }

    override fun onClick(v: View) {
        val activity = requireActivity()
        binding.apply {
            when (v) {
                returnButton -> ZHTools.onBackPressed(activity)
                addServer -> {
                    refreshActionPopupWindow(v, ViewAddOtherServerBinding.inflate(LayoutInflater.from(activity)).apply {
                        val onClickListener = View.OnClickListener { v1 ->
                            when(v1) {
                                addOtherServer -> showServerTypeSelectDialog(R.string.other_login_yggdrasil_api, 0)
                                addUniformPass -> showServerTypeSelectDialog(R.string.other_login_32_bit_server, 1)
                                addElyby -> addOtherServerDirect("ely.by", 0)
                                addBattly -> addOtherServerDirect(
                                    net.endiq.launcher.authenticator.BattlyAuthlibManager.AUTH_SERVER, 0
                                )
                            }
                            mServerActionPopupWindow.dismiss()
                        }
                        addOtherServer.setOnClickListener(onClickListener)
                        addUniformPass.setOnClickListener(onClickListener)
                        addElyby.setOnClickListener(onClickListener)
                        addBattly.setOnClickListener(onClickListener)
                    })
                }
                else -> {}
            }
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(operationLayout, TurtleTransitions.enter()))
                .apply(AnimPlayer.Entry(accountTypeLayout, TurtleTransitions.enter()))
                .apply(AnimPlayer.Entry(accountsRecycler, TurtleTransitions.enter()))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(operationLayout, TurtleTransitions.exit()))
                .apply(AnimPlayer.Entry(accountTypeLayout, TurtleTransitions.exit()))
                .apply(AnimPlayer.Entry(accountsRecycler, TurtleTransitions.exit()))
        }
    }
}