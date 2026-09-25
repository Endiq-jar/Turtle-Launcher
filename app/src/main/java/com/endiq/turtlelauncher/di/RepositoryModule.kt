package com.endiq.turtlelauncher.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.endiq.turtlelauncher.data.repository.AuthRepositoryImpl
import com.endiq.turtlelauncher.data.repository.ContentRepositoryImpl
import com.endiq.turtlelauncher.data.repository.InstanceDetailRepositoryImpl
import com.endiq.turtlelauncher.data.repository.InstanceRepositoryImpl
import com.endiq.turtlelauncher.data.repository.JvmSettingsRepositoryImpl
import com.endiq.turtlelauncher.data.repository.KeyLayoutRepositoryImpl
import com.endiq.turtlelauncher.data.repository.McVersionRepositoryImpl
import com.endiq.turtlelauncher.data.repository.NetworkRepositoryImpl
import com.endiq.turtlelauncher.domain.repository.AuthRepository
import com.endiq.turtlelauncher.domain.repository.ContentRepository
import com.endiq.turtlelauncher.domain.repository.InstanceDetailRepository
import com.endiq.turtlelauncher.domain.repository.InstanceRepository
import com.endiq.turtlelauncher.domain.repository.JvmSettingsRepository
import com.endiq.turtlelauncher.domain.repository.KeyLayoutRepository
import com.endiq.turtlelauncher.domain.repository.McVersionRepository
import com.endiq.turtlelauncher.domain.repository.NetworkRepository
import javax.inject.Singleton

/**
 * domain.repository 인터페이스 ↔ data.repository 구현체 바인딩.
 * Google 권장 Clean Architecture 방향: data 가 domain 을 구현(의존)하고,
 * domain 은 data 를 전혀 모른다. 이 모듈이 그 연결을 Hilt 그래프에 등록한다.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindInstanceRepository(impl: InstanceRepositoryImpl): InstanceRepository

    @Binds
    @Singleton
    abstract fun bindMcVersionRepository(impl: McVersionRepositoryImpl): McVersionRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindJvmSettingsRepository(impl: JvmSettingsRepositoryImpl): JvmSettingsRepository

    @Binds
    @Singleton
    abstract fun bindInstanceDetailRepository(impl: InstanceDetailRepositoryImpl): InstanceDetailRepository

    @Binds
    @Singleton
    abstract fun bindNetworkRepository(impl: NetworkRepositoryImpl): NetworkRepository

    @Binds
    @Singleton
    abstract fun bindKeyLayoutRepository(impl: KeyLayoutRepositoryImpl): KeyLayoutRepository

    @Binds
    @Singleton
    abstract fun bindContentRepository(impl: ContentRepositoryImpl): ContentRepository
}
