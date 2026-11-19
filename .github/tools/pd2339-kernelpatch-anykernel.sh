properties() { '
kernel.string=PD2339 Nova KernelPatch 0.13.9 debug
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
set +x
ui_print "PD2339 原厂内核二进制修补 · KernelPatch 0.13.9"
ui_print "此版本尚未通过真机启动测试。将修改当前槽位 boot。"
[ -b "$BLOCK" ] || abort "当前槽位 boot 不可读"
keyfile=/sdcard/Download/Nova-superkey.txt
[ -f "$AKHOME/superkey.txt" ] && keyfile="$AKHOME/superkey.txt"
[ -f "$keyfile" ] || abort "请先创建 /sdcard/Download/Nova-superkey.txt，内容为自行设置的至少16字符 SuperKey"
IFS= read -r superkey < "$keyfile"
[ "${#superkey}" -ge 16 ] && [ "${#superkey}" -le 63 ] || abort "SuperKey 需要16至63个字节"
case "$superkey" in *[!\!-\~]*) abort "SuperKey 需要可见 ASCII 字符" ;; esac
mkdir -p "$AKHOME/work" || abort "无法建立工作目录"
cd "$AKHOME/work" || abort "无法进入工作目录"
dd if="$BLOCK" of=original-boot.img bs=1048576 || abort "读取 boot 失败"
expected=cfce8cfbea7fb734ef6b9213168da36d1c2108a3ae101c1e9039f87d39e40e94
actual=$(sha256sum original-boot.img) || abort "计算镜像摘要失败"
[ "${actual%% *}" = "$expected" ] || abort "当前 boot 不匹配已核对的 PD2339 原厂镜像，停止写入"
backup=/sdcard/Download/Nova-KernelPatch-backups
mkdir -p "$backup" || abort "外部存储不可用，无法保留备份"
stamp=$(date +%Y%m%d-%H%M%S)
cp original-boot.img "$backup/boot-original-$stamp.img" || abort "保存原厂备份失败"
[ "$(sha256sum "$backup/boot-original-$stamp.img" | cut -d' ' -f1)" = "$expected" ] || abort "备份校验失败"
chmod 755 "$AKHOME/tools/kptools"
printf '%s\n' "$superkey" | "$AKHOME/tools/kptools" -p -i original-boot.img -k "$AKHOME/tools/kpimg" --root-skey-fd 0 -o patched-boot.img > patch.log 2>&1
rc=$?
unset superkey
cp patch.log "$backup/patch-$stamp.log"
[ "$rc" -eq 0 ] || abort "KernelPatch 修补失败，日志已保存"
[ -s patched-boot.img ] || abort "没有输出修补镜像"
[ "$(wc -c < original-boot.img)" = "$(wc -c < patched-boot.img)" ] || abort "输出镜像长度变化，停止写入"
current=$(sha256sum "$BLOCK") || abort "重新读取 boot 失败"
[ "${current%% *}" = "$expected" ] || abort "当前 boot 已变化，停止写入"
dd if=patched-boot.img of="$BLOCK" bs=4096 conv=fsync || abort "写入 boot 失败，请使用已保存的原厂备份恢复"
result=$(sha256sum "$BLOCK") || abort "读回校验失败"
output=$(sha256sum patched-boot.img) || abort "输出校验失败"
[ "${result%% *}" = "${output%% *}" ] || abort "写入摘要不一致，请使用原厂备份恢复"
ui_print "已写入并读回校验。备份目录 $backup"
ui_print "开机后在 Nova 中使用同一 SuperKey 连接 KernelPatch。"
