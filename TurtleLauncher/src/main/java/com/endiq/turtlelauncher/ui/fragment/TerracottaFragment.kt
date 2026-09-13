package com.endiq.turtlelauncher.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentTerracottaBinding
import com.endiq.turtlelauncher.feature.accounts.AccountsManager
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.terracotta.Terracotta
import com.endiq.turtlelauncher.feature.terracotta.TerracottaNodeList
import com.endiq.turtlelauncher.feature.terracotta.TerracottaState
import com.endiq.turtlelauncher.feature.terracotta.chat.TerracottaChat
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.subassembly.aichat.ChatMessage
import com.endiq.turtlelauncher.ui.subassembly.aichat.ChatMessageAdapter
import com.endiq.turtlelauncher.utils.ZHTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.burningtnt.terracotta.TerracottaAndroidAPI
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

/**
 * Friends/LAN screen: host-a-room / join-by-code UI on top of [Terracotta], plus the
 * P2P chat ([TerracottaChat]) that piggybacks on that connection once it's live. The
 * Terracotta foundation (native bindings, orchestration, VPN service) predates this file -
 * this is the UI and chat layer on top of it.
 */
class TerracottaFragment : FragmentWithAnim(R.layout.fragment_terracotta) {
    companion object {
        const val TAG = "TerracottaFragment"
        /** EasyTier's own official public shared node - see https://easytier.rs.
         *
         *  TurtleLauncher: was tcp://public.easytier.top:11010, which is what Terracotta's
         *  native library hardcodes. That hostname CNAMEs to public.easytier.cn, which
         *  currently has no A record - so the built-in default simply doesn't resolve, and
         *  "Use EasyTier public node" used to fill in an address that was guaranteed dead.
         *  Switched to the .cn name EasyTier's own README documents. */
        private const val EASYTIER_PUBLIC_NODE = "tcp://public.easytier.cn:11010"

        /** How many times a join is attempted before we give up and show the error. See
         *  handleException() for the (native, unmodifiable) reason retries are needed. */
        private const val MAX_JOIN_ATTEMPTS = 3
    }

    private enum class Group { WAITING, LOADING, CONNECTED, EXCEPTION }

    private lateinit var binding: FragmentTerracottaBinding
    private lateinit var chatAdapter: ChatMessageAdapter
    private var hasShownJoinNotice = false
    private val scope = CoroutineScope(Dispatchers.Main)

    private var currentGroup: Group? = null
    private var connectedValue: String = ""
    private var connectedIsHost: Boolean = false

    /** Class of the last rendered state - distinguishes "just became HostOK" (auto-copy the
     *  invite code once, like Zalith Launcher 2 does) from the player-profile re-emissions
     *  of an already-live HostOK room, which must not re-fire that side effect. */
    private var lastStateClass: Class<*>? = null

    /** [Terracotta] finished starting (or definitively failed). Until this flips, the screen
     *  stays on its "starting" state: rendering before the backend exists would show WAITING
     *  (renderState(null)) and hand the user live Host/Join buttons that can only throw. */
    private var terracottaReady = false

    // ── Join retry ──────────────────────────────────────────────────────────────────────
    // See handleException() for why this exists: the native guest flow is on a hardcoded
    // 15-second deadline that P2P setup regularly overruns.

    /** Non-null only while a join attempt chain is live - cleared on success, on cancel, and
     *  when the view goes away, so navigating off the screen never triggers a background retry. */
    private var joiningCode: String? = null
    private var joinAttempt = 0
    private var joinJob: Job? = null

    private val terracottaStateListener = Terracotta.StateListener { state ->
        // Already invoked on the UI thread - see Terracotta.java's poll daemon.
        if (isAdded && view != null) renderState(state)
    }

