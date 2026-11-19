# 将 APatch 的离线镜像检查接入 Nova v3.0.0。保留 Nova 的 Root 与 KPM 后端。
from pathlib import Path
import sys

SOURCE = "manager/app/src/main/java/com/sukisu/nova/ui/util/KpmBootPatch.kt"
VALIDATOR = """// 在调用修补后端前检查 Android boot 头，并拒绝无内核或被截断的镜像。
private fun validateKpmBootImage(image: File): Int {
    return java.io.RandomAccessFile(image, "r").use { file ->
        check(file.length() >= 44) { "Boot image header is truncated" }
        val header = ByteArray(44)
        file.readFully(header)
        check(header.copyOfRange(0, 8).contentEquals("ANDROID!".toByteArray(Charsets.US_ASCII))) {
            "Select an Android boot.img containing a kernel"
        }
        val fields = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val version = fields.getInt(40)
        check(version in 0..4) { "Unsupported Android boot header: v$version" }
        val pageSize = if (version >= 3) 4096L else fields.getInt(36).toLong()
        check(pageSize in 512L..65536L && (pageSize and (pageSize - 1)) == 0L) {
            "Invalid boot image page size"
        }
        val kernelSize = fields.getInt(8).toLong() and 0xffffffffL
        check(kernelSize > 0) { "This image has no kernel. Select boot.img instead of init_boot.img." }
        check(pageSize + kernelSize <= file.length()) { "Boot image kernel data is truncated" }
        version
    }
}


"""


def apply(root):
    path = root / SOURCE
    original = path.read_text(encoding="utf-8")
    if "private fun validateKpmBootImage(" in original:
        print("Nova 镜像检查已接入")
        return
    edits = (
        ("fun patchKpmBoot(", VALIDATOR + "fun patchKpmBoot("),
        ("        val downloads = Environment", """        val sourceHeaderVersion = validateKpmBootImage(source)
        onStdout("- Android boot header v$sourceHeaderVersion verified")
        val downloads = Environment"""),
        ('        command("chmod 644 ${q(output.absolutePath)}")\n        if (target == null) {',
         '''        command("chmod 644 ${q(output.absolutePath)}")
        check(validateKpmBootImage(output) == sourceHeaderVersion) {
            "Repacked image changed the Android boot header version"
        }
        var savedOutput = outputFile
        if (target == null) {'''),
        ("            output.copyTo(saved, overwrite = false)\n", '''            output.copyTo(saved, overwrite = false)
            savedOutput = saved
'''),
        ('return FlashResult(0, "", target != null, outputFile)',
         'return FlashResult(0, "", target != null, savedOutput)'),
    )
    updated = original
    for old, new in edits:
        if updated.count(old) != 1:
            raise SystemExit(f"Nova v3.0.0 镜像入口锚点缺失或重复：{old.splitlines()[0]}")
        updated = updated.replace(old, new, 1)
    path.write_text(updated, encoding="utf-8", newline="\n")
    print("已接入 Nova boot 镜像输入和输出校验")


if __name__ == "__main__":
    apply(Path(sys.argv[1]))
