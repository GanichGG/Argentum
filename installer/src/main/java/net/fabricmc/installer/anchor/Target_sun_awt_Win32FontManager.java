/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.installer.anchor;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM native-image on Windows crashes with "platform encoding not initialized" the very
 * first time AWT/Swing touches a {@link java.awt.Font}, because the real (native, backed by
 * fontmanager.dll) {@code sun.awt.Win32FontManager.getFontPath} calls into JNI string-encoding
 * machinery that isn't set up yet at that point under a native image — a known, still-open
 * GraalVM/SubstrateVM gap (see github.com/gluonhq/substrate/issues/945 and
 * github.com/oracle/graal/issues/12393). {@code --initialize-at-run-time} flags don't help since
 * the failure is inside the native call itself, not in JVM-side class initialization order.
 *
 * <p>Substitutes the native method with a pure-Java equivalent that just returns the standard
 * Windows fonts directory — the same answer the native call would normally give — sidestepping
 * the native call (and the bug) entirely.
 */
@TargetClass(className = "sun.awt.Win32FontManager")
final class Target_sun_awt_Win32FontManager {
	@Substitute
	@SuppressWarnings("unused")
	protected String getFontPath(boolean noType1Fonts) {
		String windir = System.getenv("SystemRoot");

		if (windir == null || windir.isEmpty()) {
			windir = System.getenv("WINDIR");
		}

		if (windir == null || windir.isEmpty()) {
			windir = "C:\\Windows";
		}

		return windir + "\\Fonts";
	}
}
