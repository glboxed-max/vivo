"""Verify the PD2339 additions: boot-time vr filter, backups, init_boot flow."""
from pathlib import Path
import sys

ultra, anykernel = Path(sys.argv[1]), Path(sys.argv[2])
main = ultra / "manager/app/src/main"
backend = main / "java/com/sukisu/ultra/backend"
assets = main / "assets/pairing"
native = main / "jniLibs/arm64-v8a"

patcher = (backend / "BootPatcher.kt").read_text(encoding="utf-8")
assert "vr_module_blocker.kpm" in patcher
assert '"pre-kernel-init"' in patcher and '"-M"' in patcher
assert '"-N"' not in patcher  # -N aliases the path slot in kptools
assert "vivoBlock: Boolean = true" in patcher
assert "PartitionBackup.backup(" in patcher

install = (backend / "PairingInstallContent.kt").read_text(encoding="utf-8")
assert "var vivoBlock by remember { mutableStateOf(true) }" in install
assert "InitBootCard()" in install
assert "BootPatcher.patch(context, original, directory, vivoBlock)" in install
assert "DownloadStore.save(context, image, \"boot-patched-$stamp.img\", stamp)" in install
assert "DownloadStore.backupLivePartitions()" in install

download = (backend / "DownloadStore.kt").read_text(encoding="utf-8")
assert 'FOLDER = "西努鲁"' in download
assert "MediaStore.Downloads.EXTERNAL_CONTENT_URI" in download
assert "backupLivePartitions" in download

assert "RootAccess.acquire(" in (backend / "BootPatcher.kt").read_text(encoding="utf-8")
assert "RootAccess.acquire(" in (backend / "InitBootContent.kt").read_text(encoding="utf-8")
root = (backend / "RootAccess.kt").read_text(encoding="utf-8")
assert "createRootShell()" in root and "PairingSession.ready" in root
cli = (main / "java/com/sukisu/ultra/ui/util/KsuCli.kt").read_text(encoding="utf-8")
assert "builder.build(\"su\")" in cli and "getRootShell().isRoot" in cli

manifest = (main / "AndroidManifest.xml").read_text(encoding="utf-8")
assert 'android.permission.WRITE_EXTERNAL_STORAGE' in manifest

backup = (backend / "PartitionBackup.kt").read_text(encoding="utf-8")
assert "Download/西努鲁" in backup
assert "init_boot" in backup and "boot" in backup
assert "sha256sum" in backup

init_boot = (backend / "InitBootTool.kt").read_text(encoding="utf-8")
assert "libmagiskboot.so" in init_boot
assert "init.real" in init_boot and "kernelsu.ko" in init_boot
assert '"repack"' in init_boot and '"unpack"' in init_boot

content = (backend / "InitBootContent.kt").read_text(encoding="utf-8")
assert "InitBootTool.removeKernelSu" in content
assert "PartitionBackup.flash" in content

for path, minimum in (
    (assets / "vr_module_blocker.kpm", 1024),
    (assets / "kpimg", 1024),
    (native / "libmagiskboot.so", 256 * 1024),
):
    size = path.stat().st_size
    assert size > minimum, f"{path} too small: {size}"
    print(f"{path}: {size} bytes")

script = anykernel.read_text(encoding="utf-8")
assert "Download/西努鲁" in script
assert "init_boot$SLOT" in script
assert "init_boot.img" in script
assert script.index("西努鲁") < script.index('dd if="$AKHOME/paired-boot.img"')

print("PASS: vr filter, download backups, independent init_boot flow wired")