    private val chatListener = TerracottaChat.Listener { message ->
        TaskExecutors.runInUIThread {
            if (isAdded && view != null) {
                chatAdapter.addMessage(message)
                binding.chatMessageList.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentTerracottaBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        chatAdapter = ChatMessageAdapter()
        binding.chatMessageList.layoutManager = LinearLayoutManager(requireContext())
        binding.chatMessageList.adapter = chatAdapter

        // Wired first and unconditionally: whatever else on this screen breaks, the user
        // must always be able to get back out without the app appearing to hang or crash.
        binding.backButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        // TurtleLauncher HANG FIX: Terracotta.initialize() used to be called straight from
        // onViewCreated, i.e. on the UI thread. It is not a cheap call - it does mkdirs,
        // System.loadLibrary("terracotta"), opens a log RandomAccessFile and then runs the
        // native start0() that boots the whole EasyTier backend. On a slow device that
        // blocks long enough for the system to declare an ANR and kill the launcher, which
        // is precisely the reported "I press the button, the launcher freezes, then it
        // closes". It now runs on the background pool and the screen shows a loading state
        // until it comes back; the rest of the screen is wired up in onTerracottaReady().
        //
        // Still catches Throwable, not just Exception: a native-library load failure
        // surfaces as UnsatisfiedLinkError/ExceptionInInitializerError, which are Errors,
        // not Exceptions - letting either escape uncaught took the whole app down instead of
        // just this screen, since nothing after the throwing line (including every other
        // click listener below) ever got the chance to run.
        switchGroup(Group.LOADING)
        binding.loadingText.setText(R.string.terracotta_status_starting)
        setButtonsEnabled(false)

        val activity = requireActivity()
        TaskExecutors.getDefault().execute {
            val failure = try {
                Terracotta.initialize(activity)
                null
            } catch (t: Throwable) {
                t
            }

            TaskExecutors.runInUIThread {
                if (!isAdded || view == null) return@runInUIThread
                if (failure != null) {
                    Logging.e(TAG, "Terracotta failed to initialize - Friends/LAN unavailable on this device", failure)
                    switchGroup(Group.EXCEPTION)
                    binding.exceptionText.setText(R.string.terracotta_unavailable)
                    binding.exceptionExportLogs.visibility = View.GONE
                    return@runInUIThread
                }
                runCatching { TerracottaChat.attach() }
                    .onFailure { t -> Logging.w(TAG, "Terracotta chat failed to attach", t) }
                terracottaReady = true
                onTerracottaReady()
                // onStart() may already have run and skipped this while init was in flight.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attachListeners()
            }
        }
    }

    /** Idempotent - remove-then-add so it's safe to call from both onStart() and the async
     *  init callback, whichever lands first. No-op until [terracottaReady]. */
    private fun attachListeners() {
        if (!terracottaReady) return
        Terracotta.removeStateListener(terracottaStateListener)
        Terracotta.addStateListener(terracottaStateListener)
        TerracottaChat.removeListener(chatListener)
        TerracottaChat.addListener(chatListener)
        // Pick up anything that changed while this screen wasn't visible.
        renderState(Terracotta.getState())
    }

    /** Wires up everything that can only run once the native backend is actually up. */
    private fun onTerracottaReady() {
        binding.hostButton.setOnClickListener { onHostClicked() }
        binding.joinButton.setOnClickListener { toggleJoinCodeRow() }
        binding.joinCodeSubmit.setOnClickListener { onJoinSubmit() }
        binding.joinCodeInput.doAfterTextChanged { validateJoinCode() }
        binding.joinCodeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { onJoinSubmit(); true } else false
        }

        binding.loadingCancel.setOnClickListener {
            cancelJoinRetry()
            Terracotta.setWaiting(requireContext(), true)
        }
        binding.connectedLeave.setOnClickListener { Terracotta.setWaiting(requireContext(), true) }
        binding.connectedInfoCopy.setOnClickListener { copyConnectedValue() }
        binding.exceptionBack.setOnClickListener { Terracotta.setWaiting(requireContext(), true) }
        binding.exceptionExportLogs.setOnClickListener { exportLogs() }

        binding.chatSendButton.setOnClickListener { sendChat() }

        setupCustomNodeControls()
        refreshRelayStatus()
        showCoreVersions()

        renderState(Terracotta.getState())
    }

    /** Core versions in the waiting screen - same info Zalith Launcher 2 shows in its
     *  multiplayer dialog, so bug reports can quote the exact Terracotta/EasyTier build. */
    private fun showCoreVersions() {
        val metadata = Terracotta.getMetadata()
        val terracottaVersion = metadata.terracottaVersion
        if (terracottaVersion == null || terracottaVersion == "unknown") return
        binding.terracottaVersionText.visibility = View.VISIBLE
        binding.terracottaVersionText.text =
            getString(R.string.terracotta_version_line, terracottaVersion, metadata.easyTierVersion ?: "unknown")
    }

    /** Probes the current relay nodes and shows the result. Also warms TerracottaNodeList's
     *  cache, so the first Host/Join tap doesn't eat the fetch + probe latency. */
    private fun refreshRelayStatus() {
        scope.launch {
            val status = runCatching { TerracottaNodeList.status() }.getOrNull() ?: return@launch
            if (!isAdded || view == null) return@launch
            binding.relayNodeStatus.text = if (status.usingCustom) {
                getString(R.string.terracotta_relay_status_custom, status.primary ?: "—")
            } else {
                getString(R.string.terracotta_relay_status, status.reachable, status.total, status.primary ?: "—")
            }
        }
    }

    /** Custom EasyTier server node override (TurtleLauncher PR #1496 port) - see
     *  AllSettings.enableTerracottaNodes/terracottaNodes and TerracottaNodeList. */
    private fun setupCustomNodeControls() {
        val enabled = com.endiq.turtlelauncher.setting.AllSettings.enableTerracottaNodes.getValue()
        binding.customNodeSwitch.isChecked = enabled
        binding.customNodeInput.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.customNodeEasytierPreset.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.customNodeInput.setText(com.endiq.turtlelauncher.setting.AllSettings.terracottaNodes.getValue())

        binding.customNodeSwitch.setOnCheckedChangeListener { _, checked ->
            com.endiq.turtlelauncher.setting.AllSettings.enableTerracottaNodes.put(checked).save()
            binding.customNodeInput.visibility = if (checked) View.VISIBLE else View.GONE
            binding.customNodeEasytierPreset.visibility = if (checked) View.VISIBLE else View.GONE
            TerracottaNodeList.invalidateCache()
            refreshRelayStatus()
        }
        binding.customNodeInput.doAfterTextChanged { text ->
            com.endiq.turtlelauncher.setting.AllSettings.terracottaNodes.put(text?.toString().orEmpty()).save()
            TerracottaNodeList.invalidateCache()
            refreshRelayStatus()
        }
        // One-tap fill for EasyTier's official public shared node - see the layout comment.
        binding.customNodeEasytierPreset.setOnClickListener {
            binding.customNodeInput.setText(EASYTIER_PUBLIC_NODE)
        }
    }

    override fun onStart() {
        super.onStart()
        // Native backend still starting on the background thread - leave the "starting…"
        // state alone; the async init callback wires the listeners up when it lands.
        attachListeners()
    }

    override fun onStop() {
        super.onStop()
        Terracotta.removeStateListener(terracottaStateListener)
        TerracottaChat.removeListener(chatListener)
    }

    private fun cancelJoinRetry() {
        joinJob?.cancel()
        joinJob = null
        joiningCode = null
    }

    override fun onDestroyView() {
        // Never leave a retry chain running against a view that's gone.
        cancelJoinRetry()
        super.onDestroyView()
    }

    // FragmentWithAnim (SlideAnimation) leaves these two to every subclass - matches the
    // BounceInRight/FadeOutLeft pattern used by the other Quick-Actions screens
    // (ShareLogsFragment, LogViewerFragment) that are also full-root ConstraintLayout fragments.
    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.exit()))
    }

    // ============================== State rendering ==============================

    private fun renderState(state: TerracottaState.Ready?) {
        val freshHostOk = state is TerracottaState.HostOK && lastStateClass != TerracottaState.HostOK::class.java
        lastStateClass = state?.javaClass

        when (state) {
            null, is TerracottaState.Waiting -> showWaiting()
            is TerracottaState.HostScanning -> showLoading(getString(R.string.terracotta_status_host_scanning))
            is TerracottaState.HostStarting -> showLoading(getString(R.string.terracotta_status_host_starting))
            is TerracottaState.GuestConnecting -> showLoading(getString(R.string.terracotta_status_guest_connecting))
            is TerracottaState.GuestStarting -> {
                // Ported from Zalith Launcher 2: while joining, the native backend
                // estimates the network difficulty from both sides' NAT types - show it so
                // "Taking forever" is explained instead of mysterious.
                val base = getString(R.string.terracotta_status_guest_starting)
                val difficultyRes = state.difficulty?.textRes ?: 0
                showLoading(if (difficultyRes != 0) "$base\n${getString(difficultyRes)}" else base)
            }
            is TerracottaState.HostOK -> {
                showConnected(
                    title = getString(R.string.terracotta_host_ok_title),
                    value = state.code ?: "",
                    players = state.profiles,
                    isHost = true
                )
                // Zalith Launcher 2 auto-copies the invite code once, on the transition
                // into host-ok - the host's very next action is pasting it to a friend.
                // Profile updates of the live room (isForkOf) deliberately don't re-fire it.
                if (freshHostOk && !state.code.isNullOrBlank()) {
                    copyToClipboard(state.code, R.string.terracotta_code_copied)
                }
            }
            is TerracottaState.GuestOK -> showConnected(
                title = getString(R.string.terracotta_guest_ok_title),
                value = state.url ?: "",
                players = state.profiles,
                isHost = false
            )
            is TerracottaState.ExceptionState -> handleException(state.type)
            else -> showWaiting()
        }
    }

    /**
     * Guest-side failures get retried automatically instead of going straight to the error
     * screen.
     *
     * Why: Terracotta's native guest flow (`start_guest` in the upstream Rust) gives up on a
     * fixed schedule - it polls the EasyTier peer list **5 times, 3 seconds apart**, and if it
     * hasn't seen a peer whose hostname starts with `scaffolding-mc-server-` by then it logs
     * "Cannot find scaffolding server" and raises PingHostFail. Fifteen seconds is plenty once
     * the tunnel is up and nowhere near enough when EasyTier still has to bootstrap a relay,
     * exchange routes and punch through NAT; the connection very often lands at 20-40 seconds.
     *
     * That deadline is inside a prebuilt .so we can't rebuild, so the only lever from Kotlin is
     * to start the attempt over - and a second attempt usually succeeds, because EasyTier
     * reuses the peer/route information it discovered the first time instead of starting cold.
     */
    private fun handleException(type: TerracottaState.ExceptionState.Type) {
        val code = joiningCode
        if (code == null || joinAttempt >= MAX_JOIN_ATTEMPTS || !type.isRetryable()) {
            joiningCode = null
            showException(type)
            return
        }

        joinAttempt++
        val attempt = joinAttempt
        val context = context
        if (context == null) {
            joiningCode = null
            showException(type)
            return
        }
        // Resolved on the UI thread - the IO block below must not call getString().
        val player = playerName()

        joinJob?.cancel()
        joinJob = scope.launch {
            showLoading(getString(R.string.terracotta_join_retrying, attempt, MAX_JOIN_ATTEMPTS))

            val started = withContext(Dispatchers.IO) {
                runCatching {
                    // setGuesting() throws unless the state is already Waiting, so the retry has
                    // to unwind the failed attempt first - same thing the Leave/Back buttons do.
                    Terracotta.setWaiting(context, true)
                    // ...and the state only reaches Waiting once the poll daemon observes it
                    // (500ms tick), so wait for it rather than racing it.
                    waitForWaiting()

                    // Lead with a different node each time - the list is otherwise identical on
                    // both devices (it has to be, or they'd never find each other), so rotating
                    // the head is the one thing we can vary between attempts.
                    val nodes = TerracottaNodeList.fetch()
                    Terracotta.setGuesting(code, player, rotate(nodes, attempt - 1))
                }.onFailure { e ->
                    Logging.w(TAG, "Join retry $attempt failed", e)
                }.getOrDefault(false)
            }

            // From here the normal state listener drives the UI - including another
            // handleException() call if this attempt also fails.
            if (!started) {
                joiningCode = null
                if (!isAdded || view == null) return@launch
                showException(type)
            }
        }
    }

    private fun TerracottaState.ExceptionState.Type.isRetryable(): Boolean = when (this) {
        // Guest-side and transient: worth another go.
        TerracottaState.ExceptionState.Type.PING_HOST_FAIL,
        TerracottaState.ExceptionState.Type.PING_HOST_RST,
        TerracottaState.ExceptionState.Type.GUEST_ET_CRASH,
        TerracottaState.ExceptionState.Type.SCAFFOLDING_INVALID_RESPONSE -> true
        // Host-side: retrying a join can't fix the host's own connection.
        TerracottaState.ExceptionState.Type.HOST_ET_CRASH,
        TerracottaState.ExceptionState.Type.PING_SERVER_RST -> false
    }

    private suspend fun waitForWaiting() {
        val deadline = System.currentTimeMillis() + 10_000
        while (Terracotta.getState() !is TerracottaState.Waiting && System.currentTimeMillis() < deadline) {
            delay(250)
        }
    }

    private fun rotate(nodes: List<String>, by: Int): List<String> {
        if (nodes.isEmpty()) return nodes
        val shift = by % nodes.size
        return if (shift == 0) nodes else nodes.drop(shift) + nodes.take(shift)
    }

    private fun switchGroup(group: Group) {
        if (currentGroup == group) return
        currentGroup = group

        binding.waitingGroup.visibility = if (group == Group.WAITING) View.VISIBLE else View.GONE
        binding.loadingGroup.visibility = if (group == Group.LOADING) View.VISIBLE else View.GONE
        binding.connectedGroup.visibility = if (group == Group.CONNECTED) View.VISIBLE else View.GONE
        binding.exceptionGroup.visibility = if (group == Group.EXCEPTION) View.VISIBLE else View.GONE

        // Whatever just became visible arrives with the launcher's configured transition,
        // same as a screen swap - so a group change feels like part of the app rather than
        // an instant jump.
        val incoming = when (group) {
            Group.WAITING -> binding.waitingGroup
            Group.LOADING -> binding.loadingGroup
            Group.CONNECTED -> binding.connectedGroup
            Group.EXCEPTION -> binding.exceptionGroup
        }
        TurtleTransitions.animateView(incoming, appearing = true)

        when (group) {
            Group.WAITING -> {
                setButtonsEnabled(true)
                binding.joinCodeRow.visibility = View.GONE
                binding.joinCodeValidation.visibility = View.GONE
                binding.joinCodeInput.setText("")
            }
            // Fresh room - previous chat history (if any) belonged to a different session.
            Group.CONNECTED -> chatAdapter.clear()
            else -> Unit
        }
    }

    private fun showWaiting() {
        switchGroup(Group.WAITING)
        // A join retry passes back through Waiting on its way to GuestConnecting - don't hand
        // the controls back for that split second and let the user tap Join on top of a join
        // that's already being restarted underneath them.
        if (joiningCode != null) setButtonsEnabled(false)
    }

    private fun showLoading(text: String) {
        switchGroup(Group.LOADING)
        binding.loadingText.text = text
    }

    private fun showConnected(title: String, value: String, players: List<TerracottaState.TerracottaProfile>?, isHost: Boolean) {
        switchGroup(Group.CONNECTED)
        setButtonsEnabled(true)
        joiningCode = null // joined (or hosted) - no retry chain is pending any more
        connectedValue = value
        connectedIsHost = isHost

        binding.connectedInfoTitle.text = title
        binding.connectedInfoValue.text = value

        val names = players.orEmpty().mapNotNull { it.name.takeIf { name -> name.isNotBlank() } }
        binding.connectedPlayers.text = if (names.isEmpty())
            getString(R.string.terracotta_players_none)
        else
            getString(R.string.terracotta_players_label, names.joinToString(", "))
    }

    private fun showException(type: TerracottaState.ExceptionState.Type) {
        switchGroup(Group.EXCEPTION)
        setButtonsEnabled(true)
        binding.exceptionText.setText(exceptionMessageRes(type))
    }

    private fun exceptionMessageRes(type: TerracottaState.ExceptionState.Type): Int = when (type) {
        TerracottaState.ExceptionState.Type.PING_HOST_FAIL -> R.string.terracotta_exception_ping_host_fail
        TerracottaState.ExceptionState.Type.PING_HOST_RST -> R.string.terracotta_exception_ping_host_rst
        TerracottaState.ExceptionState.Type.GUEST_ET_CRASH -> R.string.terracotta_exception_guest_et_crash
        TerracottaState.ExceptionState.Type.HOST_ET_CRASH -> R.string.terracotta_exception_host_et_crash
        TerracottaState.ExceptionState.Type.PING_SERVER_RST -> R.string.terracotta_exception_ping_server_rst
        TerracottaState.ExceptionState.Type.SCAFFOLDING_INVALID_RESPONSE -> R.string.terracotta_exception_scaffolding_invalid_response
    }

    // ============================== Actions ==============================

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.hostButton.isEnabled = enabled
        binding.joinButton.isEnabled = enabled
        binding.joinCodeSubmit.isEnabled = enabled
    }

    private fun onHostClicked() {
        setButtonsEnabled(false)
        showLoading(getString(R.string.terracotta_status_default))
        val player = playerName()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val nodes = TerracottaNodeList.fetch()
                    Terracotta.setScanning(null, player, nodes)
                }
            }
            result.onFailure { e ->
                Logging.w(TAG, "Failed to start hosting", e)
                if (!isAdded || view == null) return@launch
                setButtonsEnabled(true)
                renderState(Terracotta.getState())
            }
        }
    }

    private fun toggleJoinCodeRow() {
        val show = binding.joinCodeRow.visibility != View.VISIBLE
        binding.joinCodeRow.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.joinCodeInput.requestFocus()
            // TurtleLauncher: joining (not hosting) is a documented, currently-unresolved
            // upstream issue - see TurtleLauncher#1486 ("Cannot find scaffolding server" /
            // PingHostFail), reproduced across multiple devices and Terracotta integrations,
            // not something fixable from this fork's code since it's the shared EasyTier
            // rendezvous/relay layer failing, not app logic. Surfaced once per screen visit
            // so people aren't left thinking a failed join means their setup is broken.
            if (!hasShownJoinNotice) {
                hasShownJoinNotice = true
                Toast.makeText(requireContext(), R.string.terracotta_join_known_issue, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun validateJoinCode() {
        val code = binding.joinCodeInput.text?.toString().orEmpty()
        if (code.isEmpty()) {
            binding.joinCodeValidation.visibility = View.GONE
            return
        }
        binding.joinCodeValidation.visibility = View.VISIBLE
        val (textRes, colorRes) = when (Terracotta.parseRoomCode(code)) {
            null -> R.string.terracotta_join_code_invalid to R.color.turtle_error
            TerracottaAndroidAPI.RoomType.TERRACOTTA_LEGACY -> R.string.terracotta_join_code_legacy to R.color.turtle_warning
            TerracottaAndroidAPI.RoomType.PCL2CE -> R.string.terracotta_join_code_pcl2ce to R.color.turtle_warning
            TerracottaAndroidAPI.RoomType.SCAFFOLDING -> R.string.terracotta_join_code_scaffolding to R.color.turtle_success
        }
        binding.joinCodeValidation.setText(textRes)
        binding.joinCodeValidation.setTextColor(requireContext().getColor(colorRes))
    }

    private fun onJoinSubmit() {
        val code = binding.joinCodeInput.text?.toString()?.trim().orEmpty()
        if (code.isEmpty() || Terracotta.parseRoomCode(code) == null) {
            Toast.makeText(requireContext(), R.string.terracotta_join_code_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        joiningCode = code
        joinAttempt = 1

        setButtonsEnabled(false)
        showLoading(getString(R.string.terracotta_status_default))
        val player = playerName()

        joinJob?.cancel()
        joinJob = scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                runCatching {
                    val nodes = TerracottaNodeList.fetch()
                    Terracotta.setGuesting(code, player, nodes)
                }.onFailure { e -> Logging.w(TAG, "Failed to join room", e) }.getOrDefault(false)
            }
            if (!accepted) {
                joiningCode = null
                if (!isAdded || view == null) return@launch
                Toast.makeText(requireContext(), R.string.terracotta_join_code_invalid, Toast.LENGTH_SHORT).show()
                setButtonsEnabled(true)
                renderState(Terracotta.getState())
            }
        }
    }

    private fun copyConnectedValue() {
        val toastRes = if (connectedIsHost) R.string.terracotta_code_copied else R.string.terracotta_address_copied
        copyToClipboard(connectedValue, toastRes)
    }

    private fun copyToClipboard(value: String, toastRes: Int) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terracotta", value))
        Toast.makeText(requireContext(), toastRes, Toast.LENGTH_SHORT).show()
    }

    /** Display name handed to the room. Zalith Launcher 2 falls back to "Anonymous
     *  Player" when no account is selected, so the host's player list never shows a
     *  nameless entry. */
    private fun playerName(): String =
        AccountsManager.currentAccount?.username?.takeIf { it.isNotBlank() }
            ?: getString(R.string.terracotta_player_anonymous)

    private fun exportLogs() {
        scope.launch {
            val logs = withContext(Dispatchers.IO) { Terracotta.collectLogs() }
            if (!logs.isNullOrBlank()) {
                if (!isAdded || view == null) return@launch
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("terracotta_logs", logs))
                Toast.makeText(requireContext(), R.string.terracotta_export_logs, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendChat() {
        val text = binding.chatMessageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.chatMessageInput.setText("")
        TerracottaChat.sendMessage(text)
    }
}
