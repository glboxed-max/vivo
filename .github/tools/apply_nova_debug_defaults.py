# 调试版默认走 KPM 文件修补，未选文件时只打开选择器，不触碰分区。
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

APP = Path("manager/app/src/main")
UI = APP / "java/com/sukisu/nova/ui"


def rewrite(path, edits):
    source = path.read_text(encoding="utf-8")
    for before, after in edits:
        if source.count(before) != 1:
            raise SystemExit(f"源码锚点缺失或重复：{path.name} / {before.splitlines()[0]}")
        source = source.replace(before, after, 1)
    return source


def apply(root):
    install = root / UI / "screen/install/InstallScreen.kt"
    home = root / UI / "viewmodel/HomeViewModel.kt"
    picker = '''selectKpmBootLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-boot-img"))
        })'''
    changes = {
        install: rewrite(install, (
            ("mutableStateOf(KpmPatchOption.FOLLOW_KERNEL)", "mutableStateOf(KpmPatchOption.PATCH_KPM)"),
            ("var kpmBootExpanded by rememberSaveable { mutableStateOf(false) }",
             "var kpmBootExpanded by rememberSaveable { mutableStateOf(true) }"),
            ("val selectFileTip = stringResource(id = R.string.select_file_tip, defaultPartition)",
             "val selectFileTip = stringResource(id = R.string.select_file_tip_nogki)"),
            ("navigator.push(Route.Flash(FlashIt.PatchKpmBoot(boot = kpmBootUri, undo = kpmUndo)))",
             "kpmBootUri?.let { navigator.push(Route.Flash(FlashIt.PatchKpmBoot(boot = it, undo = kpmUndo))) }"),
            ("    val state = InstallUiState(", f"    val pickKpmBoot = {{\n        {picker}\n    }}\n\n    val state = InstallUiState("),
            ("        onNext = {\n", """        onNext = next@{
            if (isStandaloneKpm && kpmBootUri == null) {
                pickKpmBoot()
                return@next
            }
"""),
            ('''        onPatchKpmFile = {
            selectKpmBootLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-boot-img"))
            })
        },''', "        onPatchKpmFile = pickKpmBoot,"),
        )),
        home: rewrite(home, (
            ("if (!settingsRepo.checkUpdate || (isCi", "if (BuildConfig.DEBUG || !settingsRepo.checkUpdate || (isCi"),
            ("checkUpdateEnabled = settingsRepo.checkUpdate,", "checkUpdateEnabled = !BuildConfig.DEBUG && settingsRepo.checkUpdate,"),
            ("checkCiUpdateEnabled = settingsRepo.checkUpdate &&", "checkCiUpdateEnabled = !BuildConfig.DEBUG && settingsRepo.checkUpdate &&"),
            ('managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUAPIVersion})",',
             '''managerVersion = if (BuildConfig.DEBUG) ksuApp.getString(R.string.pd2339_debug_version)
                    else "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUAPIVersion})",'''),
        )),
    }
    for folder, label in (("values", "Debug build"), ("values-zh-rCN", "调试版本")):
        path = root / APP / f"res/{folder}/strings.xml"
        edits = [('</resources>', f'    <string name="pd2339_debug_version">{label}</string>\n</resources>')]
        if folder == "values-zh-rCN":
            edits += [
                ("手动选择 boot（可选）", "选择 boot.img"),
                ("自动提取当前槽位的 boot", "请选择与当前固件匹配的 boot.img"),
            ]
        changes[path] = rewrite(path, edits)
        ET.fromstring(changes[path])
    for path, content in changes.items():
        path.write_text(content, encoding="utf-8", newline="\n")
    print("已校验调试版首页、离线输入和默认 KPM 模式")


if __name__ == "__main__":
    apply(Path(sys.argv[1]))
