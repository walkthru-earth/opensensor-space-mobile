package earth.walkthru.aqsync.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import earth.walkthru.aqsync.data.TransportFactory
import earth.walkthru.aqsync.data.TransportSource
import earth.walkthru.aqsync.data.background.CompanionPresence
import earth.walkthru.aqsync.data.background.CompanionUnlinker
import earth.walkthru.aqsync.data.history.HistoryDeriver
import earth.walkthru.aqsync.data.history.HistoryRefresher
import earth.walkthru.aqsync.data.lan.LanDiscoveryState
import earth.walkthru.aqsync.data.lan.LanTargets
import earth.walkthru.aqsync.data.lan.LanWifiLock
import earth.walkthru.aqsync.data.lan.WifiLeases

/** Narrow ports the device repository depends on, so host tests can pass fakes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    abstract fun bindTransportSource(factory: TransportFactory): TransportSource

    @Binds
    abstract fun bindLanTargets(state: LanDiscoveryState): LanTargets

    @Binds
    abstract fun bindWifiLeases(lock: LanWifiLock): WifiLeases

    @Binds
    abstract fun bindCompanionUnlinker(presence: CompanionPresence): CompanionUnlinker

    @Binds
    abstract fun bindHistoryRefresher(deriver: HistoryDeriver): HistoryRefresher
}
