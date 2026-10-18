package com.selfmod.agent.container

import android.content.Context
import java.io.File

/**
 * Filesystem layout of the embedded Linux container. Everything lives under the
 * app-private `filesDir` — no external storage permission is required and the
 * rootfs survives app updates (it is re-installed only when the bundled asset
 * hash changes).
 *
 * ```
 * filesDir/linux/
 * ├── rootfs/            # Alpine root (/bin, /usr, /etc …)
 * ├── workspace/         # shared with the agent: bind-mounted at /workspace inside
 * └── .installed         # "rootfs-sha256=<sha> version=<n>" marker
 * ```
 *
 * The proot runtime itself is *not* copied into filesDir: it ships as
 * `libproot.so` / `libproot-loader.so` inside `jniLibs/arm64-v8a` and is
 * executed in place from `ApplicationInfo.nativeLibraryDir`. Since Android 10
 * (API 29) the platform refuses to exec binaries extracted from an APK into
 * the app's own writable data dir (W^X policy); files under
 * `nativeLibraryDir` are the sanctioned exception, hence the `lib*.so` naming.
 */
object ContainerPaths {

    /** Bundled Alpine minirootfs tarball (aarch64). */
    const val ASSET_ROOTFS = "linux/alpine-minirootfs-3.20.9-aarch64.tar.gz"

    /** `<sha256>  <filename>` line for [ASSET_ROOTFS]; install fails closed on mismatch. */
    const val ASSET_ROOTFS_SHA = "linux/rootfs.sha256"

    /** Native library name of the proot runtime (see class doc). */
    const val LIB_PROOT = "libproot.so"

    /** Native library name of the proot loader (PIE shim that bootstraps proot). */
    const val LIB_PROOT_LOADER = "libproot-loader.so"

    /** Marker written after a successful install; content pins the installed assets. */
    const val INSTALLED_MARKER = ".installed"

    fun linuxDir(filesDir: File): File = File(filesDir, "linux")

    fun rootfsDir(filesDir: File): File = File(linuxDir(filesDir), "rootfs")

    /** Bind-mounted into the container at `/workspace` (see [ContainerSession]). */
    fun workspaceDir(filesDir: File): File = File(linuxDir(filesDir), "workspace")

    fun installedMarker(filesDir: File): File = File(linuxDir(filesDir), INSTALLED_MARKER)

    /** Executable proot runtime inside the APK's extracted native library dir. */
    fun prootExecutable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, LIB_PROOT)

    /** Loader that bootstraps proot; handed over via the `PROOT_LOADER` env var. */
    fun prootLoader(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, LIB_PROOT_LOADER)
}
