# 为 Nova v3 的离线组合修补校验两个不同的分区输入。
from pathlib import Path
import sys

SOURCE = "manager/app/src/main/java/com/sukisu/nova/ui/util/KsuCli.kt"
ANCHOR = '    val work = File.createTempFile("lkm-kpm-", ".dir", ksuApp.cacheDir)'
VALIDATION = '''    // PD2339 的启动 ramdisk 来自独立 init_boot；boot 只承载 KPM。
    val splitImages = partition == "init_boot" || android.os.Build.DEVICE.equals("PD2339", ignoreCase = true)
    if (splitImages) {
        try {
            check(kpmBoot != null) { "Nova Root needs init_boot.img; separately select boot.img for KPM." }
            fun readImageHeader(uri: Uri): ByteBuffer {
                val header = ByteArray(44)
                val input = ksuApp.contentResolver.openInputStream(uri) ?: error("Cannot open selected image")
                input.use { java.io.DataInputStream(it).readFully(header) }
                check(String(header, 0, 8, Charsets.US_ASCII) == "ANDROID!") { "Invalid Android boot image" }
                return ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            }
            val lkmHeader = readImageHeader(bootUri)
            check(lkmHeader.getInt(40) in 3..4 && lkmHeader.getInt(8) == 0 && lkmHeader.getInt(12) > 0) {
                "For Nova Root select init_boot.img, not boot.img."
            }
            val kpmHeader = readImageHeader(kpmBoot)
            check(kpmHeader.getInt(40) in 0..4 && kpmHeader.getInt(8) > 0) {
                "For KPM select boot.img, not init_boot.img."
            }
        } catch (e: Exception) {
            val message = e.message ?: "Cannot validate init_boot.img and boot.img"
            onStderr(message)
            return FlashResult(1, message, false)
        }
    }

'''


def apply(root):
    path = root / SOURCE
    source = path.read_text(encoding="utf-8")
    if VALIDATION in source:
        return
    if source.count(ANCHOR) != 1:
        raise SystemExit("Nova v3.0.0 离线组合修补入口已变化")
    source = source.replace(ANCHOR, VALIDATION + ANCHOR, 1)
    output = 'val savedLkm = File(downloads, "SukiSU_Nova_LKM_${System.currentTimeMillis()}.img")'
    if source.count(output) != 1:
        raise SystemExit("Nova v3.0.0 LKM 输出入口已变化")
    source = source.replace(output, '''val lkmLabel = if (splitImages) "LKM_init_boot" else "LKM"
            val savedLkm = File(downloads, "SukiSU_Nova_${lkmLabel}_${System.currentTimeMillis()}.img")''', 1)
    path.write_text(source, encoding="utf-8", newline="\n")
    print("Nova 已接入 PD2339 双镜像输入检查")


if __name__ == "__main__":
    apply(Path(sys.argv[1]))
