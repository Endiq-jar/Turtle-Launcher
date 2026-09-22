#!/usr/bin/env python3
"""Add the Java 25 LWJGL Callback.Descriptor constructor to the bundled jar.

The Android bridge jar is built from the released LWJGL classes, while the
Minecraft 26.x libraries use the newer upcall descriptor ABI.  The descriptor
is an internal value object, so retaining the old two-argument constructor and
adding the newer overload is binary-compatible with both launch paths.

This is intentionally a small class-file patch rather than a source fork of
LWJGL.  The launcher must keep the complete bundled LWJGL payload for legacy
Minecraft versions, while new SDL versions use version-specific LWJGL jars.
"""
from __future__ import annotations

import io
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


def u1(buf: io.BytesIO) -> int:
    return struct.unpack(">B", buf.read(1))[0]


def u2(buf: io.BytesIO) -> int:
    return struct.unpack(">H", buf.read(2))[0]


def u4(buf: io.BytesIO) -> int:
    return struct.unpack(">I", buf.read(4))[0]


def pack_u1(v: int) -> bytes:
    return struct.pack(">B", v)


def pack_u2(v: int) -> bytes:
    return struct.pack(">H", v)


def pack_u4(v: int) -> bytes:
    return struct.pack(">I", v)


@dataclass
class Attribute:
    name: int
    payload: bytes


@dataclass
class Field:
    access: int
    name: int
    descriptor: int
    attributes: list[Attribute]


@dataclass
class Method:
    access: int
    name: int
    descriptor: int
    attributes: list[Attribute]


def read_attributes(buf: io.BytesIO) -> list[Attribute]:
    result = []
    for _ in range(u2(buf)):
        name = u2(buf)
        length = u4(buf)
        result.append(Attribute(name, buf.read(length)))
    return result


def write_attributes(attributes: list[Attribute]) -> bytes:
    out = io.BytesIO()
    out.write(pack_u2(len(attributes)))
    for attribute in attributes:
        out.write(pack_u2(attribute.name))
        out.write(pack_u4(len(attribute.payload)))
        out.write(attribute.payload)
    return out.getvalue()


def parse_class(data: bytes):
    buf = io.BytesIO(data)
    magic = u4(buf)
    if magic != 0xCAFEBABE:
        raise ValueError("not a class file")
    minor, major, cp_count = u2(buf), u2(buf), u2(buf)
    cp: list[object | None] = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = u1(buf)
        if tag == 1:
            length = u2(buf)
            cp[i] = (tag, buf.read(length))
        elif tag in (3, 4):
            cp[i] = (tag, buf.read(4))
        elif tag in (5, 6):
            cp[i] = (tag, buf.read(8))
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = (tag, u2(buf))
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[i] = (tag, u2(buf), u2(buf))
        elif tag == 15:
            cp[i] = (tag, u1(buf), u2(buf))
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}")
        i += 1

    access, this_class, super_class = u2(buf), u2(buf), u2(buf)
    interfaces = [u2(buf) for _ in range(u2(buf))]

    fields = []
    for _ in range(u2(buf)):
        fields.append(Field(u2(buf), u2(buf), u2(buf), read_attributes(buf)))

    methods = []
    for _ in range(u2(buf)):
        methods.append(Method(u2(buf), u2(buf), u2(buf), read_attributes(buf)))

    attributes = read_attributes(buf)
    return (minor, major, cp, access, this_class, super_class, interfaces,
            fields, methods, attributes)


def add_cp(cp: list[object | None], value: object) -> int:
    for index, existing in enumerate(cp):
        if existing == value:
            return index
    cp.append(value)
    return len(cp) - 1


def utf8(cp: list[object | None], text: str) -> int:
    return add_cp(cp, (1, text.encode("utf-8")))


def class_ref(cp: list[object | None], name: str) -> int:
    return add_cp(cp, (7, utf8(cp, name)))


def name_and_type(cp: list[object | None], name: str, descriptor: str) -> int:
    return add_cp(cp, (12, utf8(cp, name), utf8(cp, descriptor)))


def field_ref(cp: list[object | None], owner: int, name: str, descriptor: str) -> int:
    return add_cp(cp, (9, owner, name_and_type(cp, name, descriptor)))


def emit_constant_pool(cp: list[object | None]) -> bytes:
    out = io.BytesIO()
    out.write(pack_u2(len(cp)))
    for item in cp[1:]:
        if item is None:
            continue
        tag, *values = item
        out.write(pack_u1(tag))
        if tag == 1:
            out.write(pack_u2(len(values[0])))
            out.write(values[0])
        elif tag in (3, 4, 5, 6):
            out.write(values[0])
        elif tag in (7, 8, 16, 19, 20):
            out.write(pack_u2(values[0]))
        elif tag in (9, 10, 11, 12, 17, 18):
            out.write(pack_u2(values[0]))
            out.write(pack_u2(values[1]))
        elif tag == 15:
            out.write(pack_u1(values[0]))
            out.write(pack_u2(values[1]))
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}")
    return out.getvalue()


