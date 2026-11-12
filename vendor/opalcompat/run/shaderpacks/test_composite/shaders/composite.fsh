#version 120
// test_composite: grayscale the scene into colortex0 and draw a blue square in the BOTTOM-LEFT corner.
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
	vec3 c = texture2D(colortex0, texcoord).rgb;
	float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
	vec3 o = vec3(l);
	if (texcoord.x < 0.1 && texcoord.y < 0.1) o = vec3(0.0, 0.0, 1.0);
/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(o, 1.0);
}
