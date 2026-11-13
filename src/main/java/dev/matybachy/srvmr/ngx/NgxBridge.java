package dev.matybachy.srvmr.ngx;

/** {@code native/src/srvmr_ngx.cpp}. Load {@link Natives} first. */
public final class NgxBridge {

	private static final String[] FAILURES = {
			"Fail", "FeatureNotSupported", "PlatformError", "FeatureAlreadyExists", "FeatureNotFound",
			"InvalidParameter", "ScratchBufferTooSmall", "NotInitialized", "UnsupportedInputFormat", "RWFlagMissing",
			"MissingInput", "UnableToInitializeFeature", "OutOfDate", "OutOfGPUMemory", "UnsupportedFormat",
			"UnableToWriteToAppDataPath", "UnsupportedParameter", "Denied", "NotImplemented"};

	private NgxBridge() {
	}

	static native String[] instanceExtensions(String projectId, String dataPath, String featurePath);

	static native String[] deviceExtensions(String projectId, String dataPath, String featurePath, long instance,
			long physicalDevice);

	static native int init(String projectId, String dataPath, String featurePath, long instance, long physicalDevice,
			long device, long getInstanceProcAddr, long getDeviceProcAddr);

	static native int[] availability();

	static native int[] renderSizes(int outputWidth, int outputHeight, int quality);

	static native long create(long commandBuffer, int renderWidth, int renderHeight, int outputWidth, int outputHeight,
			int quality, int flags);

	static native int evaluate(long commandBuffer, long feature, long[] images, int[] info, float jitterX, float jitterY,
			int reset, float frameMillis, int renderWidth, int renderHeight);

	static native void release(long feature);

	static native void shutdown(long device);

	static native int lastResult();

	/** {@code NVSDK_NGX_SUCCEED}. */
	static boolean succeeded(int result) {
		return (result & 0xFFF00000) != 0xBAD00000;
	}

	static String describe(int result) {
		if (succeeded(result)) return "success";
		int code = result & 0xFFFFF;
		String name = code < FAILURES.length ? FAILURES[code] : "failure";
		return name + String.format(" (0x%08X)", result);
	}
}
