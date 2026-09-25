package kr.co.donghyun.flamelauncher.presentation.util.hosts

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 마인크래프트 servers.dat (멀티플레이 서버 목록) 읽기/쓰기.
 *
 * servers.dat 는 **압축되지 않은 NBT** 파일이다. 구조:
 *   TAG_Compound("")                       // 루트 (이름 빈 문자열)
 *     └ TAG_List "servers" (of Compound)
 *          └ 각 항목 TAG_Compound:
 *               "name" : TAG_String        // 표시 이름
 *               "ip"   : TAG_String        // 주소(host 또는 host:port)
 *               "icon" : TAG_String (옵션) // base64 png
 *               "acceptTextures": TAG_Byte (옵션)
 *
 * 외부 NBT 라이브러리 없이, servers.dat 가 쓰는 태그(Compound/List/String/Byte/End)만
 * 직접 직렬화한다. 기존 항목을 보존하며 추가/갱신하기 위해 읽기도 구현.
 */
object ServersDat {

    // ── NBT 태그 ID ──
    private const val TAG_END: Byte = 0
    private const val TAG_BYTE: Byte = 1
    private const val TAG_STRING: Byte = 8
    private const val TAG_LIST: Byte = 9
    private const val TAG_COMPOUND: Byte = 10

    data class ServerEntry(
        val name: String,
        val ip: String,
        val icon: String? = null,
        val acceptTextures: Byte? = null,
    )

    /**
     * servers.dat 경로 결정. installWorld/listWorlds 와 동일하게
     * legacy(1.12.2-)는 .minecraft 하위, modern 은 인스턴스 루트.
     */
    fun serversDatFile(instanceDir: File, isLegacy: Boolean): File {
        val gameDir = if (isLegacy) File(instanceDir, ".minecraft") else instanceDir
        return File(gameDir, "servers.dat")
    }

    // ───────────────────────── 읽기 ─────────────────────────

