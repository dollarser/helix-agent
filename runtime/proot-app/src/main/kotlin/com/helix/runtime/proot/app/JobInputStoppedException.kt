package com.helix.runtime.proot.app

import java.io.IOException

/** Distinguishes a proven pre-launch stop from malformed input or transport failure. */
internal class JobInputStoppedException : IOException("Job input transfer stopped before launch")
