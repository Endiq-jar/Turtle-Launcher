package com.endiq.turtlelauncher.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.endiq.turtlelauncher.data.key.KeyButton
import com.endiq.turtlelauncher.data.key.KeyLayoutManager
import com.endiq.turtlelauncher.domain.repository.KeyLayoutRepository
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
