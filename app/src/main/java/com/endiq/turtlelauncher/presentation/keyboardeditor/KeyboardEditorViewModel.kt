package com.endiq.turtlelauncher.presentation.keyboardeditor

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import com.endiq.turtlelauncher.data.key.KeyButton
import com.endiq.turtlelauncher.domain.repository.KeyLayoutRepository
import javax.inject.Inject

@HiltViewModel
class KeyboardEditorViewModel @Inject constructor(
    private val repository: KeyLayoutRepository,
) : ViewModel() {
    fun getInitialLayout(): List<KeyButton> = repository.getLayout()
    fun saveLayout(layout: List<KeyButton>) = repository.saveLayout(layout)
}
