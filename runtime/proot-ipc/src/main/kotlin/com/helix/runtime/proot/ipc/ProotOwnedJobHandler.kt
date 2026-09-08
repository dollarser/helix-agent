package com.helix.runtime.proot.ipc

import android.os.IBinder
import android.os.ParcelFileDescriptor

/** A process owner is lifecycle identity only, never a capability or approval grant. */
interface ProotOwnedJobHandler {
    fun submitOwned(
        owner: IBinder,
        spec: ProotJobSpec,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
    ): ProotJobSubmitResult
}
