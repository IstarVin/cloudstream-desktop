package androidx.fragment.app

/**
 * Shape only, for the bytecode verifier: plugin settings sheets subclass this chain, and a plugin converted from its
 * `.cs3` (old-style verification, no stack map frames) loads them while verifying its `Plugin` class. They never run.
 */
open class Fragment
