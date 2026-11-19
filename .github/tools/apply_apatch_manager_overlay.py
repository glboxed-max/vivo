# 在固定的 APatch Manager 源码上修正离线镜像处理的错误传播。
from pathlib import Path
import sys

source = Path(sys.argv[1]) / "app/src/main/assets/boot_patch.sh"
script = source.read_text(encoding="utf-8")
changes = (
    ('    exit $?\n  fi\nfi',
     '    exit "$patch_rc"\n  fi\nfi'),
    ('if [ ! $(./kptools -i kernel -f | grep CONFIG_KALLSYMS=y) ]; then',
     'if ! ./kptools -i kernel -f | grep -qx "CONFIG_KALLSYMS=y"; then'),
    ('\techo "- But your kernel seems NOT enabled it."\n\texit 0',
     '\techo "- But your kernel seems NOT enabled it."\n\texit 1'),
)
for old, new in changes:
    if script.count(old) != 1:
        raise SystemExit(f"APatch 脚本锚点已变化：{old.splitlines()[0]}")
    script = script.replace(old, new, 1)
source.write_text(script, encoding="utf-8", newline="\n")
print("已修正解包错误码和 KALLSYMS 检查的失败状态")
