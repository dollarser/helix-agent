package com.helix.app.pdf;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
interface IPdfPageReader {
    Bundle read(in ParcelFileDescriptor source, in ParcelFileDescriptor image, int page, boolean forceImage);
    oneway void shutdown();
}