    /** servers.dat 을 읽어 서버 목록 반환. 없거나 깨졌으면 emptyList. */
    fun read(file: File): List<ServerEntry> {
        if (!file.isFile) return emptyList()
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                // 루트: TAG_Compound
                val rootType = input.readByte()
                if (rootType != TAG_COMPOUND) return emptyList()
                readUtf(input) // 루트 이름 (빈 문자열)
                parseServersFromCompound(input)
            }
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "servers.dat 읽기 실패: ${e.message}")
            emptyList()
        }
    }

    /** 루트 Compound 안에서 "servers" 리스트를 찾아 파싱. */
    private fun parseServersFromCompound(input: DataInputStream): List<ServerEntry> {
        val result = mutableListOf<ServerEntry>()
        while (true) {
            val type = input.readByte()
            if (type == TAG_END) break
            val name = readUtf(input)
            if (type == TAG_LIST && name == "servers") {
                val listType = input.readByte()
                val count = input.readInt()
                if (listType != TAG_COMPOUND) {
                    // 예상과 다르면 그 길이만큼 건너뛰기 어려우므로 중단
                    break
                }
                repeat(count) {
                    result.add(readServerCompound(input))
                }
            } else {
                skipTag(type, input)
            }
        }
        return result
    }

    /** 서버 항목 하나(Compound)를 읽는다. */
    private fun readServerCompound(input: DataInputStream): ServerEntry {
        var name = ""
        var ip = ""
        var icon: String? = null
        var accept: Byte? = null
        while (true) {
            val type = input.readByte()
            if (type == TAG_END) break
            val key = readUtf(input)
            when {
                type == TAG_STRING && key == "name" -> name = readUtf(input)
                type == TAG_STRING && key == "ip"   -> ip = readUtf(input)
                type == TAG_STRING && key == "icon" -> icon = readUtf(input)
                type == TAG_BYTE   && key == "acceptTextures" -> accept = input.readByte()
                else -> skipTag(type, input)
            }
        }
        return ServerEntry(name, ip, icon, accept)
    }

    /** 알 수 없는/관심 없는 태그를 건너뛴다. (servers.dat 에서 나올 수 있는 것만 처리) */
    private fun skipTag(type: Byte, input: DataInputStream) {
        when (type) {
            TAG_BYTE -> input.skipBytes(1)
            TAG_STRING -> { val len = input.readUnsignedShort(); input.skipBytes(len) }
            TAG_LIST -> {
                val itemType = input.readByte()
                val count = input.readInt()
                repeat(count) { skipPayload(itemType, input) }
            }
            TAG_COMPOUND -> {
                while (true) {
                    val t = input.readByte()
                    if (t == TAG_END) break
                    readUtf(input)
                    skipTag(t, input)
                }
            }
            else -> {
                // servers.dat 범위 밖의 숫자 태그들 — 안전하게 대략 건너뛰기
                skipPayload(type, input)
            }
        }
    }

    /** 리스트 항목 등 '이름 없는' 페이로드 건너뛰기. */
    private fun skipPayload(type: Byte, input: DataInputStream) {
        when (type) {
            1.toByte() -> input.skipBytes(1)            // byte
            2.toByte() -> input.skipBytes(2)            // short
            3.toByte() -> input.skipBytes(4)            // int
            4.toByte() -> input.skipBytes(8)            // long
            5.toByte() -> input.skipBytes(4)            // float
            6.toByte() -> input.skipBytes(8)            // double
            TAG_STRING -> { val len = input.readUnsignedShort(); input.skipBytes(len) }
            TAG_COMPOUND -> {
                while (true) {
                    val t = input.readByte()
                    if (t == TAG_END) break
                    readUtf(input)
                    skipTag(t, input)
                }
            }
            TAG_LIST -> {
                val byteInt = input.readByte(); val c = input.readInt()
                repeat(c) { skipPayload(byteInt, input) }
            }
            else -> { /* 알 수 없음 — 더 진행 불가, 무시 */ }
        }
    }

    private fun readUtf(input: DataInputStream): String {
        val len = input.readUnsignedShort()
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    // ───────────────────────── 쓰기 ─────────────────────────

    /** 서버 목록 전체를 servers.dat 로 직렬화해 저장(기존 파일 덮어씀). */
    fun write(file: File, servers: List<ServerEntry>) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            // 루트 Compound
            out.writeByte(TAG_COMPOUND.toInt())
            writeUtf(out, "") // 루트 이름

            // "servers" 리스트
            out.writeByte(TAG_LIST.toInt())
            writeUtf(out, "servers")
            out.writeByte(TAG_COMPOUND.toInt())   // 리스트 항목 타입
            out.writeInt(servers.size)
            servers.forEach { writeServerCompound(out, it) }

            out.writeByte(TAG_END.toInt())        // 루트 Compound 종료
        }
    }

    private fun writeServerCompound(out: DataOutputStream, s: ServerEntry) {
        // name
        out.writeByte(TAG_STRING.toInt()); writeUtf(out, "name"); writeUtf(out, s.name)
        // ip
        out.writeByte(TAG_STRING.toInt()); writeUtf(out, "ip"); writeUtf(out, s.ip)
        // icon (옵션)
        if (s.icon != null) {
            out.writeByte(TAG_STRING.toInt()); writeUtf(out, "icon"); writeUtf(out, s.icon)
        }
        // acceptTextures (옵션)
        if (s.acceptTextures != null) {
            out.writeByte(TAG_BYTE.toInt()); writeUtf(out, "acceptTextures"); out.writeByte(s.acceptTextures.toInt())
        }
        out.writeByte(TAG_END.toInt()) // 항목 Compound 종료
    }

    private fun writeUtf(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    // ───────────────────────── 편의 ─────────────────────────

    /**
     * 서버 항목을 추가하거나, 같은 ip 가 있으면 이름을 갱신한다(중복 방지).
     * 기존 항목은 보존.
     */
    fun upsert(file: File, entry: ServerEntry) {
        val current = read(file).toMutableList()
        val idx = current.indexOfFirst { it.ip.equals(entry.ip, ignoreCase = true) }
        if (idx >= 0) {
            // 기존 아이콘 등은 보존하고 이름/주소만 갱신
            val old = current[idx]
            current[idx] = entry.copy(icon = entry.icon ?: old.icon,
                acceptTextures = entry.acceptTextures ?: old.acceptTextures)
        } else {
            current.add(entry)
        }
        write(file, current)
        Log.d("FLAME_LAUNCHER", "servers.dat 갱신: ${entry.name} (${entry.ip}) → 총 ${current.size}개")
    }

    /** ip 로 서버 항목 제거. */
    fun removeByIp(file: File, ip: String) {
        val current = read(file).toMutableList()
        val removed = current.removeAll { it.ip.equals(ip, ignoreCase = true) }
        if (removed) {
            write(file, current)
            Log.d("FLAME_LAUNCHER", "servers.dat 에서 제거: $ip")
        }
    }
}