package dev.opalcompat.vk;

import dev.vitrail.uniform.UniformSink;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Writes Vitrail's uniform block walk into a std140 buffer. */
public final class Std140Writer implements UniformSink {

	private ByteBuffer out;

	public Std140Writer into(ByteBuffer buffer) {
		this.out = buffer.order(ByteOrder.LITTLE_ENDIAN);
		this.out.position(0);
		return this;
	}

	@Override
	public UniformSink align(int alignment) {
		int at = this.out.position();
		int aligned = (at + alignment - 1) / alignment * alignment;
		while (at < aligned) {
			this.out.put(at++, (byte) 0);
		}
		this.out.position(aligned);
		return this;
	}

	@Override
	public UniformSink putFloat(float v) {
		align(4);
		this.out.putFloat(v);
		return this;
	}

	@Override
	public UniformSink putInt(int v) {
		align(4);
		this.out.putInt(v);
		return this;
	}

	@Override
	public UniformSink putVec2(float x, float y) {
		align(8);
		this.out.putFloat(x).putFloat(y);
		return this;
	}

	@Override
	public UniformSink putVec3(float x, float y, float z) {
		align(16);
		this.out.putFloat(x).putFloat(y).putFloat(z);
		return this;
	}

	@Override
	public UniformSink putVec4(float x, float y, float z, float w) {
		align(16);
		this.out.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
		return this;
	}

	@Override
	public UniformSink putIVec2(int x, int y) {
		align(8);
		this.out.putInt(x).putInt(y);
		return this;
	}

	@Override
	public UniformSink putIVec3(int x, int y, int z) {
		align(16);
		this.out.putInt(x).putInt(y).putInt(z);
		return this;
	}

	@Override
	public UniformSink putIVec4(int x, int y, int z, int w) {
		align(16);
		this.out.putInt(x).putInt(y).putInt(z).putInt(w);
		return this;
	}

	@Override
	public UniformSink putMat3(Matrix3fc m) {
		// Three columns, each padded to a vec4.
		putVec4(m.m00(), m.m01(), m.m02(), 0.0F);
		putVec4(m.m10(), m.m11(), m.m12(), 0.0F);
		putVec4(m.m20(), m.m21(), m.m22(), 0.0F);
		return this;
	}

	@Override
	public UniformSink putMat4(Matrix4fc m) {
		align(16);
		int at = this.out.position();
		m.get(at, this.out);
		this.out.position(at + 64);
		return this;
	}
}
