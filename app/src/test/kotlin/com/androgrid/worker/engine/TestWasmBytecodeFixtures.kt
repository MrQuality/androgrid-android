package com.androgrid.worker.engine

/**
 * Pre-compiled, hermetic WebAssembly bytecode fixtures for testing WasmComputeEngine.
 * Contains binary artifacts for WASI standard I/O, Fibonacci recursion, deliberate traps,
 * runaway infinite loops, and WASI exit status codes.
 *
 * Traces to: [INV-006], [REQ-VER-003], [NFR-PERF-001]
 */
object TestWasmBytecodeFixtures {

    /**
     * Helper to decode a hex string into a ByteArray.
     */
    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    /**
     * Minimal WASI Preview 1 module that prints "Hello, AndroGrid WASM!\n" to stdout (fd 1)
     * using wasi_snapshot_preview1:fd_write, and exports "memory" and "_start".
     */
    val HELLO_WASI: ByteArray by lazy {
        hexToBytes(
            "0061736d01000000010d0260047f7f7f7f017f60000002230116776173695f736e617073686f745f70726576696577310866645f77726974650000030201010503010001071302066d656d6f72790200065f737461727400010a0f010d00410141004101410810001a0b0b2d010041000b271000000017000000000000000000000048656c6c6f2c20416e64726f47726964205741534d210a"
        )
    }

    /**
     * Pure WebAssembly module exporting "fibonacci(n: i32) -> i32".
     * Calculates the Nth Fibonacci number recursively.
     */
    val FIBONACCI: ByteArray by lazy {
        hexToBytes(
            "0061736d0100000001060160017f017f03020100070d01096669626f6e6163636900000a1e011c00200041014c047f200005200041016b1000200041026b10006a0b0b"
        )
    }

    /**
     * WebAssembly module exporting "_start" which triggers a deliberate trap via "unreachable" (0x00).
     */
    val TRAP_ABORT: ByteArray by lazy {
        hexToBytes(
            "0061736d0100000001040160000003020100070a01065f737461727400000a05010300000b"
        )
    }

    /**
     * WebAssembly module exporting "_start" which executes an infinite loop: "loop ... br 0 ... end".
     */
    val INFINITE_LOOP: ByteArray by lazy {
        hexToBytes(
            "0061736d0100000001040160000003020100070a01065f737461727400000a0901070003400c000b0b"
        )
    }

    /**
     * WASI Preview 1 module exporting "_start" that calls wasi_snapshot_preview1:proc_exit with code 42.
     */
    val EXIT_42: ByteArray by lazy {
        hexToBytes(
            "0061736d0100000001080260017f0060000002240116776173695f736e617073686f745f70726576696577310970726f635f65786974000003020101070a01065f737461727400010a08010600412a10000b"
        )
    }
}
