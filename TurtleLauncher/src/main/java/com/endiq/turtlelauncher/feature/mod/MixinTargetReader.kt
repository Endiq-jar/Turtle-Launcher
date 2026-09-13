package com.endiq.turtlelauncher.feature.mod

import java.io.DataInputStream
import java.io.IOException

/**
 * TurtleLauncher: a minimal, dependency-free Java .class file parser whose only job is to
 * find a Mixin class's `@Mixin(...)` annotation and extract the target class names from its
 * `value` (Class[]) and `targets` (String[]) elements.
 *
 * We can't just read the mixin config JSON's "mixins" list — those are the *mixin* class
 * names (e.g. "MixinPlayerEntity"), not what they actually patch. The real target is only
 * recorded inside the compiled class's `@Mixin` annotation, so this reads just enough of the
 * JVM class file format (constant pool + RuntimeVisibleAnnotations) to pull it out, without
 * pulling in a full bytecode library like ASM as a new build dependency.
 *
 * Spec reference: JVMS §4.4 (constant pool) and §4.7.16 (RuntimeVisibleAnnotations / annotation
 * structure). Only what's needed is implemented — this is not a general-purpose class reader.
 */
object MixinTargetReader {

    private const val MIXIN_ANNOTATION_DESC = "Lorg/spongepowered/asm/mixin/Mixin;"

    /** Returns the fully-qualified (dotted) target class names declared by this mixin class's @Mixin annotation. */
    fun readMixinTargets(classBytes: ByteArray): List<String> {
        return try {
            DataInputStream(classBytes.inputStream()).use { input ->
                if (input.readInt() != -889275714) return emptyList() // 0xCAFEBABE magic check
                input.readUnsignedShort() // minor
                input.readUnsignedShort() // major

                val pool = readConstantPool(input)

                input.readUnsignedShort() // access_flags
                input.readUnsignedShort() // this_class
                input.readUnsignedShort() // super_class

                val interfacesCount = input.readUnsignedShort()
                repeat(interfacesCount) { input.readUnsignedShort() }

                skipMembers(input) // fields
                skipMembers(input) // methods

                val classAttrCount = input.readUnsignedShort()
                repeat(classAttrCount) {
                    val nameIndex = input.readUnsignedShort()
                    val length = input.readInt()
                    val attrName = pool.utf8(nameIndex)
                    if (attrName == "RuntimeVisibleAnnotations") {
                        return parseAnnotationsForMixinTargets(input, pool)
                    } else {
                        input.skipBytes(length)
                    }
                }
                emptyList()
            }
        } catch (e: IOException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private class ConstantPool(val utf8ByIndex: Map<Int, String>) {
        fun utf8(index: Int): String? = utf8ByIndex[index]
    }

    /** Reads the constant pool, keeping only UTF8 entries (everything we need lives there). */
    private fun readConstantPool(input: DataInputStream): ConstantPool {
        val count = input.readUnsignedShort()
        val utf8s = HashMap<Int, String>()
        var i = 1
        while (i < count) {
            val tag = input.readUnsignedByte()
            when (tag) {
                1 -> utf8s[i] = input.readUTF() // Utf8
                3, 4 -> input.skipBytes(4) // Integer, Float
                5, 6 -> { input.skipBytes(8); i++ } // Long, Double take two pool slots
                7, 8, 16, 19, 20 -> input.skipBytes(2) // Class, String, MethodType, Module, Package
                9, 10, 11, 12, 17, 18 -> input.skipBytes(4) // *ref, NameAndType, Dynamic, InvokeDynamic
                15 -> input.skipBytes(3) // MethodHandle
                else -> return ConstantPool(utf8s) // unknown tag: bail out safely
            }
            i++
        }
        return ConstantPool(utf8s)
    }

    /** Skips over every field_info or method_info entry (we don't need their contents). */
    private fun skipMembers(input: DataInputStream) {
        val count = input.readUnsignedShort()
        repeat(count) {
            input.readUnsignedShort() // access_flags
            input.readUnsignedShort() // name_index
            input.readUnsignedShort() // descriptor_index
            val attrCount = input.readUnsignedShort()
            repeat(attrCount) {
                input.readUnsignedShort() // attribute_name_index
                val length = input.readInt()
                input.skipBytes(length)
            }
        }
    }

    private fun parseAnnotationsForMixinTargets(input: DataInputStream, pool: ConstantPool): List<String> {
        val numAnnotations = input.readUnsignedShort()
        repeat(numAnnotations) {
            val typeIndex = input.readUnsignedShort()
            val descriptor = pool.utf8(typeIndex)
            val numPairs = input.readUnsignedShort()
            val targets = mutableListOf<String>()
            repeat(numPairs) {
                val nameIndex = input.readUnsignedShort()
                val elementName = pool.utf8(nameIndex)
                val values = readElementValue(input, pool)
                if (elementName == "value" || elementName == "targets") {
                    targets.addAll(values)
                }
            }
            if (descriptor == MIXIN_ANNOTATION_DESC) {
                return targets
            }
        }
        return emptyList()
    }

    /** Reads one element_value, returning any class/string names found (handles arrays too). Always consumes the full structure. */
    private fun readElementValue(input: DataInputStream, pool: ConstantPool): List<String> {
        val tag = input.readUnsignedByte().toChar()
        return when (tag) {
            's' -> {
                val constIndex = input.readUnsignedShort()
                listOfNotNull(pool.utf8(constIndex)?.replace('/', '.'))
            }
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> {
                input.readUnsignedShort() // const_value_index, irrelevant primitive
                emptyList()
            }
            'e' -> { input.readUnsignedShort(); input.readUnsignedShort(); emptyList() } // enum_const_value
            'c' -> {
                val classInfoIndex = input.readUnsignedShort()
                val desc = pool.utf8(classInfoIndex)
                listOfNotNull(descriptorToClassName(desc))
            }
            '@' -> { skipAnnotation(input, pool); emptyList() }
            '[' -> {
                val numValues = input.readUnsignedShort()
                val result = mutableListOf<String>()
                repeat(numValues) { result.addAll(readElementValue(input, pool)) }
                result
            }
            else -> emptyList()
        }
    }

    private fun skipAnnotation(input: DataInputStream, pool: ConstantPool) {
        input.readUnsignedShort() // type_index
        val numPairs = input.readUnsignedShort()
        repeat(numPairs) {
            input.readUnsignedShort() // element_name_index
            readElementValue(input, pool)
        }
    }

    /** Converts a JVM descriptor like "Lnet/minecraft/client/Minecraft;" into "net.minecraft.client.Minecraft". */
    private fun descriptorToClassName(descriptor: String?): String? {
        if (descriptor == null) return null
        val trimmed = descriptor.removePrefix("L").removeSuffix(";")
        return trimmed.replace('/', '.')
    }
}
