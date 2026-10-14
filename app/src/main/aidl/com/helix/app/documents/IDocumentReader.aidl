package com.helix.app.documents;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
interface IDocumentReader {
    Bundle read(in ParcelFileDescriptor source, in ParcelFileDescriptor image, in Bundle request);
    oneway void shutdown();
}
