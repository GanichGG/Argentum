import struct
import sys

IMAGE_SUBSYSTEM_WINDOWS_GUI = 2
IMAGE_SUBSYSTEM_WINDOWS_CUI = 3


def set_gui_subsystem(exe_path):
    with open(exe_path, 'rb') as f:
        data = bytearray(f.read())

    e_lfanew = struct.unpack_from('<I', data, 0x3C)[0]

    if data[e_lfanew:e_lfanew + 4] != b'PE\0\0':
        raise ValueError('не найдена сигнатура PE по ожидаемому смещению — файл повреждён?')

    # Subsystem — 2-байтовое поле в Optional Header, PE-сигнатура (4) + COFF-заголовок (20) +
    # смещение 68 внутри Optional Header (одинаково что для PE32, что для PE32+/x64).
    subsystem_offset = e_lfanew + 4 + 20 + 68
    current = struct.unpack_from('<H', data, subsystem_offset)[0]

    if current != IMAGE_SUBSYSTEM_WINDOWS_CUI:
        print('WARNING: неожиданное текущее значение Subsystem =', current, '(ожидался CUI=3) — пропускаю патч')
        return

    struct.pack_into('<H', data, subsystem_offset, IMAGE_SUBSYSTEM_WINDOWS_GUI)

    with open(exe_path, 'wb') as f:
        f.write(data)

    print('OK: subsystem CUI -> GUI for', exe_path)


if __name__ == '__main__':
    set_gui_subsystem(sys.argv[1])
