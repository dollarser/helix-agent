# Scoped SAF directory RPC experiment

This is a read-only feasibility probe, not a production mount or a new tool.
`SafRpcPrototypeDeviceTest` runs the existing PRoot/Python against an Android
local socket and the existing ContentResolver backend with a synthetic test
Provider. Only a helper script is placed in the owned execution directory; the
selected documents stay at the Provider. It tests listing, stat, original bytes,
subtree/nonce/UID/revision/revocation checks and closing a blocked socket reader.

The prototype accepts at most 32 requests, 8 KiB requests, 128 KiB file reads and
256 KiB responses. It has a request deadline and socket read timeout. These are
not a hard interruption guarantee for an arbitrary blocking DocumentsProvider;
that would require per-operation cancellation and physical-executor accounting
before any production integration.

There is deliberately no create/rename/lock API, POSIX mount, automatic mirror or
Git success claim for URI-only sources. Android document creation/rename flags do
not promise `O_EXCL`, inode/locking or replace-rename semantics. A Python command
adapter cannot make Git's ordinary syscalls use these APIs. Transparent support
would require a separate PRoot syscall/filesystem integration decision and
substantial validation. No FUSE/root/SELinux workaround is used.

The direct PATH test independently exercises Git status/add/commit/checkout,
exclusive creation/locks/replace-rename and Python compilation in an owned
app-external directory, using existing bind mapping to `/workspace`.

References (design only; no Termux/PRoot code copied):
- [PRoot bind/extension model](https://proot-me.github.io/)
- [Android document capabilities](https://developer.android.com/reference/android/provider/DocumentsContract.Document)
- [Proxy file descriptors are files, not directory mounts](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,android.os.ProxyFileDescriptorCallback,android.os.Handler))
- [Termux SAF command discussion](https://github.com/termux/termux-api/issues/475)
- [Termux SAF mount request](https://github.com/termux/termux-app/issues/3282)

Build the owner-authorized isolated developer test APK with
`-Phelix.asrValidation=true`; run only `DirectWorkspaceDeviceTest` and
`SafRpcPrototypeDeviceTest` on the designated local emulator. Never run this
against a real selected document tree or the Helix Git checkout.
