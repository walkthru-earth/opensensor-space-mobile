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

package earth.walkthru.aqsync.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import earth.walkthru.aqsync.data.DeviceRepository
import earth.walkthru.aqsync.ui.device.DeviceUiState.Error
import earth.walkthru.aqsync.ui.device.DeviceUiState.Loading
import earth.walkthru.aqsync.ui.device.DeviceUiState.Success
import javax.inject.Inject

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val uiState: StateFlow<DeviceUiState> = deviceRepository
        .devices.map<List<String>, DeviceUiState>(::Success)
        .catch { emit(Error(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Loading)

    fun addDevice(name: String) {
        viewModelScope.launch {
            deviceRepository.add(name)
        }
    }
}

sealed interface DeviceUiState {
    object Loading : DeviceUiState
    data class Error(val throwable: Throwable) : DeviceUiState
    data class Success(val data: List<String>) : DeviceUiState
}
