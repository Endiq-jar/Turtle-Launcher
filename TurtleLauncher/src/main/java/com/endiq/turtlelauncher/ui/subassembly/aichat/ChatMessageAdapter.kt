package com.endiq.turtlelauncher.ui.subassembly.aichat

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.ItemChatMessageBinding

class ChatMessageAdapter(
    private val mData: MutableList<ChatMessage> = mutableListOf()
) : RecyclerView.Adapter<ChatMessageAdapter.InnerHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InnerHolder {
        return InnerHolder(ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: InnerHolder, position: Int) {
        holder.setData(mData[position])
    }

    override fun getItemCount(): Int = mData.size

    fun addMessage(message: ChatMessage) {
        mData.add(message)
        notifyItemInserted(mData.size - 1)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clear() {
        mData.clear()
        notifyDataSetChanged()
    }

    class InnerHolder(private val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun setData(message: ChatMessage) {
            binding.messageBubble.text = message.text
            val context = binding.root.context
            val params = binding.root.layoutParams as? LinearLayout.LayoutParams
            if (message.isUser) {
                binding.root.gravity = Gravity.END
                binding.messageBubble.setBackgroundResource(R.drawable.background_chat_bubble_user)
                binding.messageBubble.setTextColor(context.getColor(R.color.background_app))
            } else {
                binding.root.gravity = Gravity.START
                binding.messageBubble.setBackgroundResource(R.drawable.background_chat_bubble_ai)
                binding.messageBubble.setTextColor(context.getColor(R.color.turtle_text_primary))
            }
            params?.let { binding.root.layoutParams = it }
        }
    }
}
