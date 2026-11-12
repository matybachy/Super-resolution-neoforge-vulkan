#version 430
// Hangs off composite: colortex0 with red and blue swapped into colorimg1, which composite then reads.
layout(local_size_x = 16, local_size_y = 16) in;
const vec2 workGroupsRender = vec2(1.0, 1.0);
uniform sampler2D colortex0;
layout(rgba8) uniform image2D colorimg1;
void main() {
	ivec2 texel = ivec2(gl_GlobalInvocationID.xy);
	imageStore(colorimg1, texel, vec4(texelFetch(colortex0, texel, 0).bgr, 1.0));
}
