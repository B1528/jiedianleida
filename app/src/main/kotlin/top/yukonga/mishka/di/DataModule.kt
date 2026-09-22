package top.yukonga.mishka.di

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import top.yukonga.mishka.R
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.data.api.RuleLatencyTester
import top.yukonga.mishka.data.database.AppDatabase
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.RadarRepositoryImpl
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.domain.repository.RadarRepository
import top.yukonga.mishka.domain.repository.SubscriptionRepository
import top.yukonga.mishka.service.RuntimeOverrideBuilder

/**
 * data 层 Koin 装配：仓库 / 存储 / 处理器。
 * Android 单例（AppDatabase / PlatformStorage / 各控制器）由 `androidPlatformModule` 提供；
 * ProfileFileManager 实现由 `androidAppModule` 绑定。
 */
val dataModule = module {
    // SupervisorJob 只隔离兄弟协程，救不了协程自己：未捕获异常会冒到默认 handler 打崩进程。
    // 各消费方仍要在 collect 体内自行兜住副作用（见 SubscriptionRepositoryImpl），这里是最后一道网
    single<CoroutineScope> {
        val handler = CoroutineExceptionHandler { _, e ->
            Log.e("AppScope", "uncaught in shared scope", e)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }

    single { get<AppDatabase>().importedDao() }
    single { get<AppDatabase>().pendingDao() }
    single { get<AppDatabase>().selectionDao() }

    single { OverrideJsonStore(get(), get()) }
    single { SubscriptionProxyResolver(get(), get()) }
    single { RuleLatencyTester(get()) }
    single { MihomoConnectionManager(get()) }

    single { SubscriptionRepositoryImpl(get(), get(), get(), get(), getOrNull(), get()) }
    single<SubscriptionRepository> { get<SubscriptionRepositoryImpl>() }

    // provider 文件路径必须与配置里声明的 proxy-providers.radar 一致，否则热加载拿 404
    single<RadarRepository> {
        RadarRepositoryImpl(
            context = androidContext(),
            proxyResolver = get(),
            providerFile = RuntimeOverrideBuilder.radarProviderFile(androidContext()),
        )
    }

    factory {
        ProfileProcessor(
            repo = get(),
            fileManager = get(),
            defaultProfileName = androidContext().getString(R.string.subscription_default_name),
            proxyResolver = get(),
        )
    }
}
