#version 430 compatibility
// The scene with red and blue swapped; along the bottom edge, four squares, green where a compute did its part:
// markImage from prepare, then setup's, begin's and final's values in the buffer.
uniform sampler2D colortex2;
uniform sampler2D markSampler;
layout(std430, binding = 0) buffer Marks { float setupValue; float beginValue; float finalValue; };
varying vec2 texcoord;
vec3 mark(bool done) {
	return done ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
}
void main() {
	vec3 o = texture2D(colortex2, texcoord).rgb;
	if (texcoord.y < 0.1) {
		if (texcoord.x < 0.1) o = texture2D(markSampler, texcoord).rgb;
		else if (texcoord.x < 0.2) o = mark(setupValue == 0.5);
		else if (texcoord.x < 0.3) o = mark(beginValue == 0.25);
		else if (texcoord.x < 0.4) o = mark(finalValue == 0.75);
	}
	gl_FragColor = vec4(o, 1.0);
}
