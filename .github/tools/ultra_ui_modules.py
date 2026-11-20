"""Apply device UI options and the module-only APD adapter to pinned sources."""
from pathlib import Path
import sys


def body(text, signature, replacement):
    start = text.index('{', text.index(signature))
    end, depth = start + 1, 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[:start + 1] + '\n' + replacement + '\n}' + text[end:]


ultra, apatch = map(Path, sys.argv[1:])
main = ultra / 'manager/app/src/main'
java = main / 'java/com/sukisu/ultra'


def edit(path, transform):
    original = path.read_text(encoding='utf-8')
    updated = transform(original)
    assert updated != original, path
    path.write_text(updated, encoding='utf-8')


edit(java / 'ui/screen/Home.kt', lambda t: t.replace('                        ContributionCard()\n', '').replace('                        DonateCard()\n', '').replace('                        LearnMoreCard()\n', ''))
edit(java / 'ui/screen/BottomBarDestination.kt', lambda t: t.replace('KpmScreenDestination, R.string.kpm_title', 'KpmScreenDestination, R.string.kpm_navigation_label'))
edit(main / 'res/values/strings.xml', lambda t: t.replace('</resources>', '    <string name="kpm_navigation_label">KPM</string>\n</resources>'))
prefs = 'com.sukisu.ultra.backend.UiPreferences'
edit(java / 'KernelSUApplication.kt', lambda t: t.replace('ksuApp = this', f'ksuApp = this\n        {prefs}.load(this)').replace('com.sukisu.ultra.backend.PairingSession.initialize(this)', 'com.sukisu.ultra.backend.PairingSession.initialize(this); runCatching { com.sukisu.ultra.backend.ModuleRuntime.probe(this) }'))
edit(java / 'ui/screen/Settings.kt', lambda t: t.replace('                    // WebUI引擎选择', f'''                    com.sukisu.ultra.ui.component.SwitchItem(
                        title = "显示底部导航名称",
                        summary = "显示主页、模块、KPM、授权和设置的名称",
                        checked = {prefs}.showNavigationNames,
                        onCheckedChange = {{ {prefs}.setNavigationNames(context, it) }}
                    )
                    com.sukisu.ultra.ui.component.SwitchItem(
                        title = "液态玻璃",
                        summary = "背景模糊、折射动画和透明卡片，关闭后恢复原始主题",
                        checked = {prefs}.liquidGlass,
                        onCheckedChange = {{ {prefs}.setLiquidGlass(context, it) }}
                    )
                    // WebUI引擎选择'''))
for path in (main / 'java/zako').rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    text = text.replace('            if (destination == BottomBarDestination.Module) return@forEach\n', '')
    if path.name == 'BottomBar.kt':
        text = text.replace('alwaysShowLabel = true', f'alwaysShowLabel = {prefs}.showNavigationNames')
        text = text.replace('NavigationBar(', 'com.sukisu.ultra.backend.LiquidNavigationBar(')
    path.write_text(text, encoding='utf-8')

theme = java / 'ui/theme/Theme.kt'
text = theme.read_text(encoding='utf-8')
start = text.index('        Box(modifier = Modifier.fillMaxSize()) {', text.index('    MaterialTheme('))
bgstart = text.index('\n', start) + 1
bgend = text.index('            // 内容层', bgstart)
end, depth = bgstart, 1
while depth:
    depth += (text[end] == '{') - (text[end] == '}')
    end += 1
text = text[:start] + '        com.sukisu.ultra.backend.GlassEnvironment(background = {\n' + text[bgstart:bgend] + '        }, content = { content() })' + text[end:]
theme.write_text(text, encoding='utf-8')
for path in (java / 'ui').rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    path.write_text(text.replace('ElevatedCard(', 'com.sukisu.ultra.backend.LiquidGlassCard('), encoding='utf-8')
edit(java / 'ui/screen/Module.kt', lambda t: t.replace('fun ModuleScreen(navigator: DestinationsNavigator) {', '''fun ModuleScreen(navigator: DestinationsNavigator) {
    if (!com.sukisu.ultra.backend.ModuleRuntime.installed) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            com.sukisu.ultra.backend.ModuleSetupCard()
        }
        return
    }'''))
runtime = 'com.sukisu.ultra.backend.ModuleRuntime'
edit(java / 'ui/util/KsuCli.kt', lambda t: body(body(t, 'private fun getKsuDaemonPath(): String', f'    return {runtime}.daemonPath'), 'fun listModules(): String', f'''    if (!{runtime}.installed) return "[]"
    val result = getRootShell().newJob().add("${{getKsuDaemonPath()}} module list").exec()
    check(result.isSuccess) {{ "读取用户态模块失败 (${{result.code}})" }}
    return result.out.joinToString("\\n")'''))
edit(ultra / 'manager/build.gradle.kts', lambda t: t.replace('extra(3000201)', 'extra(3000209)'))
edit(ultra / 'manager/app/build.gradle.kts', lambda t: t.replace('useLegacyPackaging = true', 'useLegacyPackaging = true\n            keepDebugSymbols += setOf("**/libapd.so", "**/libbusybox.so", "**/libmagiskboot.so", "**/libkptools.so", "**/libsusfs_load.so")'))

# Root permission ownership remains in Ultra's encrypted, package-pinned vault.
src = apatch / 'apd/src'
edit(src / 'supercall.rs', lambda t: body(t, 'pub fn refresh_ap_package_list(', '    let _ = (skey, mutex); // Ultra owns root grants.'))
edit(src / 'event.rs', lambda t: body(t, 'pub fn start_uid_listener()', '    Ok(()) // Package events are handled by the paired Ultra manager.'))
text = (src / 'cli.rs').read_text(encoding='utf-8').replace('insmod, late_load, ', '').replace('lua, magica, ', 'lua, ')
start, end = text.index('    /// Load a kernel module (.ko)'), text.index('    /// Resetprop -')
text = text[:start] + text[end:]
start, end = text.index('        Commands::Insmod {'), text.index('        Commands::Module { command }')
text = text[:start] + text[end:]
(src / 'cli.rs').write_text(text, encoding='utf-8')
edit(src / 'main.rs', lambda t: t.replace('mod insmod;\n', '').replace('mod late_load;\n', '').replace('mod magica;\n', ''))
edit(src / 'module.rs', lambda t: t.replace('        modules.push(module_prop_map);', '        module_prop_map.insert("dir_id".to_owned(), entry.file_name().to_string_lossy().into_owned());\n        modules.push(module_prop_map);'))
print('UI preferences, glass backdrop, module navigation and module-only APD applied')
