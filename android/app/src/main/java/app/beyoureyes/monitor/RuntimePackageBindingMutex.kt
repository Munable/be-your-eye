package app.beyoureyes.monitor

import kotlinx.coroutines.sync.Mutex

/** Serializes package activation/task binding with runtime lease/task verification in-process. */
internal object RuntimePackageBindingMutex {
    val mutex = Mutex()
}
