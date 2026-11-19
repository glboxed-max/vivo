# 在固定的 Nova v3.0.0 源码上更新镜像修补界面的简体中文文案。
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

STRINGS = {
    "select_file_tip_nogki": "请选择与当前固件匹配的启动镜像文件",
    "kpm_patch_options": "KPM 镜像修补",
    "kpm_patch_description": "修补内核镜像。能否加载 KPM 取决于设备内核和后端兼容性。",
    "kpm_patch_mode": "KPM 镜像修补方式",
    "kpm_patch_success": "KPM 镜像修补完成，尚未验证设备启动",
}


def apply(root):
    path = root / "manager/app/src/main/res/values-zh-rCN/strings.xml"
    original = path.read_text(encoding="utf-8")
    updated = original
    for name, value in STRINGS.items():
        pattern = rf'(<string name="{name}">)[^<]*(</string>)'
        updated, count = re.subn(pattern, rf"\g<1>{value}\g<2>", updated)
        if count != 1:
            raise SystemExit(f"Nova v3.0.0 资源锚点缺失或重复：{name}")
    ET.fromstring(updated)
    if updated != original:
        path.write_text(updated, encoding="utf-8", newline="\n")
    print("Nova KPM 界面文案校验通过")


if __name__ == "__main__":
    apply(Path(sys.argv[1]))
