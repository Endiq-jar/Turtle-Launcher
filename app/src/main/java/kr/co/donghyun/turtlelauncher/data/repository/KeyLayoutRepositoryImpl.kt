package kr.co.donghyun.turtlelauncher.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kr.co.donghyun.turtlelauncher.data.key.KeyButton
import kr.co.donghyun.turtlelauncher.data.key.KeyLayoutManager
import kr.co.donghyun.turtlelauncher.domain.repository.KeyLayoutRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyLayoutRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : KeyLayoutRepository {
    override fun getLayout(): List<KeyButton> = KeyLayoutManager.load(context)
    override fun saveLayout(layout: List<KeyButton>) = KeyLayoutManager.save(context, layout)
    override fun resetLayout(): List<KeyButton> = KeyLayoutManager.reset(context)
}
