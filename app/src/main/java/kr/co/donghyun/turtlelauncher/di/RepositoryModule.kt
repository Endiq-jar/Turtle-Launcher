package kr.co.donghyun.turtlelauncher.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kr.co.donghyun.turtlelauncher.data.repository.AuthRepositoryImpl
import kr.co.donghyun.turtlelauncher.data.repository.ContentRepositoryImpl
import kr.co.donghyun.turtlelauncher.data.repository.InstanceDetailRepositoryImpl
import kr.co.donghyun.turtlelauncher.data.repository.InstanceRepositoryImpl
import kr.co.donghyun.turtlelauncher.data.repository.JvmSettingsRepositoryImpl
import kr.co.donghyun.turtlelauncher.data.repository.KeyLayoutRepositoryImpl
import kr.co.donghyun.turtlelauncher.data.repository.McVersionRepositoryImpl
import kr.co.donghyun.turtlelauncher.data.repository.NetworkRepositoryImpl
import kr.co.donghyun.turtlelauncher.domain.repository.AuthRepository
import kr.co.donghyun.turtlelauncher.domain.repository.ContentRepository
import kr.co.donghyun.turtlelauncher.domain.repository.InstanceDetailRepository
import kr.co.donghyun.turtlelauncher.domain.repository.InstanceRepository
import kr.co.donghyun.turtlelauncher.domain.repository.JvmSettingsRepository
import kr.co.donghyun.turtlelauncher.domain.repository.KeyLayoutRepository
import kr.co.donghyun.turtlelauncher.domain.repository.McVersionRepository
import kr.co.donghyun.turtlelauncher.domain.repository.NetworkRepository
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
