package com.endiq.turtlelauncher.ui.subassembly.account

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.ViewAccountBinding
import com.endiq.turtlelauncher.feature.accounts.AccountUtils
import com.endiq.turtlelauncher.feature.accounts.AccountsManager
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.ui.fragment.AccountFragment
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.skin.SkinLoader
import net.endiq.launcher.Tools

class AccountViewWrapper(private val parentFragment: FragmentWithAnim? = null, val binding: ViewAccountBinding) {
    private val mContext: Context = binding.root.context

    init {
        parentFragment?.let { fragment ->
            binding.root.setOnClickListener {
                ZHTools.swapFragmentWithAnim(fragment, AccountFragment::class.java, AccountFragment.TAG, null)
            }
        }
    }

    fun refreshAccountInfo() {
        binding.apply {
            val account = AccountsManager.currentAccount
            account ?: run {
                if (parentFragment == null) {
                    userIcon.setImageDrawable(ContextCompat.getDrawable(mContext, R.drawable.ic_help))
                    userName.text = null
                } else {
                    userIcon.setImageDrawable(ContextCompat.getDrawable(mContext, R.drawable.ic_add))
                    userName.setText(R.string.account_add)
                }
                accountType.visibility = View.GONE
                return
            }

            runCatching {
                userIcon.setImageDrawable(
                    SkinLoader.getAvatarDrawable(
                        mContext,
                        account,
                        Tools.dpToPx(
                            mContext.resources.getDimensionPixelSize(R.dimen._52sdp).toFloat()
                        ).toInt()
                    )
                )
            }.onFailure { e ->
                Logging.e("AccountViewWrapper", "Failed to load avatar.", e)
            }

            userName.text = account.username
            accountType.text = AccountUtils.getAccountTypeName(mContext, account)
            accountType.visibility = View.VISIBLE
        }
    }
}
