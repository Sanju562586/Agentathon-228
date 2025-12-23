
import sys

try:
    with open('mesh-backend', 'rb') as f:
        header = f.read(4)
        print(f"Header: {header}")
        if header.startswith(b'\x7fELF'):
            print("Type: ELF (Linux)")
        elif header.startswith(b'MZ'):
            print("Type: PE (Windows)")
        else:
            print("Type: Unknown")
except FileNotFoundError:
    print("File not found")
