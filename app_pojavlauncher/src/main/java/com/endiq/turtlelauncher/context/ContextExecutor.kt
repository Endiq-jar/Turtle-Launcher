package com.endiq.turtlelauncher.context

import android.app.Activity
import android.app.Application
import android.content.Context
import android.widget.Toast
import com.endiq.turtlelauncher.task.TaskExecutors
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask
import java.lang.ref.WeakReference

class ContextExecutor {
    companion object {
        private var sApplication: WeakReference<Application>? = null
        private var sActivity: WeakReference<Activity>? = null

        /**
         * Set the Application that will be used to execute tasks if the Activity won't be available.
         * @param application the application to use as the fallback
         */
        @JvmStatic
        fun setApplication(application: Application) {
            this.sApplication = WeakReference(application)
        }

        /**
         * Clear the Application previously set, so that ContextExecutor will notify the user of a critical error
         * that is executing code after the application is ended by the system.
         */
        @JvmStatic
        fun clearApplication() {
            this.sApplication?.clear()
        }

        /**
         * Set the Activity that this ContextExecutor will use for executing tasks
         * @param activity the activity to be used
         */
        @JvmStatic
        fun setActivity(activity: Activity) {
            this.sActivity = WeakReference(activity)
        }

        /**
         * Clear the Activity previously set, so the ContextExecutor won't use it to execute tasks.
         */
        @JvmStatic
        fun clearActivity() {
            this.sActivity?.clear()
        }

        /**
         * Schedules a ContextExecutorTask to be executed. For more info on tasks
         * @see ContextExecutorTask
         * @param task the task to be executed
         */
        @JvmStatic
        fun executeTask(task: ContextExecutorTask) {
            execute(
                activity = { activity ->
                    task.executeWithActivity(activity)
                },
                application = { application ->
                    task.executeWithApplication(application)
                }
            )
        }

        /**
         * Ignore where the Context comes from and run the task on it directly.
         * @see AllContextExecutorTask
         * @param task the task to run
         */
        @JvmStatic
        fun executeTaskWithAllContext(task: AllContextExecutorTask) {
            execute(
                activity = { task.execute(it) },
                application = { task.execute(it) }
            )
        }

        private fun execute(activity: (Activity) -> Unit, application: (Application) -> Unit) {
            TaskExecutors.runInUIThread {
                Tools.getWeakReference(this.sActivity)?.let {
                    activity(it)
                    return@runInUIThread
                }
                Tools.getWeakReference(this.sApplication)?.let {
                    application(it)
                    return@runInUIThread
                }
                throw RuntimeException("The Context has not been set!")
            }
        }

        /**
         * Resolve a resource string through the Activity stored here.
         * If the Activity is unset or cannot resolve the string, the Application is asked next.
         * If that fails too, the error has to be accepted.
         */
        @JvmStatic
        fun getString(resId: Int): String {
            return this.sActivity?.get()?.getString(resId)
                ?: this.sApplication?.get()?.getString(resId)
                ?: throw IllegalStateException("ContextExecutor: neither Activity nor Application is available to resolve string resource $resId")
        }

        /**
         * Showing a Toast from plain Java is more verbose than it should be;
         * this class exists to spare everyone that pain.
         * @param resId resource id of the text to show
         * @param duration LENGTH_SHORT or LENGTH_LONG, same semantics as the platform
         */
        @JvmStatic
        fun showToast(resId: Int, duration: Int) {
            executeTaskWithAllContext { context -> Toast.makeText(context, context.getString(resId), duration).show() }
        }

        /**
         * Showing a Toast from plain Java is more verbose than it should be;
         * this class exists to spare everyone that pain.
         * @param string the text to show
         * @param duration LENGTH_SHORT or LENGTH_LONG, same semantics as the platform
         */
        @JvmStatic
        fun showToast(string: String, duration: Int) {
            executeTaskWithAllContext { context -> Toast.makeText(context, string, duration).show() }
        }

        /**
         * Try to obtain the Activity.
         * @throws RuntimeException when no Activity is available
         */
        @JvmStatic
        fun getActivity(): Activity {
            return this.sActivity?.get() ?: throw RuntimeException("Activity does not exist.")
        }

        /**
         * Try to obtain the Application.
         * @throws RuntimeException when no Application is available
         */
        @JvmStatic
        fun getApplication(): Application {
            return this.sApplication?.get() ?: throw RuntimeException("Application does not exist.")
        }
    }

    /**
     * A AllContextExecutorTask is a task that can dynamically change its behaviour, based on the context
     * used for its execution. This can be used to implement for ex. error/finish notifications from
     * background threads that may live with the Service after the activity that started them died.
     */
    fun interface AllContextExecutorTask {
        /**
         * A task that runs attached to the Activity or Application context.
         * @param context an Activity or Application context
         */
        fun execute(context: Context)
    }
}