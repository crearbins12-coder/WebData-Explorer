package com.webdata.explorer

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import java.io.ByteArrayInputStream

/** Bounded structural DEX reader. It produces a Smali-like listing and never executes the APK. */
object DexCodeInspector {
    data class Result(
        val classCount: Int,
        val methodCount: Int,
        val invokeCount: Int,
        val strings: Set<String>,
        val endpoints: Set<String>,
        val libraries: Set<String>,
        val listing: String,
        val warning: String? = null
    )

    fun inspect(bytes: ByteArray): Result {
        val strings = linkedSetOf<String>()
        val endpoints = linkedSetOf<String>()
        val libraries = linkedSetOf<String>()
        val listing = StringBuilder()
        var classCount = 0
        var methodCount = 0
        var invokeCount = 0
        var instructionCount = 0
        val maxClasses = 250
        val maxMethods = 1200
        val maxInstructions = 5000

        try {
            val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(bytes))
            val classes = dex.classes
            classCount = classes.size
            listing.appendLine("# DEX ESTRUCTURAL · listado tipo Smali (limitado)")
            listing.appendLine("# Clases totales: $classCount")
            for (clazz in classes.take(maxClasses)) {
                listing.appendLine()
                listing.appendLine(".class ${clazz.accessFlags.toString(16)} ${clazz.type}")
                clazz.superclass?.let { listing.appendLine(".super $it") }
                clazz.interfaces.forEach { listing.appendLine(".implements $it") }
                for (method in clazz.methods) {
                    if (methodCount >= maxMethods) break
                    methodCount++
                    val params = method.parameterTypes.joinToString("") { it.toString() }
                    val signature = "${method.name}($params)${method.returnType}"
                    listing.appendLine()
                    listing.appendLine(".method ${method.accessFlags.toString(16)} $signature")
                    val implementation = method.implementation
                    if (implementation != null) {
                        listing.appendLine("    .registers ${implementation.registerCount}")
                        for (instruction in implementation.instructions) {
                            if (instructionCount >= maxInstructions) break
                            instructionCount++
                            val opcode = instruction.opcode.name
                            var operand = ""
                            if (instruction is ReferenceInstruction) {
                                val ref = instruction.reference
                                operand = ref.toString()
                                when (ref) {
                                    is StringReference -> {
                                        val value = ref.string
                                        if (value.length in 4..500) {
                                            strings.add(value)
                                            URL_REGEX.findAll(value).forEach { endpoints.add(it.value.trimEnd('.', ',', ';', ')', ']', '}')) }
                                            PATH_REGEX.findAll(value).forEach { endpoints.add(it.value) }
                                            LIBRARY_REGEX.findAll(value).forEach { libraries.add(it.value) }
                                        }
                                    }
                                    is MethodReference -> {
                                        if (opcode.startsWith("invoke-")) invokeCount++
                                        val owner = ref.definingClass
                                        if (owner.contains("retrofit") || owner.contains("okhttp") ||
                                            owner.contains("volley") || owner.contains("ktor") ||
                                            owner.contains("graphql", true)) libraries.add(owner)
                                        if (ref.name in setOf("url", "baseUrl", "newCall", "enqueue", "execute", "openConnection", "newBuilder")) {
                                            strings.add("CALL ${owner}->${ref.name}")
                                        }
                                    }
                                    is FieldReference -> {
                                        if (ref.name.contains("url", true) || ref.name.contains("api", true) ||
                                            ref.name.contains("host", true) || ref.name.contains("domain", true)) {
                                            strings.add("FIELD ${ref.definingClass}->${ref.name}:${ref.type}")
                                        }
                                    }
                                    is TypeReference -> {
                                        val type = ref.type
                                        if (type.contains("retrofit") || type.contains("okhttp") ||
                                            type.contains("volley") || type.contains("ktor")) libraries.add(type)
                                    }
                                }
                            }
                            listing.appendLine("    $opcode${if (operand.isNotEmpty()) " $operand" else ""}")
                        }
                    }
                    listing.appendLine(".end method")
                    if (instructionCount >= maxInstructions) break
                }
                if (methodCount >= maxMethods || instructionCount >= maxInstructions) break
            }
            if (classes.size > maxClasses || methodCount >= maxMethods || instructionCount >= maxInstructions) {
                listing.appendLine()
                listing.appendLine("# LISTADO TRUNCADO por límites de seguridad/rendimiento.")
            }
            return Result(classCount, methodCount, invokeCount, strings, endpoints, libraries, listing.toString())
        } catch (e: Exception) {
            return Result(
                classCount, methodCount, invokeCount, strings, endpoints, libraries, listing.toString(),
                "No se pudo interpretar este DEX: ${e.javaClass.simpleName}: ${e.message ?: "sin detalles"}"
            )
        }
    }

    private val URL_REGEX = Regex("""https?://[^\s\"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val PATH_REGEX = Regex("""/(?:api|v[0-9]+|graphql|rest|auth|oauth|portalCore|wp-json)/[^\s\"'<>\\]{1,180}""", RegexOption.IGNORE_CASE)
    private val LIBRARY_REGEX = Regex("""(?:retrofit2?|okhttp3?|com\.android\.volley|ktor|apollo|graphql|cronet|websocket)""", RegexOption.IGNORE_CASE)
}
