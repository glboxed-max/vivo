# 固定 Nova v3 发布组合，并核验 ARM64 发布物的实际内容。
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

REPO = "SukiSU-Nova/SukiSU-Nova"
TAG = "v3.0.0"
COMMIT = "c60f21b9e00e35e898f8d21e0612465b76b9beec"
ASSETS = {
    "lkm-aarch64-android14-6.1_kernelsu.ko": "73748b0b87a674b9384181c52bb38619e9298fee77ca77974b6df1e5f75d095f",
    "ksud-aarch64-linux-android": "61e81ce1d1508e2edbf9bcd2ec7bb5535bd4856f009509194f808631ae2e908f",
    "ksuinit-aarch64": "8ccf81d30ba553aae9c66e617e0440fbad3d1ad48c5f046c636566a448ea1763",
    "SukiSU_Nova_v3.0.0_35000-arm64-v8a-release.apk": "1202adb835a64d8b3df703da7884a2966f8728c131cffb29fb5f48d31d81ac85",
}


def github_json(resource):
    return json.loads(subprocess.check_output(["gh", "api", f"repos/{REPO}/{resource}"]))


def verify(output):
    if github_json(f"commits/{TAG}")["sha"] != COMMIT:
        raise SystemExit("Nova 标签与锁定提交不符")
    release = github_json(f"releases/tags/{TAG}")
    published = {asset["name"]: asset["digest"] for asset in release["assets"]}
    for name, digest in ASSETS.items():
        if published.get(name) != f"sha256:{digest}":
            raise SystemExit(f"发布摘要与锁定值不符：{name}")
    output.mkdir(parents=True, exist_ok=False)
    for name, digest in ASSETS.items():
        subprocess.run(["gh", "release", "download", TAG, "-R", REPO, "-p", name, "-D", str(output)], check=True)
        path = output / name
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise SystemExit(f"下载文件的 SHA-256 不符：{name}")
        if path.suffix == ".apk":
            with zipfile.ZipFile(path) as apk:
                if "AndroidManifest.xml" not in apk.namelist():
                    raise SystemExit(f"APK 缺少 AndroidManifest.xml：{name}")
        elif data[:6] != b"\x7fELF\x02\x01" or int.from_bytes(data[18:20], "little") != 183:
            raise SystemExit(f"文件不是 ARM64 ELF：{name}")
        print(f"已核验 {name} {digest}", flush=True)


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
