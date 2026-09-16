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

package earth.walkthru.aqsync.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import earth.walkthru.aqsync.data.local.database.Device
import earth.walkthru.aqsync.data.local.database.DeviceDao

/**
 * Unit tests for [DefaultDeviceRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class) // TODO: Remove when stable
class DefaultDeviceRepositoryTest {

    @Test
    fun devices_newItemSaved_itemIsReturned() = runTest {
        val repository = DefaultDeviceRepository(FakeDeviceDao())

        repository.add("Repository")

        assertEquals(repository.devices.first().size, 1)
    }

}

private class FakeDeviceDao : DeviceDao {

    private val data = mutableListOf<Device>()

    override fun getDevices(): Flow<List<Device>> = flow {
        emit(data)
    }

    override suspend fun insertDevice(item: Device) {
        data.add(0, item)
    }
}
