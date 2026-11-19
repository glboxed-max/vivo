import argparse
from pathlib import Path
import shutil


def replace_body(source, signature, body):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[:opening + 1] + "\n" + body + "\n}" + source[end:]


parser = argparse.ArgumentParser()
parser.add_argument("ultra", type=Path)
parser.add_argument("apatch", type=Path)
args = parser.parse_args()
main = args.ultra / "manager/app/src/main"
java = main / "java/com/sukisu/ultra"
templates = Path(__file__).parent / "ultra-keyless"
backend = java / "backend"
backend.mkdir(parents=True, exist_ok=True)
for template in templates.glob("*.kt"):
    shutil.copyfile(template, backend / template.name)

ap = main / "java/me/bmax/apatch"
ap.mkdir(parents=True, exist_ok=True)
native = (args.apatch / "app/src/main/java/me/bmax/apatch/Natives.kt").read_text(encoding="utf-8")
native = native.replace("APApplication.superKey", "BackendSession.superKey")
native = native.replace("APApplication.DEFAULT_SCONTEXT", '"u:r:su:s0"')
native = native.replace("import dalvik.annotation.optimization.FastNative\n", "").replace("    @FastNative\n", "")
assert "APApplication" not in native
(ap / "Natives.kt").write_text(native, encoding="utf-8")
(ap / "BackendSession.kt").write_text('''package me.bmax.apatch

object BackendSession {
    @Volatile var superKey: String = ""
        private set
    fun authenticate(value: String): Boolean {
        if (value.isEmpty() || !Natives.nativeReady(value)) return false
        superKey = value
        return true
    }
    fun clear() { superKey = "" }
}
''', encoding="utf-8")
cpp = main / "cpp/apatch"
cpp.mkdir(parents=True, exist_ok=True)
upstream = args.apatch / "app/src/main/cpp"
for name in ("apjni.cpp", "apjni.hpp", "jni_helper.hpp", "type_traits.hpp", "supercall.h", "version"):
    shutil.copyfile(upstream / name, cpp / name)
shutil.copytree(upstream / "uapi", cpp / "uapi", dirs_exist_ok=True)
shutil.copyfile(args.apatch / "LICENSE", cpp / "LICENSE")
native = (cpp / "apjni.cpp").read_text(encoding="utf-8")
start = native.index("void nativeForkDontCareAndExecApd(")
end = native.index("JNIEXPORT jint JNI_OnLoad", start)
native = native[:start] + native[end:]
start = native.index("    auto magicaClazz =")
end = native.index('    LOGI("JNI_OnLoad Done!");', start)
(cpp / "apjni.cpp").write_text(native[:start] + native[end:], encoding="utf-8")
cmake = main / "cpp/CMakeLists.txt"
cmake.write_text(cmake.read_text(encoding="utf-8") + '''
find_package(cxx REQUIRED CONFIG)
add_library(apjni SHARED apatch/apjni.cpp)
set_target_properties(apjni PROPERTIES CXX_STANDARD 23 CXX_STANDARD_REQUIRED YES)
target_link_libraries(apjni cxx::cxx ${log-lib})
''', encoding="utf-8")

natives = java / "Natives.kt"
text = natives.read_text(encoding="utf-8")
session = "com.sukisu.ultra.backend.PairingSession"
ap_native = "me.bmax.apatch.Natives"
replacements = {
    "external fun getFullVersion(): String": f"fun getFullVersion(): String = {session}.versionText()",
    "external fun becomeManager(pkg: String?): Boolean": f"fun becomeManager(pkg: String?): Boolean = {session}.ready",
    "val version: Int\n        external get": f"val version: Int\n        get() = {session}.version().toInt()",
    "val allowList: IntArray\n        external get": f"val allowList: IntArray\n        get() = if ({session}.ready) {ap_native}.suUids() else intArrayOf()",
    "val isSafeMode: Boolean\n        external get": "val isSafeMode: Boolean\n        get() = false",
    "val isLkmMode: Boolean\n        external get": "val isLkmMode: Boolean\n        get() = false",
    "external fun uidShouldUmount(uid: Int): Boolean": "fun uidShouldUmount(uid: Int): Boolean = false",
    "external fun getAppProfile(key: String?, uid: Int): Profile": f"fun getAppProfile(key: String?, uid: Int): Profile = {session}.profile(key, uid)",
    "external fun setAppProfile(profile: Profile?): Boolean": f"fun setAppProfile(profile: Profile?): Boolean = {session}.setProfile(profile)",
    "external fun isSuEnabled(): Boolean": f"fun isSuEnabled(): Boolean = {session}.ready",
    "external fun setSuEnabled(enabled: Boolean): Boolean": "fun setSuEnabled(enabled: Boolean): Boolean = false",
    "external fun isKPMEnabled(): Boolean": f"fun isKPMEnabled(): Boolean = {session}.ready && {ap_native}.kernelPatchModuleNum() >= 0",
    "external fun getHookType(): String": 'fun getHookType(): String = "KernelPatch"',
    "external fun getSusfsFeatureStatus(): SusfsFeatureStatus?": "fun getSusfsFeatureStatus(): SusfsFeatureStatus? = null",
}
for old, new in replacements.items():
    assert old in text, old
    text = text.replace(old, new)
