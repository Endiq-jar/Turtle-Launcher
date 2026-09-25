#!/usr/bin/env python3
"""Patch the LWJGL 3.3.6 GL32C sync-delete wrapper for Android.

The arm64 liblwjgl_opengl.so used by the SDL3 renderer exports the newer
GL32C.glDeleteSync JNI entry point. Minecraft 1.21.11 ships the 3.3.6 Java
wrapper, which calls GL32C.nglDeleteSync instead. Convert only the public
wrapper to a native method; all other GL32C bytecode remains the exact
3.3.6-snapshot payload.
"""
from __future__ import annotations

import io
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Attribute:
    name: int
    payload: bytes


@dataclass
class Member:
    access: int
    name: int
    descriptor: int
    attributes: list[Attribute]


def u1(buf: io.BytesIO) -> int:
    return struct.unpack(">B", buf.read(1))[0]


def u2(buf: io.BytesIO) -> int:
    return struct.unpack(">H", buf.read(2))[0]


def u4(buf: io.BytesIO) -> int:
    return struct.unpack(">I", buf.read(4))[0]


def p1(value: int) -> bytes:
    return struct.pack(">B", value)


def p2(value: int) -> bytes:
    return struct.pack(">H", value)


def p4(value: int) -> bytes:
    return struct.pack(">I", value)


def read_attributes(buf: io.BytesIO) -> list[Attribute]:
    result = []
    for _ in range(u2(buf)):
        name = u2(buf)
        length = u4(buf)
        result.append(Attribute(name, buf.read(length)))
    return result


def write_attributes(attributes: list[Attribute]) -> bytes:
    out = io.BytesIO()
    out.write(p2(len(attributes)))
    for attribute in attributes:
        out.write(p2(attribute.name))
        out.write(p4(len(attribute.payload)))
        out.write(attribute.payload)
    return out.getvalue()


def parse(data: bytes):
    buf = io.BytesIO(data)
    if u4(buf) != 0xCAFEBABE:
        raise ValueError("not a class file")
    minor, major, cp_count = u2(buf), u2(buf), u2(buf)
    cp: list[object | None] = [None] * cp_count
    index = 1
    while index < cp_count:
        tag = u1(buf)
        if tag == 1:
            length = u2(buf)
            cp[index] = (tag, buf.read(length))
        elif tag in (3, 4):
            cp[index] = (tag, buf.read(4))
        elif tag in (5, 6):
            cp[index] = (tag, buf.read(8))
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[index] = (tag, u2(buf))
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[index] = (tag, u2(buf), u2(buf))
        elif tag == 15:
            cp[index] = (tag, u1(buf), u2(buf))
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}")
        index += 1

    access, this_class, super_class = u2(buf), u2(buf), u2(buf)
    interfaces = [u2(buf) for _ in range(u2(buf))]

    def read_members() -> list[Member]:
        members = []
        for _ in range(u2(buf)):
            members.append(Member(u2(buf), u2(buf), u2(buf), read_attributes(buf)))
        return members

    fields = read_members()
    methods = read_members()
    attributes = read_attributes(buf)
    return minor, major, cp, access, this_class, super_class, interfaces, fields, methods, attributes


def utf8(cp: list[object | None], index: int) -> str | None:
    item = cp[index]
    return item[1].decode("utf-8") if item is not None and item[0] == 1 else None


def write_cp(cp: list[object | None]) -> bytes:
    out = io.BytesIO()
    out.write(p2(len(cp)))
    for item in cp[1:]:
        if item is None:
            continue
        tag, *values = item
        out.write(p1(tag))
        if tag == 1:
            out.write(p2(len(values[0])))
            out.write(values[0])
        elif tag in (3, 4, 5, 6):
            out.write(values[0])
        elif tag in (7, 8, 16, 19, 20):
            out.write(p2(values[0]))
        elif tag in (9, 10, 11, 12, 17, 18):
            out.write(p2(values[0]))
            out.write(p2(values[1]))
        elif tag == 15:
            out.write(p1(values[0]))
            out.write(p2(values[1]))
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}")
    return out.getvalue()


def write_member(member: Member) -> bytes:
    return (
        p2(member.access)
        + p2(member.name)
        + p2(member.descriptor)
        + write_attributes(member.attributes)
    )


def patch(data: bytes) -> bytes:
    (minor, major, cp, access, this_class, super_class, interfaces,
     fields, methods, class_attributes) = parse(data)

    patched = False
    for method in methods:
        if utf8(cp, method.name) == "glDeleteSync" and utf8(cp, method.descriptor) == "(J)V":
            # ACC_NATIVE and no Code attribute: the current arm64 native
            # library exports Java_org_lwjgl_opengl_GL32C_glDeleteSync.
            method.access |= 0x0100
            method.attributes = [
                attribute for attribute in method.attributes
                if utf8(cp, attribute.name) != "Code"
            ]
            patched = True
            break

    if not patched:
        raise ValueError("GL32C.glDeleteSync(J)V was not found")

    out = io.BytesIO()
    out.write(p4(0xCAFEBABE))
    out.write(p2(minor))
    out.write(p2(major))
    out.write(write_cp(cp))
    out.write(p2(access))
    out.write(p2(this_class))
    out.write(p2(super_class))
    out.write(p2(len(interfaces)))
    for item in interfaces:
        out.write(p2(item))
    out.write(p2(len(fields)))
    for field in fields:
        out.write(write_member(field))
    out.write(p2(len(methods)))
    for method in methods:
        out.write(write_member(method))
    out.write(write_attributes(class_attributes))
    return out.getvalue()


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} LWJGL_OPENGL_JAR OUTPUT_CLASS")
    source_jar = Path(sys.argv[1])
    output_class = Path(sys.argv[2])
    with zipfile.ZipFile(source_jar) as archive:
        original = archive.read("org/lwjgl/opengl/GL32C.class")
    output_class.parent.mkdir(parents=True, exist_ok=True)
    output_class.write_bytes(patch(original))


if __name__ == "__main__":
    main()
