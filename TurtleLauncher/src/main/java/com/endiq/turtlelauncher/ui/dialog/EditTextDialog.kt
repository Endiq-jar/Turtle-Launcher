package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.EditText
import androidx.annotation.CheckResult
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.DialogEditTextBinding
import com.endiq.turtlelauncher.ui.dialog.DraggableDialog.DialogInitializationListener
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt.Companion.isEmptyOrBlank


class EditTextDialog private constructor(
    private val context: Context,
    private val title: String?,
    private val message: String?,
    private val editText: String?,
    private val hintText: String?,
    private val checkBox: String?,
    private val confirm: String?,
    private val emptyError: String?,
    private val showCheckBox: Boolean,
    private val inputType: Int,
    private val cancelListener: View.OnClickListener?,
    private val confirmListener: ConfirmListener?,
    private val required: Boolean
) : FullScreenDialog(context),
    DialogInitializationListener {
    private val binding = DialogEditTextBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.setCancelable(false)
        this.setContentView(binding.root)

        init()
        DraggableDialog.initDialog(this)
    }

    private fun init() {
        binding.apply {
            title?.let { titleView.text = it }
            message?.let {
                messageView.text = it
                messageView.visibility = View.VISIBLE
            }
            editText?.let { textEdit.setText(it) }
            hintText?.let { textEdit.hint = it } ?: run {
                if (required) textEdit.setHint(R.string.generic_required)
            }

            checkHeight()

            confirm?.let { confirmButton.text = it }
            if (showCheckBox) {
                checkBox.visibility = View.VISIBLE
                checkBox.text = this@EditTextDialog.checkBox
            }
            if (inputType != -1) textEdit.inputType = inputType

            confirmListener?.let {
                confirmButton.setOnClickListener { _ ->
                    if (required) {
                        val text = textEdit.text.toString()
                        if (isEmptyOrBlank(text)) {
                            textEdit.error = emptyError ?: context.getString(R.string.generic_error_field_empty)
                            return@setOnClickListener
                        }
                    }
                    val dismissDialog = it.onConfirm(textEdit, checkBox.isChecked)
                    if (dismissDialog) dismiss()
                }
            }

            val cancelListener = cancelListener ?: View.OnClickListener { dismiss() }
            cancelButton.setOnClickListener(cancelListener)
        }
    }

    private fun checkHeight() {
        checkHeight(binding.root, binding.contentView, binding.scrollView)
    }

    override fun onInit(): Window? = window

    fun interface ConfirmListener {
        fun onConfirm(editText: EditText, checked: Boolean): Boolean
    }

    class Builder(private val context: Context) {
        private var title: String? = null
        private var message: String? = null
        private var editText: String? = null
        private var hintText: String? = null
        private var checkBox: String? = null
        private var confirm: String? = null
        private var emptyError: String? = null
        private var showCheckBox = false
        private var inputType = -1
        private var cancelListener: View.OnClickListener? = null
        private var confirmListener: ConfirmListener? = null
        private var required = false

        /**
         * Set the dialog title text.
         */
        @CheckResult
        fun setTitle(title: String): Builder {
            this.title = title
            return this
        }

        /**
         * Set the dialog title text.
         */
        @CheckResult
        fun setTitle(title: Int): Builder {
            return setTitle(context.getString(title))
        }

        /**
         * Set the dialog message text.
         */
        @CheckResult
        fun setMessage(message: String): Builder {
            this.message = message
            return this
        }

        /**
         * Set the dialog message text.
         */
        @CheckResult
        fun setMessage(message: Int): Builder {
            return setMessage(context.getString(message))
        }

        /**
         * Set the input text.
         */
        @CheckResult
        fun setEditText(editText: String): Builder {
            this.editText = editText
            return this
        }

        /**
         * Set the input hint.
         */
        @CheckResult
        fun setHintText(hintText: Int): Builder {
            return setHintText(context.getString(hintText))
        }

        /**
         * Set the input hint.
         */
        @CheckResult
        fun setHintText(hintText: String): Builder {
            this.hintText = hintText
            return this
        }

        /**
         * Set the confirm button text.
         */
        @CheckResult
        fun setConfirmText(text: Int): Builder {
            return setConfirmText(context.getString(text))
        }

        /**
         * Set the confirm button text.
         */
        @CheckResult
        fun setConfirmText(text: String): Builder {
            this.confirm = text
            return this
        }

        /**
         * Custom error text shown when a required field is left empty.
         */
        @CheckResult
        fun setEmptyErrorText(text: Int): Builder {
            return setEmptyErrorText(context.getString(text))
        }

        /**
         * Custom error text shown when a required field is left empty.
         */
        @CheckResult
        fun setEmptyErrorText(text: String): Builder {
            this.emptyError = text
            return this
        }

        /**
         * Set whether the dialog checkbox is enabled.
         */
        @CheckResult
        fun setShowCheckBox(show: Boolean): Builder {
            this.showCheckBox = show
            return this
        }

        /**
         * Set the checkbox text.
         */
        @CheckResult
        fun setCheckBoxText(text: Int): Builder {
            return setCheckBoxText(context.getString(text))
        }

        /**
         * Set the checkbox text.
         */
        @CheckResult
        fun setCheckBoxText(text: String): Builder {
            this.checkBox = text
            return this
        }

        /**
         * Set the input type.
         */
        @CheckResult
        fun setInputType(inputType: Int): Builder {
            this.inputType = inputType
            return this
        }

        /**
         * Set the cancel button click listener.
         */
        @CheckResult
        fun setCancelListener(cancel: View.OnClickListener): Builder {
            this.cancelListener = cancel
            return this
        }

        /**
         * Set the confirm button click listener.
         */
        @CheckResult
        fun setConfirmListener(confirmListener: ConfirmListener): Builder {
            this.confirmListener = confirmListener
            return this
        }

        /**
         * Mark the field required: on confirm the content is checked for emptiness (spaces count).
         * If so, swallow the click and tell the user.
         */
        @CheckResult
        fun setAsRequired(): Builder {
            this.required = true
            return this
        }

        fun buildDialog(): EditTextDialog {
            return EditTextDialog(
                context,
                title, message, editText, hintText, checkBox, confirm, emptyError,
                showCheckBox, inputType,
                cancelListener, confirmListener,
                required
            ).apply {
                create()
            }
        }

        fun showDialog() {
            buildDialog().show()
        }
    }
}
