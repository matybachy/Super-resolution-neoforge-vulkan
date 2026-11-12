#version 120
// test_composite: shows colortex0 as composite left it, with a green bar along the BOTTOM edge.
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
	vec3 o = texture2D(colortex0, texcoord).rgb;
	if (texcoord.y < 0.03) o = vec3(0.0, 1.0, 0.0);
	gl_FragColor = vec4(o, 1.0);
}
