package dev.matybachy.srvmr.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.CrashReportCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Minecraft's crash report compares the source file names of two stack frames and takes for granted that a frame has
 * one ({@code CrashReportCategory.validateStackTrace}). A frame of a lambda or of a class without debug information has
 * none; Ixeris runs the game's render thread from such a frame. Then writing the crash report itself throws a
 * NullPointerException, the report is never written and the crash that caused it is lost: the log says only
 * "getFileName() is null". A missing name is compared as an empty one here, so the real crash gets its report.
 */
@Mixin(CrashReportCategory.class)
public abstract class CrashReportFileNameMixin {

	@WrapOperation(method = "validateStackTrace", at = @At(value = "INVOKE",
			target = "Ljava/lang/StackTraceElement;getFileName()Ljava/lang/String;"))
	private String srvmr$noFileName(StackTraceElement frame, Operation<String> original) {
		String name = original.call(frame);
		return name != null ? name : "";
	}
}
