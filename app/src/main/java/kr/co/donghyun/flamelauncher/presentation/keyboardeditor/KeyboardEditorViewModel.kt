package kr.co.donghyun.flamelauncher.presentation.keyboardeditor

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kr.co.donghyun.flamelauncher.data.key.KeyButton
import kr.co.donghyun.flamelauncher.domain.repository.KeyLayoutRepository
import javax.inject.Inject

@HiltViewModel
class KeyboardEditorViewModel @Inject constructor(
    private val repository: KeyLayoutRepository,
) : ViewModel() {
    fun getInitialLayout(): List<KeyButton> = repository.getLayout()
    fun saveLayout(layout: List<KeyButton>) = repository.saveLayout(layout)
}
