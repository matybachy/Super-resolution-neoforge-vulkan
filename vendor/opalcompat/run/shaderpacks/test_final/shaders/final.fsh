#version 120
// test_final: left half channel-swapped (BGR), right half inverted, red bar along the TOP edge.
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
	vec3 c = texture2D(colortex0, texcoord).rgb;
	vec3 o = texcoord.x < 0.5 ? c.bgr : vec3(1.0) - c;
	if (texcoord.y > 0.95) o = vec3(1.0, 0.0, 0.0);
	gl_FragColor = vec4(o, 1.0);
}
