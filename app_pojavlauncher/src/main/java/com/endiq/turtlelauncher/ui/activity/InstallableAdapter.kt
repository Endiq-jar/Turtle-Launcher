package com.endiq.turtlelauncher.ui.activity

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.kdt.pojavlaunch.databinding.ItemInstallableBinding
import com.endiq.turtlelauncher.feature.unpack.OnTaskRunningListener

class InstallableAdapter(
    private val items: List<InstallableItem>,
    private val listener: TaskCompletionListener
) : RecyclerView.Adapter<InstallableAdapter.ViewHolder>() {
    @Volatile
    private var completedTasksCount = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemInstallableBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setData(items[position])
    }

    override fun getItemCount(): Int {
        return items.size
    }

    fun checkAllTask() {
        items.forEachIndexed { index, item ->
            if (!item.task.isNeedUnpack()) {
                item.isFinished = true
                updateTaskCount(index)
            }
        }
    }

    fun startAllTasks() {
        items.forEachIndexed { index, item ->
            if (!item.isFinished) {
                Thread {
                    item.task.apply {
                        setTaskRunningListener(object : OnTaskRunningListener {
                            override fun onTaskStart() {
                                item.isRunning = true
                                updateUI { notifyItemChanged(index) }
                            }

                            override fun onTaskEnd() {
                                item.isRunning = false
                                item.isFinished = true
                                updateTaskCount(index)
                            }
                        })
                    }
                    item.task.run()
                }.start()
            }
        }
    }

    @Synchronized
    private fun updateTaskCount(index: Int) {
        completedTasksCount++
        updateUI { notifyItemChanged(index) }

        if (completedTasksCount >= itemCount) {
            updateUI { listener.onAllTasksCompleted() }
        }
    }

    private fun updateUI(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    class ViewHolder(
        private val binding: ItemInstallableBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun setData(item: InstallableItem) {
            binding.name.text = item.name

            if (item.summary.isNullOrEmpty()) {
                binding.summary.visibility = View.GONE
            } else {
                binding.summary.text = item.summary
                binding.summary.visibility = View.VISIBLE
            }

            binding.progress.visibility = if (item.isRunning) View.VISIBLE else View.GONE
            binding.finish.visibility = if (item.isFinished) View.VISIBLE else View.GONE
        }
    }

    fun interface TaskCompletionListener {
        fun onAllTasksCompleted()
    }
}
