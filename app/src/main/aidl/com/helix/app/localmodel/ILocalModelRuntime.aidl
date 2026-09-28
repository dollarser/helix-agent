package com.helix.app.localmodel;
import android.os.ParcelFileDescriptor;
interface ILocalModelRuntime {
    int load(in ParcelFileDescriptor asset, long size, String sha256, int contextTokens, int threads);
    void generate(String generationId, in ParcelFileDescriptor request, in ParcelFileDescriptor result);
    int cancel(String generationId);
    boolean unload();
    oneway void shutdown();
}
