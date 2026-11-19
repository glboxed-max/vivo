# 基于 WildKernels/AnyKernel3 固定版本。仅接受已核对的 PD2339 原厂 boot。
properties() { '
kernel.string=PD2339 Nova v3.0.0 内建诊断内核
do.devicecheck=1
do.modules=0
do.systemless=0
do.cleanup=1
do.cleanuponabort=0
do.check_boot_version=0
device.name1=PD2339
device.name2=V2339A
'; }

block=boot
is_slot_device=auto
ramdisk_compression=auto
patch_vbmeta_flag=0

. tools/ak3-core.sh

split_boot
expected=cfce8cfbea7fb734ef6b9213168da36d1c2108a3ae101c1e9039f87d39e40e94
actual=$(sha256sum "$BOOTIMG") || abort "无法读取当前槽位的 boot 摘要"
[ "${actual%% *}" = "$expected" ] || abort "当前 boot 与本次验证的 PD2339 原厂镜像不同。已停止。"
ui_print "PD2339 内建 Nova 诊断包。仅处理当前槽位 boot。"
if [ -f "$SPLITIMG/ramdisk.cpio" ]; then
    unpack_ramdisk
    write_boot
else
    flash_boot
fi