def emit_member(member: Field | Method) -> bytes:
    out = io.BytesIO()
    out.write(pack_u2(member.access))
    out.write(pack_u2(member.name))
    out.write(pack_u2(member.descriptor))
    out.write(write_attributes(member.attributes))
    return out.getvalue()


def cp_value(cp: list[object | None], index: int):
    item = cp[index]
    if item is None:
        return None
    tag, *values = item
    if tag == 1:
        return values[0].decode("utf-8")
    if tag in (7, 8, 16, 19, 20):
        return cp_value(cp, values[0])
    if tag == 12:
        return (cp_value(cp, values[0]), cp_value(cp, values[1]))
    if tag in (9, 10, 11):
        return (cp_value(cp, values[0]), cp_value(cp, values[1]))
    return item


def patch(data: bytes) -> bytes:
    (minor, major, cp, access, this_class, super_class, interfaces,
     fields, methods, class_attributes) = parse_class(data)

    owner = this_class
    type_name = utf8(cp, "type")
    type_descriptor = utf8(cp, "Ljava/lang/Class;")
    new_descriptor = utf8(
        cp,
        "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;"
        "Lorg/lwjgl/system/libffi/FFICIF;)V",
    )
    code_name = utf8(cp, "Code")
    object_init = next(
        (
            index for index, item in enumerate(cp)
            if item is not None and item[0] == 10 and
            cp_value(cp, item[1]) == "java/lang/Object" and
            cp_value(cp, item[2]) == ("<init>", "()V")
        ),
        None,
    )
    if object_init is None:
        raise ValueError("Callback.Descriptor has no java/lang/Object.<init> constant")
    type_field = field_ref(cp, owner, "type", "Ljava/lang/Class;")
    lookup_field = field_ref(cp, owner, "lookup", "Ljava/lang/invoke/MethodHandles$Lookup;")
    cif_field = field_ref(cp, owner, "cif", "Lorg/lwjgl/system/libffi/FFICIF;")

    if not any(f.name == type_name and f.descriptor == type_descriptor for f in fields):
        # package-private final, matching LWJGL's current Descriptor.type field
        fields.append(Field(0x0010, type_name, type_descriptor, []))

    init_name = utf8(cp, "<init>")
    if not any(m.name == init_name and m.descriptor == new_descriptor for m in methods):
        # aload_0; invokespecial Object.<init>(); store type, lookup and CIF; return.
        code = bytes([
            0x2A, 0xB7, (object_init >> 8) & 0xFF, object_init & 0xFF,
            0x2A, 0x2B, 0xB5, (type_field >> 8) & 0xFF, type_field & 0xFF,
            0x2A, 0x2C, 0xB5, (lookup_field >> 8) & 0xFF, lookup_field & 0xFF,
            0x2A, 0x2D, 0xB5, (cif_field >> 8) & 0xFF, cif_field & 0xFF,
            0xB1,
        ])
        code_payload = (
            pack_u2(2) +  # max_stack
            pack_u2(4) +  # max_locals (this + Class + Lookup + CIF)
            pack_u4(len(code)) + code +
            pack_u2(0) +  # exception table
            pack_u2(0)    # nested attributes
        )
        methods.append(Method(0x0001, init_name, new_descriptor,
                              [Attribute(code_name, code_payload)]))

    out = io.BytesIO()
    out.write(pack_u4(0xCAFEBABE))
    out.write(pack_u2(minor))
    out.write(pack_u2(major))
    out.write(emit_constant_pool(cp))
    out.write(pack_u2(access))
    out.write(pack_u2(this_class))
    out.write(pack_u2(super_class))
    out.write(pack_u2(len(interfaces)))
    for item in interfaces:
        out.write(pack_u2(item))
    out.write(pack_u2(len(fields)))
    for field in fields:
        out.write(emit_member(field))
    out.write(pack_u2(len(methods)))
    for method in methods:
        out.write(emit_member(method))
    out.write(write_attributes(class_attributes))
    return out.getvalue()


def patch_jar(path: Path) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with zipfile.ZipFile(path, "r") as source, zipfile.ZipFile(temporary, "w") as target:
        for entry in source.infolist():
            payload = source.read(entry.filename)
            if entry.filename == "org/lwjgl/system/Callback$Descriptor.class":
                payload = patch(payload)
            target.writestr(entry, payload)
    temporary.replace(path)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} JAR")
    patch_jar(Path(sys.argv[1]))
