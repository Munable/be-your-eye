package app.beyoureyes.core.data

import java.io.File

/** Single app-private root shared by package delivery and monitoring runtime leases. */
object ModelPackageStores {
    const val DIRECTORY_NAME: String = "model-packages-v3"
    const val DOWNLOAD_DIRECTORY_NAME: String = "model-package-downloads-v3"

    fun open(filesDirectory: File): ModelArtifactStore {
        require(filesDirectory.isDirectory || filesDirectory.mkdirs()) {
            "app-private files directory is unavailable"
        }
        return ModelArtifactStore(filesDirectory.resolve(DIRECTORY_NAME))
    }

    fun deliveryCoordinator(
        filesDirectory: File,
        transport: FixedHttpsTransport = UrlConnectionFixedHttpsTransport(),
    ): ModelPackageDeliveryCoordinator = ModelPackageDeliveryCoordinator(
        store = open(filesDirectory),
        downloadDirectory = filesDirectory.resolve(DOWNLOAD_DIRECTORY_NAME),
        transport = transport,
    )
}
