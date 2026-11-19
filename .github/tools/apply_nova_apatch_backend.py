from pathlib import Path
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("nova", type=Path)
parser.add_argument("apatch", type=Path)
args = parser.parse_args()
main = args.nova / "manager/app/src/main"
source = args.apatch / "app/src/main/java/me/bmax/apatch/Natives.kt"
target = main / "java/me/bmax/apatch"
target.mkdir(parents=True, exist_ok=True)
natives = source.read_text().replace("APApplication.superKey", "BackendSession.superKey").replace("APApplication.DEFAULT_SCONTEXT", '\"u:r:su:s0\"')
natives = natives.replace("import dalvik.annotation.optimization.FastNative\n", "").replace("    @FastNative\n", "")
assert "APApplication" not in natives
(target / "Natives.kt").write_text(natives)
(target / "BackendSession.kt").write_text('''package me.bmax.apatch

/** Keeps the authenticated KernelPatch key in process memory. */
object BackendSession {
    @Volatile
    var superKey: String = ""
        private set

    @Synchronized
    fun authenticate(candidate: String): Boolean {
        if (candidate.isEmpty() || !Natives.nativeReady(candidate)) return false
        superKey = candidate
        return true
    }

    @Synchronized
    fun clear() {
        superKey = ""
    }
}
''')
# Build the JNI library for this manager instead of importing the APK binary.
import shutil
cpp = main / "cpp/apatch"
cpp.mkdir(parents=True, exist_ok=True)
shutil.copyfile(args.apatch / "LICENSE", cpp / "LICENSE")
upstream = args.apatch / "app/src/main/cpp"
for name in ("apjni.cpp", "apjni.hpp", "jni_helper.hpp", "type_traits.hpp", "supercall.h", "version"):
    shutil.copyfile(upstream / name, cpp / name)
shutil.copytree(upstream / "uapi", cpp / "uapi", dirs_exist_ok=True)
native = (cpp / "apjni.cpp").read_text()
function_start = native.index("void nativeForkDontCareAndExecApd(")
function_end = native.index("JNIEXPORT jint JNI_OnLoad", function_start)
native = native[:function_start] + native[function_end:]
begin = native.index('    auto magicaClazz =')
end = native.index('    LOGI("JNI_OnLoad Done!");', begin)
native = native[:begin] + native[end:]
(cpp / "apjni.cpp").write_text(native)
cmake = main / "cpp/CMakeLists.txt"
content = cmake.read_text()
addition = """
add_library(apjni SHARED apatch/apjni.cpp)
set_target_properties(apjni PROPERTIES CXX_STANDARD 23 CXX_STANDARD_REQUIRED YES)
target_link_libraries(apjni ${log-lib})
"""
if "add_library(apjni " not in content:
    cmake.write_text(content + addition)
imported = main / "jniLibs/arm64-v8a/libapjni.so"
if imported.exists():
    raise RuntimeError("Remove the previous imported library in a fresh checkout before building")
print("Installed source-built APatch JNI and authenticated backend session")

backend = main / "java/com/sukisu/nova/backend"
backend.mkdir(parents=True, exist_ok=True)
for name in ("KernelPatchBackend.kt", "KernelPatchScreen.kt"):
    shutil.copyfile(Path(__file__).parent / "nova-apatch" / name, backend / name)
activity = main / "java/com/sukisu/nova/ui/MainActivity.kt"
text = activity.read_text()
old = "entry<Route.Main>(swipeDismiss = swipeDismiss) { mainScreenEntry() }"
new = "entry<Route.Main>(swipeDismiss = swipeDismiss) { com.sukisu.nova.backend.KernelPatchScreen() }"
assert old in text
text = text.replace(old, new)
text = text.replace("InstallScreen(preselectedKernelUri = key.preselectedKernelUri)", "com.sukisu.nova.backend.KernelPatchScreen()")
text = text.replace("if (isManager && Natives.kernelUAPIVersion == Natives.managerUAPIVersion) install()", "// KernelPatch installation is handled by the explicit boot patch screen.")
activity.write_text(text)
gradle = args.nova / "manager/build.gradle.kts"
text = gradle.read_text()
text = text.replace('extra["managerVersionCode"] = getVersionCode()', 'extra["managerVersionCode"] = 3000100')
text = text.replace('extra["managerVersionName"] = getVersionName()', 'extra["managerVersionName"] = "3.0.0-kernelpatch-debug"')
gradle.write_text(text)
print("Nova KernelPatch screen wired to main and install routes")

app_gradle = args.nova / "manager/app/build.gradle.kts"
text = app_gradle.read_text().replace('abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "riscv64")', 'abiFilters += listOf("arm64-v8a")')
app_gradle.write_text(text)

manifest = main / "AndroidManifest.xml"
text = manifest.read_text()
permission = '<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />'
if permission not in text:
    text = text.replace('<uses-permission android:name="android.permission.INTERNET" />', permission + '\n    <uses-permission android:name="android.permission.INTERNET" />')
manifest.write_text(text)
