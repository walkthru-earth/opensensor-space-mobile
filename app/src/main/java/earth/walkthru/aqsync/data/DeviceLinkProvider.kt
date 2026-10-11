package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.collection.CollectionLink
import earth.walkthru.aqsync.collection.LinkPreference
import earth.walkthru.aqsync.collection.LinkProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android [LinkProvider]. Each device's link stays with its [DeviceConnectionRepository], which
 * lends it to the collection and takes it back on release (see [DeviceConnectionRepository.lendLink]).
 */
@Singleton
class DeviceLinkProvider @Inject constructor(private val manager: DeviceConnectionManager) : LinkProvider {
    override suspend fun connect(device: String, prefer: LinkPreference): CollectionLink =
        manager.controller(device).lendLink(device, prefer)
}
