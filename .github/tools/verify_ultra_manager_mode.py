"""Check that the shipped adapter needs no per-install KernelPatch key."""
from pathlib import Path
import sys

ultra, original = map(Path, sys.argv[1:])
main = ultra / 'manager/app/src/main'
backend = main / 'java/com/sukisu/ultra/backend'
patcher = (backend / 'BootPatcher.kt').read_text(encoding='utf-8')
assert '--root-skey-fd' not in patcher and 'patchCredential' not in patcher
assert 'pairingFingerprint' not in patcher
session = (backend / 'PairingSession.kt').read_text(encoding='utf-8')
assert 'authenticate(' not in session and 'getString("credential")' not in session
assert session.index('BackendSession.connectManager()') < session.index('vault.read()')
bridge = (main / 'java/me/bmax/apatch/BackendSession.kt').read_text(encoding='utf-8')
assert 'protocolMarker = "su"' in bridge and 'fun authenticate' not in bridge
assert 'Natives.kernelPatchModuleNum() < 0' in bridge
assert not (backend / 'PairingArchive.kt').exists()
before, after = original.read_bytes(), (main / 'assets/pairing/kpimg').read_bytes()
start = before.index(b'me.bmax.apatch'.ljust(64, b'\0'))
assert len(before) == len(after)
assert before[:start] == after[:start] and before[start + 192:] == after[start + 192:]
assert after[start:start + 64] == b'com.sukisu.ultra.pd2339'.ljust(64, b'\0')
assert after[start + 96:start + 192] == bytes(96)
print('PASS: no SuperKey pairing, independent Root discovery, trusted-manager table only, module access retained')
