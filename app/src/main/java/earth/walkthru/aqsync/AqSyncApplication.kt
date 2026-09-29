/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package earth.walkthru.aqsync

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.background.SyncTriggers
import earth.walkthru.aqsync.data.local.security.LanTokenMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager is configured here (default initializer disabled in the manifest) so [SyncWorker]
 * can be Hilt-injected; the scheduler then keeps the periodic auto-sync registration in step
 * with the `known_device.autoSync` rows for the life of the process, and [SyncTriggers] re-arms
 * the event-driven starts (Wi-Fi arrival, companion presence) that outlive the process.
 */
@HiltAndroidApp
class AqSyncApplication :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var autoSyncScheduler: AutoSyncScheduler

    @Inject
    lateinit var syncTriggers: SyncTriggers

    @Inject
    lateinit var lanTokenMigration: LanTokenMigration

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { lanTokenMigration.migratePlaintextTokens() }
            autoSyncScheduler.start(appScope)
            syncTriggers.register(appScope)
        }
    }
}
