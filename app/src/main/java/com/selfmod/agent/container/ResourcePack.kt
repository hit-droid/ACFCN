package com.selfmod.agent.container

/**
 * A downloadable toolchain bundle for the embedded Linux container.
 *
 * Packs are produced on an x86 build machine by
 * `scripts/make-resource-pack.sh` (see
 * `.monkeycode/specs/linux-container/pack-resources.md`): the apk dependency
 * closure of a package, diffed against the bundled minirootfs and shipped as a
 * self-contained tar.gz. On device the bundle is simply extracted over the
 * rootfs — no apk tooling needed.
 *
 * Integrity is fail-closed: [sha256] is pinned in the app (same posture as the
 * rootfs manifest) and a download that does not match is refused before any
 * file is written. A null [sha256] means "not published yet" — the pack shows
 * up in listings but cannot be installed.
 */
data class ResourcePack(
    val id: String,
    val displayName: String,
    /** Release asset URL; GitHub asset redirects are followed by the downloader. */
    val url: String,
    /** Pinned sha256 of the tarball; null until the pack is published. */
    val sha256: String?,
    /** Approximate tarball size in bytes (0 = unknown); shown to the model. */
    val sizeBytes: Long = 0,
    /** Executables/modules the pack adds to the container (status output). */
    val provides: List<String> = emptyList(),
) {
    val isPublished: Boolean get() = sha256 != null
}

/**
 * Built-in pack catalog. URLs point at the fixed `container-packs` release
 * (assets are replaced in place there, decoupled from app version tags), so
 * the app keeps working across pack updates without a code change.
 */
object ResourcePacks {

    /** Fixed release tag hosting the pack assets. */
    const val RELEASE_TAG = "container-packs"

    private const val BASE = "https://github.com/hit-droid/ACFCN/releases/download/$RELEASE_TAG"

    val ALL: List<ResourcePack> = listOf(
        ResourcePack(
            id = "python3",
            displayName = "Python 3.12 (ssl / ctypes / json)",
            url = "$BASE/python3-aarch64.tar.gz",
            // make-resource-pack.sh python3 3.20 python3（ROOTFS_VER=3.20.9，
            // 与 app 内置 minirootfs 基线一致），qemu-aarch64 冒烟：
            // import ssl,ctypes,json,sqlite3 全通过。
            sha256 = "765a239468212437a4d87842868178233e5288349a047748f2ad399b2d563bd1",
            sizeBytes = 17_018_667,
            provides = listOf("python3", "pip3"),
        ),
        ResourcePack(
            id = "gcc",
            displayName = "GCC 13 + binutils + make (C/C++ 编译)",
            url = "$BASE/gcc-aarch64.tar.gz",
            // make-resource-pack.sh gcc 3.20 gcc g++ make（ROOTFS_VER=3.20.9，
            // MIRROR=mirror.nju.edu.cn），qemu-aarch64 冒烟：gcc/g++/as/ld/make
            // --version 全过；另手工深验 cc1→as→ld→运行 全链路（见 spec §2.8）。
            sha256 = "9096140088a3c84d7ec9131bb90fdd5db9b6a2feb7ab55505f022620e692413a",
            sizeBytes = 76_382_055,
            provides = listOf("gcc", "g++", "cc", "as", "ld", "make"),
        ),
        ResourcePack(
            id = "jdk",
            displayName = "OpenJDK 17 + kotlinc + d8 (dex 自产)",
            url = "$BASE/jdk-aarch64.tar.gz",
            // make-resource-pack.sh jdk 3.20 openjdk17-jdk bash
            // （ROOTFS_VER=3.20.9，MIRROR=mirror.nju.edu.cn，PACK_SKIP jmods，
            // PACK_EXTRAS 7 个 kotlin jar + d8 选择性解压，hook 建包装/改名），
            // qemu-aarch64 冒烟：java/javac/kotlinc 2.0.21/D8 8.2.2 全过。
            // kotlinc/d8 冒烟 classpath 用 $VERIFY 宿主前缀——qemu-user 8.2 的
            // statx 不做 -L 翻译，OpenJDK classpath 校验走 statx（见 spec §2.9）；
            // 设备侧 proot 全 syscall 翻译，wrapper 的 guest 路径通配不受影响。
            sha256 = "2ba0c35e91c1af89cb6c0d659f0c1c8e10735f00dcc0a70b43815a1caace5cb2",
            sizeBytes = 145_847_099,
            provides = listOf("java", "javac", "kotlinc", "d8"),
        ),
    )

    fun byId(id: String): ResourcePack? = ALL.firstOrNull { it.id == id }

    /** Lower-cased, trimmed id resolution with a couple of friendly aliases. */
    fun resolve(query: String): ResourcePack? = ALL.resolve(query)
}

/** Lower-cased, trimmed id resolution with a couple of friendly aliases. */
fun List<ResourcePack>.resolve(query: String): ResourcePack? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null
    return firstOrNull { it.id == q } ?: when (q) {
        "python", "py", "py3" -> firstOrNull { it.id == "python3" }
        "java", "kotlin", "openjdk" -> firstOrNull { it.id == "jdk" }
        "c", "cpp", "c++", "gnu" -> firstOrNull { it.id == "gcc" }
        else -> null
    }
}
