properties() { '
kernel.string=PD2339 Ultra paired KernelPatch debug
do.devicecheck=0
do.modules=0
do.systemless=0
do.cleanup=1
do.cleanuponabort=0
do.check_boot_version=0
'; }
block=boot
is_slot_device=auto
ramdisk_compression=auto
patch_vbmeta_flag=0
. tools/ak3-core.sh

[ -f "$AKHOME/paired-boot.img" ] && [ -f "$AKHOME/source-sha256.txt" ] && [ -f "$AKHOME/patched-sha256.txt" ] || abort "这是配对模板，请先在 Ultra 管理器中修补 boot 并导出配套 AnyKernel3 包"
[ -b "$BLOCK" ] || abort "无法确定当前槽位 boot"
expected=$(cat "$AKHOME/source-sha256.txt")
patched=$(cat "$AKHOME/patched-sha256.txt")
[ "${#expected}" -eq 64 ] && [ "${#patched}" -eq 64 ] || abort "摘要格式无效"
case "$expected$patched" in *[!0-9a-f]*) abort "摘要格式无效" ;; esac
actual=$(sha256sum "$BLOCK") || abort "读取当前 boot 失败"
[ "${actual%% *}" = "$expected" ] || abort "当前 boot 与修补时的来源镜像不同，停止写入"
actual=$(sha256sum "$AKHOME/paired-boot.img") || abort "读取配对镜像失败"
[ "${actual%% *}" = "$patched" ] || abort "配对镜像摘要不一致"
stamp=$(date +%Y%m%d-%H%M%S)
backup=/sdcard/Download/西努鲁/$stamp-$$
mkdir -p "$backup" || abort "外部存储不可用，无法保存原厂备份"
printf 'reason=AnyKernel3 写入 boot 前备份\nslot=%s\ndate=%s\n' "$SLOT" "$stamp" > "$backup/info.txt" || abort "写入备份信息失败"
dd if="$BLOCK" of="$backup/boot.img" bs=1048576 || abort "保存 boot 备份失败"
actual=$(sha256sum "$backup/boot.img") || abort "校验备份失败"
[ "${actual%% *}" = "$expected" ] || abort "boot 备份摘要不一致"
init_boot=/dev/block/by-name/init_boot$SLOT
[ -b "$init_boot" ] || abort "找不到 init_boot$SLOT，停止写入"
init_expected=$(sha256sum "$init_boot") || abort "读取 init_boot 失败"
dd if="$init_boot" of="$backup/init_boot.img" bs=1048576 || abort "保存 init_boot 备份失败"
actual=$(sha256sum "$backup/init_boot.img") || abort "校验 init_boot 备份失败"
[ "${actual%% *}" = "${init_expected%% *}" ] || abort "init_boot 备份摘要不一致"
printf 'boot %s\ninit_boot %s\n' "$expected" "${init_expected%% *}" >> "$backup/info.txt"
[ "$(wc -c < "$backup/boot.img")" = "$(wc -c < "$AKHOME/paired-boot.img")" ] || abort "配对镜像与分区长度不同"
boot_size=$(blockdev --getsize64 "$BLOCK" 2>/dev/null || wc -c < "$BLOCK") || abort "读取 boot 分区大小失败"
[ "$(wc -c < "$AKHOME/paired-boot.img")" -le "$boot_size" ] || abort "修补镜像大于 boot 分区，停止写入"
actual=$(sha256sum "$BLOCK") || abort "重新读取 boot 失败"
[ "${actual%% *}" = "$expected" ] || abort "当前 boot 已变化，停止写入"
sync
ui_print "将写入当前槽位 boot。原厂 boot/init_boot 备份目录 $backup"
dd if="$AKHOME/paired-boot.img" of="$BLOCK" bs=4096 conv=fsync || abort "写入失败，请使用原厂备份恢复"
actual=$(head -c "$(wc -c < "$AKHOME/paired-boot.img")" "$BLOCK" | sha256sum) || abort "写入读回失败"
[ "${actual%% *}" = "$patched" ] || abort "写入校验失败，请使用原厂备份恢复"
ui_print "写入和读回校验完成。开机后使用导出此包的管理器自动连接。"
