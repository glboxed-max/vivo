"""Select Ultra's existing signing identity in the pinned KP trusted-manager table.

No instruction patches or unconditional supercall access are introduced.
The exact upstream table layout must match the pinned release.
"""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('input', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
data = bytearray(args.input.read_bytes())
original = b'me.bmax.apatch'.ljust(64, b'\0') + bytes.fromhex(
    'd71dadc0ca07bdf594383bfb2a445134a07339f12a27044a1b326981acf5f319')
assert data.count(original) == 1, 'Unexpected trusted-manager table'
offset = data.index(original)
assert data[offset + 96:offset + 160] == b'com.example.apatch'.ljust(64, b'\0')
assert data[offset + 192:offset + 288] == bytes(96), 'Missing table terminator'
entry = b'com.sukisu.ultra.pd2339'.ljust(64, b'\0') + bytes.fromhex(
    'a3875279b8eb2d6f8a26b23fbf2d68158df7c5fe9f26f34e800e3ce1b000418b')
data[offset:offset + 192] = entry + bytes(96)
assert len(data) == args.input.stat().st_size
args.output.write_bytes(data)
print('Prepared Ultra trusted-manager identity; executable instructions unchanged')
