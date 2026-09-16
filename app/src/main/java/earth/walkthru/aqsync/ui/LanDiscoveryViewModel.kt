package earth.walkthru.aqsync.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import javax.inject.Inject

/** Thin handle so a composable can start/stop LAN discovery without touching the repository directly. */
@HiltViewModel
class LanDiscoveryViewModel @Inject constructor(private val repository: DeviceConnectionRepository) : ViewModel() {
    fun setActive(active: Boolean) = repository.setLanDiscoveryActive(active)
}