text = replace_body(text, "fun requireNewKernel(): Boolean", "        return false")
natives.write_text(text, encoding="utf-8")

cli = java / "ui/util/KsuCli.kt"
text = cli.read_text(encoding="utf-8")
text = replace_body(text, "fun rootAvailable(): Boolean", f"    return {session}.ready")
text = text.replace("fun getRootShell(globalMnt: Boolean = false): Shell {", f"fun getRootShell(globalMnt: Boolean = false): Shell {{\n    if ({session}.ready) return {session}.rootShell()")
text = replace_body(text, "fun createRootShell(globalMnt: Boolean = false): Shell", f'''    if ({session}.ready) return {session}.rootShell()
    return Shell.Builder.create().build("/system/bin/sh")''')
text = replace_body(text, "fun listModules(): String", '    return "[]"')
for signature, body in (
    ("fun loadKpmModule(path: String, args: String? = null): String", f'''    check({session}.ready) {{ "内核尚未配对" }}
    val rc = {ap_native}.loadKernelPatchModule(path, args.orEmpty())
    return if (rc == 0L) "Success" else "Error: KPM load failed ($rc)"'''),
    ("fun unloadKpmModule(name: String): String", f'''    check({session}.ready) {{ "内核尚未配对" }}
    val rc = {ap_native}.unloadKernelPatchModule(name)
    return if (rc == 0L) "Success" else "Error: KPM unload failed ($rc)"'''),
    ("fun getKpmModuleCount(): Int", f"    return if ({session}.ready) {ap_native}.kernelPatchModuleNum().toInt() else 0"),
    ("fun listKpmModules(): String", f'    return if ({session}.ready) {ap_native}.kernelPatchModuleList() else ""'),
    ("fun getKpmModuleInfo(name: String): String", f'''    check({session}.ready) {{ "内核尚未配对" }}
    return {ap_native}.kernelPatchModuleInfo(name)'''),
    ("fun controlKpmModule(name: String, args: String? = null): Int", f'''    check({session}.ready) {{ "内核尚未配对" }}
    return {ap_native}.kernelPatchModuleControl(name, args.orEmpty()).rc.toInt()'''),
    ("fun getKpmVersion(): String", f'    return if ({session}.ready) {session}.versionText() else ""'),
):
    text = replace_body(text, signature, body)
cli.write_text(text, encoding="utf-8")

apps = java / "ui/viewmodel/SuperUserViewModel.kt"
text = apps.read_text(encoding="utf-8")
text = replace_body(text, "suspend fun fetchAppList()", '''        isRefreshing = true
        try {
            apps = withContext(Dispatchers.IO) {
                val pm = ksuApp.packageManager
                pm.getInstalledPackages(0).filter { it.applicationInfo != null && it.packageName != ksuApp.packageName }
                    .map { info ->
                        AppInfo(info.applicationInfo!!.loadLabel(pm).toString(), info,
                            Natives.getAppProfile(info.packageName, info.applicationInfo!!.uid))
                    }
            }
        } finally {
            isRefreshing = false
            loadingProgress = 0f
            loadingMessage = ""
        }''')
apps.write_text(text, encoding="utf-8")

