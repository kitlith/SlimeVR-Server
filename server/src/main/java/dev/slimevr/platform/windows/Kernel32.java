package dev.slimevr.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;


// This file is necessary because JNA doesn't currently bind GetOverlappedResult
// TODO: submit an upstream issue
interface Kernel32 extends com.sun.jna.platform.win32.Kernel32 {
	Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

	boolean GetOverlappedResult(
		HANDLE hFile,
		WinBase.OVERLAPPED lpOverlapped,
		IntByReference lpNumberOfBytesTransferred,
		boolean bWait
	);
}
