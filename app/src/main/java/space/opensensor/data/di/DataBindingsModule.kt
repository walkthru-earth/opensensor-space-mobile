package space.opensensor.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.opensensor.collection.KeepAlive
import space.opensensor.data.TransportFactory
import space.opensensor.data.TransportSource
import space.opensensor.data.background.AndroidKeepAlive
import space.opensensor.data.background.AutoSyncScheduler
import space.opensensor.data.background.CompanionPresence
import space.opensensor.data.background.CompanionUnlinker
import space.opensensor.data.history.DeriveBackfill
import space.opensensor.data.history.DerivedDayFiles
import space.opensensor.data.history.HistoryDeriver
import space.opensensor.data.history.HistoryRefresher
import space.opensensor.data.history.RoomDerivedDayFiles
import space.opensensor.data.lan.LanDiscoveryState
import space.opensensor.data.lan.LanTargets
import space.opensensor.data.lan.LanWifiLock
import space.opensensor.data.lan.WifiLeases

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
    abstract fun bindKeepAlive(keepAlive: AndroidKeepAlive): KeepAlive

    @Binds
    abstract fun bindCompanionUnlinker(presence: CompanionPresence): CompanionUnlinker

    @Binds
    abstract fun bindDeriveBackfill(scheduler: AutoSyncScheduler): DeriveBackfill

    @Binds
    abstract fun bindHistoryRefresher(deriver: HistoryDeriver): HistoryRefresher

    @Binds
    abstract fun bindDerivedDayFiles(files: RoomDerivedDayFiles): DerivedDayFiles
}