application = java / "KernelSUApplication.kt"
text = application.read_text(encoding="utf-8").replace("ksuApp = this", f"ksuApp = this\n        Thread {{ {session}.initialize(this) }}.start()")
application.write_text(text, encoding="utf-8")
activity = java / "ui/MainActivity.kt"
text = activity.read_text(encoding="utf-8").replace("            install()", "            // Boot installation is handled by the paired installer.")
text = text.replace("            UltraToolInstall.tryToInstall()", "            // No separate KernelSU daemon installation for this backend.")
activity.write_text(text, encoding="utf-8")
install = java / "ui/screen/Install.kt"
text = replace_body(install.read_text(encoding="utf-8"), "fun InstallScreen(navigator: DestinationsNavigator)",
    "    com.sukisu.ultra.backend.PairingInstallContent(onBack = { navigator.navigateUp() })")
install.write_text(text, encoding="utf-8")
home = java / "ui/screen/Home.kt"
text = home.read_text(encoding="utf-8").replace("                if (checkUpdate) {\n                    UpdateCard()\n                }", "")
assert "if (checkUpdate)" not in text
home.write_text(text, encoding="utf-8")
kpm = java / "ui/screen/Kpm.kt"
text = kpm.read_text(encoding="utf-8")
embed = text.index("isEmbed = true")
button = text.rfind("                        Button(", 0, embed)
assert button >= 0
position = button + len("                        Button(")
text = text[:position] + "\n                            enabled = false," + text[position:]
kpm.write_text(text, encoding="utf-8")
for path in (main / "java/zako").rglob("*.kt"):
    text = path.read_text(encoding="utf-8").replace("Natives.version >= Natives.MINIMAL_SUPPORTED_KPM", "Natives.isKPMEnabled()")
    text = text.replace("BottomBarDestination.entries.forEach { destination ->", "BottomBarDestination.entries.forEach { destination ->\n            if (destination == BottomBarDestination.Module) return@forEach")
    path.write_text(text, encoding="utf-8")

manifest = main / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8").replace('android:allowBackup="true"', 'android:allowBackup="false"')
permissions = '''<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    '''
text = text.replace("<application", permissions + "<application", 1)
text = text.replace("</application>", '''    <receiver android:name="com.sukisu.ultra.backend.PairingBootReceiver" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.PACKAGE_REMOVED" />
                <action android:name="android.intent.action.PACKAGE_REPLACED" />
                <action android:name="android.intent.action.PACKAGE_ADDED" />
                <data android:scheme="package" />
            </intent-filter>
        </receiver>
    </application>''')
manifest.write_text(text, encoding="utf-8")

root_gradle = args.ultra / "manager/build.gradle.kts"
text = root_gradle.read_text(encoding="utf-8")
text = text.replace('val androidCompileNdkVersion = "28.0.13004108"', 'val androidCompileNdkVersion = "29.0.14206865"')
text = text.replace('val managerVersionCode by extra(1 * 10000 + getGitCommitCount() + 700)', 'val managerVersionCode by extra(3000200)')
text = text.replace('val managerVersionName by extra(getGitDescribe())', 'val managerVersionName by extra("Ultra3x-keyless-debug")')
text = text.replace('abiFilters("arm64-v8a", "x86_64", "armeabi-v7a")', 'abiFilters("arm64-v8a")')
text = text.replace('abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")', 'abiFilters += listOf("arm64-v8a")')
root_gradle.write_text(text, encoding="utf-8")
app_gradle = args.ultra / "manager/app/build.gradle.kts"
text = app_gradle.read_text(encoding="utf-8").replace('namespace = "com.sukisu.ultra"', '''namespace = "com.sukisu.ultra"
    defaultConfig { applicationId = "com.sukisu.ultra.pd2339" }
    signingConfigs.getByName("debug") {
        storeFile = file(System.getenv("ULTRA_KEYSTORE_FILE"))
        storePassword = System.getenv("ULTRA_KEYSTORE_PASSWORD")
        keyAlias = "pd2339"
        keyPassword = System.getenv("ULTRA_KEYSTORE_PASSWORD")
    }''')
text = text.replace("dependencies {", 'dependencies {\n    testImplementation("junit:junit:4.13.2")', 1)
app_gradle.write_text(text, encoding="utf-8")
tests = args.ultra / "manager/app/src/test/java/com/sukisu/ultra/backend"
tests.mkdir(parents=True, exist_ok=True)
shutil.copyfile(templates / "PairingArchiveTest.java", tests / "PairingArchiveTest.java")
print("Ultra 3.x original pages now use the paired KernelPatch backend")
