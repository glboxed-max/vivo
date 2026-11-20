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
assert "vivoBlock: Boolean = true" in patcher
assert "PartitionBackup.backup(" in patcher

install = (backend / "PairingInstallContent.kt").read_text(encoding="utf-8")
assert "var vivoBlock by remember { mutableStateOf(true) }" in install
assert "InitBootCard()" in install
assert "BootPatcher.patch(context, original, directory, vivoBlock)" in install

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

assert (assets / "vr_module_blocker.kpm").stat().st_size > 1024
assert (assets / "kpimg").stat().st_size > 1024
assert (native / "libmagiskboot.so").stat().st_size > 1024 * 1024

script = anykernel.read_text(encoding="utf-8")
assert "Download/西努鲁" in script
assert "init_boot$SLOT" in script
assert "init_boot.img" in script
assert script.index("西努鲁") < script.index('dd if="$AKHOME/paired-boot.img"')

print("PASS: vr filter, download backups, independent init_boot flow wired")
